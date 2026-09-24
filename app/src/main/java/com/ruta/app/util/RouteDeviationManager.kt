package com.ruta.app.util

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.location.Location
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.google.android.gms.maps.model.LatLng
import com.ruta.app.R
import com.ruta.app.ui.PassengerHomeActivity
import kotlin.math.*

object RouteDeviationManager {

    private const val CHANNEL_ID = "ruta_route_alerts"
    private const val BASE_CORRIDOR_BUFFER_METERS = 50.0
    private var consecutiveDeviations = 0

    /**
     * Evaluates route deviation offline using Speed-Scaled Buffer + Heading Vector matching.
     * Evaluates against polyline segments to eliminate false positives on straight roads.
     */
    fun isDriverDeviated(
        driverLocation: Location,
        routePolylinePoints: List<LatLng>
    ): Boolean {
        if (routePolylinePoints.isEmpty()) return false

        val driverLatLng = LatLng(driverLocation.latitude, driverLocation.longitude)

        // 1. Measure shortest perpendicular distance to nearest polyline SEGMENT
        val (minDistance, segmentIndex) = calculateMinDistanceToPolyline(driverLatLng, routePolylinePoints)

        // 2. Dynamic threshold calculation (Base Buffer + GPS Accuracy + Speed scaling)
        val speedMps = if (driverLocation.hasSpeed()) driverLocation.speed else 0f
        val dynamicThreshold = driverLocation.accuracy + BASE_CORRIDOR_BUFFER_METERS + (speedMps * 2.0)

        // 3. Heading Angle Delta Check — only enforced when a real bearing is available.
        //    A single teleport/mock-location update (Extended Controls, one-shot setValue,
        //    the very first fix after a jump) reports bearing == 0, which used to hard-block
        //    detection no matter how far off-route the distance check already proved. Now it
        //    only *confirms* a deviation when bearing data actually exists.
        val headingConfirmsDeviation = if (driverLocation.hasBearing() && driverLocation.bearing != 0f) {
            calculateHeadingDelta(driverLocation.bearing, routePolylinePoints, segmentIndex) > 45.0
        } else {
            true
        }

        // 4. Persistence Check (k >= 3 consecutive pings off-track)
        if (minDistance > dynamicThreshold && headingConfirmsDeviation) {
            consecutiveDeviations++
            if (consecutiveDeviations >= 3) {
                return true
            }
        } else {
            // Driver is back within bounds: Decay/reset the counter
            consecutiveDeviations = max(0, consecutiveDeviations - 1)
        }

        return false
    }

    private fun calculateMinDistanceToPolyline(point: LatLng, polyline: List<LatLng>): Pair<Double, Int> {
        if (polyline.size < 2) return Pair(Double.MAX_VALUE, 0)
        var minDistance = Double.MAX_VALUE
        var minIndex = 0
        for (i in 0 until polyline.size - 1) {
            val dist = distToSegment(point, polyline[i], polyline[i + 1])
            if (dist < minDistance) {
                minDistance = dist
                minIndex = i
            }
        }
        return Pair(minDistance, minIndex)
    }

    private fun distToSegment(p: LatLng, p1: LatLng, p2: LatLng): Double {
        val l2 = distSq(p1, p2)
        if (l2 == 0.0) return distanceBetween(p, p1)
        var t = ((p.latitude - p1.latitude) * (p2.latitude - p1.latitude) +
                (p.longitude - p1.longitude) * (p2.longitude - p1.longitude)) / l2
        t = max(0.0, min(1.0, t))
        val projection = LatLng(
            p1.latitude + t * (p2.latitude - p1.latitude),
            p1.longitude + t * (p2.longitude - p1.longitude)
        )
        return distanceBetween(p, projection)
    }

    private fun distSq(p1: LatLng, p2: LatLng): Double {
        val dLat = p1.latitude - p2.latitude
        val dLng = p1.longitude - p2.longitude
        return dLat * dLat + dLng * dLng
    }

    private fun distanceBetween(p1: LatLng, p2: LatLng): Double {
        val results = FloatArray(1)
        Location.distanceBetween(p1.latitude, p1.longitude, p2.latitude, p2.longitude, results)
        return results[0].toDouble()
    }

    private fun calculateHeadingDelta(driverBearing: Float, polyline: List<LatLng>, segmentIndex: Int): Double {
        if (polyline.size < 2 || driverBearing.isNaN() || driverBearing == 0f) return 0.0
        val p1 = polyline[segmentIndex]
        val p2 = polyline[segmentIndex + 1]

        val segmentHeading = atan2(
            sin(Math.toRadians(p2.longitude - p1.longitude)) * cos(Math.toRadians(p2.latitude)),
            cos(Math.toRadians(p1.latitude)) * sin(Math.toRadians(p2.latitude)) -
                    sin(Math.toRadians(p1.latitude)) * cos(Math.toRadians(p2.latitude)) * cos(Math.toRadians(p2.longitude - p1.longitude))
        ).let { Math.toDegrees(it) }.let { (it + 360) % 360 }

        var delta = abs(driverBearing - segmentHeading).toDouble()
        if (delta > 180.0) delta = 360.0 - delta
        return delta
    }

    fun sendDeviationNotification(context: Context, bookingId: String) {
        val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Route Safety Alerts",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "Alerts passenger when driver deviates from route"
                enableVibration(true)
                enableLights(true)
            }
            notificationManager.createNotificationChannel(channel)
        }

        val intent = Intent(context, PassengerHomeActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra("BOOKING_ID", bookingId)
        }

        val pendingIntent = PendingIntent.getActivity(
            context,
            System.currentTimeMillis().toInt(),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        // Fallback to launcher icon if ic_notification fails
        val iconRes = try {
            R.drawable.ic_notification
        } catch (e: Exception) {
            R.mipmap.ic_launcher
        }

        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(iconRes)
            .setContentTitle("⚠️ Route Deviation Warning")
            .setContentText("Your driver has moved off the designated path. Tap to review.")
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setDefaults(NotificationCompat.DEFAULT_ALL)
            .setAutoCancel(true)
            .setContentIntent(pendingIntent)
            .build()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(
                    context,
                    Manifest.permission.POST_NOTIFICATIONS
                ) != PackageManager.PERMISSION_GRANTED
            ) {
                // Permission not granted yet! Request handled in Activity
                return
            }
        }

        val notificationId = (System.currentTimeMillis() % 10000).toInt()
        notificationManager.notify(notificationId, notification)
    }
}