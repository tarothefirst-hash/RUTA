package com.ruta.app.ui

import android.os.Bundle
import android.widget.Button
import android.widget.EditText
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.appbar.MaterialToolbar
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.database.ChildEventListener
import com.google.firebase.database.DataSnapshot
import com.google.firebase.database.DatabaseError
import com.google.firebase.database.FirebaseDatabase
import com.ruta.app.R

class ChatActivity : AppCompatActivity() {

    private lateinit var bookingId: String
    private lateinit var currentUserId: String

    private lateinit var recyclerChat: RecyclerView
    private lateinit var chatAdapter: ChatAdapter
    private val messageList = mutableListOf<ChatMessage>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_chat)

        bookingId = intent.getStringExtra("BOOKING_ID") ?: run {
            Toast.makeText(this, "No active booking found", Toast.LENGTH_SHORT).show()
            finish()
            return
        }
        currentUserId = FirebaseAuth.getInstance().currentUser?.uid ?: return

        // Matching your XML IDs: rvChatMessage, btnSendChat, chatToolbar, recyclerChat
        val etChatMessage = findViewById<EditText>(R.id.rvChatMessage)
        val btnSendChat = findViewById<Button>(R.id.btnSendChat)
        val toolbar = findViewById<MaterialToolbar>(R.id.chatToolbar)
        setSupportActionBar(toolbar)
        FirebaseDatabase.getInstance().reference.child("users").child(currentUserId).child("role")
            .get().addOnSuccessListener { snapshot ->
                val role = snapshot.getValue(String::class.java)
                if (role == "DRIVER") {
                    toolbar.title = "Chat with Passenger"
                } else {
                    toolbar.title = "Chat with Driver"
                }
            }
        toolbar.setNavigationOnClickListener {
            finish()
        }

        // Initialize RecyclerView with your layout's ID: recyclerChat
        recyclerChat = findViewById(R.id.recyclerChat)
        val layoutManager = LinearLayoutManager(this)
        layoutManager.stackFromEnd = true // Keeps chat pushed to bottom
        recyclerChat.layoutManager = layoutManager

        chatAdapter = ChatAdapter(messageList, currentUserId)
        recyclerChat.adapter = chatAdapter

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
                    val chatMessage = snapshot.getValue(ChatMessage::class.java)
                    if (chatMessage != null) {
                        messageList.add(chatMessage)
                        chatAdapter.notifyItemInserted(messageList.size - 1)
                        recyclerChat.scrollToPosition(messageList.size - 1)
                    }
                }

                override fun onChildChanged(snapshot: DataSnapshot, previousChildName: String?) {}
                override fun onChildRemoved(snapshot: DataSnapshot) {}
                override fun onChildMoved(snapshot: DataSnapshot, previousChildName: String?) {}
                override fun onCancelled(error: DatabaseError) {}
            })
    }
}