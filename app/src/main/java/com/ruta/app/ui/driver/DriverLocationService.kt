package com.ruta.app.driver

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.os.Looper
import androidx.core.app.NotificationCompat
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.database.FirebaseDatabase
import com.google.firebase.database.ServerValue
import com.ruta.app.R

class DriverLocationService : Service() {

    private lateinit var fusedLocationClient: FusedLocationProviderClient
    private lateinit var locationCallback: LocationCallback

    companion object {
        private const val CHANNEL_ID = "ruta_driver_location_channel"
        private const val NOTIF_ID = 1001

        fun startService(context: Context) {
            val intent = Intent(context, DriverLocationService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stopService(context: Context) {
            val intent = Intent(context, DriverLocationService::class.java)
            context.stopService(intent)
        }
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        startForeground(NOTIF_ID, buildNotification())

        fusedLocationClient = LocationServices.getFusedLocationProviderClient(this)

        val locationRequest = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, 3000)
            .setMinUpdateIntervalMillis(1500)
            .build()

        locationCallback = object : LocationCallback() {
            override fun onLocationResult(locationResult: LocationResult) {
                val lastLocation = locationResult.lastLocation ?: return
                val uid = FirebaseAuth.getInstance().currentUser?.uid ?: return

                val database = FirebaseDatabase.getInstance().reference
                val bearing = if (lastLocation.hasBearing()) lastLocation.bearing.toDouble() else 0.0
                val speed = if (lastLocation.hasSpeed()) lastLocation.speed.toDouble() else 0.0
                val locationMap = hashMapOf<String, Any>(
                    "lat" to lastLocation.latitude,
                    "lng" to lastLocation.longitude,
                    "bearing" to bearing,
                    "speed" to speed,
                    "updatedAt" to ServerValue.TIMESTAMP
                )
                database.child("drivers").child(uid).updateChildren(locationMap)

                // Safety net: if the driver screen is gone (destroyed tab, app in background)
                // keep every passenger on the trip seeing a moving driver. While the screen
                // is alive it already writes these, so the service stays out of the way.
                val prefs = getSharedPreferences("DRIVER_SESSION", Context.MODE_PRIVATE)
                val screenAlive = System.currentTimeMillis() - prefs.getLong("SCREEN_LIVE_AT", 0L) < 6000L
                val ids = prefs.getString("ACTIVE_BOOKING_IDS", "").orEmpty()
                    .split(",").filter { it.isNotBlank() }
                if (!screenAlive && ids.isNotEmpty()) {
                    val live = hashMapOf<String, Any>(
                        "driverLat" to lastLocation.latitude,
                        "driverLng" to lastLocation.longitude,
                        "driverBearing" to bearing,
                        "driverSpeed" to speed,
                        "driverUpdatedAt" to ServerValue.TIMESTAMP
                    )
                    ids.forEach { database.child("bookings").child(it).updateChildren(live) }
                }
            }
        }

        try {
            fusedLocationClient.requestLocationUpdates(
                locationRequest,
                locationCallback,
                Looper.getMainLooper()
            )
        } catch (e: SecurityException) {
            e.printStackTrace()
        }
    }

    private fun buildNotification(): Notification {
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("RUTA Driver Online")
            .setContentText("Location tracking active for ride matching & safety.")
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(true)
            .build()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Driver Location Tracking",
                NotificationManager.IMPORTANCE_LOW
            )
            val manager = getSystemService(NotificationManager::class.java)
            manager?.createNotificationChannel(channel)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        fusedLocationClient.removeLocationUpdates(locationCallback)
    }

    override fun onBind(intent: Intent?): IBinder? = null
}