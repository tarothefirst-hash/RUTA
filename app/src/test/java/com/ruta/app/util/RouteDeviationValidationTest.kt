package com.ruta.app.util

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Table 40 validation: runs the REAL RouteDeviationManager decision logic
 * (threshold + persistence counter) for the nine DEV scenarios.
 * Put this file in: app/src/test/java/com/ruta/app/util/
 * Run: right-click the class -> Run 'RouteDeviationValidationTest'.
 */
class RouteDeviationValidationTest {

    private val trip = "DEV-TEST"

    @Before fun reset() { RouteDeviationManager.clearTripDeviationState(trip) }
    @After fun clean() { RouteDeviationManager.clearTripDeviationState(trip) }

    /** headingDelta/ bearing: pass headingConfirms = (delta > 45) or true when bearing unavailable. */
    private fun ping(distM: Double, acc: Float, speed: Float, headingConfirms: Boolean): Boolean {
        val theta = RouteDeviationManager.deviationThresholdMeters(acc, speed)
        return RouteDeviationManager.evaluateDeviationReading(false, distM, theta, headingConfirms, trip)
    }

    private fun log(id: String, msg: String) = println("[$id] $msg")

    /** Proves the stored counter equals n by sending off-track pings until the alert fires. */
    private fun pingsUntilAlert(): Int {
        var n = 0
        while (n < 10) { n++; if (ping(120.0, 10f, 8f, true)) return n }
        return -1
    }

    @Test fun dev01_onPlannedRoad() {
        assertEquals(76.0, RouteDeviationManager.deviationThresholdMeters(10f, 8f), 0.001)
        assertFalse(ping(12.0, 10f, 8f, false)); log("DEV-01", "ON ROUTE, theta=76 m")
        assertEquals(3, pingsUntilAlert()) // counter was 0
    }

    @Test fun dev02_insideCorridorHeadingDiffers() {
        assertFalse(ping(70.0, 10f, 8f, true)); log("DEV-02", "ON ROUTE, counter 0")
        assertEquals(3, pingsUntilAlert())
    }

    @Test fun dev03_outsideCorridorHeadingAligned() {
        assertFalse(ping(120.0, 10f, 8f, false)); log("DEV-03", "ON ROUTE (heading not confirmed), counter 0")
        assertEquals(3, pingsUntilAlert())
    }

    @Test fun dev04_firstOffTrackPing() {
        assertFalse(ping(120.0, 10f, 8f, true)); log("DEV-04", "OFF-TRACK ping, no alert, counter 1")
        assertEquals(2, pingsUntilAlert()) // counter was 1 -> alert on 2nd more ping
    }

    @Test fun dev05_threeConsecutivePings() {
        assertFalse(ping(120.0, 10f, 8f, true))
        assertFalse(ping(120.0, 10f, 8f, true))
        assertTrue(ping(120.0, 10f, 8f, true)); log("DEV-05", "ALERT at 3rd ping, counter 3")
    }

    @Test fun dev06_twoOffTrackThenOnRoute() {
        assertFalse(ping(120.0, 10f, 8f, true))
        assertFalse(ping(120.0, 10f, 8f, true))
        assertFalse(ping(12.0, 10f, 8f, false)); log("DEV-06", "No alert, counter 2 -> decays to 1")
        assertEquals(2, pingsUntilAlert()) // counter was 1
    }

    @Test fun dev07_poorAccuracyWidensCorridor() {
        assertEquals(146.0, RouteDeviationManager.deviationThresholdMeters(80f, 8f), 0.001)
        assertFalse(ping(120.0, 80f, 8f, true)); log("DEV-07", "ON ROUTE, theta=146 m")
        assertEquals(3, pingsUntilAlert())
    }

    @Test fun dev08_distanceEqualsThreshold() {
        assertEquals(100.0, RouteDeviationManager.deviationThresholdMeters(10f, 20f), 0.001)
        assertFalse(ping(100.0, 10f, 20f, true)); log("DEV-08", "ON ROUTE (strict > required), theta=100 m")
        assertEquals(3, pingsUntilAlert())
    }

    @Test fun dev09_bearingUnavailable() {
        // bearing == 0 / unavailable => headingConfirmsDeviation = true in isDriverDeviated
        assertFalse(ping(120.0, 10f, 8f, true)); log("DEV-09", "OFF-TRACK ping (heading assumed), counter 1")
        assertEquals(2, pingsUntilAlert())
    }
}