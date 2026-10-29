package com.ruta.app.util

/**
 * Drivers may keep accepting rides while their wallet is below zero (commission is taken
 * after each ride), but only down to -₱100. At that point they must top up first.
 */
object WalletLimit {
    const val MAX_DEBT = 100.0

    /** True while the driver is still allowed to accept a booking. */
    fun canAccept(balance: Double): Boolean = balance > -MAX_DEBT

    /** How much more debt is allowed (never negative). */
    fun roomLeft(balance: Double): Double = (balance + MAX_DEBT).coerceAtLeast(0.0)

    /** Reads a wallet value that Firebase may hand back as Long, Double or String. */
    fun parse(raw: Any?): Double = when (raw) {
        is Number -> raw.toDouble()
        is String -> raw.toDoubleOrNull() ?: 0.0
        else -> 0.0
    }
}
