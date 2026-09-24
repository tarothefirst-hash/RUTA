package com.ruta.app.util

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.appcompat.app.AlertDialog
import com.google.firebase.database.DataSnapshot
import com.google.firebase.database.DatabaseError
import com.google.firebase.database.DatabaseReference
import com.google.firebase.database.FirebaseDatabase
import com.google.firebase.database.ValueEventListener

/**
 * Watches a single booking's routeStatus in real time and surfaces a
 * deviation warning dialog + tray notification exactly once per
 * DEVIATED transition. Shared by PassengerHomeActivity and
 * LocationSelectionActivity so the two screens can't drift out of sync.
 */
class RouteDeviationWatcher(
    private val context: Context,
    private val bookingId: String,
    private val onDeviationChanged: (isDeviated: Boolean) -> Unit = {}
) {
    private val bookingRef: DatabaseReference =
        FirebaseDatabase.getInstance().reference.child("bookings").child(bookingId)

    private var listener: ValueEventListener? = null
    private var hasShownDialog = false
    private var activeDialog: AlertDialog? = null

    fun start() {
        if (listener != null) return

        listener = object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                val routeStatus = snapshot.child("routeStatus").getValue(String::class.java)

                if (routeStatus == "DEVIATED") {
                    onDeviationChanged(true)

                    if (!hasShownDialog) {
                        hasShownDialog = true
                        RouteDeviationManager.sendDeviationNotification(context, bookingId)
                        activeDialog = buildDialog().also { it.show() }
                    }
                } else {
                    onDeviationChanged(false)
                    hasShownDialog = false
                    activeDialog?.dismiss()
                    activeDialog = null
                }
            }

            override fun onCancelled(error: DatabaseError) {}
        }

        bookingRef.addValueEventListener(listener!!)
    }

    fun stop() {
        listener?.let { bookingRef.removeEventListener(it) }
        listener = null
        activeDialog?.dismiss()
        activeDialog = null
    }

    fun markSafe() {
        bookingRef.child("routeStatus").setValue("NORMAL")
    }

    private fun buildDialog(): AlertDialog {
        return AlertDialog.Builder(context)
            .setTitle("⚠️ Route Deviation Warning")
            .setMessage("Your driver has moved off the designated path. If you feel unsafe, click below to notify emergency contacts or verify with your driver.")
            .setCancelable(false)
            .setPositiveButton("I'm Safe") { dialog, _ ->
                markSafe()
                dialog.dismiss()
            }
            .setNegativeButton("Emergency / SOS") { dialog, _ ->
                dialog.dismiss()
                val intent = Intent(Intent.ACTION_DIAL).apply { data = Uri.parse("tel:911") }
                context.startActivity(intent)
            }
            .create()
    }
}