package com.ruta.app.util

import android.location.Location
import com.google.android.gms.maps.model.LatLng

object RouteDeviationManager {

    // Baseline road corridor buffer in meters (handles parallel lanes, parking stops, turning radiuses)
    private const val BASE_CORRIDOR_BUFFER_METERS = 50.0

    /**
     * Calculates T_deviation = A_GPS + B_corridor dynamically.
     * Returns true if driver is beyond the allowed tolerance of the polyline.
     */
    fun isDriverDeviated(driverLocation: Location, routePolylinePoints: List<LatLng>): Boolean {
        if (routePolylinePoints.isEmpty()) return false

        // 1. Dynamic threshold calculation
        val dynamicThreshold = driverLocation.accuracy + BASE_CORRIDOR_BUFFER_METERS

        var minimumDistance = Double.MAX_VALUE
        val driverLatLng = LatLng(driverLocation.latitude, driverLocation.longitude)

        // 2. Measure perpendicular distance to nearest point on active route
        for (point in routePolylinePoints) {
            val results = FloatArray(1)
            Location.distanceBetween(
                driverLatLng.latitude, driverLatLng.longitude,
                point.latitude, point.longitude,
                results
            )
            val distance = results[0].toDouble()
            if (distance < minimumDistance) {
                minimumDistance = distance
            }
        }

        // 3. Return true if driver exceeds dynamic threshold
        return minimumDistance > dynamicThreshold
    }
}