package com.ruta.app.util

import com.google.firebase.database.DataSnapshot
import com.google.firebase.database.DatabaseError
import com.google.firebase.database.DatabaseReference
import com.google.firebase.database.MutableData
import com.google.firebase.database.Transaction

/**
 * WalletManager
 * -------------
 * Driver wallet: platform commission auto-deducted per completed trip, plus
 * pseudo-real GCash cash-in (driver declares an amount sent via QR, an admin
 * confirms it out-of-band — no proof upload / admin approval screen tonight,
 * that's next-pass scope).
 *
 * Balance is allowed to go negative. RUTA gives drivers a 2-day grace period
 * before any enforcement kicks in, so no floor/blocking is applied here at all —
 * that's a deliberate product decision, not a missing feature.
 */
object WalletManager {

    private const val STANDARD_COMMISSION_RATE = 0.10   // regular rides
    private const val DISCOUNTED_COMMISSION_RATE = 0.04 // PWD / Student rides

    fun commissionFor(fare: Double, hasDiscount: Boolean): Double {
        val rate = if (hasDiscount) DISCOUNTED_COMMISSION_RATE else STANDARD_COMMISSION_RATE
        return fare * rate
    }

    /**
     * Deducts commission via a Firebase transaction so two trips completing close
     * together (e.g. a shared ride's two bookings finishing back-to-back) can't
     * race each other and silently drop an update.
     */
    fun deductCommission(
        database: DatabaseReference,
        driverId: String,
        fare: Double,
        hasDiscount: Boolean,
        onComplete: (newBalance: Double) -> Unit = {}
    ) {
        val commission = commissionFor(fare, hasDiscount)
        val walletRef = database.child("users").child(driverId).child("walletBalance")

        walletRef.runTransaction(object : Transaction.Handler {
            override fun doTransaction(currentData: MutableData): Transaction.Result {
                val current = currentData.getValue(Double::class.java) ?: 0.0
                currentData.value = current - commission
                return Transaction.success(currentData)
            }

            override fun onComplete(error: DatabaseError?, committed: Boolean, snapshot: DataSnapshot?) {
                if (committed) {
                    onComplete(snapshot?.getValue(Double::class.java) ?: 0.0)
                }
            }
        })
    }

    /**
     * Records a declared cash-in: driver picks a preset amount, sends it via the
     * GCash QR shown in-app, taps confirm. Whoever approves this (admin console,
     * or manually in the Firebase console for tonight's demo) is responsible for
     * crediting walletBalance — this call only files the request.
     */
    fun submitTopUpRequest(
        database: DatabaseReference,
        driverId: String,
        driverName: String,
        amount: Double,
        onComplete: (success: Boolean) -> Unit = {}
    ) {
        val ref = database.child("walletTopUpRequests").push()
        val data = hashMapOf(
            "requestId" to ref.key,
            "driverId" to driverId,
            "driverName" to driverName,
            "amount" to amount,
            "status" to "PENDING", // PENDING -> APPROVED / REJECTED (admin-side, not built tonight)
            "createdAt" to System.currentTimeMillis()
        )
        ref.setValue(data)
            .addOnSuccessListener { onComplete(true) }
            .addOnFailureListener { onComplete(false) }
    }
}