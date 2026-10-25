package com.ruta.app.util

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.ruta.app.R
import com.ruta.app.ui.LocationSelectionActivity

/**
 * Local notifications for the rideshare matching flow, so a passenger who left the
 * app while waiting still hears about a co-passenger or the end of the search.
 * Tapping one reopens the booking.
 */
object RideshareNotifier {

    private const val CHANNEL_ID = "rideshare_matching"
    const val ID_MATCH_FOUND = 7101
    const val ID_NO_MATCH = 7102

    fun notify(context: Context, notificationId: Int, title: String, text: String, bookingId: String) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS)
            != PackageManager.PERMISSION_GRANTED
        ) {
            return
        }

        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                context.getString(R.string.notif_channel_rideshare),
                NotificationManager.IMPORTANCE_HIGH
            ).apply { enableVibration(true) }
            manager.createNotificationChannel(channel)
        }

        val intent = Intent(context, LocationSelectionActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra("BOOKING_ID", bookingId)
            putExtra("IS_ACTIVE_BOOKING", true)
        }
        val pendingIntent = PendingIntent.getActivity(
            context,
            notificationId,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(title)
            .setContentText(text)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_STATUS)
            .setAutoCancel(true)
            .setContentIntent(pendingIntent)
            .build()

        manager.notify(notificationId, notification)
    }

    fun cancel(context: Context, notificationId: Int) {
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.cancel(notificationId)
    }
}
