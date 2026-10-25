package com.ruta.app.ui

import android.os.Handler
import android.os.Looper
import android.view.View
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.ruta.app.R
import com.ruta.app.util.RideshareFlowManager
import com.ruta.app.util.RideshareFlowManager.EndReason
import com.ruta.app.util.RideshareMatchLogic

/**
 * The passenger-facing screens of the rideshare flow:
 *
 *  - the live countdown on the status button while searching
 *  - the "Co-Passenger Found" sheet (details, both fares, Confirm / Decline)
 *  - the "No Co-Passenger available. Switch to Solo-Ride?" question
 *  - the Solo Ride summary, with the price, before anything is switched
 *
 * It only shows things and reports taps through [Callbacks]; everything that
 * touches Firebase or the map stays in the activity and the flow manager.
 */
class RideshareMatchUi(
    private val activity: AppCompatActivity,
    private val statusButton: Button,
    private val callbacks: Callbacks
) {

    interface Callbacks {
        fun onConfirmMatch()
        fun onDeclineMatch()

        /** The passenger answered Yes to "Switch to Solo-Ride?". The caller shows the summary. */
        fun onWantsSolo()

        fun onKeepSearching()
        fun onConfirmSolo()
    }

    /** Numbers and addresses for the Solo Ride summary. */
    data class SoloSummary(
        val pickupAddress: String,
        val dropoffAddress: String,
        val distanceKm: Double,
        val durationMin: Int,
        val fare: Double,
        val discountAmount: Double
    )

    private val handler = Handler(Looper.getMainLooper())
    private var ticker: Runnable? = null

    private var searchExpiresAtLocalMs = 0L
    private var proposal: RideshareFlowManager.Proposal? = null

    private var matchSheet: BottomSheetDialog? = null
    private var matchView: View? = null
    private var soloSheet: BottomSheetDialog? = null
    private var expiryDialog: AlertDialog? = null

    // ------------------------------------------------------------------
    // Searching
    // ------------------------------------------------------------------

    fun onSearching(expiresAtLocalMs: Long) {
        searchExpiresAtLocalMs = expiresAtLocalMs
        proposal = null
        startTicker()
        refreshSearchText()
    }

    private fun refreshSearchText() {
        if (proposal != null) return
        val left = RideshareMatchLogic.searchRemainingMs(System.currentTimeMillis(), searchExpiresAtLocalMs)
        statusButton.text = activity.getString(
            R.string.match_searching_timer,
            RideshareMatchLogic.formatCountdown(left)
        )
    }

    // ------------------------------------------------------------------
    // A co-passenger was found
    // ------------------------------------------------------------------

    fun onProposalReady(p: RideshareFlowManager.Proposal) {
        expiryDialog?.dismiss()
        expiryDialog = null
        soloSheet?.dismiss()
        soloSheet = null

        proposal = p
        showOrUpdateMatchSheet(p)
        startTicker()
    }

    fun onProposalEnded(reason: EndReason) {
        matchSheet?.dismiss()
        matchSheet = null
        matchView = null
        proposal = null

        val message = when (reason) {
            EndReason.DECLINED_BY_PARTNER -> R.string.match_declined_by_partner
            EndReason.TIMED_OUT -> R.string.match_timed_out
            EndReason.DECLINED_BY_ME -> R.string.match_declined_by_me
            EndReason.FAILED -> R.string.match_failed
        }
        Toast.makeText(activity, message, Toast.LENGTH_LONG).show()
    }

    private fun showOrUpdateMatchSheet(p: RideshareFlowManager.Proposal) {
        if (matchSheet == null) {
            val dialog = BottomSheetDialog(activity)
            val view = activity.layoutInflater.inflate(R.layout.dialog_copassenger_match, null)
            dialog.setContentView(view)
            dialog.setCancelable(false)
            dialog.setCanceledOnTouchOutside(false)
            dialog.window?.setDimAmount(0.12f)
            dialog.behavior.state = BottomSheetBehavior.STATE_EXPANDED
            dialog.behavior.skipCollapsed = true

            view.findViewById<View>(R.id.btnConfirmMatch).setOnClickListener {
                it.isEnabled = false
                callbacks.onConfirmMatch()
            }
            view.findViewById<View>(R.id.btnDeclineMatch).setOnClickListener {
                callbacks.onDeclineMatch()
            }

            matchSheet = dialog
            matchView = view
            dialog.show()
        }
        bindMatchSheet(p)
    }

    private fun bindMatchSheet(p: RideshareFlowManager.Proposal) {
        val view = matchView ?: return
        val coFirst = p.partnerName.trim().substringBefore(' ').ifBlank { p.partnerName }

        view.findViewById<TextView>(R.id.txtMatchName).text = p.partnerName
        view.findViewById<TextView>(R.id.txtMatchPhone).apply {
            text = p.partnerPhone
            visibility = if (p.partnerPhone.isBlank()) View.GONE else View.VISIBLE
        }
        view.findViewById<TextView>(R.id.txtMatchPickup).text = p.partnerPickupAddress
        view.findViewById<TextView>(R.id.txtMatchDropoff).text = p.partnerDropoffAddress

        view.findViewById<TextView>(R.id.txtYouFare).text = RideshareMatchLogic.formatPeso(p.myFare.total)
        view.findViewById<TextView>(R.id.txtCoPays).text = activity.getString(R.string.match_co_pays, coFirst)
        view.findViewById<TextView>(R.id.txtCoFare).text = RideshareMatchLogic.formatPeso(p.partnerFare.total)

        val confirmButton = view.findViewById<Button>(R.id.btnConfirmMatch)
        val status = view.findViewById<TextView>(R.id.txtMatchStatus)
        when {
            p.myConfirmed -> {
                confirmButton.isEnabled = false
                confirmButton.text = "✓  " + activity.getString(R.string.match_confirm)
                status.visibility = View.VISIBLE
                status.text = activity.getString(R.string.match_waiting_for, coFirst)
            }
            p.partnerConfirmed -> {
                confirmButton.isEnabled = true
                confirmButton.text = activity.getString(R.string.match_confirm)
                status.visibility = View.VISIBLE
                status.text = activity.getString(R.string.match_partner_confirmed, coFirst)
            }
            else -> {
                confirmButton.isEnabled = true
                confirmButton.text = activity.getString(R.string.match_confirm)
                status.visibility = View.GONE
            }
        }
        refreshProposalTimer()
    }

    private fun refreshProposalTimer() {
        val p = proposal ?: return
        val timer = matchView?.findViewById<TextView>(R.id.txtMatchTimer) ?: return
        val left = RideshareMatchLogic.searchRemainingMs(System.currentTimeMillis(), p.expiresAtLocalMs)
        timer.text = activity.getString(R.string.match_confirm_timer, RideshareMatchLogic.formatCountdown(left))
        statusButton.text = activity.getString(R.string.match_confirm_timer, RideshareMatchLogic.formatCountdown(left))
    }

    // ------------------------------------------------------------------
    // The five minutes are up: ask, never switch automatically
    // ------------------------------------------------------------------

    fun onSearchExpired() {
        if (activity.isFinishing || activity.isDestroyed) return
        if (proposal != null || soloSheet != null || expiryDialog?.isShowing == true) return

        statusButton.text = activity.getString(R.string.no_copassenger_title)
        expiryDialog = AlertDialog.Builder(activity)
            .setTitle(R.string.no_copassenger_title)
            .setMessage(R.string.no_copassenger_message)
            .setCancelable(false)
            .setPositiveButton(R.string.no_copassenger_yes) { _, _ ->
                expiryDialog = null
                callbacks.onWantsSolo()
            }
            .setNegativeButton(R.string.no_copassenger_no) { _, _ ->
                expiryDialog = null
                callbacks.onKeepSearching()
            }
            .show()
    }

    /** The solo price, distance and time, shown BEFORE the booking is switched. */
    fun showSoloSummary(summary: SoloSummary) {
        soloSheet?.dismiss()

        val dialog = BottomSheetDialog(activity)
        val view = activity.layoutInflater.inflate(R.layout.dialog_solo_summary, null)
        dialog.setContentView(view)
        dialog.setCancelable(false)
        dialog.setCanceledOnTouchOutside(false)
        dialog.window?.setDimAmount(0.12f)
        dialog.behavior.state = BottomSheetBehavior.STATE_EXPANDED
        dialog.behavior.skipCollapsed = true

        view.findViewById<TextView>(R.id.txtSoloTripSummary).text =
            String.format(java.util.Locale.US, "%.1f km • ~%d mins", summary.distanceKm, summary.durationMin)
        view.findViewById<TextView>(R.id.txtSoloPickup).text = summary.pickupAddress
        view.findViewById<TextView>(R.id.txtSoloDropoff).text = summary.dropoffAddress
        view.findViewById<TextView>(R.id.txtSoloFare).text = RideshareMatchLogic.formatPeso(summary.fare)
        view.findViewById<TextView>(R.id.txtSoloFareNote).apply {
            if (summary.discountAmount > 0.0) {
                text = activity.getString(
                    R.string.solo_fare_note_discount,
                    RideshareMatchLogic.formatPeso(summary.discountAmount)
                )
                visibility = View.VISIBLE
            } else {
                visibility = View.GONE
            }
        }

        view.findViewById<View>(R.id.btnConfirmSolo).setOnClickListener {
            it.isEnabled = false
            callbacks.onConfirmSolo()
        }
        view.findViewById<View>(R.id.btnKeepSearching).setOnClickListener {
            dialog.dismiss()
            soloSheet = null
            callbacks.onKeepSearching()
        }

        soloSheet = dialog
        dialog.show()
    }

    /** Re-enables the solo confirm button after a failed switch. */
    fun onSoloSwitchFailed() {
        soloSheet?.findViewById<View>(R.id.btnConfirmSolo)?.isEnabled = true
        Toast.makeText(activity, R.string.solo_switch_failed, Toast.LENGTH_LONG).show()
    }

    // ------------------------------------------------------------------
    // Wrap-up
    // ------------------------------------------------------------------

    /** The match is final or the booking became solo: close everything. */
    fun dismissAll() {
        stopTicker()
        matchSheet?.dismiss()
        soloSheet?.dismiss()
        expiryDialog?.dismiss()
        matchSheet = null
        matchView = null
        soloSheet = null
        expiryDialog = null
        proposal = null
    }

    private fun startTicker() {
        if (ticker != null) return
        val r = object : Runnable {
            override fun run() {
                if (proposal != null) refreshProposalTimer() else refreshSearchText()
                handler.postDelayed(this, 500L)
            }
        }
        ticker = r
        handler.post(r)
    }

    private fun stopTicker() {
        ticker?.let { handler.removeCallbacks(it) }
        ticker = null
    }
}
