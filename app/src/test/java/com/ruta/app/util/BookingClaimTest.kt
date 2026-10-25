package com.ruta.app.util

import com.ruta.app.util.BookingClaim.Decision
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * JVM unit tests for the pure rule behind the atomic "first driver wins" claim.
 *
 * The Firebase transaction itself needs a live database, so what is tested here
 * is the decision it makes on the booking's current state. Includes the
 * two-drivers-accept-at-once scenario found during IT-expert testing: replaying
 * the second driver's attempt against the state the first driver left behind.
 */
class BookingClaimTest {

    private val driverA = "driverA"
    private val driverB = "driverB"

    @Test
    fun openUnassignedRequestCanBeClaimed() {
        assertEquals(Decision.CLAIM, BookingClaim.decide("REQUESTED", null, driverA))
        assertEquals(Decision.CLAIM, BookingClaim.decide("MATCHED", null, driverA))
        assertEquals(Decision.CLAIM, BookingClaim.decide("REQUESTED", "", driverA))
    }

    @Test
    fun secondDriverLosesAfterFirstDriverClaimed() {
        // State left behind once driver A won: ACCEPTED and assigned to A.
        assertEquals(Decision.TAKEN, BookingClaim.decide("ACCEPTED", driverA, driverB))
    }

    @Test
    fun sameDriverRetryingIsTreatedAsWon() {
        assertEquals(Decision.ALREADY_MINE, BookingClaim.decide("ACCEPTED", driverA, driverA))
    }

    @Test
    fun cancelledOrFinishedBookingCannotBeClaimed() {
        assertEquals(Decision.UNAVAILABLE, BookingClaim.decide("CANCELLED", null, driverA))
        assertEquals(Decision.UNAVAILABLE, BookingClaim.decide("COMPLETED", null, driverA))
        assertEquals(Decision.UNAVAILABLE, BookingClaim.decide("IN_PROGRESS", null, driverA))
    }

    @Test
    fun matchingStatusIsNotClaimableByDrivers() {
        // MATCHING is shared-only and invisible to drivers until a co-passenger is found.
        assertEquals(Decision.UNAVAILABLE, BookingClaim.decide("MATCHING", null, driverA))
    }

    @Test
    fun missingBookingIsUnavailable() {
        assertEquals(Decision.UNAVAILABLE, BookingClaim.decide(null, null, driverA))
    }

    @Test
    fun everyDriverPicksTheSameLeadBookingForAGroup() {
        val keys = listOf("-Nzz9", "-Naab", "-Nmmm")
        assertEquals("-Naab", BookingClaim.leadBookingKey(keys))
        assertEquals("-Naab", BookingClaim.leadBookingKey(keys.reversed()))
    }

    @Test
    fun emptyGroupHasNoLeadBooking() {
        assertNull(BookingClaim.leadBookingKey(emptyList()))
    }
}
