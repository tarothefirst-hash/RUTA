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

                database.child("users").child(requesterUid).child("trustedContacts").get()
                    .addOnSuccessListener { requesterSnap ->
                        if (requesterSnap.childrenCount.toInt() >= MAX_TRUSTED_CONTACTS) {
                            onComplete(false, "Their trusted list is full ($MAX_TRUSTED_CONTACTS max) — they need to remove someone first.")
                        } else {
                            val updates = mapOf(
                                "users/$myUid/trustedContacts/$requesterUid" to true,
                                "users/$requesterUid/trustedContacts/$myUid" to true,
                                "pairRequests/$myUid/$requesterUid" to null
                            )

                            database.updateChildren(updates)
                                .addOnSuccessListener { onComplete(true, "Added to your trusted list.") }
                                .addOnFailureListener { onComplete(false, "Failed to accept. Try again.") }
                        }
                    }
                    .addOnFailureListener { onComplete(false, "Failed to check their trusted list. Try again.") }
            }
            .addOnFailureListener { onComplete(false, "Failed to check your trusted list. Try again.") }
    }

    fun declinePairRequest(database: DatabaseReference, myUid: String, requesterUid: String) {
        database.child("pairRequests").child(myUid).child(requesterUid).removeValue()
    }

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
    // EMERGENCY ALERT FAN-OUT (Supports both Passengers and Drivers)
    // ---------------------------------------------------------------------

    fun sendEmergencyAlertForBooking(
        database: DatabaseReference,
        bookingId: String,
        isDriver: Boolean = false,
        onComplete: (contactsNotified: Int) -> Unit = {}
    ) {
        database.child("bookings").child(bookingId).get().addOnSuccessListener { booking ->
            val passengerId = booking.child("passengerId").getValue(String::class.java) ?: ""
            val driverId = booking.child("driverId").getValue(String::class.java) ?: ""
            val passengerName = booking.child("passengerName").getValue(String::class.java)?.ifEmpty { null } ?: "A RUTA passenger"
            val pickupAddress = booking.child("pickupAddress").getValue(String::class.java) ?: "N/A"
            val dropoffAddress = booking.child("dropoffAddress").getValue(String::class.java) ?: "N/A"
            val lat = booking.child("driverLat").getValue(Double::class.java)
                ?: booking.child("lastDeviatedLat").getValue(Double::class.java) ?: 0.0
            val lng = booking.child("driverLng").getValue(Double::class.java)
                ?: booking.child("lastDeviatedLng").getValue(Double::class.java) ?: 0.0

            // If triggered by Driver, target driver's UID; otherwise target passenger's UID
            val targetUid = if (isDriver) driverId else passengerId
            if (targetUid.isEmpty()) return@addOnSuccessListener onComplete(0)

            database.child("users").child(driverId).get().addOnSuccessListener { driverSnap ->
                val firstName = driverSnap.child("firstName").getValue(String::class.java) ?: ""
                val lastName = driverSnap.child("lastName").getValue(String::class.java) ?: ""
                var driverFullName = "$firstName $lastName".trim()
                if (driverFullName.isEmpty()) {
                    driverFullName = driverSnap.child("name").getValue(String::class.java) ?: "Assigned Driver"
                }

                val vehicleModel = driverSnap.child("vehicleModel").getValue(String::class.java)
                    ?: driverSnap.child("carModel").getValue(String::class.java) ?: "Vehicle"
                val plateNumber = driverSnap.child("plateNumber").getValue(String::class.java)
                    ?: driverSnap.child("licensePlate").getValue(String::class.java) ?: ""
                val vehicleColor = driverSnap.child("vehicleColor").getValue(String::class.java) ?: ""

                val vehicleInfo = buildString {
                    if (vehicleColor.isNotEmpty()) append("$vehicleColor ")
                    append(vehicleModel)
                    if (plateNumber.isNotEmpty()) append(" • $plateNumber")
                }

                val senderName = if (isDriver) "$driverFullName (Driver)" else passengerName

                database.child("users").child(targetUid).child("trustedContacts").get()
                    .addOnSuccessListener { contactsSnap ->
                        val contactUids = contactsSnap.children.mapNotNull { it.key }
                        if (contactUids.isEmpty()) return@addOnSuccessListener onComplete(0)

                        val alertData = mapOf(
                            "senderName" to senderName,
                            "bookingId" to bookingId,
                            "driverName" to driverFullName,
                            "vehicleInfo" to vehicleInfo,
                            "pickupAddress" to pickupAddress,
                            "dropoffAddress" to dropoffAddress,
                            "lat" to lat,
                            "lng" to lng,
                            "isDriverEmergency" to isDriver,
                            "timestamp" to System.currentTimeMillis()
                        )

                        contactUids.forEach { contactUid ->
                            database.child("emergencyAlerts").child(contactUid).push().setValue(alertData)
                        }
                        onComplete(contactUids.size)
                    }
                    .addOnFailureListener { onComplete(0) }
            }.addOnFailureListener { onComplete(0) }
        }.addOnFailureListener { onComplete(0) }
    }

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