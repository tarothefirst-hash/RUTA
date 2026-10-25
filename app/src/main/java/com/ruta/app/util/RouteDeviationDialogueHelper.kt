package com.ruta.app.util

import android.content.Context
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import com.google.firebase.database.DatabaseReference

object RouteDeviationDialogHelper {

    /**
     * Shows the main Route Deviation Alert dialog.
     * Displays options for "I'm OK" and "Emergency".
     */
    fun showDeviationAlert(
        context: Context,
        database: DatabaseReference,
        bookingId: String,
        onRerouteValidated: () -> Unit = {}
    ) {
        AlertDialog.Builder(context)
            .setTitle("⚠️ Route Deviation Detected")
            .setMessage("Your driver has moved off the designated route. Please review your situation.")
            .setCancelable(false)
            .setPositiveButton("I'm OK") { dialog, _ ->
                dialog.dismiss()
                showValidateRerouteConfirmation(context, bookingId, onRerouteValidated)
            }
            .setNegativeButton("Emergency") { dialog, _ ->
                dialog.dismiss()
                // An emergency always denies the reroute, whether or not contacts are notified.
                writeDeviationResponse(bookingId, DeviationApproval.EMERGENCY)
                showEmergencyConfirmation(context, database, bookingId)
            }
            .show()
    }

    /**
     * Popup asking: "Are you sure you want to validate this reroute? Yes / No"
     */
    private fun showValidateRerouteConfirmation(
        context: Context,
        bookingId: String,
        onRerouteValidated: () -> Unit
    ) {
        AlertDialog.Builder(context)
            .setTitle("Validate Reroute")
            .setMessage("Are you sure you want to validate this reroute?")
            .setPositiveButton("Yes") { _, _ ->
                // Clear the route deviation streak state for this booking
                RouteDeviationManager.clearTripDeviationState(bookingId)
                // Tell the driver's screen: this passenger approves the new route.
                writeDeviationResponse(bookingId, DeviationApproval.OK)
                Toast.makeText(context, "Reroute validated.", Toast.LENGTH_SHORT).show()
                onRerouteValidated()
            }
            .setNegativeButton("No") { dialog, _ ->
                dialog.dismiss()
            }
            .show()
    }

    /**
     * Popup asking: "Want to notify your Contacts? Yes / No"
     */
    private fun showEmergencyConfirmation(
        context: Context,
        database: DatabaseReference,
        bookingId: String
    ) {
        AlertDialog.Builder(context)
            .setTitle("Emergency Alert")
            .setMessage("Want to notify your Contacts?")
            .setPositiveButton("Yes") { _, _ ->
                EmergencyContactManager.sendEmergencyAlertForBooking(database, bookingId) { count ->
                    if (count > 0) {
                        Toast.makeText(context, "Emergency alert sent to $count contact(s)!", Toast.LENGTH_LONG).show()
                    } else {
                        Toast.makeText(context, "No trusted contacts found to notify.", Toast.LENGTH_SHORT).show()
                    }
                }
            }
            .setNegativeButton("No") { dialog, _ ->
                dialog.dismiss()
            }
            .show()
    }

    /** The passenger's answer, read by the driver app to approve or deny the reroute. */
    fun writeDeviationResponse(bookingId: String, response: String) {
        val ref = com.google.firebase.database.FirebaseDatabase.getInstance().reference
            .child("bookings").child(bookingId)
        ref.updateChildren(
            mapOf(
                "deviationResponse" to response,
                "deviationResponseAt" to System.currentTimeMillis()
            )
        )
    }
}
