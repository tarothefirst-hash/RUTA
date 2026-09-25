package com.ruta.app.util

import android.content.Context
import android.widget.Toast
import com.google.firebase.database.DataSnapshot
import com.google.firebase.database.DatabaseError
import com.google.firebase.database.DatabaseReference
import com.google.firebase.database.FirebaseDatabase
import com.google.firebase.database.ValueEventListener

/**
 * Watches a single booking's routeStatus in real time and fires a tray
 * notification exactly once per DEVIATED transition.
 */
class RouteDeviationWatcher(
    private val context: Context,
    private val bookingId: String,
    private val onDeviationChanged: (isDeviated: Boolean) -> Unit = {}
) {
    private val database: DatabaseReference = FirebaseDatabase.getInstance().reference
    private val bookingRef: DatabaseReference = database.child("bookings").child(bookingId)

    private var listener: ValueEventListener? = null
    private var hasFiredNotification = false

    fun start() {
        if (listener != null) return

        listener = object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                val routeStatus = snapshot.child("routeStatus").getValue(String::class.java)

                if (routeStatus == "DEVIATED") {
                    onDeviationChanged(true)

                    // Fire ONLY the status bar notification banner on deviation.
                    // No automatic full-screen dialog popup!
                    if (!hasFiredNotification) {
                        hasFiredNotification = true
                        RouteDeviationManager.sendDeviationNotification(context, bookingId)
                    }
                } else {
                    onDeviationChanged(false)
                    hasFiredNotification = false
                }
            }

            override fun onCancelled(error: DatabaseError) {}
        }

        bookingRef.addValueEventListener(listener!!)
    }

    fun stop() {
        listener?.let { bookingRef.removeEventListener(it) }
        listener = null
    }

    fun markSafe() {
        bookingRef.child("routeStatus").setValue("NORMAL")
    }

    /**
     * Optional helper in case you want to trigger the emergency alert programmatically.
     */
    fun triggerEmergencyAlert() {
        EmergencyContactManager.sendEmergencyAlertForBooking(database, bookingId) { notified ->
            val message = if (notified > 0) {
                "Alert sent to $notified trusted contact${if (notified == 1) "" else "s"}."
            } else {
                "No trusted contacts set up yet — add one from your Profile."
            }
            Toast.makeText(context, message, Toast.LENGTH_LONG).show()
        }
    }
}