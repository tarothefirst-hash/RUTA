package com.ruta.app.ui

import android.content.Context
import com.google.android.gms.maps.CameraUpdateFactory
import com.google.android.gms.maps.GoogleMap
import com.google.android.gms.maps.model.LatLng
import com.google.android.gms.maps.model.LatLngBounds
import com.google.android.gms.maps.model.Marker
import com.google.android.gms.maps.model.MarkerOptions
import com.google.android.gms.maps.model.Polyline
import androidx.core.content.ContextCompat
import com.ruta.app.R
import com.ruta.app.util.MapMarkerUtils
import com.ruta.app.util.RideshareFlowManager
import com.ruta.app.util.RideshareManager
import com.ruta.app.util.RideshareMapStyle
import com.ruta.app.util.RideshareRouteOptimizer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Everything the passenger map draws for a rideshare: labeled pins and the
 * per-passenger route colours. One colour per passenger: "you" are ruta_primary
 * (lavender), the co-passenger is blue.
 *
 * It owns the markers and lines it adds, so [clear] removes exactly those and
 * never touches the booking's own route or pins.
 */
class RideshareMapOverlay(
    private val context: Context,
    private val map: GoogleMap,
    private val scope: CoroutineScope,
    private val apiKey: String
) {
    private val markers = mutableListOf<Marker>()
    private val lines = mutableListOf<Polyline>()
    private var generation = 0

    private val youColor: Int get() = ContextCompat.getColor(context, R.color.ruta_primary)
    private val coColor: Int get() = ContextCompat.getColor(context, R.color.ruta_copassenger)
    private val idleColor: Int get() = ContextCompat.getColor(context, R.color.ruta_route_idle)

    fun clear() {
        generation++
        markers.forEach { it.remove() }
        lines.forEach { it.remove() }
        markers.clear()
        lines.clear()
    }

    /**
     * The proposal view: both passengers' pickups and drop-offs as labeled pins
     * and the co-passenger's own route in blue. (Your own route is already drawn,
     * in lavender, by the booking screen.)
     */
    fun showProposal(p: RideshareFlowManager.Proposal, myPickup: LatLng, myDropoff: LatLng) {
        clear()
        val gen = generation
        val coName = firstName(p.partnerName)

        addPin(myPickup, "P", "You", youColor, isDropoff = false, title = "You • Pickup")
        addPin(myDropoff, "D", "You", youColor, isDropoff = true, title = "You • Drop-off")
        addPin(p.partnerPickup, "P", coName, coColor, isDropoff = false, title = "$coName • Pickup")
        addPin(p.partnerDropoff, "D", coName, coColor, isDropoff = true, title = "$coName • Drop-off")

        scope.launch(Dispatchers.IO) {
            val json = DirectionsHelper.downloadUrl(
                DirectionsHelper.getDirectionsUrl(p.partnerPickup, p.partnerDropoff, apiKey)
            )
            val points = DirectionsHelper.parseDirections(json)
            withContext(Dispatchers.Main) {
                if (gen != generation) return@withContext
                if (points.isNotEmpty()) {
                    lines.add(RideshareMapStyle.addLine(map, points, coColor, 12f, 1f))
                }
            }
        }

        fitTo(listOf(myPickup, myDropoff, p.partnerPickup, p.partnerDropoff))
    }

    /**
     * The matched trip: the driver's whole route coloured by who is onboard, and a
     * pin at every stop saying whose stop it is.
     */
    fun showGroupRoute(
        route: RideshareRouteOptimizer.SequencedRoute,
        myBookingId: String,
        partnerName: String?
    ) {
        clear()
        val coName = firstName(partnerName)
        val passengerIds = route.orderedStops.map { it.bookingId }.filter { it != DRIVER_ORIGIN }.toSet()

        lines.addAll(
            RideshareMapStyle.drawSegmentedRoute(
                map = map,
                route = route,
                passengerIds = passengerIds,
                primaryBookingId = myBookingId,
                primaryColor = youColor,
                secondaryColor = coColor,
                idleColor = idleColor
            )
        )

        var order = 1
        for (stop in route.orderedStops) {
            if (stop.bookingId == DRIVER_ORIGIN) continue
            val mine = stop.bookingId == myBookingId
            val isDropoff = stop.type == RideshareManager.StopType.DROPOFF
            val who = if (mine) "You" else coName
            addPin(
                stop.location,
                label = if (isDropoff) "D" else "P",
                caption = who,
                color = if (mine) youColor else coColor,
                isDropoff = isDropoff,
                title = "${order++}. $who • ${if (isDropoff) "Drop-off" else "Pickup"}"
            )
        }
    }

    private fun addPin(
        position: LatLng,
        label: String,
        caption: String,
        color: Int,
        isDropoff: Boolean,
        title: String
    ) {
        val pin = MapMarkerUtils.createPassengerPin(context, label, caption, color, isDropoff)
        val marker = map.addMarker(
            MarkerOptions()
                .position(position)
                .title(title)
                .icon(pin.icon)
                .anchor(0.5f, pin.anchorY)
                .zIndex(3f)
        )
        if (marker != null) markers.add(marker)
    }

    private fun fitTo(points: List<LatLng>) {
        if (points.isEmpty()) return
        try {
            val bounds = LatLngBounds.Builder()
            points.forEach { bounds.include(it) }
            map.animateCamera(CameraUpdateFactory.newLatLngBounds(bounds.build(), 160))
        } catch (e: Exception) {
            // The map may not be laid out yet; the pins are still drawn.
        }
    }

    private fun firstName(name: String?): String =
        name?.trim()?.substringBefore(' ')?.takeIf { it.isNotBlank() } ?: "Co-rider"

    private companion object {
        const val DRIVER_ORIGIN = "DRIVER_ORIGIN"
    }
}
