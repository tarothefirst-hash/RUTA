package com.ruta.app.util

import android.os.Handler
import android.os.Looper
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.database.DataSnapshot
import com.google.firebase.database.DatabaseError
import com.google.firebase.database.DatabaseReference
import com.google.firebase.database.MutableData
import com.google.firebase.database.Transaction
import com.ruta.app.network.BrevoEmailService

/** Firebase + email side of receipts. Content comes from [ReceiptLogic]. */
object ReceiptMailer {

    /** Loads a booking (plus its driver) into a [ReceiptLogic.Receipt]. */
    fun load(db: DatabaseReference, bookingId: String, onResult: (ReceiptLogic.Receipt?, passengerId: String) -> Unit) {
        db.child("bookings").child(bookingId).get().addOnSuccessListener { b ->
            if (!b.exists()) return@addOnSuccessListener onResult(null, "")
            @Suppress("UNCHECKED_CAST")
            val fields = (b.value as? Map<String, Any?>) ?: emptyMap()
            val passengerId = b.child("passengerId").getValue(String::class.java).orEmpty()
            val driverId = b.child("driverId").getValue(String::class.java).orEmpty()
            if (driverId.isEmpty()) return@addOnSuccessListener onResult(ReceiptLogic.fromFields(bookingId, fields, "", ""), passengerId)
            db.child("users").child(driverId).get().addOnSuccessListener { d ->
                val name = ("${d.child("firstName").getValue(String::class.java).orEmpty()} " +
                    d.child("lastName").getValue(String::class.java).orEmpty()).trim()
                    .ifEmpty { d.child("name").getValue(String::class.java).orEmpty() }
                val model = d.child("vehicleModel").getValue(String::class.java)
                    ?: d.child("carModel").getValue(String::class.java).orEmpty()
                val plate = d.child("plateNumber").getValue(String::class.java)
                    ?: d.child("licensePlate").getValue(String::class.java).orEmpty()
                val vehicle = listOf(model, plate).filter { it.isNotEmpty() }.joinToString(" • ")
                onResult(ReceiptLogic.fromFields(bookingId, fields, name, vehicle), passengerId)
            }.addOnFailureListener { onResult(ReceiptLogic.fromFields(bookingId, fields, "", ""), passengerId) }
        }.addOnFailureListener { onResult(null, "") }
    }

    /**
     * Emails the receipt of a COMPLETED booking once. The first device to claim
     * `receiptEmailedAt` sends it, so two devices never send duplicates; a failed send
     * releases the claim so it can be tried again.
     */
    fun sendOnce(db: DatabaseReference, bookingId: String, attempt: Int = 0) {
        // A shared trip's settled fare is written a moment AFTER the last drop-off completes;
        // wait for it so the receipt never shows the quoted fare by mistake.
        db.child("bookings").child(bookingId).get().addOnSuccessListener { b ->
            val shared = b.child("isShared").getValue(Boolean::class.java) == true &&
                !b.child("tripGroupId").getValue(String::class.java).isNullOrEmpty()
            val settled = (b.child("finalFare").getValue(Double::class.java) ?: 0.0) > 0.0
            if (shared && !settled && attempt < RETRY_DELAYS_MS.size) {
                Handler(Looper.getMainLooper()).postDelayed(
                    { sendOnce(db, bookingId, attempt + 1) }, RETRY_DELAYS_MS[attempt]
                )
            } else {
                claimAndSend(db, bookingId)
            }
        }
    }

    private val RETRY_DELAYS_MS = longArrayOf(4_000, 10_000, 30_000, 60_000)

    private fun claimAndSend(db: DatabaseReference, bookingId: String) {
        val claim = db.child("bookings").child(bookingId).child("receiptEmailedAt")
        claim.runTransaction(object : Transaction.Handler {
            override fun doTransaction(currentData: MutableData): Transaction.Result {
                if (currentData.value != null) return Transaction.abort()
                currentData.value = System.currentTimeMillis()
                return Transaction.success(currentData)
            }
            override fun onComplete(error: DatabaseError?, committed: Boolean, currentData: DataSnapshot?) {
                if (!committed) return
                send(db, bookingId) { ok, _ -> if (!ok) claim.removeValue() }
            }
        })
    }

    /** Sends (or re-sends) the receipt to the passenger's email. */
    fun send(db: DatabaseReference, bookingId: String, onResult: (Boolean, String?) -> Unit) {
        load(db, bookingId) { receipt, passengerId ->
            if (receipt == null || !receipt.completed) return@load onResult(false, "Receipts are available for completed trips.")
            val authEmail = FirebaseAuth.getInstance().currentUser?.takeIf { it.uid == passengerId }?.email
            fun go(email: String?) {
                if (email.isNullOrBlank()) return onResult(false, "No email address on this account.")
                BrevoEmailService.sendEmail(email, ReceiptLogic.subject(receipt), ReceiptLogic.html(receipt), onResult)
            }
            if (!authEmail.isNullOrBlank()) go(authEmail)
            else db.child("users").child(passengerId).child("email").get()
                .addOnSuccessListener { go(it.getValue(String::class.java)) }
                .addOnFailureListener { go(null) }
        }
    }
}
