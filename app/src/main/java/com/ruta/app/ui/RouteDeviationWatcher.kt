package com.ruta.app.util

import android.content.Context
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
}