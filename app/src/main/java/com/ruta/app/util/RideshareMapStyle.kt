package com.ruta.app.util

import android.graphics.Color
import com.google.android.gms.maps.GoogleMap
import com.google.android.gms.maps.model.Polyline
import com.google.android.gms.maps.model.PolylineOptions
import com.google.android.gms.maps.model.RoundCap
import com.ruta.app.util.RideshareMatchLogic.LegOwner

/**
 * Draws a shared route so every part of it says whose it is.
 *
 * One colour per passenger: the "primary" passenger (you, on your own phone) is
 * lavender, the other passenger is blue. A leg with both of them onboard draws a
 * wide blue line with the lavender line on top of it, so the shared part visibly
 * belongs to both. The driver driving to the first pickup, with nobody onboard,
 * is grey.
 */
object RideshareMapStyle {

    private const val WIDTH_SINGLE = 12f
    private const val WIDTH_SHARED_OUTER = 18f
    private const val WIDTH_SHARED_INNER = 8f
    private const val WIDTH_IDLE = 8f

    /**
     * @param passengerIds the two booking ids that are riding
     * @param primaryBookingId whose point of view the colours follow
     * @return every polyline added, so the caller can remove them later
     */
    fun drawSegmentedRoute(
        map: GoogleMap,
        route: RideshareRouteOptimizer.SequencedRoute,
        passengerIds: Set<String>,
        primaryBookingId: String,
        primaryColor: Int,
        secondaryColor: Int,
        idleColor: Int
    ): List<Polyline> {
        val added = mutableListOf<Polyline>()

        // No per-leg geometry (older response shape): fall back to one line.
        if (route.legPolylines.size != route.orderedStops.size || route.legPolylines.all { it.isEmpty() }) {
            if (route.polyline.isNotEmpty()) {
                added.add(addLine(map, route.polyline, primaryColor, WIDTH_SINGLE, 1f))
            }
            return added
        }

        val stopIds = route.orderedStops.map { it.bookingId }
        val stopIsPickup = route.orderedStops.map { it.type == RideshareManager.StopType.PICKUP }
        val onboardPerLeg = RideshareMatchLogic.legOnboard(
            stopBookingIds = stopIds,
            stopIsPickup = stopIsPickup,
            passengerIds = passengerIds,
            // A route already under way: whoever is picked up but not yet dropped is in the car.
            initiallyOnboard = RideshareMatchLogic.alreadyOnboard(stopIds, stopIsPickup, passengerIds)
        )

        for (i in route.legPolylines.indices) {
            val points = route.legPolylines[i]
            if (points.size < 2) continue

            when (RideshareMatchLogic.legOwner(onboardPerLeg[i], primaryBookingId)) {
                LegOwner.EMPTY -> added.add(addLine(map, points, idleColor, WIDTH_IDLE, 0f))
                LegOwner.ME -> added.add(addLine(map, points, primaryColor, WIDTH_SINGLE, 2f))
                LegOwner.PARTNER -> added.add(addLine(map, points, secondaryColor, WIDTH_SINGLE, 2f))
                LegOwner.BOTH -> {
                    added.add(addLine(map, points, secondaryColor, WIDTH_SHARED_OUTER, 1f))
                    added.add(addLine(map, points, primaryColor, WIDTH_SHARED_INNER, 2f))
                }
            }
        }
        return added
    }

    /** A single coloured line, used for a passenger's own direct route before the match is final. */
    fun addLine(
        map: GoogleMap,
        points: List<com.google.android.gms.maps.model.LatLng>,
        color: Int,
        width: Float,
        zIndex: Float
    ): Polyline = map.addPolyline(
        PolylineOptions()
            .addAll(points)
            .width(width)
            .color(color)
            .zIndex(zIndex)
            .startCap(RoundCap())
            .endCap(RoundCap())
            .geodesic(true)
    )

    /** Convenience: a lighter tone of [color], for outlines. */
    fun withAlpha(color: Int, alpha: Int): Int =
        Color.argb(alpha, Color.red(color), Color.green(color), Color.blue(color))
}
