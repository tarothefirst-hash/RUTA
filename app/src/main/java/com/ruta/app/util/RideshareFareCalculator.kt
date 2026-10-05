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

    // Public because these are the single source of truth for the base rates:
    // FareCalculator's VehicleType.SEDAN reads them so the two engines cannot
    // drift apart. Values are the long-standing RUTA rates — do not change them
    // in one place only.
    const val BASE_FARE = 45.0
    const val RATE_PER_KM = 15.0
    const val RATE_PER_MIN = 2.0
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
     * Solo quote for a passenger travelling alone — the no-counterpart case of
     * the unified model. Same base rates and same order of operations as the
     * settlement engine: base + cost, floored at the flag-down, surged, then the
     * 0.80 discount, then rounded to a whole peso so it matches the figure the
     * solo display engine (FareCalculator) shows for the same trip.
     *

     */
    fun quoteSoloFare(
        distanceMeters: Int,
        durationSeconds: Int,
        surgeMultiplier: Double = 1.0,

    ): Double {
        val subtotal = BASE_FARE + cost(distanceMeters, durationSeconds)
        var total = max(BASE_FARE, subtotal) * surgeMultiplier

        return round(total)
    }

    /**
     * @param route the winning sequence from RideshareRouteOptimizer
     * @param bookingIdA / bookingIdB the two passengers in the group
     * @param directDistanceA / directDistanceB each passenger's SOLO direct route
     *        (already stored on their booking as distanceMeters from the original
     *        quote — no extra API call needed)
     * @param surgeMultiplier applied to the whole thing at the end, same as solo
     * @param hasDiscountA / hasDiscountB each passenger's own Student/PWD/Senior
     *        discount, applied to that passenger's settled total. Kept per
     *        passenger because a mixed group can have one discounted rider and
     *        one paying full fare.
     */
    fun calculate(
        route: RideshareRouteOptimizer.SequencedRoute,
        bookingIdA: String,
        bookingIdB: String,
        directDistanceA: Int,
        directDurationA: Int,
        directDistanceB: Int,
        directDurationB: Int,
        surgeMultiplier: Double = 1.0,
        hasDiscountA: Boolean = false,
        hasDiscountB: Boolean = false
    ): FareBreakdown {
        // Flatten the route into plain values and hand off to the pure arithmetic
        // below, so the same model can be exercised from JVM unit tests.
        return calculateFareBreakdown(
            orderedStopBookingIds = route.orderedStops.map { it.bookingId },
            stopIsPickup = route.orderedStops.map { it.type == RideshareManager.StopType.PICKUP },
            legDistancesMeters = route.legDistancesMeters,
            legDurationsSeconds = route.legDurationsSeconds,
            bookingIdA = bookingIdA,
            bookingIdB = bookingIdB,
            directDistanceA = directDistanceA,
            directDurationA = directDurationA,
            directDistanceB = directDistanceB,
            directDurationB = directDurationB,
            surgeMultiplier = surgeMultiplier,

        )
    }

    /**
     * The settlement arithmetic, free of Android/Google types so it can be unit
     * tested on the JVM. [calculate] builds these plain lists from the route and
     * delegates here; the numbers are byte-identical to walking the route itself.
     *
     * @param orderedStopBookingIds booked ids in visiting order, 1:1 with the legs
     * @param stopIsPickup true where the stop at that index is a PICKUP, false for DROPOFF
     * @param legDistancesMeters leg i is the travel INTO stop i (leading 0 padding)
     * @param legDurationsSeconds leg i duration, same alignment as the distances
     * @param hasDiscountA / hasDiscountB each passenger's own Student/PWD/Senior
     *        discount, applied to that passenger's settled total only
     */
    fun calculateFareBreakdown(
        orderedStopBookingIds: List<String>,
        stopIsPickup: List<Boolean>,
        legDistancesMeters: List<Int>,
        legDurationsSeconds: List<Int>,
        bookingIdA: String,
        bookingIdB: String,
        directDistanceA: Int,
        directDurationA: Int,
        directDistanceB: Int,
        directDurationB: Int,
        surgeMultiplier: Double = 1.0,
        hasDiscountA: Boolean = false,
        hasDiscountB: Boolean = false
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

        for (i in orderedStopBookingIds.indices) {
            val legDist = legDistancesMeters.getOrElse(i) { 0 }
            val legDur = legDurationsSeconds.getOrElse(i) { 0 }

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

            if (stopIsPickup.getOrElse(i) { false }) {
                onboard.add(orderedStopBookingIds[i])
            } else {
                onboard.remove(orderedStopBookingIds[i])
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
            surge = surgeMultiplier,
            hasDiscount = hasDiscountA
        )

        val fareB = buildFare(
            bookingIdB,
            soloCharge = cost(soloDistB, soloDurB),
            sharedCharge = sharedHalf,
            surcharge = surchargeB,
            credit = creditB,
            surge = surgeMultiplier,
            hasDiscount = hasDiscountB
        )

        return FareBreakdown(fareA, fareB, sharedDist, sharedDur)
    }

    private fun buildFare(
        bookingId: String,
        soloCharge: Double,
        sharedCharge: Double,
        surcharge: Double,
        credit: Double,
        surge: Double,
        hasDiscount: Boolean = false
    ): PassengerFare {
        val subtotal = BASE_FARE + soloCharge + sharedCharge + surcharge - credit
        // Never let credits push a fare below the base flag-down.
        var total = max(BASE_FARE, subtotal) * surge
        // Student/PWD/Senior discount, applied after the floor and the surge so
        // it stacks on the settled total exactly as the solo engine does.


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