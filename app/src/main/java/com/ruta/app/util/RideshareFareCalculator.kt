package com.ruta.app.util

import kotlin.math.max
import kotlin.math.round

/**
 * RideshareFareCalculator
 * -----------------------
 * The RUTA 50/50 rideshare pricing model.
 *
 *   Fare_X = Base
 *          + (X's solo km x Rkm  +  X's solo min x Rmin)     <- 100% to X
 *          + (shared km x Rkm + shared min x Rmin) / 2       <- 50/50 split
 *          + Detour Surcharge (if X made the OTHER rider travel further)
 *          - Inconvenience Credit (if the OTHER rider did that to X)
 *
 * HOW "DETOUR" IS MEASURED
 * "A took a side-street loop" isn't something code can see on a map. What it CAN
 * see is the consequence: B sat in the car longer than B's own direct route would
 * have taken. So:
 *
 *     detour caused by A = B's actual onboard distance - B's direct solo distance
 *
 * A pays that extra distance at the full standard rate (charged ONCE — it is not
 * also billed as solo distance, which would double-charge it). 30% of that
 * surcharge is credited back to B for the inconvenience.
 *
 * If A adds nothing to B's journey, A pays no surcharge. Symmetric for B.
 *
 * REVENUE NOTE: the 30% credit is paid out of platform revenue, not by the other
 * passenger — the group total collected is (surcharge x 0.30) less than the sum of
 * charges. That's a deliberate goodwill cost, not a rounding bug.
 */
object RideshareFareCalculator {

    private const val BASE_FARE = 45.0
    private const val RATE_PER_KM = 15.0
    private const val RATE_PER_MIN = 2.0
    private const val INCONVENIENCE_CREDIT_RATE = 0.30

    data class PassengerFare(
        val bookingId: String,
        val baseFare: Double,
        val soloCharge: Double,
        val sharedCharge: Double,
        val detourSurcharge: Double,
        val inconvenienceCredit: Double,
        val total: Double
    )

    data class FareBreakdown(
        val passengerA: PassengerFare,
        val passengerB: PassengerFare,
        val sharedDistanceMeters: Int,
        val sharedDurationSeconds: Int
    )

    /**
     * @param route the winning sequence from RideshareRouteOptimizer
     * @param bookingIdA / bookingIdB the two passengers in the group
     * @param directDistanceA / directDistanceB each passenger's SOLO direct route
     *        (already stored on their booking as distanceMeters from the original
     *        quote — no extra API call needed)
     * @param surgeMultiplier applied to the whole thing at the end, same as solo
     */
    fun calculate(
        route: RideshareRouteOptimizer.SequencedRoute,
        bookingIdA: String,
        bookingIdB: String,
        directDistanceA: Int,
        directDurationA: Int,
        directDistanceB: Int,
        directDurationB: Int,
        surgeMultiplier: Double = 1.0
    ): FareBreakdown {

        // --- Walk the sequence, attributing every leg to whoever was onboard ---
        val onboard = mutableSetOf<String>()

        var soloDistA = 0; var soloDurA = 0
        var soloDistB = 0; var soloDurB = 0
        var sharedDist = 0; var sharedDur = 0

        // Total distance each passenger personally sat through, used for the
        // detour comparison below.
        var onboardDistA = 0; var onboardDurA = 0
        var onboardDistB = 0; var onboardDurB = 0

        for (i in route.orderedStops.indices) {
            val legDist = route.legDistancesMeters.getOrElse(i) { 0 }
            val legDur = route.legDurationsSeconds.getOrElse(i) { 0 }

            // Attribute the leg travelled to REACH this stop, based on who was
            // already in the car for it.
            when {
                onboard.contains(bookingIdA) && onboard.contains(bookingIdB) -> {
                    sharedDist += legDist; sharedDur += legDur
                    onboardDistA += legDist; onboardDurA += legDur
                    onboardDistB += legDist; onboardDurB += legDur
                }
                onboard.contains(bookingIdA) -> {
                    soloDistA += legDist; soloDurA += legDur
                    onboardDistA += legDist; onboardDurA += legDur
                }
                onboard.contains(bookingIdB) -> {
                    soloDistB += legDist; soloDurB += legDur
                    onboardDistB += legDist; onboardDurB += legDur
                }
                // Nobody onboard = driver heading to the first pickup. Free.
            }

            val stop = route.orderedStops[i]
            when (stop.type) {
                RideshareManager.StopType.PICKUP -> onboard.add(stop.bookingId)
                RideshareManager.StopType.DROPOFF -> onboard.remove(stop.bookingId)
            }
        }

        // --- Detour: how much longer did each rider's journey get? ---
        val extraForB = max(0, onboardDistB - directDistanceB)
        val extraDurForB = max(0, onboardDurB - directDurationB)
        val extraForA = max(0, onboardDistA - directDistanceA)
        val extraDurForA = max(0, onboardDurA - directDurationA)

        // A caused B's extra distance, so A pays for it and B is credited.
        val surchargeA = cost(extraForB, extraDurForB)
        val creditB = surchargeA * INCONVENIENCE_CREDIT_RATE

        val surchargeB = cost(extraForA, extraDurForA)
        val creditA = surchargeB * INCONVENIENCE_CREDIT_RATE

        // --- Assemble ---
        val sharedHalf = cost(sharedDist, sharedDur) / 2.0

        val fareA = buildFare(
            bookingIdA,
            soloCharge = cost(soloDistA, soloDurA),
            sharedCharge = sharedHalf,
            surcharge = surchargeA,
            credit = creditA,
            surge = surgeMultiplier
        )

        val fareB = buildFare(
            bookingIdB,
            soloCharge = cost(soloDistB, soloDurB),
            sharedCharge = sharedHalf,
            surcharge = surchargeB,
            credit = creditB,
            surge = surgeMultiplier
        )

        return FareBreakdown(fareA, fareB, sharedDist, sharedDur)
    }

    private fun buildFare(
        bookingId: String,
        soloCharge: Double,
        sharedCharge: Double,
        surcharge: Double,
        credit: Double,
        surge: Double
    ): PassengerFare {
        val subtotal = BASE_FARE + soloCharge + sharedCharge + surcharge - credit
        // Never let credits push a fare below the base flag-down.
        val total = max(BASE_FARE, subtotal) * surge

        return PassengerFare(
            bookingId = bookingId,
            baseFare = BASE_FARE,
            soloCharge = round2(soloCharge),
            sharedCharge = round2(sharedCharge),
            detourSurcharge = round2(surcharge),
            inconvenienceCredit = round2(credit),
            total = round2(total)
        )
    }

    private fun cost(distanceMeters: Int, durationSeconds: Int): Double {
        val km = distanceMeters / 1000.0
        val min = durationSeconds / 60.0
        return (km * RATE_PER_KM) + (min * RATE_PER_MIN)
    }

    private fun round2(v: Double): Double = round(v * 100.0) / 100.0
}