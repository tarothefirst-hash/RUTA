package com.ruta.app.model

data class Booking(
    val bookingId: String = "",
    val passengerId: String = "",
    val passengerName: String = "",
    val pickupAddress: String = "",
    val pickupLat: Double = 0.0,
    val pickupLng: Double = 0.0,
    val dropoffAddress: String = "",
    val dropoffLat: Double = 0.0,
    val dropoffLng: Double = 0.0,
    val fare: Double = 0.0,
    val serviceType: String = "REGULAR_4", // REGULAR_4, SHARED_4, or REGULAR_6
    val isShared: Boolean = false,
    val maxPassengersAllowed: Int = 1,     // 1 for Regular, 2 for Shared
    val status: String = "REQUESTED",      // REQUESTED -> MATCHED -> IN_TRANSIT -> COMPLETED
    val tripId: String? = null,            // Filled when a driver accepts/creates a trip
    val timestamp: Long = System.currentTimeMillis()
)