package com.ruta.app.util

import com.ruta.app.util.RideshareMatchLogic.Resolution
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** JVM unit tests for the rideshare match / confirm / search-timer rules. */
class RideshareMatchLogicTest {

    private val expires = 100_000L

    @Test
    fun pairKeyIsTheSameWhicheverBookingAsks() {
        assertEquals("a_b", RideshareMatchLogic.pairKey("a", "b"))
        assertEquals("a_b", RideshareMatchLogic.pairKey("b", "a"))
    }

    @Test
    fun claimOrderAlwaysStartsWithTheLowerId() {
        assertEquals("a" to "b", RideshareMatchLogic.claimOrder("b", "a"))
        assertEquals("a" to "b", RideshareMatchLogic.claimOrder("a", "b"))
    }

    @Test
    fun cannotBeMatchedWithYourself() {
        assertFalse(RideshareMatchLogic.isEligiblePartner("me", "me", true, false, emptySet()))
    }

    @Test
    fun soloOrAlreadyProposedBookingsAreNotEligible() {
        assertFalse(RideshareMatchLogic.isEligiblePartner("me", "x", false, false, emptySet()))
        assertFalse(RideshareMatchLogic.isEligiblePartner("me", "x", true, true, emptySet()))
    }

    @Test
    fun aPartnerWhoWasDeclinedIsNeverProposedAgain() {
        assertFalse(RideshareMatchLogic.isEligiblePartner("me", "x", true, false, setOf("x")))
        assertTrue(RideshareMatchLogic.isEligiblePartner("me", "y", true, false, setOf("x")))
    }

    @Test
    fun bothConfirmedWinsEvenAtTheDeadline() {
        assertEquals(Resolution.BOTH_CONFIRMED, RideshareMatchLogic.resolveConfirmation(true, true, expires, expires))
        assertEquals(Resolution.BOTH_CONFIRMED, RideshareMatchLogic.resolveConfirmation(true, true, 0, expires))
    }

    @Test
    fun oneConfirmationIsNotEnough() {
        assertEquals(Resolution.WAIT, RideshareMatchLogic.resolveConfirmation(true, false, 50_000, expires))
        assertEquals(Resolution.WAIT, RideshareMatchLogic.resolveConfirmation(false, true, 50_000, expires))
        assertEquals(Resolution.EXPIRED, RideshareMatchLogic.resolveConfirmation(true, false, expires, expires))
    }

    @Test
    fun proposalExpiresWhenTimeIsUpWithoutBothConfirming() {
        assertEquals(Resolution.WAIT, RideshareMatchLogic.resolveConfirmation(false, false, expires - 1, expires))
        assertEquals(Resolution.EXPIRED, RideshareMatchLogic.resolveConfirmation(false, false, expires, expires))
        assertEquals(Resolution.EXPIRED, RideshareMatchLogic.resolveConfirmation(false, false, expires + 5_000, expires))
    }

    @Test
    fun leaderCancelsAtOnceAndTheOtherWaitsForTheGracePeriod() {
        // "a" < "b" so "a" is the leader.
        assertFalse(RideshareMatchLogic.shouldCancelExpiredProposal("a", "b", expires - 1, expires))
        assertTrue(RideshareMatchLogic.shouldCancelExpiredProposal("a", "b", expires, expires))
        assertFalse(RideshareMatchLogic.shouldCancelExpiredProposal("b", "a", expires, expires))
        assertFalse(RideshareMatchLogic.shouldCancelExpiredProposal("b", "a", expires + 4_999, expires))
        assertTrue(RideshareMatchLogic.shouldCancelExpiredProposal("b", "a", expires + RideshareMatchLogic.FALLBACK_GRACE_MS, expires))
    }

    @Test
    fun exactlyOneSideIsTheTimeoutLeader() {
        assertTrue(RideshareMatchLogic.isTimeoutLeader("a", "b") xor RideshareMatchLogic.isTimeoutLeader("b", "a"))
    }

    @Test
    fun searchRemainingNeverGoesNegative() {
        assertEquals(30_000L, RideshareMatchLogic.searchRemainingMs(70_000, 100_000))
        assertEquals(0L, RideshareMatchLogic.searchRemainingMs(120_000, 100_000))
    }

    @Test
    fun countdownIsFormattedAsMinutesAndSeconds() {
        assertEquals("5:00", RideshareMatchLogic.formatCountdown(RideshareMatchLogic.SEARCH_WINDOW_MS))
        assertEquals("4:32", RideshareMatchLogic.formatCountdown(272_000))
        assertEquals("1:00", RideshareMatchLogic.formatCountdown(RideshareMatchLogic.CONFIRM_WINDOW_MS))
        assertEquals("0:01", RideshareMatchLogic.formatCountdown(1))
        assertEquals("0:00", RideshareMatchLogic.formatCountdown(0))
        assertEquals("0:00", RideshareMatchLogic.formatCountdown(-5))
    }

    @Test
    fun pesoAmountsKeepTwoDecimals() {
        assertEquals("₱128.00", RideshareMatchLogic.formatPeso(128.0))
        assertEquals("₱64.50", RideshareMatchLogic.formatPeso(64.5))
    }

    // Stops: driver start, pickup A, pickup B, dropoff A, dropoff B
    private val stopIds = listOf("DRIVER_ORIGIN", "A", "B", "A", "B")
    private val stopPickup = listOf(true, true, true, false, false)
    private val riders = setOf("A", "B")

    @Test
    fun legOnboardTracksWhoIsInTheCar() {
        val onboard = RideshareMatchLogic.legOnboard(stopIds, stopPickup, riders)

        assertEquals(emptySet<String>(), onboard[0])          // start
        assertEquals(emptySet<String>(), onboard[1])          // driver -> pickup A, nobody yet
        assertEquals(setOf("A"), onboard[2])                  // pickup A -> pickup B
        assertEquals(setOf("A", "B"), onboard[3])             // shared part
        assertEquals(setOf("B"), onboard[4])                  // dropoff A -> dropoff B
    }

    @Test
    fun theDriversStartingPointIsNeverCountedAsAPassenger() {
        val onboard = RideshareMatchLogic.legOnboard(stopIds, stopPickup, riders)
        assertFalse(onboard.any { "DRIVER_ORIGIN" in it })
    }

    @Test
    fun legOwnerIsSeenFromMyPointOfView() {
        val owner = RideshareMatchLogic.LegOwner.values()
        assertEquals(RideshareMatchLogic.LegOwner.EMPTY, RideshareMatchLogic.legOwner(emptySet(), "A"))
        assertEquals(RideshareMatchLogic.LegOwner.ME, RideshareMatchLogic.legOwner(setOf("A"), "A"))
        assertEquals(RideshareMatchLogic.LegOwner.PARTNER, RideshareMatchLogic.legOwner(setOf("B"), "A"))
        assertEquals(RideshareMatchLogic.LegOwner.BOTH, RideshareMatchLogic.legOwner(setOf("A", "B"), "A"))
        assertEquals(4, owner.size)
    }

    @Test
    fun theSameLegIsMyColourForMeAndTheOtherColourForMyCoPassenger() {
        val onboard = RideshareMatchLogic.legOnboard(stopIds, stopPickup, riders)
        // Leg into stop 2: only A is onboard.
        assertEquals(RideshareMatchLogic.LegOwner.ME, RideshareMatchLogic.legOwner(onboard[2], "A"))
        assertEquals(RideshareMatchLogic.LegOwner.PARTNER, RideshareMatchLogic.legOwner(onboard[2], "B"))
    }

    @Test
    fun windowsMatchTheAgreedValues() {
        assertEquals(300_000L, RideshareMatchLogic.SEARCH_WINDOW_MS)
        assertEquals(60_000L, RideshareMatchLogic.CONFIRM_WINDOW_MS)
    }

    // Route already under way: A was picked up earlier, so only B's pickup and both drop-offs remain.
    private val laterIds = listOf("DRIVER_ORIGIN", "B", "A", "B")
    private val laterPickup = listOf(true, true, false, false)

    @Test
    fun aPassengerPickedUpEarlierIsOnboardFromTheStart() {
        assertEquals(setOf("A"), RideshareMatchLogic.alreadyOnboard(laterIds, laterPickup, riders))
        assertEquals(emptySet<String>(), RideshareMatchLogic.alreadyOnboard(stopIds, stopPickup, riders))
    }

    @Test
    fun legsOfAPartlyDrivenRouteCountThePassengerAlreadyInTheCar() {
        val onboard = RideshareMatchLogic.legOnboard(
            laterIds, laterPickup, riders,
            initiallyOnboard = RideshareMatchLogic.alreadyOnboard(laterIds, laterPickup, riders)
        )
        assertEquals(setOf("A"), onboard[1])          // heading to B's pickup with A aboard
        assertEquals(setOf("A", "B"), onboard[2])     // shared leg
        assertEquals(setOf("B"), onboard[3])          // after A is dropped
    }
}
