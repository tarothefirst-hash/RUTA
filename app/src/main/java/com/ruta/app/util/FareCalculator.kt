package com.ruta.app.util

enum class VehicleType(
    val flagDown: Double,
    val ratePerKm: Double,
    val ratePerMin: Double
) {
    SEDAN(45.0, 15.0, 2.0),
    SUV(55.0, 18.0, 2.0)
}

object FareCalculator {
//dapat kahit icancel ng isa sa rideshare ano mangyayari sa babayaran
    fun calculateFare(
        distanceKm: Double,
        durationMin: Double,
        vehicleType: VehicleType,
        isShared: Boolean = false,
        hasDiscount: Boolean = false,
        sharedDiscountPercentage: Double = 0.25 // 25% discount for shared rides
    ): Double {
        val distanceCost = distanceKm * vehicleType.ratePerKm
        val timeCost = durationMin * vehicleType.ratePerMin
        var totalFare = vehicleType.flagDown + distanceCost + timeCost

        // Apply shared discount
        if (isShared) {
            totalFare *= (1.0 - sharedDiscountPercentage)
            // passenger 1
        }

        // Apply 20% Student/Senior/PWD discount
        if (hasDiscount) {
            totalFare *= 0.80
        }

        return kotlin.math.round(totalFare)
    }
}