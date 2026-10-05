package com.ruta.app.util

import com.google.android.gms.maps.model.LatLng
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * JVM unit tests for the unified rideshare pricing model in
 * [RideshareFareCalculator].
 *
 * The settlement arithmetic is exposed as
 * [RideshareFareCalculator.calculateFareBreakdown], which takes only plain Kotlin
 * values (no LatLng / stop enum), so the whole split can be pinned on the local
 * JVM with the existing JUnit4 dependency — no Robolectric, no new dependencies.
 *
 * These tests also guard against the two pricing engines drifting apart: the solo
 * display engine ([FareCalculator]) and the shared engine's solo quote
 * ([RideshareFareCalculator.quoteSoloFare]) must agree for the same trip.
 */
class RideshareFareCalculatorTest {

    private val delta = 0.01

    // ------------------------------------------------------------------
    // Solo quote — the no-counterpart case of the unified model.
    // ------------------------------------------------------------------

    // flagDown 45 + 5 km * 15/km + 10 min * 2/min = 140.00
    @Test
    fun quoteSoloFare_fiveKmTenMin_is140() {
        assertEquals(140.0, RideshareFareCalculator.quoteSoloFare(5000, 600, 1.0), delta)
    }

    // Same trip at the morning-peak surge: 140 * 1.25 = 175.00
    @Test
    fun quoteSoloFare_appliesSurge() {
        assertEquals(175.0, RideshareFareCalculator.quoteSoloFare(5000, 600, 1.25), delta)
    }

    // Zero distance and duration still pays the flag-down: max(45, 45) = 45.00
    @Test
    fun quoteSoloFare_zeroTrip_isFlagDown() {
        assertEquals(45.0, RideshareFareCalculator.quoteSoloFare(0, 0, 1.0), delta)
    }

    // Student/PWD/Senior discount: 140 * 0.80 = 112.00
    @Test
    fun quoteSoloFare_appliesDiscountAfterSurge() {
        assertEquals(112.0, RideshareFareCalculator.quoteSoloFare(5000, 600, 1.0, hasDiscount = true), delta)
    }

    // Drift guard: the solo display engine and the shared engine's solo quote
    // price the same 5 km / 10 min trip identically.
    @Test
    fun soloDisplayEngine_andSoloQuote_agree() {
        val display = FareCalculator.calculateFare(
            distanceKm = 5.0,
            durationMin = 10.0,
            vehicleType = VehicleType.SEDAN,
            hourOfDay = 10
        )
        val quote = RideshareFareCalculator.quoteSoloFare(5000, 600, 1.0)
        assertEquals(140.0, display, delta)
        assertEquals(display, quote, delta)
    }

    // ------------------------------------------------------------------
    // Settlement — the 50/50 model with detour surcharge and credit.
    // ------------------------------------------------------------------

    // Stops in visit order: A boards, B boards, A dropped, B dropped.
    // Legs: dist 0/1000/2000/1000 m ; dur 0/120/240/120 s.
    // A direct 2000 m / 240 s ; B direct 3000 m / 360 s.
    private fun settlement(
        surge: Double,
        hasDiscountA: Boolean = false,
        hasDiscountB: Boolean = false
    ) =
        RideshareFareCalculator.calculateFareBreakdown(
            orderedStopBookingIds = listOf("A", "B", "A", "B"),
            stopIsPickup = listOf(true, true, false, false),
            legDistancesMeters = listOf(0, 1000, 2000, 1000),
            legDurationsSeconds = listOf(0, 120, 240, 120),
            bookingIdA = "A",
            bookingIdB = "B",
            directDistanceA = 2000,
            directDurationA = 240,
            directDistanceB = 3000,
            directDurationB = 360,
            surgeMultiplier = surge,
            hasDiscountA = hasDiscountA,
            hasDiscountB = hasDiscountB
        )

    @Test
    fun settlement_attributesSoloSharedDetourAndCredit() {
        val result = settlement(surge = 1.0)
        val a = result.passengerA
        val b = result.passengerB

        // A: solo 1000 m/120 s = 19.00 ; shared 2000 m/240 s = 38.00, half = 19.00.
        // surcharge 0.00 (A made B no later) ; credit 0.30 * B's 19.00 = 5.70.
        // 45 + 19 + 19 + 0 - 5.70 = 77.30
        assertEquals(77.30, a.total, delta)
        assertEquals(45.00, a.baseFare, delta)
        assertEquals(19.00, a.soloCharge, delta)
        assertEquals(19.00, a.sharedCharge, delta)
        assertEquals(0.00, a.detourSurcharge, delta)
        assertEquals(5.70, a.inconvenienceCredit, delta)

        // B: solo 1000 m/120 s = 19.00 ; shared half 19.00.
        // surcharge 19.00 (A added 1000 m/120 s to B's trip) ; credit 0.00.
        // 45 + 19 + 19 + 19 = 102.00
        assertEquals(102.00, b.total, delta)
        assertEquals(45.00, b.baseFare, delta)
        assertEquals(19.00, b.soloCharge, delta)
        assertEquals(19.00, b.sharedCharge, delta)
        assertEquals(19.00, b.detourSurcharge, delta)
        assertEquals(0.00, b.inconvenienceCredit, delta)

        // The shared portion was travelled once: leg index 2, 2000 m / 240 s.
        assertEquals(2000, result.sharedDistanceMeters)
        assertEquals(240, result.sharedDurationSeconds)
    }

    // Same route under the evening-peak surge:
    // A 77.30 * 1.30 = 100.49 ; B 102.00 * 1.30 = 132.60
    @Test
    fun settlement_appliesSurgeToSettledTotals() {
        val result = settlement(surge = 1.30)
        assertEquals(100.49, result.passengerA.total, delta)
        assertEquals(132.60, result.passengerB.total, delta)
    }

    // No-detour symmetric case: both riders' direct trip equals the shared leg,
    // so neither owes a surcharge and neither earns a credit.
    // Shared 4000 m / 480 s = 76.00 ; half = 38.00 ; 45 + 38 = 83.00 each.
    @Test
    fun settlement_noDetourSymmetricCase_chargesOnlyBaseAndSharedHalf() {
        val result = RideshareFareCalculator.calculateFareBreakdown(
            orderedStopBookingIds = listOf("A", "B", "A", "B"),
            stopIsPickup = listOf(true, true, false, false),
            legDistancesMeters = listOf(0, 0, 4000, 0),
            legDurationsSeconds = listOf(0, 0, 480, 0),
            bookingIdA = "A",
            bookingIdB = "B",
            directDistanceA = 4000,
            directDurationA = 480,
            directDistanceB = 4000,
            directDurationB = 480,
            surgeMultiplier = 1.0
        )
        val a = result.passengerA
        val b = result.passengerB

        assertEquals(83.00, a.total, delta)
        assertEquals(0.00, a.detourSurcharge, delta)
        assertEquals(0.00, a.inconvenienceCredit, delta)

        assertEquals(83.00, b.total, delta)
        assertEquals(0.00, b.detourSurcharge, delta)
        assertEquals(0.00, b.inconvenienceCredit, delta)
    }

    // ------------------------------------------------------------------
    // Added coverage for the unified-pricing change (no existing assertion
    // above was modified).
    // ------------------------------------------------------------------

    private data class Trip(
        val distanceMeters: Int,
        val durationSeconds: Int,
        val surge: Double,
        val hasDiscount: Boolean
    )

    private val trips = listOf(
        Trip(5000, 600, 1.0, false),    // flat demo trip
        Trip(5000, 600, 1.25, true),    // morning peak, discounted user
        Trip(5000, 600, 1.30, false),   // evening peak
        Trip(12345, 678, 1.0, false),   // odd numbers, off-peak
        Trip(3000, 900, 1.25, true),    // short distance / long time, peak, discount
        Trip(0, 0, 1.0, false)          // zero trip -> flag-down
    )

    // CORE PROMISE: the passenger's shared quote (the dialog now fills it from
    // quoteSoloFare) must equal the solo display engine's fare for the SAME trip,
    // across several trips including a surge hour and a discounted user.
    @Test
    fun sharedQuote_equalsSoloDisplayEngine_forSameTrip() {
        for (t in trips) {
            val soloDisplay = FareCalculator.calculateFare(
                distanceKm = t.distanceMeters / 1000.0,
                durationMin = t.durationSeconds / 60.0,
                vehicleType = VehicleType.SEDAN,
                hasDiscount = t.hasDiscount,
                hourOfDay = 10,
                surgeMultiplierOverride = t.surge
            )
            val sharedQuote = RideshareFareCalculator.quoteSoloFare(
                distanceMeters = t.distanceMeters,
                durationSeconds = t.durationSeconds,
                surgeMultiplier = t.surge,
                hasDiscount = t.hasDiscount
            )
            assertEquals("solo engine vs shared quote for $t", soloDisplay, sharedQuote, delta)
        }
    }

    // The discount must land identically on all three surfaces: solo display engine,
    // shared quote, and the settled total. 0.80 for a discounted user; none for a
    // non-discounted one.
    @Test
    fun discount_appliedConsistently_acrossQuoteSettlementAndSoloEngine() {
        val soloDiscounted = FareCalculator.calculateFare(
            distanceKm = 5.0, durationMin = 10.0, vehicleType = VehicleType.SEDAN,
            hasDiscount = true, hourOfDay = 10
        )
        val soloUndiscounted = FareCalculator.calculateFare(
            distanceKm = 5.0, durationMin = 10.0, vehicleType = VehicleType.SEDAN,
            hasDiscount = false, hourOfDay = 10
        )
        assertEquals(112.0, soloDiscounted, delta)
        assertEquals(140.0, soloUndiscounted, delta)

        val quoteDiscounted = RideshareFareCalculator.quoteSoloFare(5000, 600, 1.0, true)
        val quoteUndiscounted = RideshareFareCalculator.quoteSoloFare(5000, 600, 1.0, false)
        assertEquals(112.0, quoteDiscounted, delta)
        assertEquals(140.0, quoteUndiscounted, delta)
        assertEquals("0.80 of the undiscounted quote", quoteUndiscounted * 0.80, quoteDiscounted, delta)

        // Settlement of the standard route with and without the discount.
        val undiscounted = settlement(surge = 1.0)
        val discounted = settlement(surge = 1.0, hasDiscountA = true, hasDiscountB = true)
        assertEquals(77.30, undiscounted.passengerA.total, delta)
        assertEquals(102.00, undiscounted.passengerB.total, delta)
        assertEquals(61.84, discounted.passengerA.total, delta)   // 77.30 * 0.80
        assertEquals(81.60, discounted.passengerB.total, delta)   // 102.00 * 0.80
        assertEquals(undiscounted.passengerA.total * 0.80, discounted.passengerA.total, delta)
        assertEquals(undiscounted.passengerB.total * 0.80, discounted.passengerB.total, delta)
    }

    // Per-passenger discount, mixed group: only A is a student/PWD/senior. A's own
    // total takes the 0.80 factor; B's is untouched, proving the flag does not leak
    // across the pair. A 77.30 * 0.80 = 61.84 ; B stays 102.00.
    @Test
    fun perPassengerDiscount_onlyADiscounted_leavesBAtFullFare() {
        val undiscounted = settlement(surge = 1.0)
        val mixed = settlement(surge = 1.0, hasDiscountA = true, hasDiscountB = false)

        assertEquals(61.84, mixed.passengerA.total, delta)                        // 77.30 * 0.80
        assertEquals(102.00, mixed.passengerB.total, delta)                       // unchanged
        assertEquals(undiscounted.passengerA.total * 0.80, mixed.passengerA.total, delta)
        assertEquals(undiscounted.passengerB.total, mixed.passengerB.total, delta)
    }

    // Mirror image: only B discounted. A pays full fare, B pays 0.80.
    // B 102.00 * 0.80 = 81.60 ; A stays 77.30.
    @Test
    fun perPassengerDiscount_onlyBDiscounted_leavesAAtFullFare() {
        val undiscounted = settlement(surge = 1.0)
        val mixed = settlement(surge = 1.0, hasDiscountA = false, hasDiscountB = true)

        assertEquals(77.30, mixed.passengerA.total, delta)                        // unchanged
        assertEquals(81.60, mixed.passengerB.total, delta)                        // 102.00 * 0.80
        assertEquals(undiscounted.passengerA.total, mixed.passengerA.total, delta)
        assertEquals(undiscounted.passengerB.total * 0.80, mixed.passengerB.total, delta)
    }

    // Both discounted: each total is 0.80 of its own undiscounted value, so the
    // factor is applied once per passenger rather than once to the group.
    @Test
    fun perPassengerDiscount_bothDiscounted_discountsEachOwnTotal() {
        val undiscounted = settlement(surge = 1.0)
        val both = settlement(surge = 1.0, hasDiscountA = true, hasDiscountB = true)

        assertEquals(61.84, both.passengerA.total, delta)                         // 77.30 * 0.80
        assertEquals(81.60, both.passengerB.total, delta)                         // 102.00 * 0.80
        assertEquals(undiscounted.passengerA.total * 0.80, both.passengerA.total, delta)
        assertEquals(undiscounted.passengerB.total * 0.80, both.passengerB.total, delta)
    }

    // Neither discounted (the default path): totals are identical to the pre-change
    // settlement, so callers that pass no flags at all are completely unaffected.
    @Test
    fun perPassengerDiscount_neitherDiscounted_isUnchangedDefaultPath() {
        val defaults = settlement(surge = 1.0)
        val explicitOff = settlement(surge = 1.0, hasDiscountA = false, hasDiscountB = false)

        assertEquals(77.30, defaults.passengerA.total, delta)
        assertEquals(102.00, defaults.passengerB.total, delta)
        assertEquals(defaults.passengerA.total, explicitOff.passengerA.total, delta)
        assertEquals(defaults.passengerB.total, explicitOff.passengerB.total, delta)
    }

    // Order of operations: the factor still lands after the floor AND after the
    // surge, per passenger. Evening peak (A 77.30 * 1.30 = 100.49) with only A
    // discounted: 100.49 * 0.80 = 80.392 -> 80.39 ; B 102.00 * 1.30 = 132.60.
    @Test
    fun perPassengerDiscount_appliedAfterSurge_toThatPassengerAlone() {
        val mixed = settlement(surge = 1.30, hasDiscountA = true, hasDiscountB = false)

        assertEquals(80.39, mixed.passengerA.total, delta)
        assertEquals(132.60, mixed.passengerB.total, delta)
    }

    // The flag-down floor must hold for every settled passenger, including routes
    // where the inconvenience credit is large.
    @Test
    fun settledTotal_isNeverBelowBaseFare() {
        data class Case(
            val dist: List<Int>, val dur: List<Int>,
            val directA: Int, val durA: Int, val directB: Int, val durB: Int
        )
        val cases = listOf(
            Case(listOf(0, 0, 0, 0), listOf(0, 0, 0, 0), 0, 0, 0, 0),
            Case(listOf(0, 1000, 2000, 1000), listOf(0, 120, 240, 120), 2000, 240, 3000, 360),
            // Big shared leg; B's direct is inflated so A earns a large credit.
            Case(listOf(0, 0, 20000, 20000), listOf(0, 0, 3600, 3600), 0, 0, 100000, 0),
            // A long solo leg for A, B never moves.
            Case(listOf(0, 50000, 0, 0), listOf(0, 3600, 0, 0), 50000, 3600, 0, 0)
        )
        for (c in cases) {
            val r = RideshareFareCalculator.calculateFareBreakdown(
                orderedStopBookingIds = listOf("A", "B", "A", "B"),
                stopIsPickup = listOf(true, true, false, false),
                legDistancesMeters = c.dist,
                legDurationsSeconds = c.dur,
                bookingIdA = "A", bookingIdB = "B",
                directDistanceA = c.directA, directDurationA = c.durA,
                directDistanceB = c.directB, directDurationB = c.durB,
                surgeMultiplier = 1.0
            )
            assertTrue(
                "A total ${r.passengerA.total} below base for $c",
                r.passengerA.total >= RideshareFareCalculator.BASE_FARE - delta
            )
            assertTrue(
                "B total ${r.passengerB.total} below base for $c",
                r.passengerB.total >= RideshareFareCalculator.BASE_FARE - delta
            )
        }
    }

    // The extracted pure function and the production calculate(...) path must agree
    // byte-for-byte on an identical route, proving the extraction changed nothing.
    @Test
    fun pureBreakdown_matchesProductionCalculate_forIdenticalRoute() {
        val stops = listOf(
            RideshareManager.RideshareStop("A", RideshareManager.StopType.PICKUP, LatLng(14.0, 121.0)),
            RideshareManager.RideshareStop("B", RideshareManager.StopType.PICKUP, LatLng(14.1, 121.1)),
            RideshareManager.RideshareStop("A", RideshareManager.StopType.DROPOFF, LatLng(14.2, 121.2)),
            RideshareManager.RideshareStop("B", RideshareManager.StopType.DROPOFF, LatLng(14.3, 121.3))
        )
        val route = RideshareRouteOptimizer.SequencedRoute(
            orderedStops = stops,
            totalDistanceMeters = 4000,
            totalDurationSeconds = 480,
            legDistancesMeters = listOf(0, 1000, 2000, 1000),
            legDurationsSeconds = listOf(0, 120, 240, 120),
            polyline = emptyList()
        )

        val viaProduction = RideshareFareCalculator.calculate(
            route = route,
            bookingIdA = "A", bookingIdB = "B",
            directDistanceA = 2000, directDurationA = 240,
            directDistanceB = 3000, directDurationB = 360,
            surgeMultiplier = 1.0
        )
        val viaPure = RideshareFareCalculator.calculateFareBreakdown(
            orderedStopBookingIds = listOf("A", "B", "A", "B"),
            stopIsPickup = listOf(true, true, false, false),
            legDistancesMeters = listOf(0, 1000, 2000, 1000),
            legDurationsSeconds = listOf(0, 120, 240, 120),
            bookingIdA = "A", bookingIdB = "B",
            directDistanceA = 2000, directDurationA = 240,
            directDistanceB = 3000, directDurationB = 360,
            surgeMultiplier = 1.0
        )

        assertEquals(viaProduction.passengerA.total, viaPure.passengerA.total, delta)
        assertEquals(viaProduction.passengerB.total, viaPure.passengerB.total, delta)
        assertEquals(viaProduction.passengerA.soloCharge, viaPure.passengerA.soloCharge, delta)
        assertEquals(viaProduction.passengerA.sharedCharge, viaPure.passengerA.sharedCharge, delta)
        assertEquals(viaProduction.passengerA.detourSurcharge, viaPure.passengerA.detourSurcharge, delta)
        assertEquals(viaProduction.passengerA.inconvenienceCredit, viaPure.passengerA.inconvenienceCredit, delta)
        assertEquals(viaProduction.sharedDistanceMeters, viaPure.sharedDistanceMeters)
        assertEquals(viaProduction.sharedDurationSeconds, viaPure.sharedDurationSeconds)
    }

    // Boundary: surcharge / credit are exactly zero when nobody detoured, and step
    // to their exact 30 %-credit values as soon as a metre of detour exists.
    @Test
    fun detourSurcharge_andCredit_boundaryZeroVersusNonZero() {
        // Standard route: B's direct (3000 m) equals B's onboard distance, so A's
        // surcharge is exactly 0 and B's credit is exactly 0. A added 1000 m to B,
        // so B pays a 19.00 surcharge and A is credited 30 % = 5.70.
        val standard = settlement(surge = 1.0)
        assertEquals(0.00, standard.passengerA.detourSurcharge, delta)
        assertEquals(0.00, standard.passengerB.inconvenienceCredit, delta)
        assertEquals(19.00, standard.passengerB.detourSurcharge, delta)
        assertEquals(5.70, standard.passengerA.inconvenienceCredit, delta)
        assertEquals(
            "credit must be exactly 30 % of the other rider's surcharge",
            standard.passengerB.detourSurcharge * 0.30,
            standard.passengerA.inconvenienceCredit,
            delta
        )

        // Truly symmetric, no-detour route: every surcharge and credit is zero.
        val symmetric = RideshareFareCalculator.calculateFareBreakdown(
            orderedStopBookingIds = listOf("A", "B", "A", "B"),
            stopIsPickup = listOf(true, true, false, false),
            legDistancesMeters = listOf(0, 0, 4000, 0),
            legDurationsSeconds = listOf(0, 0, 480, 0),
            bookingIdA = "A", bookingIdB = "B",
            directDistanceA = 4000, directDurationA = 480,
            directDistanceB = 4000, directDurationB = 480,
            surgeMultiplier = 1.0
        )
        assertEquals(0.00, symmetric.passengerA.detourSurcharge, delta)
        assertEquals(0.00, symmetric.passengerA.inconvenienceCredit, delta)
        assertEquals(0.00, symmetric.passengerB.detourSurcharge, delta)
        assertEquals(0.00, symmetric.passengerB.inconvenienceCredit, delta)
    }

    // Units guard: the quote takes METRES and SECONDS. A kilometres/minutes mix-up
    // would be a silent 1000x error, so pin that 5 m != 5 km.
    @Test
    fun quoteSoloFare_treatsDistanceAsMetersNotKilometers() {
        assertEquals(140.0, RideshareFareCalculator.quoteSoloFare(5000, 600, 1.0), delta)
        val fiveMetresTenMinutes = RideshareFareCalculator.quoteSoloFare(5, 600, 1.0)
        assertEquals(65.0, fiveMetresTenMinutes, delta)   // 45 + 0.075 + 20 = 65.075 -> 65
        assertTrue(
            "5 m and 5 km must not price the same",
            kotlin.math.abs(fiveMetresTenMinutes - 140.0) > 1.0
        )
    }
}
