package com.ruta.app.ui

import android.os.Bundle
import android.widget.Button
import android.widget.EditText
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.appbar.MaterialToolbar
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.database.ChildEventListener
import com.google.firebase.database.DataSnapshot
import com.google.firebase.database.DatabaseError
import com.google.firebase.database.FirebaseDatabase
import com.ruta.app.R
import com.ruta.app.util.keepClearOfKeyboard
class ChatActivity : AppCompatActivity() {

    private lateinit var bookingId: String
    private lateinit var currentUserId: String

    private lateinit var recyclerChat: RecyclerView
    private lateinit var chatAdapter: ChatAdapter
    private val messageList = mutableListOf<ChatMessage>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // 1. Enable Edge-to-Edge display
        WindowCompat.setDecorFitsSystemWindows(window, false)

        setContentView(R.layout.activity_chat)

        // 2. Adjust top padding for Status Bar so MaterialToolbar isn't cut off
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(android.R.id.content)) { view, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            view.setPadding(
                systemBars.left,
                systemBars.top,
                systemBars.right,
                view.paddingBottom
            )
            insets
        }

        // 3. Attach keyboard handler to dynamically lift chat input and messages
        keepClearOfKeyboard(findViewById(android.R.id.content))

        bookingId = intent.getStringExtra("BOOKING_ID") ?: run {
            Toast.makeText(this, "No active booking found", Toast.LENGTH_SHORT).show()
            finish()
            return
        }
        currentUserId = FirebaseAuth.getInstance().currentUser?.uid ?: run {
            Toast.makeText(this, "User not authenticated", Toast.LENGTH_SHORT).show()
            finish()
            return
        }

        val etChatMessage = findViewById<EditText>(R.id.rvChatMessage)
        val btnSendChat = findViewById<Button>(R.id.btnSendChat)
        val toolbar = findViewById<MaterialToolbar>(R.id.chatToolbar)

        setSupportActionBar(toolbar)

        FirebaseDatabase.getInstance().reference.child("users").child(currentUserId).child("role")
            .get().addOnSuccessListener { snapshot ->
                val role = snapshot.getValue(String::class.java)
                toolbar.title = if (role == "DRIVER") "Chat with Passenger" else "Chat with Driver"
            }

        toolbar.setNavigationOnClickListener {
            finish()
        }

        // Initialize RecyclerView
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