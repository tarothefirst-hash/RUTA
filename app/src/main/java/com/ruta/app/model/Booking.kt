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
    var noShowFee: Double = 0.0,
    // Fare & Discount Breakdown
    val fare: Double = 0.0,
    val fareBeforeDiscount: Double = 0.0,
    val discountAmount: Double = 0.0,
    val hasDiscount: Boolean = false,

    // Written by the driver app once a shared trip completes and the group fare
    // has been split. Stays 0.0 for solo rides (fare is already final there).
    val finalFare: Double = 0.0,

    // Per-passenger breakdown of finalFare, also written by the driver app at
    // settlement so the receipt can explain how the total was reached. Additive
    // with defaults, so Firebase records that predate these keys still load.
    val fareBase: Double = 0.0,                 // flag-down base built into the fare
    val fareSoloCharge: Double = 0.0,           // this passenger's own untouched km/min
    val fareSharedCharge: Double = 0.0,         // this passenger's half of the shared km/min
    val fareDetourSurcharge: Double = 0.0,      // paid because they made the other rider travel further
    val fareInconvenienceCredit: Double = 0.0,  // credited because the other rider made them travel further

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

    // Driver & Vehicle Details (Populated when accepted by a driver)
    val driverName: String = "",
    val driverPhone: String = "",
    val vehicleModel: String = "",
    val vehicleColor: String = "",
    val plateNumber: String = "",

    val routeStatus: String = "NORMAL",

    val timestamp: Long = System.currentTimeMillis()
)