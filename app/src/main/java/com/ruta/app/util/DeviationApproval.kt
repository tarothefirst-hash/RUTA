package com.ruta.app.util

/**
 * Decides what the driver's screen does after a route deviation, from the answers the
 * passengers wrote to their booking(s) (`deviationResponse` = "OK" or "EMERGENCY").
 *
 * Rules agreed for rideshare: any one passenger's "I'm OK" approves the reroute for the
 * whole trip, but a single Emergency from anyone blocks it.
 */
object DeviationApproval {

    const val OK = "OK"
    const val EMERGENCY = "EMERGENCY"

    enum class Outcome { PENDING, APPROVED, BLOCKED }

    fun resolve(responses: Collection<String?>): Outcome {
        val clean = responses.filterNotNull().map { it.trim().uppercase() }
        return when {
            clean.any { it == EMERGENCY } -> Outcome.BLOCKED
            clean.any { it == OK } -> Outcome.APPROVED
            else -> Outcome.PENDING
        }
    }

    /** True when enough time passed with no answer that the original route must be kept. */
    fun timedOut(deviatedAtMillis: Long, nowMillis: Long, windowMillis: Long = 90_000L): Boolean =
        deviatedAtMillis > 0 && nowMillis - deviatedAtMillis >= windowMillis
}
