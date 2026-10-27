package com.ruta.app.util

/**
 * Fare rates, editable by the admin (Firebase `config/fare`). Defaults match the
 * original pricing, so the app behaves exactly as before until an admin changes them.
 * New bookings use the current rates; fares already quoted or locked are never touched.
 */
data class FareRates(
    val soloBase: Double = 65.0,
    val soloPerKm: Double = 15.0,
    val soloPerMin: Double = 2.0,
    val sharedBase: Double = 35.0,
    val sharedPerKm: Double = 7.5,
    val sharedPerMin: Double = 1.0
) {
    fun toMap(): Map<String, Any> = mapOf(
        "soloBase" to soloBase, "soloPerKm" to soloPerKm, "soloPerMin" to soloPerMin,
        "sharedBase" to sharedBase, "sharedPerKm" to sharedPerKm, "sharedPerMin" to sharedPerMin
    )

    companion object {
        val DEFAULT = FareRates()

        /** Reads a Firebase map; any missing/invalid field falls back to the default. */
        fun fromMap(m: Map<*, *>?): FareRates {
            fun d(key: String, fallback: Double): Double {
                val v = (m?.get(key) as? Number)?.toDouble()
                return if (v != null && v > 0.0 && v.isFinite()) v else fallback
            }
            return FareRates(
                d("soloBase", DEFAULT.soloBase), d("soloPerKm", DEFAULT.soloPerKm), d("soloPerMin", DEFAULT.soloPerMin),
                d("sharedBase", DEFAULT.sharedBase), d("sharedPerKm", DEFAULT.sharedPerKm), d("sharedPerMin", DEFAULT.sharedPerMin)
            )
        }

        /** Error text for the admin form, or null when the values are acceptable. */
        fun validate(r: FareRates): String? {
            val all = listOf(r.soloBase, r.soloPerKm, r.soloPerMin, r.sharedBase, r.sharedPerKm, r.sharedPerMin)
            if (all.any { !it.isFinite() || it <= 0.0 }) return "Every rate must be greater than zero."
            if (all.any { it > 1000.0 }) return "A rate above 1000 looks like a typo."
            if (r.sharedPerKm > r.soloPerKm || r.sharedPerMin > r.soloPerMin) {
                return "Shared per-km and per-minute rates must not be higher than solo."
            }
            return null
        }
    }
}

object FareConfig {
    @Volatile
    var rates: FareRates = FareRates.DEFAULT
        private set

    fun update(newRates: FareRates) { rates = newRates }
}
