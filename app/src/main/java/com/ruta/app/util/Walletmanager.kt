package com.ruta.app.util

import com.google.firebase.database.DataSnapshot
import com.google.firebase.database.DatabaseError
import com.google.firebase.database.DatabaseReference
import com.google.firebase.database.MutableData
import com.google.firebase.database.Transaction

object WalletManager {

    private const val STANDARD_COMMISSION_RATE = 0.10   // regular rides
    private const val DISCOUNTED_COMMISSION_RATE = 0.04 // PWD / Student rides

    fun commissionFor(fare: Double, hasDiscount: Boolean): Double {
        val rate = if (hasDiscount) DISCOUNTED_COMMISSION_RATE else STANDARD_COMMISSION_RATE
        return fare * rate
    }

    private fun Any?.toDoubleValue(): Double {
        return when (this) {
            is Double -> this
            is Long -> this.toDouble()
            is Int -> this.toDouble()
            is Float -> this.toDouble()
            is String -> this.toDoubleOrNull() ?: 0.0
            else -> 0.0
        }
    }

    fun deductCommission(
        database: DatabaseReference,
        driverId: String,
        fare: Double,
        hasDiscount: Boolean,
        onComplete: (newBalance: Double) -> Unit = {},
        bookingId: String? = null
    ) {
        val commission = commissionFor(fare, hasDiscount)
        val walletRef = database.child("users").child(driverId).child("walletBalance")

        walletRef.runTransaction(object : Transaction.Handler {
            override fun doTransaction(currentData: MutableData): Transaction.Result {
                // Read raw value safely to prevent Long -> Double deserialization nulls
                val currentRaw = currentData.value
                val current = currentRaw.toDoubleValue()

                val updatedBalance = current - commission
                currentData.value = updatedBalance
                return Transaction.success(currentData)
            }

            override fun onComplete(error: DatabaseError?, committed: Boolean, snapshot: DataSnapshot?) {
                if (committed && snapshot != null) {
                    val finalBalance = snapshot.value.toDoubleValue()
                    recordCommission(database, driverId, bookingId, fare, commission, hasDiscount)
                    onComplete(finalBalance)
                } else {
                    onComplete(0.0)
                }
            }
        })
    }

    /** One ledger line per deduction, so the admin can see what RUTA earned. Create-only. */
    private fun recordCommission(
        database: DatabaseReference, driverId: String, bookingId: String?,
        fare: Double, commission: Double, hasDiscount: Boolean
    ) {
        if (commission <= 0.0) return
        val ref = database.child("commissions").push()
        ref.setValue(
            mapOf(
                "driverId" to driverId,
                "bookingId" to (bookingId ?: ""),
                "fare" to fare,
                "commission" to commission,
                "hasDiscount" to hasDiscount,
                "timestamp" to com.google.firebase.database.ServerValue.TIMESTAMP
            )
        )
    }
}
