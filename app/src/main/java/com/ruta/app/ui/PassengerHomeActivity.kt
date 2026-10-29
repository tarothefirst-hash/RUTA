package com.ruta.app.ui

import com.ruta.app.util.NotificationWatchService
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import com.google.android.material.snackbar.Snackbar
import android.os.Bundle
import android.view.View
import android.widget.ImageView
import android.widget.Toast
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
import com.google.firebase.database.Query
import com.google.firebase.database.ValueEventListener
import com.ruta.app.R
import com.ruta.app.util.DeviationApproval
import com.ruta.app.util.EmergencyContactManager
import com.ruta.app.util.RouteDeviationDialogHelper
import com.ruta.app.util.hideWhileKeyboardOpen
import com.ruta.app.util.RouteDeviationManager

class PassengerHomeActivity : AppCompatActivity() {

    private lateinit var viewPager: ViewPager2
    private lateinit var navHome: ImageView
    private lateinit var navLiveTrack: ImageView
    private lateinit var navPromos: ImageView
    private lateinit var navSettings: ImageView
    private lateinit var btnProfile: ImageView

    private lateinit var cardRouteDeviationWarning: View
    private lateinit var btnDismissWarning: MaterialButton
    private lateinit var btnSosEmergency: MaterialButton

    private val database: DatabaseReference = FirebaseDatabase.getInstance().reference

    private var activeBookingListener: ValueEventListener? = null
    private var sequenceListener: ValueEventListener? = null
    private var allBookingsListener: ValueEventListener? = null
    private var allBookingsQuery: Query? = null
    private var activeBookingId: String? = null
    private var activeTripGroupId: String? = null
    private var lastShownPickupOrdinal: Int? = null
    private var hasFiredNotification = false
    private var deviationAnswered = false

    private var pairRequestListener: ValueEventListener? = null
    private var alertListener: ValueEventListener? = null
    private val shownPairRequestUids = mutableSetOf<String>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_passenger_home)

        requestNotificationPermission()
        NotificationWatchService.start(this)
        initViews()
        hideWhileKeyboardOpen(findViewById(android.R.id.content), findViewById(R.id.bottomNav))
        setupViewPager()
        setupClickListeners()
        listenForActivePassengerTrip()
        startIncomingPairRequestListener()
        startIncomingEmergencyAlertListener()

        handleIncomingNotificationIntent(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIncomingNotificationIntent(intent)
    }

    private fun showAlertBanner(bookingId: String, headline: String, detail: String) {
        if (bookingId.isEmpty()) return
        AlertBanner.show(this, bookingId, headline, detail) {
            LiveTrackingFragment.requestedBookingId = bookingId
            viewPager.setCurrentItem(1, true)
        }
    }

    private fun handleIncomingNotificationIntent(intent: Intent?) {
        val navigateTo = intent?.getStringExtra("NAVIGATE_TO")
        val bookingId = intent?.getStringExtra("BOOKING_ID")

        // Tapping a safety notification opens the banner with the driver's details.
        if (intent?.getBooleanExtra("SHOW_ALERT_BANNER", false) == true && !bookingId.isNullOrEmpty()) {
            intent.removeExtra("SHOW_ALERT_BANNER")
            showAlertBanner(bookingId, intent.getStringExtra("ALERT_TITLE") ?: "⚠️ Safety alert",
                intent.getStringExtra("ALERT_BODY") ?: "A trusted contact's ride needs attention.")
            return
        }

        if (navigateTo == "LIVE_TRACKING" || !bookingId.isNullOrEmpty()) {
            viewPager.setCurrentItem(1, true)
        }
    }

    /** Location first (live tracking and booking need it), then notifications, in one request. */
    private fun requestNotificationPermission() {
        val needed = mutableListOf<String>()
        if (ContextCompat.checkSelfPermission(this, android.Manifest.permission.ACCESS_FINE_LOCATION)
            != PackageManager.PERMISSION_GRANTED) {
            needed += android.Manifest.permission.ACCESS_FINE_LOCATION
            needed += android.Manifest.permission.ACCESS_COARSE_LOCATION
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, android.Manifest.permission.POST_NOTIFICATIONS)
            != PackageManager.PERMISSION_GRANTED) {
            needed += android.Manifest.permission.POST_NOTIFICATIONS
        }
        if (needed.isNotEmpty()) ActivityCompat.requestPermissions(this, needed.toTypedArray(), 101)
    }

    private fun initViews() {
        viewPager = findViewById(R.id.viewPager)
        navHome = findViewById(R.id.navHome)
        navLiveTrack = findViewById(R.id.navLiveTrack)
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
                    1 -> updateActiveTab(navLiveTrack)
                    2 -> updateActiveTab(navPromos)
                    3 -> updateActiveTab(navSettings)
                    4 -> updateActiveTab(btnProfile)
                }
            }
        })
    }

    private fun setupClickListeners() {
        navHome.setOnClickListener { viewPager.setCurrentItem(0, true) }
        navLiveTrack.setOnClickListener { viewPager.setCurrentItem(1, true) }
        navPromos.setOnClickListener { viewPager.setCurrentItem(2, true) }
        navSettings.setOnClickListener { viewPager.setCurrentItem(3, true) }
        btnProfile.setOnClickListener { viewPager.setCurrentItem(4, true) }

        // One panel only: "I'm safe" approves the driver's reroute straight away.
        btnDismissWarning.setOnClickListener {
            val bookingId = activeBookingId ?: return@setOnClickListener
            RouteDeviationManager.clearTripDeviationState(bookingId)
            RouteDeviationDialogHelper.writeDeviationResponse(bookingId, DeviationApproval.OK)
            deviationAnswered = true
            cardRouteDeviationWarning.visibility = View.GONE
            Toast.makeText(this, "Thanks. Your driver can now reroute.", Toast.LENGTH_SHORT).show()
        }

        btnSosEmergency.setOnClickListener {
            val bookingId = activeBookingId
            if (bookingId == null) {
                Toast.makeText(this, "No active trip to report.", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            AlertDialog.Builder(this)
                .setTitle("Emergency Alert")
                .setMessage("Want to notify your Contacts?")
                .setPositiveButton("Yes") { _, _ ->
                    // An emergency always denies the reroute.
                    RouteDeviationDialogHelper.writeDeviationResponse(bookingId, DeviationApproval.EMERGENCY)
                    deviationAnswered = true
                    cardRouteDeviationWarning.visibility = View.GONE
                    EmergencyContactManager.sendEmergencyAlertForBooking(database, bookingId, isDriver = false) { notified ->
                        val message = if (notified > 0) {
                            "Alert sent to $notified trusted contact${if (notified == 1) "" else "s"}."
                        } else {
                            "No trusted contacts set up yet — add one from your Profile."
                        }
                        Toast.makeText(this, message, Toast.LENGTH_LONG).show()
                    }
                }
                .setNegativeButton("No", null)
                .show()
        }
    }

    private fun updateActiveTab(selectedTab: ImageView) {
        val navTabs = listOf(navHome, navLiveTrack, navPromos, navSettings, btnProfile)
        for (tab in navTabs) {
            tab.setColorFilter(
                ContextCompat.getColor(this, if (tab == selectedTab) R.color.ruta_primary else R.color.ruta_text_muted)
            )
        }
    }

    private fun listenForActivePassengerTrip() {
        val currentUserId = FirebaseAuth.getInstance().currentUser?.uid ?: return

        allBookingsListener = object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                var foundActiveTrip = false
                var newActiveBookingId: String? = null
                var tripGroupId: String? = null

                for (child in snapshot.children) {
                    val passengerId = child.child("passengerId").getValue(String::class.java)
                    val status = child.child("status").getValue(String::class.java)

                    if (passengerId == currentUserId &&
                        (status == "MATCHING" || status == "MATCHED" || status == "ACCEPTED" || status == "IN_PROGRESS")) {

                        newActiveBookingId = child.key
                        tripGroupId = child.child("tripGroupId").getValue(String::class.java)
                        foundActiveTrip = true
                        break
                    }
                }

                if (foundActiveTrip && newActiveBookingId != null) {
                    if (activeBookingId != newActiveBookingId) {
                        detachRouteDeviationListener()
                        activeBookingId = newActiveBookingId
                        monitorRouteDeviationForBooking(newActiveBookingId)
                    }

                    if (!tripGroupId.isNullOrEmpty()) {
                        if (activeTripGroupId != tripGroupId) {
                            listenForTripSequence(tripGroupId, currentUserId)
                        }
                    } else {
                        detachSequenceListener()
                    }
                } else {
                    detachRouteDeviationListener()
                    detachSequenceListener()
                    activeBookingId = null
                    cardRouteDeviationWarning.visibility = View.GONE
                }
            }

            override fun onCancelled(error: DatabaseError) {}
        }

        allBookingsQuery = database.child("bookings")
            .orderByChild("passengerId")
            .equalTo(currentUserId)
        allBookingsQuery?.addValueEventListener(allBookingsListener!!)
    }

    private fun listenForTripSequence(tripGroupId: String, currentPassengerId: String) {
        detachSequenceListener()
        lastShownPickupOrdinal = null
        activeTripGroupId = tripGroupId

        sequenceListener = object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                val sequence = snapshot.children.mapNotNull { it.value as? Map<*, *> }
                val position = pickupPositionOf(sequence, currentPassengerId) ?: return

                if (lastShownPickupOrdinal != position.ordinal) {
                    lastShownPickupOrdinal = position.ordinal
                    Toast.makeText(
                        this@PassengerHomeActivity,
                        "You are Stop #${position.ordinal} (${position.label}) in the pickup queue.",
                        Toast.LENGTH_SHORT
                    ).show()
                }
            }

            override fun onCancelled(error: DatabaseError) {}
        }

        database.child("tripGroups").child(tripGroupId).child("stops")
            .addValueEventListener(sequenceListener!!)
    }

    private fun monitorRouteDeviationForBooking(bookingId: String) {
        val bookingRef = database.child("bookings").child(bookingId)

        activeBookingListener = object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                val status = snapshot.child("status").getValue(String::class.java)
                val routeStatus = snapshot.child("routeStatus").getValue(String::class.java)

                if (status == "CANCELLED" || status == "COMPLETED") {
                    cardRouteDeviationWarning.visibility = View.GONE
                    hasFiredNotification = false
                    detachRouteDeviationListener()
                    detachSequenceListener()
                    return
                }

                if (routeStatus == "DEVIATED") {
                    // Show the single warning panel until the rider answers it once.
                    cardRouteDeviationWarning.visibility = if (deviationAnswered) View.GONE else View.VISIBLE
                    if (!hasFiredNotification) {
                        hasFiredNotification = true
                        RouteDeviationManager.sendDeviationNotification(this@PassengerHomeActivity, bookingId)
                    }
                } else {
                    cardRouteDeviationWarning.visibility = View.GONE
                    hasFiredNotification = false
                    deviationAnswered = false
                }
            }

            override fun onCancelled(error: DatabaseError) {}
        }

        bookingRef.addValueEventListener(activeBookingListener!!)
    }

    private fun detachRouteDeviationListener() {
        activeBookingId?.let { id ->
            activeBookingListener?.let { listener ->
                database.child("bookings").child(id).removeEventListener(listener)
            }
        }
        activeBookingListener = null
    }

    private fun detachSequenceListener() {
        activeTripGroupId?.let { groupId ->
            sequenceListener?.let { listener ->
                database.child("tripGroups").child(groupId).child("stops").removeEventListener(listener)
            }
        }
        sequenceListener = null
        activeTripGroupId = null
    }

    private fun startIncomingPairRequestListener() {
        val myUid = FirebaseAuth.getInstance().currentUser?.uid ?: return

        pairRequestListener = EmergencyContactManager.listenForIncomingPairRequests(database, myUid) { requesterUid, requesterName ->
            if (shownPairRequestUids.contains(requesterUid)) return@listenForIncomingPairRequests
            shownPairRequestUids.add(requesterUid)

            AlertDialog.Builder(this)
                .setTitle("Trusted Contact Request")
                .setMessage("$requesterName wants to add you as a trusted emergency contact.")
                .setCancelable(false)
                .setPositiveButton("Accept") { dialog, _ ->
                    EmergencyContactManager.acceptPairRequest(database, myUid, requesterUid) { _, message ->
                        Toast.makeText(this, message, Toast.LENGTH_LONG).show()
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

    private fun startIncomingEmergencyAlertListener() {
        val myUid = FirebaseAuth.getInstance().currentUser?.uid ?: return

        alertListener = EmergencyContactManager.listenForIncomingAlertsDetailed(database, myUid) { alert ->
            if (isFinishing || isDestroyed) return@listenForIncomingAlertsDetailed

            // Stays on screen until dismissed (never a modal, never a forced screen switch).
            val headline = if (alert.isDriverEmergency) "🚨 Driver emergency" else "⚠️ Route deviation alert"
            val detail = if (alert.isDriverEmergency) "${alert.senderName} triggered an emergency alert."
                else "${alert.senderName}'s ride went off the planned route."
            showAlertBanner(alert.bookingId, headline, detail)

            // Keep a copy in the in-app notification list too.
            FirebaseAuth.getInstance().currentUser?.uid?.let { uid ->
                com.ruta.app.util.NotificationStore.push(
                    database, uid, com.ruta.app.util.NotificationStore.TYPE_SAFETY,
                    headline, "$detail Driver: ${alert.driverName} • ${alert.vehicleInfo}",
                    key = if (alert.key.isNotEmpty()) "al_${alert.key}" else null,
                    extras = mapOf("bookingId" to alert.bookingId)
                )
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        detachRouteDeviationListener()
        detachSequenceListener()
        allBookingsListener?.let { listener -> allBookingsQuery?.removeEventListener(listener) }
        allBookingsListener = null
        allBookingsQuery = null
        FirebaseAuth.getInstance().currentUser?.uid?.let { myUid ->
            pairRequestListener?.let { database.child("pairRequests").child(myUid).removeEventListener(it) }
            alertListener?.let { database.child("emergencyAlerts").child(myUid).removeEventListener(it) }
        }
    }
}

internal data class PickupPosition(val ordinal: Int, val label: String)

internal fun pickupPositionOf(sequence: List<Map<*, *>>, passengerId: String): PickupPosition? {
    var pickupCount = 0
    for (item in sequence) {
        val isPickup = item["isPickup"] as? Boolean ?: true
        if (!isPickup) continue

        pickupCount++

        val pId = item["passengerId"] as? String
        if (pId == passengerId) {
            val label = item["label"] as? String ?: ""
            return PickupPosition(pickupCount, label)
        }
    }
    return null
}