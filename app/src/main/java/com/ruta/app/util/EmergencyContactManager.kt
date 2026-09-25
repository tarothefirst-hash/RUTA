package com.ruta.app.util

import com.google.firebase.database.DataSnapshot
import com.google.firebase.database.DatabaseError
import com.google.firebase.database.DatabaseReference
import com.google.firebase.database.ValueEventListener


object EmergencyContactManager {

    const val MAX_TRUSTED_CONTACTS = 3

    // ---------------------------------------------------------------------
    // PAIRING — request + accept, never instant
    // ---------------------------------------------------------------------

    fun sendPairRequest(
        database: DatabaseReference,
        fromUid: String,
        fromName: String,
        toUid: String,
        onComplete: (success: Boolean, message: String) -> Unit
    ) {
        if (fromUid == toUid) {
            onComplete(false, "You can't add yourself.")
            return
        }

        database.child("users").child(fromUid).child("trustedContacts").get()
            .addOnSuccessListener { snap ->
                if (snap.childrenCount.toInt() >= MAX_TRUSTED_CONTACTS) {
                    onComplete(false, "You already have $MAX_TRUSTED_CONTACTS trusted contacts — remove one first.")
                    return@addOnSuccessListener
                }

                val requestData = mapOf(
                    "fromUid" to fromUid,
                    "fromName" to fromName,
                    "status" to "PENDING",
                    "timestamp" to System.currentTimeMillis()
                )

                database.child("pairRequests").child(toUid).child(fromUid).setValue(requestData)
                    .addOnSuccessListener { onComplete(true, "Request sent — waiting for them to accept.") }
                    .addOnFailureListener { onComplete(false, "Failed to send request. Try again.") }
            }
            .addOnFailureListener { onComplete(false, "Failed to check your trusted list. Try again.") }
    }

    fun acceptPairRequest(
        database: DatabaseReference,
        myUid: String,
        requesterUid: String,
        onComplete: (success: Boolean, message: String) -> Unit
    ) {
        database.child("users").child(myUid).child("trustedContacts").get()
            .addOnSuccessListener { mySnap ->
                if (mySnap.childrenCount.toInt() >= MAX_TRUSTED_CONTACTS) {
                    onComplete(false, "Your trusted list is full ($MAX_TRUSTED_CONTACTS max) — remove someone first.")
                    return@addOnSuccessListener
                }

                val updates = mapOf(
                    "users/$myUid/trustedContacts/$requesterUid" to true,
                    "users/$requesterUid/trustedContacts/$myUid" to true,
                    "pairRequests/$myUid/$requesterUid" to null
                )

                database.updateChildren(updates)
                    .addOnSuccessListener { onComplete(true, "Added to your trusted list.") }
                    .addOnFailureListener { onComplete(false, "Failed to accept. Try again.") }
            }
            .addOnFailureListener { onComplete(false, "Failed to check your trusted list. Try again.") }
    }

    fun declinePairRequest(database: DatabaseReference, myUid: String, requesterUid: String) {
        database.child("pairRequests").child(myUid).child(requesterUid).removeValue()
    }

    /**
     * One-directional: removes the contact from MY list only. They may still have
     * me in theirs unless they remove me too — same as unfriending doesn't require
     * mutual consent.
     */
    fun removeTrustedContact(database: DatabaseReference, myUid: String, contactUid: String) {
        database.child("users").child(myUid).child("trustedContacts").child(contactUid).removeValue()
    }

    fun listenForTrustedContacts(
        database: DatabaseReference,
        myUid: String,
        onChanged: (contactUids: List<String>) -> Unit
    ): ValueEventListener {
        val listener = object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                onChanged(snapshot.children.mapNotNull { it.key })
            }
            override fun onCancelled(error: DatabaseError) {}
        }
        database.child("users").child(myUid).child("trustedContacts").addValueEventListener(listener)
        return listener
    }

    fun listenForIncomingPairRequests(
        database: DatabaseReference,
        myUid: String,
        onRequest: (requesterUid: String, requesterName: String) -> Unit
    ): ValueEventListener {
        val ref = database.child("pairRequests").child(myUid)
        val listener = object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                for (child in snapshot.children) {
                    if (child.child("status").getValue(String::class.java) == "PENDING") {
                        val requesterUid = child.key ?: continue
                        val requesterName = child.child("fromName").getValue(String::class.java) ?: "Someone"
                        onRequest(requesterUid, requesterName)
                    }
                }
            }
            override fun onCancelled(error: DatabaseError) {}
        }
        ref.addValueEventListener(listener)
        return listener
    }

    // ---------------------------------------------------------------------
    // EMERGENCY ALERT FAN-OUT
    // ---------------------------------------------------------------------

    /**
     * Reads the booking + driver profile, then pushes one alert per trusted
     * contact. Every call site (the deviation dialog, an always-visible SOS
     * button) just needs a bookingId — driver name, vehicle, plate, and current
     * location are all looked up here.
     */
    fun sendEmergencyAlertForBooking(
        database: DatabaseReference,
        bookingId: String,
        onComplete: (contactsNotified: Int) -> Unit = {}
    ) {
        database.child("bookings").child(bookingId).get().addOnSuccessListener { booking ->
            val passengerId = booking.child("passengerId").getValue(String::class.java)
                ?: return@addOnSuccessListener onComplete(0)
            val passengerName = booking.child("passengerName").getValue(String::class.java)
                ?.ifEmpty { null } ?: "A RUTA passenger"
            val driverId = booking.child("driverId").getValue(String::class.java) ?: ""
            val pickupAddress = booking.child("pickupAddress").getValue(String::class.java) ?: "N/A"
            val dropoffAddress = booking.child("dropoffAddress").getValue(String::class.java) ?: "N/A"
            val lat = booking.child("driverLat").getValue(Double::class.java)
                ?: booking.child("lastDeviatedLat").getValue(Double::class.java) ?: 0.0
            val lng = booking.child("driverLng").getValue(Double::class.java)
                ?: booking.child("lastDeviatedLng").getValue(Double::class.java) ?: 0.0

            fun dispatch(driverName: String, vehicleInfo: String) {
                database.child("users").child(passengerId).child("trustedContacts").get()
                    .addOnSuccessListener { contactsSnap ->
                        val contactUids = contactsSnap.children.mapNotNull { it.key }
                        if (contactUids.isEmpty()) return@addOnSuccessListener onComplete(0)

                        val alertData = mapOf(
                            "senderName" to passengerName,
                            "bookingId" to bookingId,
                            "driverName" to driverName,
                            "vehicleInfo" to vehicleInfo,
                            "pickupAddress" to pickupAddress,
                            "dropoffAddress" to dropoffAddress,
                            "lat" to lat,
                            "lng" to lng,
                            "timestamp" to System.currentTimeMillis()
                        )

                        contactUids.forEach { contactUid ->
                            database.child("emergencyAlerts").child(contactUid).push().setValue(alertData)
                        }
                        onComplete(contactUids.size)
                    }
                    .addOnFailureListener { onComplete(0) }
            }

            if (driverId.isEmpty()) {
                dispatch("Not yet assigned", "N/A")
            } else {
                database.child("users").child(driverId).get()
                    .addOnSuccessListener { driverSnap ->
                        val firstName = driverSnap.child("firstName").getValue(String::class.java) ?: ""
                        val lastName = driverSnap.child("lastName").getValue(String::class.java) ?: ""
                        var fullName = "$firstName $lastName".trim()
                        if (fullName.isEmpty()) {
                            fullName = driverSnap.child("name").getValue(String::class.java) ?: "Assigned Driver"
                        }

                        val vehicleModel = driverSnap.child("vehicleModel").getValue(String::class.java)
                            ?: driverSnap.child("carModel").getValue(String::class.java) ?: "Vehicle"
                        val plateNumber = driverSnap.child("plateNumber").getValue(String::class.java)
                            ?: driverSnap.child("licensePlate").getValue(String::class.java) ?: ""
                        val vehicleColor = driverSnap.child("vehicleColor").getValue(String::class.java) ?: ""

                        val vehicleInfo = buildString {
                            if (vehicleColor.isNotEmpty()) append("$vehicleColor ")
                            append(vehicleModel)
                            if (plateNumber.isNotEmpty()) append(" \u2022 $plateNumber")
                        }

                        dispatch(fullName, vehicleInfo)
                    }
                    .addOnFailureListener { dispatch("Assigned Driver", "N/A") }
            }
        }.addOnFailureListener { onComplete(0) }
    }

    /**
     * Each alert is consumed (removed) right after being surfaced, so a
     * reconnecting listener never replays an alert the contact already saw.
     */
    fun listenForIncomingAlerts(
        database: DatabaseReference,
        myUid: String,
        onAlert: (
            senderName: String, driverName: String, vehicleInfo: String,
            pickupAddress: String, dropoffAddress: String, lat: Double, lng: Double
        ) -> Unit
    ): ValueEventListener {
        val ref = database.child("emergencyAlerts").child(myUid)
        val listener = object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                for (child in snapshot.children) {
                    val key = child.key ?: continue
                    onAlert(
                        child.child("senderName").getValue(String::class.java) ?: "A trusted contact",
                        child.child("driverName").getValue(String::class.java) ?: "Unknown",
                        child.child("vehicleInfo").getValue(String::class.java) ?: "N/A",
                        child.child("pickupAddress").getValue(String::class.java) ?: "N/A",
                        child.child("dropoffAddress").getValue(String::class.java) ?: "N/A",
                        child.child("lat").getValue(Double::class.java) ?: 0.0,
                        child.child("lng").getValue(Double::class.java) ?: 0.0
                    )
                    ref.child(key).removeValue()
                }
            }
            override fun onCancelled(error: DatabaseError) {}
        }
        ref.addValueEventListener(listener)
        return listener
    }
}