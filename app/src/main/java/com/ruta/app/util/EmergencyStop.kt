package com.ruta.app.util

import com.google.firebase.database.DatabaseReference
import com.google.firebase.database.ServerValue

/**
 * Emergency stop: ends the trip on the spot, records where, alerts the stopper's trusted
 * contacts, and queues an "Are you safe?" check for BOTH passenger and driver.
 *
 *  bookings/{id}/status = CANCELLED, cancelReason = EMERGENCY_STOP, emergencyStop{...}
 *  safetyChecks/{uid}/{bookingId} = { role, bookingIds, stoppedBy, at, answered=false }
 */
object EmergencyStop {

    fun perform(
        db: DatabaseReference,
        bookingIds: List<String>,
        stoppedByRole: String,
        stoppedByUid: String,
        fallbackLat: Double,
        fallbackLng: Double,
        onDone: (ok: Boolean) -> Unit
    ) {
        val ids = bookingIds.distinct()
        if (ids.isEmpty()) return onDone(false)

        db.child("bookings").child(ids.first()).get().addOnSuccessListener { first ->
            val driverId = first.child("driverId").getValue(String::class.java) ?: ""
            val lat = first.child("driverLat").getValue(Double::class.java)?.takeIf { it != 0.0 } ?: fallbackLat
            val lng = first.child("driverLng").getValue(Double::class.java)?.takeIf { it != 0.0 } ?: fallbackLng

            // Read every passenger so each one gets their own check.
            val passengers = linkedMapOf<String, String>() // bookingId -> passengerId
            var pending = ids.size
            ids.forEach { id ->
                db.child("bookings").child(id).child("passengerId").get()
                    .addOnCompleteListener { t ->
                        passengers[id] = t.result?.getValue(String::class.java) ?: ""
                        if (--pending == 0) write(db, ids, passengers, driverId, stoppedByRole, stoppedByUid, lat, lng, onDone)
                    }
            }
        }.addOnFailureListener { onDone(false) }
    }

    private fun write(
        db: DatabaseReference,
        ids: List<String>,
        passengers: Map<String, String>,
        driverId: String,
        role: String,
        byUid: String,
        lat: Double,
        lng: Double,
        onDone: (Boolean) -> Unit
    ) {
        val now = System.currentTimeMillis()
        val updates = hashMapOf<String, Any?>()
        ids.forEach { id ->
            updates["bookings/$id/status"] = "CANCELLED"
            updates["bookings/$id/cancelledAt"] = now
            updates["bookings/$id/cancelReason"] = SafetyLogic.REASON_EMERGENCY_STOP
            updates["bookings/$id/emergencyStop/by"] = role
            updates["bookings/$id/emergencyStop/byUid"] = byUid
            updates["bookings/$id/emergencyStop/at"] = now
            updates["bookings/$id/emergencyStop/lat"] = lat
            updates["bookings/$id/emergencyStop/lng"] = lng

            val passengerId = passengers[id].orEmpty()
            if (passengerId.isNotEmpty()) {
                updates["safetyChecks/$passengerId/$id"] = checkNode(SafetyLogic.ROLE_PASSENGER, listOf(id), role, now)
            }
        }
        if (driverId.isNotEmpty()) {
            updates["safetyChecks/$driverId/${ids.first()}"] = checkNode(SafetyLogic.ROLE_DRIVER, ids, role, now)
        }

        db.updateChildren(updates).addOnSuccessListener {
            // The stopper's own trusted contacts get the live-location alert right away.
            EmergencyContactManager.sendEmergencyAlertForBooking(
                db, ids.first(), isDriver = role == SafetyLogic.ROLE_DRIVER
            )
            onDone(true)
        }.addOnFailureListener { onDone(false) }
    }

    private fun checkNode(role: String, ids: List<String>, stoppedBy: String, at: Long) = mapOf(
        "role" to role,
        "bookingIds" to ids,
        "stoppedBy" to stoppedBy,
        "at" to at,
        "answered" to false
    )

    /** Records the answer, tells the answerer's trusted contacts, and escalates if help is needed. */
    fun answer(
        db: DatabaseReference,
        uid: String,
        checkKey: String,
        role: String,
        bookingIds: List<String>,
        safe: Boolean
    ) {
        val field = if (role == SafetyLogic.ROLE_DRIVER) "driverSafe" else "passengerSafe"
        val updates = hashMapOf<String, Any?>(
            "safetyChecks/$uid/$checkKey/answered" to true,
            "safetyChecks/$uid/$checkKey/safe" to safe,
            "safetyChecks/$uid/$checkKey/answeredAt" to ServerValue.TIMESTAMP
        )
        bookingIds.forEach { id ->
            updates["bookings/$id/emergencyStop/$field"] = safe
            updates["bookings/$id/emergencyStop/${field}At"] = ServerValue.TIMESTAMP
        }
        db.updateChildren(updates)

        val firstId = bookingIds.firstOrNull().orEmpty()
        db.child("users").child(uid).get().addOnSuccessListener { u ->
            val first = u.child("firstName").getValue(String::class.java).orEmpty()
            val last = u.child("lastName").getValue(String::class.java).orEmpty()
            val name = "$first $last".trim().ifEmpty { u.child("name").getValue(String::class.java) ?: "Your contact" }
            val notice = SafetyLogic.contactNotice(name, safe)
            u.child("trustedContacts").children.mapNotNull { it.key }.forEach { contactUid ->
                NotificationStore.push(
                    db, contactUid, NotificationStore.TYPE_SAFETY, notice.first, notice.second,
                    extras = if (firstId.isNotEmpty()) mapOf("bookingId" to firstId) else emptyMap()
                )
            }
        }

        // "I need help" raises the full alert again so contacts see the live location.
        if (!safe && firstId.isNotEmpty()) {
            EmergencyContactManager.sendEmergencyAlertForBooking(
                db, firstId, isDriver = role == SafetyLogic.ROLE_DRIVER
            )
        }
    }
}
