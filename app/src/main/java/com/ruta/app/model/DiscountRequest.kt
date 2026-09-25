package com.ruta.app.model

data class DiscountRequest(
    val requestId: String = "",
    val userId: String = "",
    val userName: String = "",
    val discountType: String = "", // "STUDENT" or "SENIOR"
    val idFrontUrl: String = "",
    val idBackUrl: String = "",
    val status: String = "PENDING", // "PENDING", "APPROVED", "REJECTED"
    val timestamp: Long = System.currentTimeMillis()
)