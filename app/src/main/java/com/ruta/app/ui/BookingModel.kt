package com.ruta.app.model

data class BookingModel(
    var bookingId: String? = null,
    var passengerId: String? = null,
    var driverId: String? = null,
    var pickupAddress: String? = null,
    var dropoffAddress: String? = null,
    var pickupLat: Double? = 0.0,
    var pickupLng: Double? = 0.0,
    var dropoffLat: Double? = 0.0,
    var dropoffLng: Double? = 0.0,
    var fare: Double? = 0.0,
    var serviceType: String? = "Standard",
    var status: String? = "REQUESTED",
    var createdAt: Long = System.currentTimeMillis()
)