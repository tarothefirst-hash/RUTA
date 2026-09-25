package com.ruta.app.ui

import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.view.View
import android.widget.ImageView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.viewpager2.widget.ViewPager2
import com.google.android.material.button.MaterialButton
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.database.DataSnapshot
import com.google.firebase.database.DatabaseError
import com.google.firebase.database.DatabaseReference
import com.google.firebase.database.FirebaseDatabase
import com.google.firebase.database.ValueEventListener
import com.ruta.app.R
import com.ruta.app.util.EmergencyContactManager
import com.ruta.app.util.RouteDeviationManager

class PassengerHomeActivity : AppCompatActivity() {

    private lateinit var viewPager: ViewPager2
    private lateinit var navHome: ImageView
    private lateinit var navPromos: ImageView
    private lateinit var navSettings: ImageView
    private lateinit var btnProfile: ImageView

    private lateinit var cardRouteDeviationWarning: View
    private lateinit var btnDismissWarning: MaterialButton
    private lateinit var btnSosEmergency: MaterialButton

    private val database: DatabaseReference = FirebaseDatabase.getInstance().reference

    private var activeBookingListener: ValueEventListener? = null
    private var activeBookingId: String? = null
    private var hasFiredNotification = false

    // Incoming pairing / emergency listeners — live only while this app is open,
    // same scope limitation as everything else in RUTA's notification system.
    private var pairRequestListener: ValueEventListener? = null
    private var alertListener: ValueEventListener? = null
    private val shownPairRequestUids = mutableSetOf<String>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_passenger_home)

        requestNotificationPermission()
        initViews()
        setupViewPager()
        setupClickListeners()
        listenForActivePassengerTrip()
        startIncomingPairRequestListener()
        startIncomingEmergencyAlertListener()

        intent?.getStringExtra("BOOKING_ID")?.let {
            cardRouteDeviationWarning.visibility = View.VISIBLE
        }
    }

    private fun requestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, android.Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED) {
                ActivityCompat.requestPermissions(
                    this, arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), 101
                )
            }
        }
    }

    private fun initViews() {
        viewPager = findViewById(R.id.viewPager)
        navHome = findViewById(R.id.navHome)
        navPromos = findViewById(R.id.navPromos)
        navSettings = findViewById(R.id.navSettings)
        btnProfile = findViewById(R.id.btnProfile)

        cardRouteDeviationWarning = findViewById(R.id.cardRouteDeviationWarning)
        btnDismissWarning = findViewById(R.id.btnDismissWarning)
        btnSosEmergency = findViewById(R.id.btnSosEmergency)
    }

    private fun setupViewPager() {
        val adapter = PassengerPagerAdapter(this)
        viewPager.adapter = adapter

        viewPager.registerOnPageChangeCallback(object : ViewPager2.OnPageChangeCallback() {
            override fun onPageSelected(position: Int) {
                super.onPageSelected(position)
                when (position) {
                    0 -> updateActiveTab(navHome)
                    1 -> updateActiveTab(navPromos)
                    2 -> updateActiveTab(navSettings)
                    3 -> updateActiveTab(btnProfile)
                }
            }
        })
    }

    private fun setupClickListeners() {
        navHome.setOnClickListener { viewPager.setCurrentItem(0, true) }
        navPromos.setOnClickListener { viewPager.setCurrentItem(1, true) }
        navSettings.setOnClickListener { viewPager.setCurrentItem(2, true) }
        btnProfile.setOnClickListener { viewPager.setCurrentItem(3, true) }

        btnDismissWarning.setOnClickListener {
            activeBookingId?.let { id ->
                database.child("bookings").child(id).child("routeStatus").setValue("NORMAL")
            }
            cardRouteDeviationWarning.visibility = View.GONE
            hasFiredNotification = false
        }

        // Emergency SOS: no more dialing 911 — RUTA notifies the rider's own
        // trusted contacts with the driver's info and current location instead.
        btnSosEmergency.setOnClickListener {
            val bookingId = activeBookingId
            if (bookingId == null) {
                android.widget.Toast.makeText(this, "No active trip to report.", android.widget.Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            EmergencyContactManager.sendEmergencyAlertForBooking(database, bookingId) { notified ->
                val message = if (notified > 0) {
                    "Alert sent to $notified trusted contact${if (notified == 1) "" else "s"}."
                } else {
                    "No trusted contacts set up yet — add one from your Profile."
                }
                android.widget.Toast.makeText(this, message, android.widget.Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun updateActiveTab(selectedTab: ImageView) {
        val navTabs = listOf(navHome, navPromos, navSettings, btnProfile)
        for (tab in navTabs) {
            tab.setColorFilter(
                ContextCompat.getColor(this, if (tab == selectedTab) R.color.ruta_primary else R.color.ruta_text_muted)
            )
        }
    }

    private fun listenForActivePassengerTrip() {
        val currentUserId = FirebaseAuth.getInstance().currentUser?.uid ?: return

        database.child("bookings").addValueEventListener(object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                for (child in snapshot.children) {
                    val passengerId = child.child("passengerId").getValue(String::class.java)
                    val status = child.child("status").getValue(String::class.java)

                    if (passengerId == currentUserId && (status == "ACCEPTED" || status == "IN_PROGRESS")) {
                        val bookingId = child.key ?: return
                        if (activeBookingId != bookingId) {
                            activeBookingId = bookingId
                            monitorRouteDeviationForBooking(bookingId)
                        }
                        return
                    }
                }
            }
            override fun onCancelled(error: DatabaseError) {}
        })
    }

    private fun monitorRouteDeviationForBooking(bookingId: String) {
        val bookingRef = database.child("bookings").child(bookingId)

        activeBookingListener = object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                val routeStatus = snapshot.child("routeStatus").getValue(String::class.java)

                if (routeStatus == "DEVIATED") {
                    cardRouteDeviationWarning.visibility = View.VISIBLE
                    if (!hasFiredNotification) {
                        hasFiredNotification = true
                        RouteDeviationManager.sendDeviationNotification(this@PassengerHomeActivity, bookingId)
                    }
                } else {
                    cardRouteDeviationWarning.visibility = View.GONE
                    hasFiredNotification = false
                }
            }
            override fun onCancelled(error: DatabaseError) {}
        }

        bookingRef.addValueEventListener(activeBookingListener!!)
    }

    /**
     * Shows an Accept/Decline dialog the moment someone scans your QR and sends a
     * pairing request. shownPairRequestUids stops the same pending request from
     * re-triggering a dialog every time this node fires again for an unrelated
     * reason (e.g. a second, different request arriving).
     */
    private fun startIncomingPairRequestListener() {
        val myUid = FirebaseAuth.getInstance().currentUser?.uid ?: return

        pairRequestListener = EmergencyContactManager.listenForIncomingPairRequests(database, myUid) { requesterUid, requesterName ->
            if (shownPairRequestUids.contains(requesterUid)) return@listenForIncomingPairRequests
            shownPairRequestUids.add(requesterUid)

            AlertDialog.Builder(this)
                .setTitle("Trusted Contact Request")
                .setMessage("$requesterName wants to add you as a trusted emergency contact. If your ride shows a route deviation, they'll be notified with your driver's info.")
                .setCancelable(false)
                .setPositiveButton("Accept") { dialog, _ ->
                    EmergencyContactManager.acceptPairRequest(database, myUid, requesterUid) { _, message ->
                        android.widget.Toast.makeText(this, message, android.widget.Toast.LENGTH_LONG).show()
                    }
                    shownPairRequestUids.remove(requesterUid)
                    dialog.dismiss()
                }
                .setNegativeButton("Decline") { dialog, _ ->
                    EmergencyContactManager.declinePairRequest(database, myUid, requesterUid)
                    shownPairRequestUids.remove(requesterUid)
                    dialog.dismiss()
                }
                .show()
        }
    }

    /**
     * Shows a popup with the sender's name, their driver's info, and a "View on
     * Map" shortcut whenever a trusted contact you're watching over sends an
     * emergency alert.
     */
    private fun startIncomingEmergencyAlertListener() {
        val myUid = FirebaseAuth.getInstance().currentUser?.uid ?: return

        alertListener = EmergencyContactManager.listenForIncomingAlerts(database, myUid) { senderName, driverName, vehicleInfo, pickupAddress, dropoffAddress, lat, lng ->
            AlertDialog.Builder(this)
                .setTitle("\u26A0\uFE0F Emergency Alert")
                .setMessage(
                    "$senderName's ride showed a route deviation.\n\n" +
                            "Driver: $driverName\n" +
                            "Vehicle: $vehicleInfo\n" +
                            "Pickup: $pickupAddress\n" +
                            "Dropoff: $dropoffAddress"
                )
                .setCancelable(false)
                .setPositiveButton("View on Map") { dialog, _ ->
                    dialog.dismiss()
                    if (lat != 0.0 || lng != 0.0) {
                        startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("geo:$lat,$lng?q=$lat,$lng")))
                    }
                }
                .setNegativeButton("Dismiss") { dialog, _ -> dialog.dismiss() }
                .show()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        activeBookingId?.let { id ->
            activeBookingListener?.let { listener ->
                database.child("bookings").child(id).removeEventListener(listener)
            }
        }
        FirebaseAuth.getInstance().currentUser?.uid?.let { myUid ->
            pairRequestListener?.let { database.child("pairRequests").child(myUid).removeEventListener(it) }
            alertListener?.let { database.child("emergencyAlerts").child(myUid).removeEventListener(it) }
        }
    }
}