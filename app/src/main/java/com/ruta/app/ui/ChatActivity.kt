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
import com.google.firebase.database.Query
import com.ruta.app.R
import com.ruta.app.util.keepClearOfKeyboard
class ChatActivity : AppCompatActivity() {

    private lateinit var bookingId: String
    private lateinit var currentUserId: String

    /**
     * Where the messages live. A solo ride chats on the booking. A matched rideshare
     * chats on the TRIP GROUP, so the driver and both passengers share one room
     * (before, each booking had its own room and the driver only ever saw one).
     */
    private var chatRoomId: String = ""
    private var myDisplayName: String = ""
    private var roomReady = false

    private lateinit var recyclerChat: RecyclerView
    private lateinit var chatAdapter: ChatAdapter
    private val messageList = mutableListOf<ChatMessage>()

    private var messagesQuery: Query? = null
    private var messagesListener: ChildEventListener? = null

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

        val rootRef = FirebaseDatabase.getInstance().reference

        // Who am I (for the name shown to the others), and which room is this ride's?
        rootRef.child("users").child(currentUserId).get().addOnSuccessListener { userSnap ->
            val role = userSnap.child("role").getValue(String::class.java)
            val first = userSnap.child("firstName").getValue(String::class.java).orEmpty()
            val last = userSnap.child("lastName").getValue(String::class.java).orEmpty()
            val name = "$first $last".trim().ifEmpty { if (role == "DRIVER") "Driver" else "Passenger" }
            myDisplayName = if (role == "DRIVER") "$name (Driver)" else name

            rootRef.child("bookings").child(bookingId).child("tripGroupId").get()
                .addOnSuccessListener { groupSnap ->
                    val groupId = groupSnap.getValue(String::class.java)
                    val isGroup = !groupId.isNullOrEmpty()
                    chatRoomId = if (isGroup) "group_$groupId" else bookingId
                    toolbar.title = when {
                        role == "DRIVER" && isGroup -> "Chat with Passengers"
                        role == "DRIVER" -> "Chat with Passenger"
                        isGroup -> "Rideshare Chat"
                        else -> "Chat with Driver"
                    }
                    roomReady = true
                    if (!isDestroyed) listenForMessages()
                }
                .addOnFailureListener { openSoloRoomFallback(toolbar, role) }
        }.addOnFailureListener { openSoloRoomFallback(toolbar, null) }

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
                if (!roomReady) {
                    Toast.makeText(this, "Connecting to the chat... try again in a moment.", Toast.LENGTH_SHORT).show()
                } else {
                    sendMessage(text, etChatMessage)
                    etChatMessage.setText("")
                }
            }
        }
    }

    /** If the profile or group lookup fails, still open the ride's own chat instead of a dead screen. */
    private fun openSoloRoomFallback(toolbar: MaterialToolbar, role: String?) {
        if (roomReady || isDestroyed) return
        chatRoomId = bookingId
        if (myDisplayName.isEmpty()) myDisplayName = if (role == "DRIVER") "Driver" else "Passenger"
        toolbar.title = if (role == "DRIVER") "Chat with Passenger" else "Chat with Driver"
        roomReady = true
        listenForMessages()
        Toast.makeText(this, "Chat opened (limited connection).", Toast.LENGTH_SHORT).show()
    }

    private fun sendMessage(messageText: String, input: EditText) {
        val messageMap = hashMapOf<String, Any>(
            "senderId" to currentUserId,
            "senderName" to myDisplayName,
            "message" to messageText,
            "timestamp" to System.currentTimeMillis()
        )
        FirebaseDatabase.getInstance().reference
            .child("chats").child(chatRoomId).push().setValue(messageMap)
            .addOnFailureListener {
                Toast.makeText(this, "Failed to send: ${it.message ?: "try again"}", Toast.LENGTH_LONG).show()
                input.setText(messageText)
            }
    }

    private fun listenForMessages() {
        val query = FirebaseDatabase.getInstance().reference
            .child("chats").child(chatRoomId)
            .limitToLast(MAX_HISTORY)
        messagesQuery = query

        val listener = object : ChildEventListener {
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
            override fun onCancelled(error: DatabaseError) {
                Toast.makeText(this@ChatActivity, "Failed to load messages: ${error.message}", Toast.LENGTH_LONG).show()
            }
        }
        messagesListener = listener
        query.addChildEventListener(listener)
    }

    override fun onDestroy() {
        messagesListener?.let { messagesQuery?.removeEventListener(it) }
        messagesListener = null
        messagesQuery = null
        super.onDestroy()
    }

    private companion object {
        const val MAX_HISTORY = 50
    }
}