package com.ruta.app.util

import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.database.FirebaseDatabase
import com.google.firebase.messaging.FirebaseMessaging

/**
 * Keeps this phone's push address in `users/{uid}/fcmTokens/{token} = true`, so the
 * Cloud Function can reach it even when the app is closed.
 */
object PushTokens {

    /** Saves the current token for the signed-in user. Safe to call repeatedly. */
    fun register() {
        val uid = FirebaseAuth.getInstance().currentUser?.uid ?: return
        FirebaseMessaging.getInstance().token.addOnSuccessListener { token -> save(uid, token) }
    }

    fun save(uid: String, token: String) {
        if (token.isBlank()) return
        FirebaseDatabase.getInstance().reference
            .child("users").child(uid).child("fcmTokens").child(sanitize(token)).setValue(true)
    }

    /** Called when logging out: the old token stops working, so the next person on this phone is not sent the previous user's alerts. */
    fun onSignOut() {
        try { FirebaseMessaging.getInstance().deleteToken() } catch (_: Exception) {}
    }

    /** Realtime Database keys cannot contain . $ # [ ] / (FCM tokens normally do not, but be safe). */
    fun sanitize(token: String): String = token.replace(Regex("[.$#\\[\\]/]"), "_")
}
