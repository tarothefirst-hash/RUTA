package com.ruta.app.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * JVM unit tests for [RouteDeviationManager]'s deviation decision.
 *
 * The public [RouteDeviationManager.isDriverDeviated] takes an Android `Location`,
 * which cannot be constructed on a plain JVM test. The decision has therefore been
 * split into two pure pieces that the real entry point delegates to:
 *
 *  - [RouteDeviationManager.deviationThresholdMeters] (accuracy/speed corridor width)
 *  - [RouteDeviationManager.evaluateDeviationReading] (the per-trip persistence rule)
 *
 * No Robolectric, no Mockito, no new dependencies. Each test uses a distinct trip key
 * and clears it first, because the deviation counters are process-wide state.
 */
class RouteDeviationManagerTest {

    private val manager = RouteDeviationManager

    /** An off-track reading: 100 m from the route against a 50 m corridor, heading confirmed. */
    private fun deviated(
        trip: String,
        distanceMeters: Double = 100.0,
        thresholdMeters: Double = 50.0,
        headingConfirms: Boolean = true
    ): Boolean = manager.evaluateDeviationReading(
        routeIsEmpty = false,
        minDistanceMeters = distanceMeters,
        dynamicThresholdMeters = thresholdMeters,
        headingConfirmsDeviation = headingConfirms,
        bookingId = trip
    )

    /** A back-on-track reading: 10 m from the route against a 50 m corridor. */
    private fun onTrack(trip: String): Boolean = deviated(trip, distanceMeters = 10.0)

    private fun emptyRoute(trip: String): Boolean = manager.evaluateDeviationReading(
        routeIsEmpty = true,
        minDistanceMeters = 5000.0,
        dynamicThresholdMeters = 50.0,
        headingConfirmsDeviation = true,
        bookingId = trip
    )

    private fun reset(vararg trips: String) = trips.forEach { manager.clearTripDeviationState(it) }

    // ------------------------------------------------------------------
    // Persistence rule (k >= 3 consecutive off-track pings)
    // ------------------------------------------------------------------

    @Test
    fun singleDeviatedReading_doesNotCountAsDeviated() {
        val trip = "single-reading"
        reset(trip)

        assertFalse(deviated(trip))
    }

    @Test
    fun threeConsecutiveDeviatedReadings_countAsDeviated() {
        val trip = "three-consecutive"
        reset(trip)

        assertFalse(deviated(trip)) // 1
        assertFalse(deviated(trip)) // 2
        assertTrue(deviated(trip))  // 3 -> deviated
    }

    // The on-track branch decays the counter by one (floor 0), it does NOT reset it.
    // Start 0 -> 1 -> 2, back on track decays 2 -> 1, then two more off-track readings
    // reach 3 and trip the alert. A reset-to-zero implementation would only be at 2
    // when the last assertion runs and would return false here.
    @Test
    fun backOnTrack_decaysCounterInsteadOfResettingIt() {
        val trip = "decay"
        reset(trip)

        assertFalse(deviated(trip)) // count 1
        assertFalse(deviated(trip)) // count 2
        assertFalse(onTrack(trip))  // decay 2 -> 1
        assertFalse(deviated(trip)) // count 2
        assertTrue(deviated(trip))  // count 3 -> deviated
    }

    // A reading that is far from the route but whose heading does NOT confirm the
    // deviation is treated as on-track (the condition requires distance AND heading),
    // so it decays the streak rather than advancing it.
    @Test
    fun headingMismatch_decaysInsteadOfCounting() {
        val trip = "heading-mismatch"
        reset(trip)

        assertFalse(deviated(trip))                              // count 1
        assertFalse(deviated(trip))                              // count 2
        assertFalse(deviated(trip, headingConfirms = false))     // far but unconfirmed -> decay to 1
        assertFalse(deviated(trip))                              // count 2
        assertTrue(deviated(trip))                               // count 3 -> deviated
    }

    // ------------------------------------------------------------------
    // Dynamic corridor width
    // ------------------------------------------------------------------

    // Threshold = reported accuracy + 50 m base corridor + 2 * speed (m/s).
    @Test
    fun accuracyAndSpeed_widenTheCorridorExactly() {
        assertEquals(50.0, manager.deviationThresholdMeters(0f, 0f), 1e-9)
        assertEquals(70.0, manager.deviationThresholdMeters(10f, 5f), 1e-9)    // 10 + 50 + 10
        assertEquals(120.5, manager.deviationThresholdMeters(20.5f, 25f), 1e-9) // 20.5 + 50 + 50

        // The widening changes the verdict for the same reading: a 65 m gap is
        // off-route with a clean fix (65 > 50) but inside the widened corridor
        // once accuracy 10 m and speed 5 m/s are taken into account (65 > 70 false).
        val clean = manager.deviationThresholdMeters(0f, 0f)
        val widened = manager.deviationThresholdMeters(10f, 5f)
        assertTrue("65 m is beyond the clean corridor", 65.0 > clean)
        assertFalse("65 m is inside the widened corridor", 65.0 > widened)
    }

    // ------------------------------------------------------------------
    // Per-trip isolation (the earlier cross-trip pollution bug)
    // ------------------------------------------------------------------

    // Two trips must keep independent streaks. If the counter were shared, the third
    // call below (B's second reading) would already be the third consecutive count
    // and would return true.
    @Test
    fun differentTrips_doNotShareCounterState() {
        val a = "isolation-A"
        val b = "isolation-B"
        reset(a, b)

        assertFalse(deviated(a)) // A = 1
        assertFalse(deviated(b)) // B = 1, A still 1
        assertFalse(deviated(b)) // B = 2 -- shared state would make this the 3rd -> true
        assertTrue(deviated(b))  // B = 3 -> deviated
        assertFalse(deviated(a)) // A = 2, untouched by B's streak
        assertTrue(deviated(a))  // A = 3 -> deviated
    }

    // ------------------------------------------------------------------
    // Empty route
    // ------------------------------------------------------------------

    @Test
    fun emptyRoute_returnsNotDeviated_andDoesNotTouchTheCounter() {
        val trip = "empty-route"
        reset(trip)

        assertFalse(emptyRoute(trip))
        assertFalse(emptyRoute(trip))
        assertFalse(emptyRoute(trip))

        // The three empty-route calls must not have accumulated anything: real
        // off-track readings still need three of their own to trip.
        assertFalse(deviated(trip)) // count 1
        assertFalse(deviated(trip)) // count 2
        assertTrue(deviated(trip))  // count 3 -> deviated
    }

    // clearTripDeviationState must drop the streak outright (unlike the decay path):
    // after two readings, clearing then one more reading is only count 1.
    @Test
    fun clearTripDeviationState_dropsTheStreak() {
        val trip = "clear-state"
        reset(trip)

        assertFalse(deviated(trip)) // count 1
        assertFalse(deviated(trip)) // count 2
        manager.clearTripDeviationState(trip)
        assertFalse(deviated(trip)) // count 1 again, NOT 3
        assertFalse(deviated(trip)) // count 2
        assertTrue(deviated(trip))  // count 3 -> deviated
    }
}
