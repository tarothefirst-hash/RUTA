package com.ruta.app.util

import java.util.Locale

/**
 * Pure rules behind the rideshare "match, confirm, then book" flow.
 *
 * Nothing here touches Firebase, Android or the clock: times are passed in. That
 * keeps every rule unit-testable on the JVM, and keeps the flow manager a thin
 * layer that only wires these rules to the database.
 *
 * The flow in one paragraph: a passenger searches for up to [SEARCH_WINDOW_MS].
 * When two compatible searchers are found, one of them claims both bookings, the
 * route and the locked fares are worked out, and BOTH passengers get
 * [CONFIRM_WINDOW_MS] to press "Confirm Passenger Match". Only when both confirm
 * do the bookings become MATCHED. If either declines, or the window runs out, the
 * proposal is dropped, the two are never paired again, and both go back to
 * searching.
 */
object RideshareMatchLogic {

    /** How long a passenger looks for a co-passenger before being asked about solo. */
    const val SEARCH_WINDOW_MS = 5 * 60_000L

    /** How long both passengers get to confirm a proposed match. */
    const val CONFIRM_WINDOW_MS = 60_000L

    /**
     * Grace before the passenger who is NOT the timeout leader also cancels an
     * expired proposal, in case the leader's phone is offline.
     */
    const val FALLBACK_GRACE_MS = 5_000L

    /** Stable key for a pair, the same whichever booking asks. Used as the trip group id. */
    fun pairKey(a: String, b: String): String =
        if (a < b) "${a}_${b}" else "${b}_${a}"

    /**
     * The order in which two bookings must be claimed. Everyone claims the lower
     * id first, so two passengers racing for each other can never each hold one
     * booking and block the other forever.
     */
    fun claimOrder(a: String, b: String): Pair<String, String> =
        if (a < b) a to b else b to a

    /** Can [candidateId] be proposed to a searcher whose booking is [myBookingId]? */
    fun isEligiblePartner(
        myBookingId: String,
        candidateId: String,
        candidateIsShared: Boolean,
        candidateInProposal: Boolean,
        excludedPartners: Set<String>
    ): Boolean =
        candidateId != myBookingId &&
            candidateIsShared &&
            !candidateInProposal &&
            candidateId !in excludedPartners

    enum class Resolution { WAIT, BOTH_CONFIRMED, EXPIRED }

    /** What should happen to a proposal given who has confirmed and what time it is. */
    fun resolveConfirmation(
        myConfirmed: Boolean,
        partnerConfirmed: Boolean,
        nowMs: Long,
        expiresAtMs: Long
    ): Resolution = when {
        myConfirmed && partnerConfirmed -> Resolution.BOTH_CONFIRMED
        nowMs >= expiresAtMs -> Resolution.EXPIRED
        else -> Resolution.WAIT
    }

    /** The lower booking id is the one that cancels an expired proposal first. */
    fun isTimeoutLeader(myBookingId: String, partnerBookingId: String): Boolean =
        myBookingId < partnerBookingId

    /**
     * Whether this passenger should cancel an expired proposal now. The leader
     * cancels as soon as it expires; the other passenger only steps in after a
     * short grace period, so the two do not both write at the same instant.
     */
    fun shouldCancelExpiredProposal(
        myBookingId: String,
        partnerBookingId: String,
        nowMs: Long,
        expiresAtMs: Long
    ): Boolean {
        if (nowMs < expiresAtMs) return false
        return if (isTimeoutLeader(myBookingId, partnerBookingId)) true
        else nowMs >= expiresAtMs + FALLBACK_GRACE_MS
    }

    /** Milliseconds left in the search window, never negative. */
    fun searchRemainingMs(nowMs: Long, searchExpiresAtMs: Long): Long =
        (searchExpiresAtMs - nowMs).coerceAtLeast(0L)

    /** "4:32" style countdown. Rounds up so it never shows 0:00 while time remains. */
    fun formatCountdown(remainingMs: Long): String {
        val totalSeconds = ((remainingMs.coerceAtLeast(0L) + 999L) / 1000L)
        return String.format(Locale.US, "%d:%02d", totalSeconds / 60, totalSeconds % 60)
    }

    /** Peso amount as shown on the match and summary screens. */
    fun formatPeso(amount: Double): String = String.format(Locale.US, "₱%.2f", amount)

    // ---------------------------------------------------------------------
    // Route colouring: who is in the car on each leg of a shared trip
    // ---------------------------------------------------------------------

    /** Who is riding on a leg, from one passenger's point of view. */
    enum class LegOwner {
        /** Driver travelling to the first pickup with nobody onboard. */
        EMPTY,

        /** Only "me" is onboard. */
        ME,

        /** Only the co-passenger is onboard. */
        PARTNER,

        /** Both passengers are onboard (the shared part). */
        BOTH
    }

    /**
     * For each stop index i, the set of passengers onboard while travelling INTO
     * stop i (index 0 is the start of the route, so it is always empty).
     *
     * Stops are applied in order: a PICKUP puts that booking in the car, a DROPOFF
     * takes it out. Stops whose booking id is not in [passengerIds] (such as the
     * driver's own starting point) are ignored.
     *
     * [initiallyOnboard] is for a route that is already under way: a passenger who
     * was picked up earlier is in the car from the start of the remaining stops.
     */
    fun legOnboard(
        stopBookingIds: List<String>,
        stopIsPickup: List<Boolean>,
        passengerIds: Set<String>,
        initiallyOnboard: Set<String> = emptySet()
    ): List<Set<String>> {
        val onboard = initiallyOnboard.toMutableSet()
        val result = mutableListOf<Set<String>>()
        for (i in stopBookingIds.indices) {
            result.add(onboard.toSet())
            val id = stopBookingIds[i]
            if (id in passengerIds) {
                if (stopIsPickup[i]) onboard.add(id) else onboard.remove(id)
            }
        }
        return result
    }

    /**
     * Passengers already in the car at the start of a (partly driven) route: the
     * ones whose drop-off is still ahead but whose pickup is not.
     */
    fun alreadyOnboard(
        stopBookingIds: List<String>,
        stopIsPickup: List<Boolean>,
        passengerIds: Set<String>
    ): Set<String> {
        val pickups = mutableSetOf<String>()
        val dropoffs = mutableSetOf<String>()
        for (i in stopBookingIds.indices) {
            val id = stopBookingIds[i]
            if (id !in passengerIds) continue
            if (stopIsPickup[i]) pickups.add(id) else dropoffs.add(id)
        }
        return dropoffs - pickups
    }

    /** Classifies a leg for the passenger whose booking is [myBookingId]. */
    fun legOwner(onboard: Set<String>, myBookingId: String): LegOwner = when {
        onboard.isEmpty() -> LegOwner.EMPTY
        myBookingId in onboard && onboard.size > 1 -> LegOwner.BOTH
        myBookingId in onboard -> LegOwner.ME
        else -> LegOwner.PARTNER
    }
}
