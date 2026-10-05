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
    // Settled group fare written once a shared trip completes; 0.0 for solo
    // and unfinished rides, whose quoted `fare` is already final.
    var finalFare: Double = 0.0,
    var serviceType: String? = "Standard",
    var status: String? = "REQUESTED",
    var timestamp: Long = 0L
) {
    // Live bookings are written under `createdAt` (LocationSelectionActivity.writeBooking);
    // older records used `timestamp`. Read both — prefer `createdAt` when it is present.
    var createdAt: Long = 0L
        get() = if (field != 0L) field else timestamp
}