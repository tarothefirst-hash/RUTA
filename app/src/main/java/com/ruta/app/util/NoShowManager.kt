package com.ruta.app.util

import com.google.firebase.database.DataSnapshot
import com.google.firebase.database.DatabaseError
import com.google.firebase.database.DatabaseReference
import com.google.firebase.database.MutableData
import com.google.firebase.database.Transaction

object NoShowManager {
    const val WAIT_MS = 5 * 60 * 1000L
    const val INCONVENIENCE_FEE = 20.0
    const val REASON_NO_SHOW = "NO_SHOW"
    const val REASON_CO_PASSENGER_NO_SHOW = "CO_PASSENGER_NO_SHOW"

    /** Atomically moves status from -> to. onDone(true) only for the caller that won. */
    private fun claimStatus(ref: DatabaseReference, from: String, to: String, onDone: (Boolean) -> Unit) {
        ref.child("status").runTransaction(object : Transaction.Handler {
            override fun doTransaction(d: MutableData): Transaction.Result {
                if (d.getValue(String::class.java) != from) return Transaction.abort()
                d.value = to
                return Transaction.success(d)
            }
            override fun onComplete(e: DatabaseError?, committed: Boolean, s: DataSnapshot?) = onDone(committed)
        })
    }

    fun addToNumber(ref: DatabaseReference, delta: Double) {
        ref.runTransaction(object : Transaction.Handler {
            override fun doTransaction(d: MutableData): Transaction.Result {
                d.value = (d.getValue(Double::class.java) ?: 0.0) + delta
                return Transaction.success(d)
            }
            override fun onComplete(e: DatabaseError?, committed: Boolean, s: DataSnapshot?) {}
        })
    }

    /**
     * Cancels [bookingId] as a no-show, records the debt, pays the driver, and
     * cancels [coPassengerIds] (not yet picked up) without penalty.
     */
    fun reportNoShow(
        db: DatabaseReference,
        driverId: String,
        bookingId: String,
        coPassengerIds: Set<String>,
        onDone: (Boolean) -> Unit
    ) {
        val ref = db.child("bookings").child(bookingId)
        // Read first so a failed read can't leave a cancelled booking with no penalty.
        ref.get().addOnSuccessListener { b ->
            val fare = b.child("fare").getValue(Double::class.java) ?: 0.0
            val passengerId = b.child("passengerId").getValue(String::class.java) ?: ""
            val owed = fare + INCONVENIENCE_FEE
            val now = System.currentTimeMillis()

            claimStatus(ref, "ACCEPTED", "CANCELLED") { won ->
                if (!won) { onDone(false); return@claimStatus }

                ref.updateChildren(mapOf(
                    "cancelReason" to REASON_NO_SHOW,
                    "noShow" to true,
                    "noShowAt" to now,
                    "noShowFeeOwed" to owed
                ))
                if (passengerId.isNotEmpty()) {
                    addToNumber(db.child("users").child(passengerId).child("noShowDebt"), owed)
                    addToNumber(db.child("users").child(passengerId).child("noShowCount"), 1.0)
                }
                // Ruta pays the driver what the passenger should have paid, net of commission.
                val net = fare - WalletManager.commissionFor(fare, false)
                addToNumber(db.child("users").child(driverId).child("walletBalance"), net)

                coPassengerIds.forEach { id ->
                    val r = db.child("bookings").child(id)
                    claimStatus(r, "ACCEPTED", "CANCELLED") { ok ->
                        if (ok) r.updateChildren(mapOf(
                            "cancelReason" to REASON_CO_PASSENGER_NO_SHOW,
                            "cancelledAt" to System.currentTimeMillis()
                        ))
                    }
                }
                onDone(true)
            }
        }.addOnFailureListener { onDone(false) }
    }

    /**
     * Call when a booking completes. If it carried a noShowFee, clear that much of the
     * passenger's debt and deduct it from the driver's wallet (the driver collected it in cash).
     */
    fun settleFeeOnCompletion(db: DatabaseReference, driverId: String, bookingId: String) {
        val ref = db.child("bookings").child(bookingId)
        ref.get().addOnSuccessListener { b ->
            val fee = b.child("noShowFee").getValue(Double::class.java) ?: 0.0
            val pid = b.child("passengerId").getValue(String::class.java) ?: ""
            if (fee <= 0.0 || pid.isEmpty()) return@addOnSuccessListener

            ref.child("noShowFeeSettled").runTransaction(object : Transaction.Handler {
                override fun doTransaction(d: MutableData): Transaction.Result {
                    if (d.getValue(Boolean::class.java) == true) return Transaction.abort()
                    d.value = true
                    return Transaction.success(d)
                }
                override fun onComplete(e: DatabaseError?, committed: Boolean, s: DataSnapshot?) {
                    if (!committed) return
                    var cleared = 0.0
                    db.child("users").child(pid).child("noShowDebt").runTransaction(object : Transaction.Handler {
                        override fun doTransaction(d: MutableData): Transaction.Result {
                            val debt = d.getValue(Double::class.java) ?: 0.0
                            cleared = minOf(fee, debt)
                            d.value = debt - cleared
                            return Transaction.success(d)
                        }
                        override fun onComplete(e: DatabaseError?, ok: Boolean, s: DataSnapshot?) {
                            if (ok && cleared > 0.0) {
                                addToNumber(db.child("users").child(driverId).child("walletBalance"), -cleared)
                            }
                        }
                    })
                }
            })
        }
    }
}