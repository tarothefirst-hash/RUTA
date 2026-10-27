package com.ruta.app.util

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.database.ChildEventListener
import com.google.firebase.database.DataSnapshot
import com.google.firebase.database.DatabaseError
import com.google.firebase.database.FirebaseDatabase
import com.google.firebase.database.Query
import com.google.firebase.database.ValueEventListener
import com.ruta.app.MainApplication
import com.ruta.app.R

/**
 * Best-effort "app closed" notifications without a server.
 *
 * While it runs it (1) turns booking status changes into notifications and (2) raises a
 * system notification for every new item in `notifications/{uid}`. Android may stop it
 * (battery saver, force-stop, some phone brands); reopening the app starts it again. For
 * guaranteed delivery a server (Cloud Functions + FCM) is needed.
 */
class NotificationWatchService : Service() {

    private val database = FirebaseDatabase.getInstance().reference
    private var uid: String? = null
    private var newListener: ChildEventListener? = null
    private var bookingsQuery: Query? = null
    private var bookingsListener: ValueEventListener? = null
    private val lastStatus = mutableMapOf<String, String>()
    private var firstBookingSnapshot = true

    companion object {
        private const val WATCH_CHANNEL = "ruta_watch"
        private const val UPDATES_CHANNEL = "ruta_updates"
        private const val WATCH_ID = 2001

        fun start(context: Context) {
            try {
                val i = Intent(context, NotificationWatchService::class.java)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) context.startForegroundService(i)
                else context.startService(i)
            } catch (_: Exception) {
                // Background-start restrictions: it will start the next time the app is opened.
            }
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, NotificationWatchService::class.java))
        }
    }

    override fun onCreate() {
        super.onCreate()
        createChannels()
        val n = buildWatchNotification()
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(WATCH_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(WATCH_ID, n)
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val me = FirebaseAuth.getInstance().currentUser?.uid
        if (me == null) {
            stopSelf()
            return START_NOT_STICKY
        }
        if (me != uid) {
            detach()
            uid = me
            database.child("users").child(me).child("role").get().addOnSuccessListener { snap ->
                if (snap.getValue(String::class.java) == "ADMIN") {
                    stopSelf()
                } else {
                    attach(me)
                }
            }.addOnFailureListener { attach(me) }
        }
        return START_STICKY
    }

    private fun prefs() = getSharedPreferences("NOTIF_STATE", Context.MODE_PRIVATE)

    private fun attach(me: String) {
        // First run for this account: don't replay history, only show what arrives from now on.
        if (!prefs().contains("last_$me")) {
            prefs().edit().putLong("last_$me", System.currentTimeMillis()).apply()
        }

        newListener = NotificationStore.listenForNew(database, me) { item ->
            val last = prefs().getLong("last_$me", 0L)
            if (NotificationLogic.shouldPop(item, last, MainApplication.inForeground)) {
                showSystemNotification(item)
            }
            if (item.timestamp > last) prefs().edit().putLong("last_$me", item.timestamp).apply()
        }

        // Ride updates for this rider: one idempotent notification per booking event.
        firstBookingSnapshot = true
        lastStatus.clear()
        val q = database.child("bookings").orderByChild("passengerId").equalTo(me)
        bookingsQuery = q
        bookingsListener = object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                for (b in snapshot.children) {
                    val id = b.key ?: continue
                    val status = b.child("status").getValue(String::class.java) ?: continue
                    val before = lastStatus.put(id, status)
                    if (firstBookingSnapshot || before == status) continue
                    val text = NotificationLogic.bookingEventText(status) ?: continue
                    NotificationStore.push(
                        database, me, NotificationStore.TYPE_RIDE, text.first, text.second,
                        key = NotificationLogic.bookingEventKey(id, status)
                    )
                }
                firstBookingSnapshot = false
            }
            override fun onCancelled(error: DatabaseError) {}
        }
        q.addValueEventListener(bookingsListener!!)
    }

    private fun detach() {
        val me = uid
        if (me != null) newListener?.let { NotificationStore.stopListeningForNew(database, me, it) }
        bookingsListener?.let { bookingsQuery?.removeEventListener(it) }
        newListener = null
        bookingsListener = null
        bookingsQuery = null
    }

    private fun showSystemNotification(item: NotificationLogic.Item) {
        val launch = packageManager.getLaunchIntentForPackage(packageName) ?: return
        launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        val pi = PendingIntent.getActivity(
            this, item.id.hashCode(), launch, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val n = NotificationCompat.Builder(this, UPDATES_CHANNEL)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(item.title)
            .setContentText(item.body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(item.body))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setDefaults(NotificationCompat.DEFAULT_ALL)
            .setAutoCancel(true)
            .setContentIntent(pi)
            .build()
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.notify(item.id.hashCode(), n)
    }

    private fun buildWatchNotification(): Notification =
        NotificationCompat.Builder(this, WATCH_CHANNEL)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("RUTA")
            .setContentText("Keeping your ride updates coming.")
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .setOngoing(true)
            .build()

    private fun createChannels() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val nm = getSystemService(NotificationManager::class.java) ?: return
        nm.createNotificationChannel(
            NotificationChannel(WATCH_CHANNEL, "Background updates", NotificationManager.IMPORTANCE_MIN)
        )
        nm.createNotificationChannel(
            NotificationChannel(UPDATES_CHANNEL, "Ride & account updates", NotificationManager.IMPORTANCE_HIGH)
        )
    }

    override fun onDestroy() {
        detach()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
