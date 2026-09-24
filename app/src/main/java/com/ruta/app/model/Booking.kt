package com.ruta.app.model

data class Booking(
    val bookingId: String = "",
    val passengerId: String = "",
    val passengerName: String = "",
    val passengerPhone: String = "",
    val pickupAddress: String = "",
    val pickupLat: Double = 0.0,
    val pickupLng: Double = 0.0,
    val dropoffAddress: String = "",
    val dropoffLat: Double = 0.0,
    val dropoffLng: Double = 0.0,
    val distanceMeters: Int = 0,
    val durationSeconds: Int = 0,
    val surgeMultiplier: Double = 1.0,
    val fare: Double = 0.0,

    // Written by the driver app once a shared trip completes and the group fare
    // has been split. Stays 0.0 for solo rides (fare is already final there).
    val finalFare: Double = 0.0,

    val serviceType: String = "REGULAR",   // REGULAR or SHARED
    val isShared: Boolean = false,
    val maxPassengersAllowed: Int = 1,     // 1 for Regular, 2 for Rideshare

    // MATCHING -> REQUESTED -> ACCEPTED -> IN_PROGRESS -> COMPLETED / CANCELLED
    // MATCHING is shared-only: the booking is invisible to drivers until a
    // co-passenger is found (or the matching window expires).
    val status: String = "REQUESTED",

    val tripId: String? = null,
    val tripGroupId: String? = null,
    val driverId: String? = null,
    val routeStatus: String = "NORMAL",

    val timestamp: Long = System.currentTimeMillis()
)