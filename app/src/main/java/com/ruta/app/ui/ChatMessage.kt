package com.ruta.app.ui

data class ChatMessage(
    val senderId: String = "",
    val message: String = "",
    val timestamp: Long = 0L
)