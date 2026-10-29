package com.ruta.app.util

import com.google.firebase.database.ChildEventListener
import com.google.firebase.database.DataSnapshot
import com.google.firebase.database.DatabaseError
import com.google.firebase.database.DatabaseReference
import com.google.firebase.database.MutableData
import com.google.firebase.database.ServerValue
import com.google.firebase.database.Transaction
import com.google.firebase.database.ValueEventListener

/** Firebase side of the notification list: `notifications/{uid}/{id}`. */
object NotificationStore {

    const val TYPE_ID_REVIEW = "ID_REVIEW"
    const val TYPE_DRIVER_REVIEW = "DRIVER_REVIEW"
    const val TYPE_RIDE = "RIDE"
    const val TYPE_WALLET = "WALLET"
    const val TYPE_SAFETY = "SAFETY"

    /**
     * Writes a notification. With a [key] the write is idempotent: if that notification
     * already exists (even if the user has read it) nothing is overwritten.
     */
    fun push(
        db: DatabaseReference, uid: String, type: String, title: String, body: String, key: String? = null
    ) {
        val parent = db.child("notifications").child(uid)
        val data = mapOf<String, Any>(
            "type" to type, "title" to title, "body" to body,
            "timestamp" to ServerValue.TIMESTAMP, "read" to false
        )
        if (key == null) {
            parent.push().setValue(data)
            return
        }
        parent.child(key).runTransaction(object : Transaction.Handler {
            override fun doTransaction(currentData: MutableData): Transaction.Result {
                if (currentData.value != null) return Transaction.abort()
                currentData.value = data
                return Transaction.success(currentData)
            }
            override fun onComplete(error: DatabaseError?, committed: Boolean, currentData: DataSnapshot?) {}
        })
    }

    fun parse(snap: DataSnapshot): NotificationLogic.Item? {
        val id = snap.key ?: return null
        return NotificationLogic.Item(
            id = id,
            type = snap.child("type").getValue(String::class.java) ?: "",
            title = snap.child("title").getValue(String::class.java) ?: "RUTA",
            body = snap.child("body").getValue(String::class.java) ?: "",
            timestamp = snap.child("timestamp").getValue(Long::class.java) ?: 0L,
            read = snap.child("read").getValue(Boolean::class.java) ?: false
        )
    }

    /** Live list (newest 50) for the bell badge and the notifications screen. */
    fun listen(
        db: DatabaseReference, uid: String, onChange: (List<NotificationLogic.Item>) -> Unit
    ): ValueEventListener {
        val l = object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                onChange(snapshot.children.mapNotNull { parse(it) }.sortedByDescending { it.timestamp })
            }
            override fun onCancelled(error: DatabaseError) {}
        }
        db.child("notifications").child(uid).limitToLast(50).addValueEventListener(l)
        return l
    }

    fun stopListening(db: DatabaseReference, uid: String, l: ValueEventListener) {
        db.child("notifications").child(uid).limitToLast(50).removeEventListener(l)
    }

    /** Child-added stream used by the background service to raise system notifications. */
    fun listenForNew(
        db: DatabaseReference, uid: String, onNew: (NotificationLogic.Item) -> Unit
    ): ChildEventListener {
        val l = object : ChildEventListener {
            override fun onChildAdded(snapshot: DataSnapshot, previousChildName: String?) {
                parse(snapshot)?.let(onNew)
            }
            override fun onChildChanged(snapshot: DataSnapshot, previousChildName: String?) {}
            override fun onChildRemoved(snapshot: DataSnapshot) {}
            override fun onChildMoved(snapshot: DataSnapshot, previousChildName: String?) {}
            override fun onCancelled(error: DatabaseError) {}
        }
        db.child("notifications").child(uid).limitToLast(30).addChildEventListener(l)
        return l
    }

    fun stopListeningForNew(db: DatabaseReference, uid: String, l: ChildEventListener) {
        db.child("notifications").child(uid).limitToLast(30).removeEventListener(l)
    }

    fun markRead(db: DatabaseReference, uid: String, id: String) {
        db.child("notifications").child(uid).child(id).child("read").setValue(true)
    }

    fun markAllRead(db: DatabaseReference, uid: String, items: List<NotificationLogic.Item>) {
        items.filter { !it.read }.forEach { markRead(db, uid, it.id) }
    }
}
