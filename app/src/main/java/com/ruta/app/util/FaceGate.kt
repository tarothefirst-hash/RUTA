package com.ruta.app.util

import android.app.Activity
import android.content.Intent
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.database.FirebaseDatabase
import com.ruta.app.ui.FaceVerificationActivity

/** Sends a signed-in passenger/driver to the face check until they have passed it. */
object FaceGate {
    fun check(activity: Activity) {
        val uid = FirebaseAuth.getInstance().currentUser?.uid ?: return
        FirebaseDatabase.getInstance().reference.child("users").child(uid).get()
            .addOnSuccessListener { u ->
                if (!u.exists() || activity.isFinishing || activity.isDestroyed) return@addOnSuccessListener
                val need = LivenessLogic.needsCheck(
                    role = u.child("role").getValue(String::class.java),
                    verified = u.child("faceVerified").getValue(Boolean::class.java) == true,
                    skipped = u.child("faceSkipped").getValue(Boolean::class.java) == true
                )
                if (need) activity.startActivity(Intent(activity, FaceVerificationActivity::class.java))
            }
    }
}
