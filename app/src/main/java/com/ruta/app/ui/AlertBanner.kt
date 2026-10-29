package com.ruta.app.ui

import android.app.Activity
import android.graphics.Color
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import com.google.firebase.database.FirebaseDatabase
import com.ruta.app.util.StoragePhotos

/**
 * A banner that stays on screen until it is dismissed: used when a trusted contact's ride
 * goes off route. Shows the driver (photo, name, vehicle), the ride, and a button that opens
 * Live Tracking on that driver.
 */
object AlertBanner {

    private const val TAG = "ruta_alert_banner"

    fun show(activity: Activity, bookingId: String, headline: String, detail: String, onTrack: () -> Unit) {
        val root = activity.findViewById<ViewGroup>(android.R.id.content) ?: return
        dismiss(activity)

        val dp = activity.resources.displayMetrics.density
        fun px(v: Int) = (v * dp).toInt()

        val card = MaterialCardView(activity).apply {
            tag = TAG
            radius = 16 * dp
            cardElevation = 24 * dp
            translationZ = 24 * dp
            setCardBackgroundColor(Color.WHITE)
            strokeColor = Color.parseColor("#DC2626")
            strokeWidth = px(2)
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP
            ).apply { setMargins(px(12), px(12), px(12), 0) }
        }
        ViewCompat.setOnApplyWindowInsetsListener(card) { v, insets ->
            val top = insets.getInsets(WindowInsetsCompat.Type.systemBars()).top
            (v.layoutParams as? FrameLayout.LayoutParams)?.let { it.topMargin = top + px(8); v.layoutParams = it }
            insets
        }

        val col = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(px(16), px(14), px(16), px(10))
        }
        col.addView(TextView(activity).apply {
            text = headline
            setTextColor(Color.parseColor("#B91C1C"))
            textSize = 15f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
        })
        col.addView(TextView(activity).apply {
            text = detail
            setTextColor(Color.parseColor("#374151"))
            textSize = 13f
            setPadding(0, px(2), 0, px(8))
        })

        val row = LinearLayout(activity).apply { gravity = Gravity.CENTER_VERTICAL }
        val photo = ImageView(activity).apply { layoutParams = LinearLayout.LayoutParams(px(56), px(56)) }
        val info = TextView(activity).apply {
            text = "Loading driver details..."
            setTextColor(Color.parseColor("#111827"))
            textSize = 13f
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginStart = px(12) }
        }
        row.addView(photo)
        row.addView(info)
        col.addView(row)

        val buttons = LinearLayout(activity).apply {
            gravity = Gravity.END
            setPadding(0, px(8), 0, 0)
        }
        val dismissBtn = MaterialButton(activity, null, com.google.android.material.R.attr.borderlessButtonStyle).apply {
            text = "Dismiss"
            setOnClickListener { dismiss(activity) }
        }
        val trackBtn = MaterialButton(activity).apply {
            text = "Track driver"
            setBackgroundColor(Color.parseColor("#DC2626"))
            setTextColor(Color.WHITE)
            setOnClickListener { dismiss(activity); onTrack() }
        }
        buttons.addView(dismissBtn)
        buttons.addView(trackBtn)
        col.addView(buttons)

        card.addView(col)
        root.addView(card)
        card.bringToFront()

        // Fill in the driver once we know who it is.
        val db = FirebaseDatabase.getInstance().reference
        StoragePhotos.showAvatar(photo, null, "Driver", px(56))
        db.child("bookings").child(bookingId).get().addOnSuccessListener { b ->
            if (activity.isFinishing || activity.isDestroyed) return@addOnSuccessListener
            val driverId = b.child("driverId").getValue(String::class.java).orEmpty()
            val name = b.child("driverName").getValue(String::class.java)?.ifBlank { null } ?: "Assigned driver"
            val vehicle = listOf(
                b.child("vehicleColor").getValue(String::class.java).orEmpty(),
                b.child("vehicleModel").getValue(String::class.java).orEmpty()
            ).filter { it.isNotBlank() }.joinToString(" ")
            val plate = b.child("plateNumber").getValue(String::class.java).orEmpty()
            val pickup = b.child("pickupAddress").getValue(String::class.java).orEmpty()
            val dropoff = b.child("dropoffAddress").getValue(String::class.java).orEmpty()
            info.text = buildString {
                append("Driver: ").append(name)
                val v = listOf(vehicle, plate).filter { it.isNotBlank() }.joinToString(" • ")
                if (v.isNotBlank()) append("\n").append(v)
                if (pickup.isNotBlank() || dropoff.isNotBlank()) append("\n").append(pickup).append(" ➔ ").append(dropoff)
            }
            StoragePhotos.showAvatar(photo, null, name, px(56))
            if (driverId.isNotEmpty()) {
                db.child("users").child(driverId).child("profilePhoto").get().addOnSuccessListener { p ->
                    if (activity.isFinishing || activity.isDestroyed) return@addOnSuccessListener
                    StoragePhotos.showAvatar(photo, p.getValue(String::class.java), name, px(56))
                }
            }
        }.addOnFailureListener { info.text = "Driver details unavailable" }
    }

    fun dismiss(activity: Activity) {
        val root = activity.findViewById<ViewGroup>(android.R.id.content) ?: return
        root.findViewWithTag<View>(TAG)?.let { root.removeView(it) }
    }
}
