package com.ruta.app.util

enum class VehicleType(
    val flagDown: Double,
    val ratePerKm: Double,
    val ratePerMin: Double
) {
    SEDAN(45.0, 15.0, 2.0),

}

object FareCalculator {

    private fun getSurgeMultiplier(hourOfDay: Int): Double {
        return when (hourOfDay) {
            7, 8 -> 1.25          // Morning Peak (7:00–8:59 AM)
            17, 18, 19 -> 1.30    // Evening Peak (5:00–7:59 PM)
            else -> 1.0
        }
    }

    /**
     * Calculates single-passenger or full-route fares.
     */
    fun calculateFare(
        distanceKm: Double,
        durationMin: Double,
        vehicleType: VehicleType,
        isShared: Boolean = false,
        hasDiscount: Boolean = false,
        sharedDiscountPercentage: Double = 0.25,
        hourOfDay: Int = java.util.Calendar.getInstance().get(java.util.Calendar.HOUR_OF_DAY)
    ): Double {
        val distanceCost = distanceKm * vehicleType.ratePerKm
        val timeCost = durationMin * vehicleType.ratePerMin
        var totalFare = vehicleType.flagDown + distanceCost + timeCost

        totalFare *= getSurgeMultiplier(hourOfDay)

        // Apply shared ride reduction factor to base distance/time rates
        if (isShared) {
            totalFare *= (1.0 - sharedDiscountPercentage)
        }

        // Apply Student/PWD/Senior ID discount
        if (hasDiscount) {
            totalFare *= 0.80
        }

        return kotlin.math.round(totalFare)
    }


    fun calculateRideshareSegmentFare(
        passengerSegmentDistanceKm: Double,
        passengerSegmentDurationMin: Double,
        vehicleType: VehicleType,
        hasDiscount: Boolean = false,
        sharedDiscountPercentage: Double = 0.25,
        hourOfDay: Int = java.util.Calendar.getInstance().get(java.util.Calendar.HOUR_OF_DAY)
    ): Double {
        return calculateFare(
            distanceKm = passengerSegmentDistanceKm,
            durationMin = passengerSegmentDurationMin,
            vehicleType = vehicleType,
            isShared = true,
            hasDiscount = hasDiscount,
            sharedDiscountPercentage = sharedDiscountPercentage,
            hourOfDay = hourOfDay
        )
    }
}