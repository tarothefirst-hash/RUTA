package com.ruta.app.precision

import java.math.BigDecimal
import java.math.MathContext
import java.math.RoundingMode

/**
 * An exact rational amount (num / den) with exact decimal numerator, so repeating values such as
 * "seconds / 60" never lose precision. Used by the independent fare calculators.
 */
class Exact(val num: BigDecimal, val den: BigDecimal) {

    operator fun times(x: BigDecimal) = Exact(num * x, den)

    private fun floorInt(): BigDecimal = num.divide(den, 0, RoundingMode.FLOOR)
    private fun remainderTimes2(): BigDecimal = (num - floorInt() * den) * BigDecimal(2) // compared with den

    /** True when the amount ends in exactly .5 */
    fun isTie(): Boolean = remainderTimes2().compareTo(den) == 0

    fun roundHalfUp(): Int = (if (remainderTimes2() >= den) floorInt() + BigDecimal.ONE else floorInt()).toInt()

    fun roundHalfEven(): Int {
        val f = floorInt()
        val cmp = remainderTimes2().compareTo(den)
        return when {
            cmp > 0 -> f + BigDecimal.ONE
            cmp < 0 -> f
            f.remainder(BigDecimal(2)).signum() == 0 -> f
            else -> f + BigDecimal.ONE
        }.toInt()
    }

    fun floor(): Int = floorInt().toInt()

    fun toDouble(): Double = num.divide(den, MathContext(30)).toDouble()

    companion object {
        /** (base + km x perKm + min x perMin), with km = meters/1000 and min = seconds/60, all exact. */
        fun trip(base: String, perKm: String, perMin: String, meters: Int, seconds: Int): Exact {
            val den = BigDecimal(60_000)
            val n = BigDecimal(base) * den + BigDecimal(60) * BigDecimal(meters) * BigDecimal(perKm) +
                BigDecimal(1000) * BigDecimal(seconds) * BigDecimal(perMin)
            return Exact(n, den)
        }
    }
}
