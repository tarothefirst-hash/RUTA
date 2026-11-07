package com.ruta.app.util

import android.content.Context
import androidx.appcompat.app.AlertDialog
import com.google.firebase.database.DatabaseReference

/**
 * Before booking (passenger) or going online (driver): if the user has no trusted contact yet,
 * ask whether they want to add one first. Never blocks: "Not now" carries on as before.
 */
object TrustedContactPrompt {

    private val askedThisSession = mutableSetOf<String>()

    fun ensure(
        context: Context,
        db: DatabaseReference,
        uid: String,
        isDriver: Boolean,
        oncePerSession: Boolean,
        onAddNow: () -> Unit,
        onContinue: () -> Unit
    ) {
        val key = (if (isDriver) "driver:" else "passenger:") + uid
        db.child("users").child(uid).child("trustedContacts").get()
            .addOnSuccessListener { snap ->
                val ask = SafetyLogic.shouldAskTrustedContact(snap.childrenCount.toInt(), oncePerSession && key in askedThisSession)
                if (!ask) return@addOnSuccessListener onContinue()
                askedThisSession.add(key)
                val who = if (isDriver) "going online" else "booking a ride"
                AlertDialog.Builder(context)
                    .setTitle("Add a trusted contact first?")
                    .setMessage(
                        "Before $who, you can add someone we alert (with your live location) if something goes wrong on a trip. " +
                            "It only takes a minute."
                    )
                    .setPositiveButton("Add now") { _, _ -> onAddNow() }
                    .setNegativeButton("Not now") { _, _ -> onContinue() }
                    .setOnCancelListener { onContinue() }
                    .show()
            }
            // Offline or unreadable: never get in the way of the trip.
            .addOnFailureListener { onContinue() }
    }
}
