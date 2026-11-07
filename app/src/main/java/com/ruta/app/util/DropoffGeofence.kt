package com.ruta.app.util

/**
 * The drop-off geofence: a drop-off can only be confirmed when the driver is within
 * [RADIUS_METERS] of the drop-off point. Kept free of Android classes so the precision
 * tests can run the exact same code the driver app runs.
 */
object DropoffGeofence {

    const val RADIUS_METERS = 60.0

    data class Result(val distanceMeters: Double, val allowed: Boolean)

    fun distanceMeters(driverLat: Double, driverLng: Double, dropLat: Double, dropLng: Double): Double =
        NavigationLogic.haversine(driverLat, driverLng, dropLat, dropLng)

    fun check(driverLat: Double, driverLng: Double, dropLat: Double, dropLng: Double): Result {
        val d = distanceMeters(driverLat, driverLng, dropLat, dropLng)
        return Result(d, d <= RADIUS_METERS)
    }
}
