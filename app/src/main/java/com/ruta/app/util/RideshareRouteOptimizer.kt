package com.ruta.app.util

import com.google.android.gms.maps.model.LatLng
import com.ruta.app.ui.DirectionsHelper
import org.json.JSONObject


object RideshareRouteOptimizer {

    data class SequencedRoute(
        val orderedStops: List<RideshareManager.RideshareStop>,
        val totalDistanceMeters: Int,
        val totalDurationSeconds: Int,
        /** Per-leg distances, aligned 1:1 with orderedStops. */
        val legDistancesMeters: List<Int>,
        /** Per-leg durations, aligned 1:1 with orderedStops. */
        val legDurationsSeconds: List<Int>,
        val polyline: List<LatLng>,
        /**
         * Road geometry of each leg, aligned 1:1 with orderedStops (index i is the
         * travel INTO stop i; index 0 is empty). Lets the map colour each part of a
         * shared route by who is onboard. Empty when the geometry is unavailable.
         */
        val legPolylines: List<List<LatLng>> = emptyList()
    )

    /**
     * Evaluates all 4 valid chains and returns the cheapest by total distance.
     * Returns null if every routing call failed.
     */
    fun findOptimalSequence(
        passengerA: RideshareManager.RideshareCandidate,
        passengerB: RideshareManager.RideshareCandidate,
        apiKey: String
    ): SequencedRoute? {
        val pa = RideshareManager.RideshareStop(passengerA.bookingId, RideshareManager.StopType.PICKUP, passengerA.pickup)
        val da = RideshareManager.RideshareStop(passengerA.bookingId, RideshareManager.StopType.DROPOFF, passengerA.dropoff)
        val pb = RideshareManager.RideshareStop(passengerB.bookingId, RideshareManager.StopType.PICKUP, passengerB.pickup)
        val db = RideshareManager.RideshareStop(passengerB.bookingId, RideshareManager.StopType.DROPOFF, passengerB.dropoff)

        val candidateSequences = listOf(
            listOf(pa, pb, da, db),
            listOf(pa, pb, db, da),
            listOf(pb, pa, da, db),
            listOf(pb, pa, db, da)
        )

        return candidateSequences
            .mapNotNull { evaluateSequence(it, apiKey) }
            .minByOrNull { it.totalDistanceMeters }
    }

    /**
     * Prices one chain with a single Directions call: origin = first stop,
     * destination = last stop, everything between as waypoints. One call per
     * sequence rather than one per leg keeps this to 4 API calls total.
     */
    private fun evaluateSequence(
        stops: List<RideshareManager.RideshareStop>,
        apiKey: String
    ): SequencedRoute? {
        if (stops.size < 2) return null

        val origin = stops.first().location
        val destination = stops.last().location
        val waypoints = stops.subList(1, stops.size - 1).map { it.location }

        val url = buildWaypointUrl(origin, destination, waypoints, apiKey)
        val json = DirectionsHelper.downloadUrl(url)
        if (json.isEmpty()) return null

        return try {
            val routes = JSONObject(json).getJSONArray("routes")
            if (routes.length() == 0) return null

            val legs = routes.getJSONObject(0).getJSONArray("legs")
            val legDistances = mutableListOf<Int>()
            val legDurations = mutableListOf<Int>()
            val legGeometry = mutableListOf<List<LatLng>>()
            var totalDistance = 0
            var totalDuration = 0

            for (i in 0 until legs.length()) {
                val leg = legs.getJSONObject(i)
                val d = leg.getJSONObject("distance").getInt("value")
                val t = leg.getJSONObject("duration").getInt("value")
                legDistances.add(d)
                legDurations.add(t)
                legGeometry.add(decodeLeg(leg))
                totalDistance += d
                totalDuration += t
            }

            // Leg i is the travel INTO stop i+1, so there are (stops-1) legs. Pad a
            // leading zero so indices line up 1:1 with orderedStops — the fare
            // splitter walks stops and legs together and relies on that alignment.
            legDistances.add(0, 0)
            legDurations.add(0, 0)
            legGeometry.add(0, emptyList())

            SequencedRoute(
                orderedStops = stops,
                totalDistanceMeters = totalDistance,
                totalDurationSeconds = totalDuration,
                legDistancesMeters = legDistances,
                legDurationsSeconds = legDurations,
                polyline = DirectionsHelper.parseDirections(json),
                legPolylines = legGeometry
            )
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }

    /** Joins the step-by-step road geometry of one Directions leg into a single line. */
    private fun decodeLeg(leg: JSONObject): List<LatLng> {
        val points = ArrayList<LatLng>()
        val steps = leg.optJSONArray("steps") ?: return points
        for (i in 0 until steps.length()) {
            val encoded = steps.getJSONObject(i).optJSONObject("polyline")?.optString("points").orEmpty()
            if (encoded.isNotEmpty()) points.addAll(DirectionsHelper.decodePolyline(encoded))
        }
        return points
    }

    /**
     * Once a driver accepts, prepend their live location to the winning sequence
     * and re-route. This is the path the driver actually navigates, and the one
     * to feed into RouteDeviationManager.isDriverDeviated().
     */
    fun buildFinalDriverRoute(
        driverLocation: LatLng,
        winningSequence: List<RideshareManager.RideshareStop>,
        apiKey: String
    ): SequencedRoute? {
        val full = mutableListOf<RideshareManager.RideshareStop>()
        full.add(
            RideshareManager.RideshareStop(
                bookingId = "DRIVER_ORIGIN",
                type = RideshareManager.StopType.PICKUP,
                location = driverLocation
            )
        )
        full.addAll(winningSequence)
        return evaluateSequence(full, apiKey)
    }

    /**
     * Directions URL with waypoints. Note NO optimize:true — the whole point is
     * that WE choose the order (pickup-before-dropoff must hold); letting Google
     * reorder could produce an invalid chain.
     */
    private fun buildWaypointUrl(
        origin: LatLng,
        destination: LatLng,
        waypoints: List<LatLng>,
        apiKey: String
    ): String {
        val originStr = "origin=${origin.latitude},${origin.longitude}"
        val destStr = "destination=${destination.latitude},${destination.longitude}"
        val waypointStr = if (waypoints.isEmpty()) "" else
            "&waypoints=" + waypoints.joinToString("|") { "${it.latitude},${it.longitude}" }
        return "https://maps.googleapis.com/maps/api/directions/json?$originStr&$destStr$waypointStr&mode=driving&key=$apiKey"
    }
}