package com.ruta.app.util

import com.google.firebase.database.DataSnapshot
import com.google.firebase.database.DatabaseError
import com.google.firebase.database.DatabaseReference
import com.google.firebase.database.MutableData
import com.google.firebase.database.Transaction

/**
 * Atomic "first driver wins" claim on a booking.
 *
 * Why this exists: accepting a ride used to be a plain `updateChildren`, written
 * after the driver's screen had already switched to ACCEPTED. When two drivers
 * tapped Accept at nearly the same time, the loser's screen still showed an
 * accepted ride that the database had given to someone else (a "fake" booking).
 *
 * The claim runs as a Realtime Database transaction on the booking itself, so
 * the server decides the winner from the booking's *current* state: the claim
 * only goes through while the booking is still open and has no driver. The UI
 * must move to ACCEPTED only when the outcome is [Outcome.WON].
 *
 * The decision rule is a pure function ([decide]) so it can be unit tested on
 * the JVM without Firebase.
 */
object BookingClaim {

    /** Statuses a driver can still claim. Mirrors the pending-request listener. */
    val CLAIMABLE_STATUSES = setOf("REQUESTED", "MATCHED")

    /** What the rule says about a booking in a given state. */
    enum class Decision {
        /** Open and unassigned: this driver may take it. */
        CLAIM,

        /** Already assigned to this same driver (e.g. a retried tap): treat as won. */
        ALREADY_MINE,

        /** Assigned to a different driver. */
        TAKEN,

        /** No longer open (cancelled, completed, in progress) or gone. */
        UNAVAILABLE
    }

    /** What the caller finally learns. */
    enum class Outcome { WON, TAKEN, UNAVAILABLE, ERROR }

    /**
     * Pure claim rule.
     *
     * @param status the booking's current status, or null if it does not exist
     * @param assignedDriverId the booking's current driverId, or null/blank if none
     * @param driverId the driver trying to claim it
     */
    fun decide(status: String?, assignedDriverId: String?, driverId: String): Decision {
        if (status == null) return Decision.UNAVAILABLE
        if (!assignedDriverId.isNullOrBlank()) {
            return if (assignedDriverId == driverId) Decision.ALREADY_MINE else Decision.TAKEN
        }
        return if (status in CLAIMABLE_STATUSES) Decision.CLAIM else Decision.UNAVAILABLE
    }

    /**
     * Tries to claim [bookingRef] for [driverId], writing [details] (status,
     * driver and vehicle fields) in the same transaction. [onResult] is called
     * exactly once, on the main thread, with the final [Outcome].
     */
    fun claim(
        bookingRef: DatabaseReference,
        driverId: String,
        details: Map<String, Any>,
        onResult: (Outcome) -> Unit
    ) {
        bookingRef.runTransaction(object : Transaction.Handler {
            override fun doTransaction(data: MutableData): Transaction.Result {
                // First pass can run on an empty local cache. Returning the data
                // unchanged makes Firebase re-run us with the server's value.
                if (data.value == null) return Transaction.success(data)

                val status = data.child("status").getValue(String::class.java)
                val assigned = data.child("driverId").getValue(String::class.java)

                return when (decide(status, assigned, driverId)) {
                    Decision.CLAIM -> {
                        for ((key, value) in details) data.child(key).value = value
                        Transaction.success(data)
                    }
                    Decision.ALREADY_MINE -> Transaction.success(data)
                    Decision.TAKEN, Decision.UNAVAILABLE -> Transaction.abort()
                }
            }

            override fun onComplete(error: DatabaseError?, committed: Boolean, snapshot: DataSnapshot?) {
                if (error != null) {
                    onResult(Outcome.ERROR)
                    return
                }
                val status = snapshot?.child("status")?.getValue(String::class.java)
                val assigned = snapshot?.child("driverId")?.getValue(String::class.java)
                val finalDecision = decide(status, assigned, driverId)
                onResult(
                    when {
                        committed && finalDecision == Decision.ALREADY_MINE -> Outcome.WON
                        finalDecision == Decision.TAKEN -> Outcome.TAKEN
                        else -> Outcome.UNAVAILABLE
                    }
                )
            }
        })
    }

    /**
     * Picks the booking every driver contends on for a shared trip group: the
     * smallest booking key. All drivers compute the same lead, so the group is
     * won or lost on one record, and two drivers can no longer each win a
     * different booking of the same group.
     */
    fun leadBookingKey(groupBookingKeys: Collection<String>): String? =
        groupBookingKeys.minOrNull()
}
