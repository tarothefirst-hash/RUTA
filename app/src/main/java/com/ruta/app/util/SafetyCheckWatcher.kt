package com.ruta.app.util

import android.app.Activity
import androidx.appcompat.app.AlertDialog
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.database.DataSnapshot
import com.google.firebase.database.DatabaseError
import com.google.firebase.database.FirebaseDatabase
import com.google.firebase.database.ValueEventListener

/**
 * App-wide: while signed in, watches `safetyChecks/{uid}` and asks "Are you safe?" on whatever
 * screen is in front, for every emergency stop that has not been answered yet.
 */
object SafetyCheckWatcher {

    private var uid: String? = null
    private var listener: ValueEventListener? = null
    private var top: Activity? = null
    private var showing = false
    private var queue: List<Pending> = emptyList()

    private data class Pending(val key: String, val role: String, val bookingIds: List<String>, val stoppedBy: String)

    private val db get() = FirebaseDatabase.getInstance().reference

    fun onAuthChanged() {
        val me = FirebaseAuth.getInstance().currentUser?.uid
        if (me == uid) return
        detach()
        uid = me
        if (me == null) return
        val l = object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                queue = snapshot.children.mapNotNull { c ->
                    if (c.child("answered").getValue(Boolean::class.java) == true) return@mapNotNull null
                    val key = c.key ?: return@mapNotNull null
                    val ids = c.child("bookingIds").children.mapNotNull { it.getValue(String::class.java) }
                        .ifEmpty { listOf(key) }
                    Pending(
                        key,
                        c.child("role").getValue(String::class.java) ?: SafetyLogic.ROLE_PASSENGER,
                        ids,
                        c.child("stoppedBy").getValue(String::class.java) ?: SafetyLogic.ROLE_PASSENGER
                    )
                }
                maybeShow()
            }
            override fun onCancelled(error: DatabaseError) {}
        }
        listener = l
        db.child("safetyChecks").child(me).addValueEventListener(l)
    }

    fun onResumed(activity: Activity) { top = activity; maybeShow() }
    fun onPaused(activity: Activity) { if (top === activity) top = null }

    private fun detach() {
        val me = uid
        val l = listener
        if (me != null && l != null) db.child("safetyChecks").child(me).removeEventListener(l)
        listener = null
        uid = null
        queue = emptyList()
    }

    private fun maybeShow() {
        val activity = top ?: return
        val me = uid ?: return
        val next = queue.firstOrNull() ?: return
        if (showing || activity.isFinishing || activity.isDestroyed) return
        // Not on the sign-in screens.
        val name = activity.javaClass.simpleName
        if (name == "LoginActivity" || name == "SignupActivity" || name == "SplashActivity" || name == "OtpActivity") return
        showing = true

        val notice = SafetyLogic.endedNotice(next.stoppedBy)
        AlertDialog.Builder(activity)
            .setTitle("Are you safe?")
            .setMessage("${notice.second}\n\nYour trusted contacts will see your answer.")
            .setCancelable(false)
            .setPositiveButton("I'm safe") { _, _ -> finish(me, next, true) }
            .setNegativeButton("I need help") { _, _ -> finish(me, next, false) }
            .show()
    }

    private fun finish(me: String, p: Pending, safe: Boolean) {
        EmergencyStop.answer(db, me, p.key, p.role, p.bookingIds, safe)
        queue = queue.filter { it.key != p.key }
        showing = false
        maybeShow()
    }
}
