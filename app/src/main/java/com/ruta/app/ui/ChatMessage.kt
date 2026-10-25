package com.ruta.app.ui

data class ChatMessage(
    val senderId: String = "",
    val message: String = "",
    val timestamp: Long = 0L,
    /** Shown above messages from other people. Empty on messages sent before names existed. */
    val senderName: String = ""
)
