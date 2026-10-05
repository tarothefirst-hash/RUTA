package com.ruta.app.util

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * JVM unit tests for [FareCalculator].
 *
 * [FareCalculator] and [VehicleType] are pure Kotlin with no Android framework
 * dependencies, so these run on the local JVM with the plain JUnit4 dependency —
 * no Robolectric and no new dependencies were added.
 *
 * This is the first automated test in the project and doubles as executable proof
 * of the fare figure the capstone demo narrates (PHP 140.00 for 5 km / 10 min).
 */
class FareCalculatorTest {

    private val delta = 0.01

    // Case 1 — the exact figure the capstone demo narrates.
    // flagDown 45 + 5 km * 15/km + 10 min * 2/min = 140.00
    @Test
    fun soloNoSurgeNoDiscount_5km10min_is140() {
        val fare = FareCalculator.calculateFare(
            distanceKm = 5.0,
            durationMin = 10.0,
            vehicleType = VehicleType.SEDAN,
            hourOfDay = 10
        )
        assertEquals(140.0, fare, delta)
    }

    // Case 2 — peak-hour surge multipliers: 7/8 -> 1.25, 17/18/19 -> 1.30, else 1.0.
    // 140 * 1.25 = 175.00 ; 140 * 1.30 = 182.00 ; 140 * 1.0 = 140.00
    @Test
    fun peakSurge_multipliers_match() {
        assertEquals(175.0, fare(hour = 7), delta)   // morning peak x1.25
        assertEquals(175.0, fare(hour = 8), delta)
        assertEquals(182.0, fare(hour = 17), delta)  // evening peak x1.30
        assertEquals(182.0, fare(hour = 18), delta)
        assertEquals(182.0, fare(hour = 19), delta)
        assertEquals(140.0, fare(hour = 10), delta)  // off-peak x1.0
    }

    @Test
    fun surgeBoundaryHours_areNotPeak() {
        for (h in listOf(6, 9, 16, 20, 0, 23)) {
            assertEquals("hour $h should be x1.0", 140.0, fare(hour = h), delta)
        }
    }

    // Case 3 — Student/PWD/Senior discount applied on top: observed multiplier 0.80.
    // off-peak: 140 * 0.80 = 112.00 ; peak hour 7: 175 * 0.80 = 140.00
    @Test
    fun studentPwdDiscount_isEightyPercent() {
        assertEquals(112.0, fare(hour = 10, hasDiscount = true), delta)
        assertEquals(140.0, fare(hour = 7, hasDiscount = true), delta)
    }

    // Case 4 — zero distance / zero duration returns the flag-down base.
    @Test
    fun zeroDistanceZeroDuration_returnsFlagDownBase() {
        val fare = FareCalculator.calculateFare(
            distanceKm = 0.0,
            durationMin = 0.0,
            vehicleType = VehicleType.SEDAN,
            hourOfDay = 10
        )
        assertEquals(VehicleType.SEDAN.flagDown, 45.0, delta)
        assertEquals(45.0, fare, delta)
    }

    // Edge — result is rounded to a whole peso: 45 + 1.55*15 + 3*2 = 74.25 -> 74.0
    @Test
    fun resultIsRoundedToWholePeso() {
        val fare = FareCalculator.calculateFare(
            distanceKm = 1.55,
            durationMin = 3.0,
            vehicleType = VehicleType.SEDAN,
            hourOfDay = 10
        )
        assertEquals(74.0, fare, delta)
    }

    // Failure-mode documentation: there is no input validation on negatives.
    // A negative distance currently credits money back against the base fare:
    // 45 + (-1)*15 + 0 = 30.00. Asserted as the observed behaviour so a future
    // guard, if added, shows up as an intentional test change.
    @Test
    fun negativeDistance_isNotGuarded() {
        val fare = FareCalculator.calculateFare(
            distanceKm = -1.0,
            durationMin = 0.0,
            vehicleType = VehicleType.SEDAN,
            hourOfDay = 10
        )
        assertEquals(30.0, fare, delta)
    }

    // ------------------------------------------------------------------
    // Round 2 — surgeMultiplierOverride must win over the derived surge,
    // and null must preserve the old derived-surge default.
    // ------------------------------------------------------------------

    // getSurgeMultiplier is now public and is the single source of truth.
    // 7,8 -> 1.25 ; 17,18,19 -> 1.30 ; every other hour -> 1.0.
    @Test
    fun surgeMultiplierMapping_isExposedAndStable() {
        assertEquals(1.25, FareCalculator.getSurgeMultiplier(7), delta)
        assertEquals(1.25, FareCalculator.getSurgeMultiplier(8), delta)
        assertEquals(1.30, FareCalculator.getSurgeMultiplier(17), delta)
        assertEquals(1.30, FareCalculator.getSurgeMultiplier(18), delta)
        assertEquals(1.30, FareCalculator.getSurgeMultiplier(19), delta)
        assertEquals(1.0, FareCalculator.getSurgeMultiplier(10), delta)
    }

    // Override beats the internally derived surge, in BOTH directions.
    // Base 5 km / 10 min = 45 + 75 + 20 = 140.00.
    //   hour 7  derives 1.25, override 1.0  -> UNSURGED 140.00
    //   hour 10 derives 1.0,  override 1.25 -> SURGED   175.00
    @Test
    fun surgeMultiplierOverride_winsOverDerivedSurge() {
        val unSurged = FareCalculator.calculateFare(
            distanceKm = 5.0,
            durationMin = 10.0,
            vehicleType = VehicleType.SEDAN,
            hourOfDay = 7,
            surgeMultiplierOverride = 1.0
        )
        assertEquals(140.0, unSurged, delta)

        val surged = FareCalculator.calculateFare(
            distanceKm = 5.0,
            durationMin = 10.0,
            vehicleType = VehicleType.SEDAN,
            hourOfDay = 10,
            surgeMultiplierOverride = 1.25
        )
        assertEquals(175.0, surged, delta)
    }

    // A null override still falls back to the derived surge: the default path is
    // unchanged. 140 * 1.25 = 175 (h7) ; 140 * 1.30 = 182 (h17) ; 140 * 1.0 (h10).
    @Test
    fun nullOverride_fallsBackToDerivedSurge() {
        assertEquals(175.0, FareCalculator.calculateFare(
            distanceKm = 5.0, durationMin = 10.0, vehicleType = VehicleType.SEDAN,
            hourOfDay = 7, surgeMultiplierOverride = null
        ), delta)
        assertEquals(182.0, FareCalculator.calculateFare(
            distanceKm = 5.0, durationMin = 10.0, vehicleType = VehicleType.SEDAN,
            hourOfDay = 17, surgeMultiplierOverride = null
        ), delta)
        assertEquals(140.0, FareCalculator.calculateFare(
            distanceKm = 5.0, durationMin = 10.0, vehicleType = VehicleType.SEDAN,
            hourOfDay = 10, surgeMultiplierOverride = null
        ), delta)
    }

    private fun fare(
        hour: Int,
        hasDiscount: Boolean = false
    ): Double = FareCalculator.calculateFare(
        distanceKm = 5.0,
        durationMin = 10.0,
        vehicleType = VehicleType.SEDAN,
        hasDiscount = hasDiscount,
        hourOfDay = hour
    )
}
