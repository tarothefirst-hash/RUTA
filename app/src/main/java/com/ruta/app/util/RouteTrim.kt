package com.ruta.app.util

import kotlin.math.cos
import kotlin.math.sqrt

/**
 * Pure helper for "erase the part of the route the driver already drove".
 * Works on plain lat/lng pairs so it can be unit tested without Android.
 */
object RouteTrim {

    /** Flat-earth metres between two points; accurate enough for a few hundred metres. */
    fun metersBetween(lat1: Double, lng1: Double, lat2: Double, lng2: Double): Double {
        val dLat = (lat2 - lat1) * 111_320.0
        val dLng = (lng2 - lng1) * 111_320.0 * cos(Math.toRadians((lat1 + lat2) / 2))
        return sqrt(dLat * dLat + dLng * dLng)
    }

    /**
     * Index of the route point nearest the driver, or -1 when the driver is not within
     * [maxMeters] of the line (off route) or nothing has been travelled yet (index 0).
     * Only the first [searchLimit] points are searched so a route that loops back near
     * itself can't make a later leg look "already driven".
     */
    fun passedIndex(
        lats: DoubleArray,
        lngs: DoubleArray,
        driverLat: Double,
        driverLng: Double,
        maxMeters: Double = 50.0,
        searchLimit: Int = Int.MAX_VALUE
    ): Int {
        val n = minOf(lats.size, lngs.size, searchLimit)
        if (n <= 1) return -1
        var best = -1
        var bestDist = Double.MAX_VALUE
        for (i in 0 until n) {
            val d = metersBetween(driverLat, driverLng, lats[i], lngs[i])
            if (d < bestDist) { bestDist = d; best = i }
        }
        return if (best > 0 && bestDist < maxMeters) best else -1
    }
}
