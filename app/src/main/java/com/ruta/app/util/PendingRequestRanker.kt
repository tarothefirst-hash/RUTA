package com.ruta.app.util

import com.google.android.gms.maps.model.LatLng

/**
 * Nearest Neighbor ordering of the pending ride requests a driver can see.
 *
 * The driver app offers the request whose pickup is closest to the driver's
 * current position. This was previously an inline `minByOrNull` inside the
 * request listener; it now lives here so the ordering can be unit tested.
 *
 * Distance is a straight line between two points (no roads or traffic), the
 * same limitation already stated for route sequencing. The measurement is passed
 * in as a parameter so tests do not need Android's `Location.distanceBetween`.
 */
object PendingRequestRanker {

    data class Candidate(val bookingKey: String, val pickup: LatLng)

    /**
     * All candidates ordered nearest pickup first. Ties are broken by booking key
     * so every driver, and every test run, gets the same order.
     */
    fun nearestFirst(
        driver: LatLng,
        candidates: List<Candidate>,
        distance: (LatLng, LatLng) -> Double
    ): List<Candidate> =
        candidates.sortedWith(
            compareBy<Candidate> { distance(driver, it.pickup) }.thenBy { it.bookingKey }
        )

    /** The single nearest candidate, or null when there are no requests. */
    fun nearest(
        driver: LatLng,
        candidates: List<Candidate>,
        distance: (LatLng, LatLng) -> Double
    ): Candidate? = nearestFirst(driver, candidates, distance).firstOrNull()
}
