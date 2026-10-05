package com.ruta.app.util

import com.google.android.gms.maps.model.LatLng
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * JVM unit tests for [RideshareManager]'s matcher and sequencer.
 *
 * These two pieces used to be un-testable on the local JVM because every entry point
 * ultimately calls Android's `Location.distanceBetween`. That measurement has been
 * moved behind plain seams:
 *
 *  - [RideshareManager.Matcher.isCompatible] measures the pair and delegates the four
 *    compatibility rules to the pure overload taking `Boolean`/`Double` values.
 *  - [RideshareManager.Sequencer.sequenceStops] delegates the ordering search to
 *    [RideshareManager.Sequencer.sequenceStopsWithDistance], which takes the
 *    `(LatLng, LatLng) -> Double` measurement as a parameter.
 *
 * No Robolectric, no Mockito, no new dependencies. [LatLng] is a plain coordinate
 * holder from the maps SDK jar (no Android framework at construction time), so the
 * tests can build candidates and feed a deterministic distance function.
 */
class RideshareManagerTest {

    private val matcher = RideshareManager.Matcher
    private val sequencer = RideshareManager.Sequencer

    private val maxPickup = RideshareManager.Matcher.MAX_PICKUP_DETOUR_METERS
    private val maxDropoff = RideshareManager.Matcher.MAX_DROPOFF_DETOUR_METERS
    private val maxAngle = RideshareManager.Matcher.MAX_DIRECTION_ANGLE_DEGREES

    private fun candidate(
        bookingId: String,
        passengerId: String = bookingId,
        pickup: LatLng,
        dropoff: LatLng
    ) = RideshareManager.RideshareCandidate(bookingId, passengerId, pickup, dropoff)

    /** Deterministic stand-in for the geodesic measurement: Euclidean degrees. */
    private val euclidean: (LatLng, LatLng) -> Double = { p, q ->
        val dLat = p.latitude - q.latitude
        val dLng = p.longitude - q.longitude
        kotlin.math.sqrt(dLat * dLat + dLng * dLng)
    }

    private fun pathLength(
        start: LatLng,
        stops: List<RideshareManager.RideshareStop>,
        distance: (LatLng, LatLng) -> Double
    ): Double {
        var total = 0.0
        var prev = start
        for (stop in stops) {
            total += distance(prev, stop.location)
            prev = stop.location
        }
        return total
    }

    // ------------------------------------------------------------------
    // Matcher — compatibility arithmetic
    // ------------------------------------------------------------------

    // Rule 1: the same passenger booking the same ride twice is never compatible,
    // even when every measured value sits comfortably inside the thresholds.
    @Test
    fun samePassenger_isRejected() {
        assertFalse(
            matcher.isCompatible(
                samePassenger = true,
                bearingDifferenceDegrees = 0.0,
                pickupGapMeters = 0.0,
                dropoffGapMeters = 0.0
            )
        )
    }

    // Rule 2 (pickup): the rule is "> threshold rejects", so the boundary itself is
    // accepted. Asserted on both sides against the real MAX_PICKUP_DETOUR_METERS.
    @Test
    fun pickupGap_atThresholdAccepted_justOutsideRejected() {
        assertTrue(matcher.isCompatible(false, 0.0, maxPickup, 0.0))
        assertTrue(matcher.isCompatible(false, 0.0, maxPickup - 0.001, 0.0))
        assertFalse(matcher.isCompatible(false, 0.0, maxPickup + 0.001, 0.0))
    }

    // Rule 3 (dropoff): same strictly-greater-than boundary, real constant.
    @Test
    fun dropoffGap_atThresholdAccepted_justOutsideRejected() {
        assertTrue(matcher.isCompatible(false, 0.0, 0.0, maxDropoff))
        assertTrue(matcher.isCompatible(false, 0.0, 0.0, maxDropoff - 0.001))
        assertFalse(matcher.isCompatible(false, 0.0, 0.0, maxDropoff + 0.001))
    }

    // Rule 4 (bearing): a difference above the angle threshold rejects; at or just
    // below it accepts. Real MAX_DIRECTION_ANGLE_DEGREES.
    @Test
    fun bearingDifference_atThresholdAccepted_justOutsideRejected() {
        assertTrue(matcher.isCompatible(false, maxAngle, 0.0, 0.0))
        assertTrue(matcher.isCompatible(false, maxAngle - 0.5, 0.0, 0.0))
        assertFalse(matcher.isCompatible(false, maxAngle + 0.5, 0.0, 0.0))
    }

    // All four rules satisfied -> compatible.
    @Test
    fun pairInsideEveryThreshold_isAccepted() {
        assertTrue(matcher.isCompatible(false, 10.0, 1000.0, 1000.0))
    }

    // The bearing gate uses the shorter arc. 350 vs 10 is 20 degrees apart, not 340;
    // the raw subtraction then wraps. Real helper, real arithmetic.
    @Test
    fun angleDifference_wrapsAround360() {
        assertEquals(20.0, matcher.angleDifference(350.0, 10.0), 1e-9)
        assertEquals(20.0, matcher.angleDifference(10.0, 350.0), 1e-9)
        assertEquals(0.0, matcher.angleDifference(90.0, 90.0), 1e-9)
        assertEquals(180.0, matcher.angleDifference(0.0, 180.0), 1e-9)
        assertEquals(45.0, matcher.angleDifference(0.0, 315.0), 1e-9)
    }

    // ------------------------------------------------------------------
    // Sequencer — ordering
    // ------------------------------------------------------------------

    @Test
    fun emptyCandidates_returnsEmpty() {
        assertTrue(
            sequencer.sequenceStopsWithDistance(LatLng(0.0, 0.0), emptyList(), euclidean).isEmpty()
        )
    }

    @Test
    fun singleCandidate_returnsItsPickupThenDropoff() {
        val a = candidate("A", pickup = LatLng(0.0, 1.0), dropoff = LatLng(0.0, 3.0))

        val stops = sequencer.sequenceStopsWithDistance(LatLng(0.0, 0.0), listOf(a), euclidean)

        assertEquals(
            listOf(
                "A" to RideshareManager.StopType.PICKUP,
                "A" to RideshareManager.StopType.DROPOFF
            ),
            stops.map { it.bookingId to it.type }
        )
    }

    // Two passengers, both on the same heading, all four points collinear along the
    // meridian (lat increases). Every PICKUP must appear before its own DROPOFF.
    @Test
    fun twoPassengerOrdering_everyPickupPrecedesItsOwnDropoff() {
        val a = candidate("A", pickup = LatLng(0.0, 1.0), dropoff = LatLng(0.0, 3.0))
        val b = candidate("B", pickup = LatLng(0.0, 2.0), dropoff = LatLng(0.0, 4.0))

        val stops = sequencer.sequenceStopsWithDistance(LatLng(0.0, 0.0), listOf(a, b), euclidean)

        assertEquals(4, stops.size)
        val idxPA = stops.indexOfFirst { it.bookingId == "A" && it.type == RideshareManager.StopType.PICKUP }
        val idxDA = stops.indexOfFirst { it.bookingId == "A" && it.type == RideshareManager.StopType.DROPOFF }
        val idxPB = stops.indexOfFirst { it.bookingId == "B" && it.type == RideshareManager.StopType.PICKUP }
        val idxDB = stops.indexOfFirst { it.bookingId == "B" && it.type == RideshareManager.StopType.DROPOFF }
        assertTrue("A pickup must precede A dropoff", idxPA < idxDA)
        assertTrue("B pickup must precede B dropoff", idxPB < idxDB)
    }

    // Same collinear setup; hand-computed totals for all six candidate orderings
    // (start = 0, so the meridian distance between points is just the lat gap):
    //   pA(1) pB(2) dA(3) dB(4) : 1+1+1+1 = 4   <- shortest
    //   pA(1) pB(2) dB(4) dA(3) : 1+1+2+1 = 5
    //   pA(1) dA(3) pB(2) dB(4) : 1+2+1+2 = 6
    //   pB(2) pA(1) dB(4) dA(3) : 2+1+3+1 = 7
    //   pB(2) pA(1) dA(3) dB(4) : 2+1+2+1 = 6
    //   pB(2) dB(4) pA(1) dA(3) : 2+2+3+2 = 9
    // Minimum is unique, so the winner is pA, pB, dA, dB with a total of 4.
    @Test
    fun twoPassengerOrdering_choosesShortestPermutation_totalFour() {
        val start = LatLng(0.0, 0.0)
        val a = candidate("A", pickup = LatLng(0.0, 1.0), dropoff = LatLng(0.0, 3.0))
        val b = candidate("B", pickup = LatLng(0.0, 2.0), dropoff = LatLng(0.0, 4.0))

        val stops = sequencer.sequenceStopsWithDistance(start, listOf(a, b), euclidean)

        assertEquals(
            listOf(
                "A" to RideshareManager.StopType.PICKUP,
                "B" to RideshareManager.StopType.PICKUP,
                "A" to RideshareManager.StopType.DROPOFF,
                "B" to RideshareManager.StopType.DROPOFF
            ),
            stops.map { it.bookingId to it.type }
        )
        assertEquals("hand-computed shortest total", 4.0, pathLength(start, stops, euclidean), 1e-9)
    }

    // 3-passenger insertion branch, also collinear (A=1, B=2, C=3, drops at 3/4/5).
    // At each step the nearest pickup is at an equal-or-shorter distance than the
    // nearest outstanding dropoff, so all three pickups are taken (in candidate
    // order A, B, C) before any dropoff, then the dropoffs fall out in distance
    // order A, B, C. Hand-computed total: 1+1+1+0+1+1 = 5.
    @Test
    fun threePassengerOrdering_takesPickupsThenNearestDropoffs() {
        val start = LatLng(0.0, 0.0)
        val a = candidate("A", pickup = LatLng(0.0, 1.0), dropoff = LatLng(0.0, 3.0))
        val b = candidate("B", pickup = LatLng(0.0, 2.0), dropoff = LatLng(0.0, 4.0))
        val c = candidate("C", pickup = LatLng(0.0, 3.0), dropoff = LatLng(0.0, 5.0))

        val stops = sequencer.sequenceStopsWithDistance(start, listOf(a, b, c), euclidean)

        assertEquals(
            listOf(
                "A" to RideshareManager.StopType.PICKUP,
                "B" to RideshareManager.StopType.PICKUP,
                "C" to RideshareManager.StopType.PICKUP,
                "A" to RideshareManager.StopType.DROPOFF,
                "B" to RideshareManager.StopType.DROPOFF,
                "C" to RideshareManager.StopType.DROPOFF
            ),
            stops.map { it.bookingId to it.type }
        )
        assertEquals("hand-computed insertion total", 5.0, pathLength(start, stops, euclidean), 1e-9)
    }
}
