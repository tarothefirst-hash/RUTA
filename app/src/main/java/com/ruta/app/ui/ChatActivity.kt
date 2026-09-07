package com.ruta.app.ui

import android.os.Bundle
import android.widget.Button
import android.widget.EditText
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.database.ChildEventListener
import com.google.firebase.database.DataSnapshot
import com.google.firebase.database.DatabaseError
import com.google.firebase.database.FirebaseDatabase
import com.ruta.app.R

class ChatActivity : AppCompatActivity() {

    private lateinit var bookingId: String
    private lateinit var currentUserId: String

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_chat)

        bookingId = intent.getStringExtra("BOOKING_ID") ?: run {
            Toast.makeText(this, "No active booking found", Toast.LENGTH_SHORT).show()
            finish()
            return
        }
        currentUserId = FirebaseAuth.getInstance().currentUser?.uid ?: return

        val etChatMessage = findViewById<EditText>(R.id.etChatMessage)
        val btnSendChat = findViewById<Button>(R.id.btnSendChat)
        val toolbar = findViewById<com.google.android.material.appbar.MaterialToolbar>(R.id.chatToolbar)
        setSupportActionBar(toolbar)

// Handle Back Button Click
        toolbar.setNavigationOnClickListener {
            finish() // Closes ChatActivity and returns to PassengerHomeActivity
        }

        btnSendChat.setOnClickListener {
            val text = etChatMessage.text.toString().trim()
            if (text.isNotEmpty()) {
                sendMessage(text)
                etChatMessage.setText("")
            }
        }

        listenForMessages()
    }

    private fun sendMessage(messageText: String) {
        val messageMap = hashMapOf<String, Any>(
            "senderId" to currentUserId,
            "message" to messageText,
            "timestamp" to System.currentTimeMillis()
        )
        FirebaseDatabase.getInstance().reference
            .child("chats").child(bookingId).push().setValue(messageMap)
    }

    private fun listenForMessages() {
        FirebaseDatabase.getInstance().reference
            .child("chats").child(bookingId)
            .addChildEventListener(object : ChildEventListener {
                override fun onChildAdded(snapshot: DataSnapshot, previousChildName: String?) {
                    val msg = snapshot.child("message").getValue(String::class.java)
                    // Messages update in realtime here
                }
                override fun onChildChanged(snapshot: DataSnapshot, previousChildName: String?) {}
                override fun onChildRemoved(snapshot: DataSnapshot) {}
                override fun onChildMoved(snapshot: DataSnapshot, previousChildName: String?) {}
                override fun onCancelled(error: DatabaseError) {}
            })
    }
}