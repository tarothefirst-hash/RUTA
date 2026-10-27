package com.ruta.app.util

enum class VehicleType {
    // Rates come from FareConfig (admin-editable, defaults 65 / 15 / 2), so the solo
    // engine and the rideshare engine always read the same numbers.
    SEDAN;

    val flagDown: Double get() = FareConfig.rates.soloBase
    val ratePerKm: Double get() = FareConfig.rates.soloPerKm
    val ratePerMin: Double get() = FareConfig.rates.soloPerMin
}

object FareCalculator {
    const val DISCOUNT_RATE = 0.20

    /**
     * Single source of truth for the time-of-day surge multiplier. Callers that
     * display the surge (for example the booking screen) should read it from here
     * so the multiplier shown is the one applied to the fare.
     */
    fun getSurgeMultiplier(hourOfDay: Int): Double {
        return when (hourOfDay) {
            7, 8 -> 1.25          // Morning Peak (7:00–8:59 AM)
            17, 18, 19 -> 1.30    // Evening Peak (5:00–7:59 PM)
            else -> 1.0
        }
    }

    /**
     * Calculates a single-passenger (solo) fare.
     *
     * When [surgeMultiplierOverride] is supplied it is used verbatim instead of
     * re-deriving the surge from [hourOfDay], so the fare can never disagree with
     * the multiplier a caller has already displayed.
     */
    fun calculateFare(
        distanceKm: Double,
        durationMin: Double,
        vehicleType: VehicleType,
        hasDiscount: Boolean = false,
        hourOfDay: Int = java.util.Calendar.getInstance().get(java.util.Calendar.HOUR_OF_DAY),
        surgeMultiplierOverride: Double? = null
    ): Double {
        val distanceCost = distanceKm * vehicleType.ratePerKm
        val timeCost = durationMin * vehicleType.ratePerMin
        var totalFare = vehicleType.flagDown + distanceCost + timeCost

        totalFare *= (surgeMultiplierOverride ?: getSurgeMultiplier(hourOfDay))

        // Apply Student/PWD/Senior ID discount
        if (hasDiscount) {
            totalFare *= (1.0 - DISCOUNT_RATE)
        }

        return kotlin.math.round(totalFare)
    }
}