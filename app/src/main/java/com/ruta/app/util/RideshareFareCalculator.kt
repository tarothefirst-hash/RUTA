package com.ruta.app.util

import kotlin.math.max
import kotlin.math.round
object RideshareFareCalculator {

    val BASE_FARE: Double get() = FareConfig.rates.soloBase

    // Rideshare pricing (per passenger):
    //   ₱35 base
    //   + the legs ridden ALONE at the regular rate (₱15/km + ₱2/min)
    //   + the legs ridden TOGETHER at the shared rate (₱7.50/km + ₱1/min)
    // so the discount applies only to the part of the trip that is actually shared.
    val SHARED_BASE_FARE: Double get() = FareConfig.rates.sharedBase
    val SHARED_RATE_PER_KM: Double get() = FareConfig.rates.sharedPerKm
    val SHARED_RATE_PER_MIN: Double get() = FareConfig.rates.sharedPerMin
    val RATE_PER_KM: Double get() = FareConfig.rates.soloPerKm
    val RATE_PER_MIN: Double get() = FareConfig.rates.soloPerMin
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
     * Estimates an individual passenger's shared fare before matching.
     * Indicative only (the whole trip at the shared rate); the real price is quoted when a co-passenger is matched.
     */
    fun quoteSharedFare(
        distanceMeters: Int,
        durationSeconds: Int,
        surgeMultiplier: Double = 1.0,
        hasDiscount: Boolean = false
    ): Double {
        val subtotal = SHARED_BASE_FARE + sharedCost(distanceMeters, durationSeconds)
        var total = max(SHARED_BASE_FARE, subtotal) * surgeMultiplier
        if (hasDiscount) total *= 0.80 // Student/PWD discount if applicable
        return round(total)
    }

    /**
     * Solo quote (unmatched / regular ride)
     */
    fun quoteSoloFare(
        distanceMeters: Int,
        durationSeconds: Int,
        surgeMultiplier: Double = 1.0,
        hasDiscount: Boolean = false
    ): Double {
        val subtotal = BASE_FARE + cost(distanceMeters, durationSeconds)
        var total = max(BASE_FARE, subtotal) * surgeMultiplier
        if (hasDiscount) total *= 0.80
        return round(total)
    }

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
            hasDiscountA = hasDiscountA,
            hasDiscountB = hasDiscountB
        )
    }

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

        val onboard = mutableSetOf<String>()

        var soloDistA = 0; var soloDurA = 0
        var soloDistB = 0; var soloDurB = 0
        var sharedDist = 0; var sharedDur = 0

        var onboardDistA = 0; var onboardDurA = 0
        var onboardDistB = 0; var onboardDurB = 0

        for (i in orderedStopBookingIds.indices) {
            val legDist = legDistancesMeters.getOrElse(i) { 0 }
            val legDur = legDurationsSeconds.getOrElse(i) { 0 }

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
            }

            if (stopIsPickup.getOrElse(i) { false }) {
                onboard.add(orderedStopBookingIds[i])
            } else {
                onboard.remove(orderedStopBookingIds[i])
            }
        }

        val extraForB = max(0, onboardDistB - directDistanceB)
        val extraDurForB = max(0, onboardDurB - directDurationB)
        val extraForA = max(0, onboardDistA - directDistanceA)
        val extraDurForA = max(0, onboardDurA - directDurationA)

        val surchargeA = cost(extraForB, extraDurForB)
        val creditB = surchargeA * INCONVENIENCE_CREDIT_RATE

        val surchargeB = cost(extraForA, extraDurForA)
        val creditA = surchargeB * INCONVENIENCE_CREDIT_RATE

        val sharedHalf = sharedCost(sharedDist, sharedDur)

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
        // ₱35 base + solo legs at the regular rate + shared legs at the shared rate
        val subtotal = SHARED_BASE_FARE + soloCharge + sharedCharge + surcharge - credit
        var total = max(SHARED_BASE_FARE, subtotal) * surge
        if (hasDiscount) total *= 0.80

        return PassengerFare(
            bookingId = bookingId,
            baseFare = SHARED_BASE_FARE,
            soloCharge = round2(soloCharge),
            sharedCharge = round2(sharedCharge),
            detourSurcharge = round2(surcharge),
            inconvenienceCredit = round2(credit),
            total = round2(total)
        )
    }

    /** What each passenger pays for [distanceMeters]/[durationSeconds] ridden together. */
    fun sharedCost(distanceMeters: Int, durationSeconds: Int): Double =
        (distanceMeters / 1000.0) * SHARED_RATE_PER_KM + (durationSeconds / 60.0) * SHARED_RATE_PER_MIN

    private fun cost(distanceMeters: Int, durationSeconds: Int): Double {
        val km = distanceMeters / 1000.0
        val min = durationSeconds / 60.0
        return (km * RATE_PER_KM) + (min * RATE_PER_MIN)
    }

    private fun round2(v: Double): Double = round(v * 100.0) / 100.0
}