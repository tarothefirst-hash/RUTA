package com.ruta.app.util

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The rideshare price: P35 base, legs ridden alone at the regular rate (P15/km + P2/min),
 * legs ridden together at the shared rate (P7.50/km + P1/min).
 *
 * Example from the spec: A is picked up at the mall, B at SM, both are dropped at
 * Hacienda. A rides alone to SM (solo rate), then A and B ride together to Hacienda.
 */
class RideshareSharedFormulaTest {

    private val delta = 0.005

    private fun breakdown(
        soloMetres: Int, soloSeconds: Int, sharedMetres: Int, sharedSeconds: Int
    ): RideshareFareCalculator.FareBreakdown =
        RideshareFareCalculator.calculateFareBreakdown(
            // driver-origin placeholder first, then A pickup, B pickup, A drop, B drop
            orderedStopBookingIds = listOf("DRIVER", "A", "B", "A", "B"),
            stopIsPickup = listOf(true, true, true, false, false),
            legDistancesMeters = listOf(0, 0, soloMetres, sharedMetres, 0),
            legDurationsSeconds = listOf(0, 0, soloSeconds, sharedSeconds, 0),
            bookingIdA = "A",
            bookingIdB = "B",
            // Direct trips equal what each actually rides, so no detour surcharge or credit.
            directDistanceA = soloMetres + sharedMetres,
            directDurationA = soloSeconds + sharedSeconds,
            directDistanceB = sharedMetres,
            directDurationB = sharedSeconds
        )

    @Test
    fun baseFareIsThirtyFive() {
        assertEquals(35.0, RideshareFareCalculator.SHARED_BASE_FARE, delta)
    }

    @Test
    fun passengerRidingAloneThenSharing_paysRegularThenSharedRates() {
        // A rides 2 km / 4 min alone, then 5 km / 10 min with B.
        val r = breakdown(2000, 240, 5000, 600)

        // A = 35 + (2*15 + 4*2) + (5*7.5 + 10*1) = 35 + 38 + 47.5
        assertEquals(120.5, r.passengerA.total, delta)
        assertEquals(38.0, r.passengerA.soloCharge, delta)
        assertEquals(47.5, r.passengerA.sharedCharge, delta)
    }

    @Test
    fun passengerWhoOnlyRidesTheSharedPart_paysBaseAndSharedRateOnly() {
        val r = breakdown(2000, 240, 5000, 600)

        // B = 35 + (5*7.5 + 10*1) = 82.5, no solo charge at all
        assertEquals(82.5, r.passengerB.total, delta)
        assertEquals(0.0, r.passengerB.soloCharge, delta)
        assertEquals(47.5, r.passengerB.sharedCharge, delta)
    }

    @Test
    fun sharedRateIsHalfTheRegularRate() {
        // 1 km / 1 min together: 7.50 + 1.00; alone it would be 15 + 2.
        assertEquals(8.5, RideshareFareCalculator.sharedCost(1000, 60), delta)
    }

    @Test
    fun surgeMultipliesTheWholeFare() {
        val r = RideshareFareCalculator.calculateFareBreakdown(
            orderedStopBookingIds = listOf("DRIVER", "A", "B", "A", "B"),
            stopIsPickup = listOf(true, true, true, false, false),
            legDistancesMeters = listOf(0, 0, 2000, 5000, 0),
            legDurationsSeconds = listOf(0, 0, 240, 600, 0),
            bookingIdA = "A", bookingIdB = "B",
            directDistanceA = 7000, directDurationA = 840,
            directDistanceB = 5000, directDurationB = 600,
            surgeMultiplier = 1.25
        )
        assertEquals(120.5 * 1.25, r.passengerA.total, delta)
        assertEquals(82.5 * 1.25, r.passengerB.total, delta)
    }
}
