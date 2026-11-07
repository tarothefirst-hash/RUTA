package com.ruta.app.util

import android.content.Context
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import com.google.firebase.database.FirebaseDatabase

/** The digital receipt for one finished trip, with "Email me a copy". */
object ReceiptDialog {

    fun show(context: Context, bookingId: String, canEmail: Boolean = true) {
        val db = FirebaseDatabase.getInstance().reference
        ReceiptMailer.load(db, bookingId) { receipt, _ ->
            if (receipt == null) {
                Toast.makeText(context, "Couldn't load this receipt.", Toast.LENGTH_SHORT).show()
                return@load
            }
            val b = AlertDialog.Builder(context)
                .setTitle(if (receipt.completed) "Receipt" else "Trip details")
                .setMessage(ReceiptLogic.plainText(receipt))
                .setNegativeButton("Close", null)
            if (receipt.completed && canEmail) {
                b.setPositiveButton("Email me a copy") { _, _ ->
                    Toast.makeText(context, "Sending…", Toast.LENGTH_SHORT).show()
                    ReceiptMailer.send(db, bookingId) { ok, err ->
                        android.os.Handler(android.os.Looper.getMainLooper()).post {
                            Toast.makeText(
                                context,
                                if (ok) "Receipt sent to your email." else (err ?: "Couldn't send the receipt."),
                                Toast.LENGTH_LONG
                            ).show()
                        }
                    }
                }
            }
            b.show()
        }
    }
}
