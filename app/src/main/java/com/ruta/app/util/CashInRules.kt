package com.ruta.app.util

/** Custom cash-in amount rules for the driver wallet: whole or decimal pesos, 20 to 10,000. */
object CashInRules {
    const val MIN = 20.0
    const val MAX = 10_000.0

    sealed class Result {
        data class Valid(val amount: Double) : Result()
        data class Invalid(val message: String) : Result()
        object Empty : Result()
    }

    fun parse(text: String?): Result {
        val clean = text.orEmpty().replace(",", "").replace("₱", "").trim()
        if (clean.isEmpty()) return Result.Empty
        val value = clean.toDoubleOrNull()
            ?: return Result.Invalid("Enter numbers only.")
        if (!value.isFinite()) return Result.Invalid("Enter a valid amount.")
        if (value < MIN) return Result.Invalid("Minimum cash-in is ₱${MIN.toInt()}.")
        if (value > MAX) return Result.Invalid("Maximum cash-in is ₱${"%,d".format(MAX.toInt())}.")
        // Keep centavos sane: at most two decimals.
        val rounded = Math.round(value * 100.0) / 100.0
        return Result.Valid(rounded)
    }
}
