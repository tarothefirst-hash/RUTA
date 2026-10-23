package com.ruta.app.util

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.database.DataSnapshot
import com.google.firebase.database.DatabaseError
import com.google.firebase.database.FirebaseDatabase
import com.google.firebase.database.ValueEventListener
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import com.ruta.app.R
import com.ruta.app.ui.PassengerHomeActivity

class RutaFirebaseMessagingService : FirebaseMessagingService() {

    override fun onCreate() {
        super.onCreate()
        listenForDirectDatabaseAlerts()
    }

    override fun onMessageReceived(remoteMessage: RemoteMessage) {
        super.onMessageReceived(remoteMessage)

        val title = remoteMessage.notification?.title ?: remoteMessage.data["title"] ?: "RUTA Alert"
        val body = remoteMessage.notification?.body ?: remoteMessage.data["message"] ?: "New update from RUTA."
        val bookingId = remoteMessage.data["bookingId"]
        val contactUid = remoteMessage.data["contactUid"]

        showNotification(title, body, bookingId, contactUid)
    }

    private fun listenForDirectDatabaseAlerts() {
        val myUid = FirebaseAuth.getInstance().currentUser?.uid ?: return
        val alertsRef = FirebaseDatabase.getInstance().reference.child("emergencyAlerts").child(myUid)

        alertsRef.addValueEventListener(object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                for (child in snapshot.children) {
                    val read = child.child("read").getValue(Boolean::class.java) ?: false
                    if (!read) {
                        val title = child.child("title").getValue(String::class.java) ?: "🚨 RUTA EMERGENCY ALERT"
                        val message = child.child("message").getValue(String::class.java) ?: "Your partner triggered an alert!"
                        val bookingId = child.child("bookingId").getValue(String::class.java)
                        val contactUid = child.child("contactUid").getValue(String::class.java)

                        showNotification(title, message, bookingId, contactUid)

                        child.ref.child("read").setValue(true)
                    }
                }
            }

            override fun onCancelled(error: DatabaseError) {}
        })
    }

    private fun showNotification(title: String, body: String, bookingId: String?, contactUid: String?) {
        val channelId = "ruta_push_alerts"
        val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                channelId,
                "RUTA Safety Push Notifications",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "Route deviations and safety alerts"
                enableVibration(true)
                enableLights(true)
            }
            notificationManager.createNotificationChannel(channel)
        }

        val intent = Intent(this, PassengerHomeActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra("NAVIGATE_TO", "LIVE_TRACKING")
            putExtra("BOOKING_ID", bookingId)
            putExtra("CONTACT_UID", contactUid)
        }

        val pendingIntent = PendingIntent.getActivity(
            this,
            System.currentTimeMillis().toInt(),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(this, channelId)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(title)
            .setContentText(body)
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setDefaults(NotificationCompat.DEFAULT_ALL)
            .setAutoCancel(true)
            .setContentIntent(pendingIntent)
            .build()

        notificationManager.notify((System.currentTimeMillis() % 10000).toInt(), notification)
    }

    override fun onNewToken(token: String) {
        super.onNewToken(token)
    }
}