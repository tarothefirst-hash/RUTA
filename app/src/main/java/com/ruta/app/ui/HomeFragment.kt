package com.ruta.app.ui

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.ruta.app.model.BookingModel
import com.ruta.app.util.NotificationLogic
import com.ruta.app.util.NotificationStore
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.database.DataSnapshot
import com.google.firebase.database.DatabaseError
import com.google.firebase.database.DatabaseReference
import com.google.firebase.database.FirebaseDatabase
import com.google.firebase.database.Query
import com.google.firebase.database.ValueEventListener
import com.ruta.app.R

class HomeFragment : Fragment() {

    private lateinit var txtGreeting: TextView
    private lateinit var cardActiveTripEta: View
    private lateinit var txtActiveTripStatus: TextView
    private lateinit var txtActiveTripEta: TextView
    private var txtDriverInfoHome: TextView? = null

    private lateinit var database: DatabaseReference
    private var activeTripListener: ValueEventListener? = null
    private var activeTripQueryRef: Query? = null

    // Tracks current active booking ID to lock search behavior
    private var currentActiveBookingId: String? = null

    private var recyclerRecent: RecyclerView? = null
    private var txtNoRecent: TextView? = null
    private var txtNotifBadge: TextView? = null
    private var notifListener: ValueEventListener? = null

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View? {
        return inflater.inflate(R.layout.fragment_home, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        database = FirebaseDatabase.getInstance().reference

        // 1. Bind Layout Views
        txtGreeting = view.findViewById(R.id.txtGreeting)
        cardActiveTripEta = view.findViewById(R.id.cardActiveTripEta)
        txtActiveTripStatus = view.findViewById(R.id.txtActiveTripStatus)
        txtActiveTripEta = view.findViewById(R.id.txtActiveTripEta)
        txtDriverInfoHome = view.findViewById(R.id.txtDriverInfoHome)

        recyclerRecent = view.findViewById(R.id.recyclerRecentBookings)
        recyclerRecent?.layoutManager = LinearLayoutManager(requireContext())
        txtNoRecent = view.findViewById(R.id.txtNoRecentRides)
        txtNotifBadge = view.findViewById(R.id.txtNotifBadge)

        // The bell opens the notification list.
        view.findViewById<View>(R.id.btnNotification)?.setOnClickListener {
            startActivity(Intent(requireContext(), NotificationsActivity::class.java))
        }

        // 2. Fetch User Name
        loadUserName()

        // 3. Smart Search Bar Click Logic
        val layoutSearchBar = view.findViewById<View>(R.id.layoutSearchBar)
        layoutSearchBar?.setOnClickListener {
            if (currentActiveBookingId != null) {
                // Active ride exists -> Direct to tracking view instead of ride setup
                navigateToActiveBooking(currentActiveBookingId!!)
            } else {
                // No active ride -> Open location selection for a new ride
                startActivity(Intent(requireContext(), LocationSelectionActivity::class.java))
            }
        }
    }

    override fun onResume() {
        super.onResume()
        // Automatically checks Firebase state when returning to HomeFragment
        listenForActiveTrip()
        listenForNotificationBadge()
    }

    override fun onPause() {
        super.onPause()
        detachActiveTripListener()
        val uid = FirebaseAuth.getInstance().currentUser?.uid
        notifListener?.let { l -> if (uid != null) NotificationStore.stopListening(database, uid, l) }
        notifListener = null
    }

    private fun listenForNotificationBadge() {
        val uid = FirebaseAuth.getInstance().currentUser?.uid ?: return
        notifListener?.let { NotificationStore.stopListening(database, uid, it) }
        notifListener = NotificationStore.listen(database, uid) { items ->
            val unread = NotificationLogic.unreadCount(items)
            txtNotifBadge?.apply {
                text = if (unread > 9) "9+" else unread.toString()
                visibility = if (unread > 0) View.VISIBLE else View.GONE
            }
        }
    }

    /** The passenger's last few finished trips, newest first. */
    private fun showRecentRides(snapshot: DataSnapshot) {
        val rides = snapshot.children.mapNotNull { child ->
            val status = child.child("status").getValue(String::class.java)
            if (status != "COMPLETED" && status != "CANCELLED") return@mapNotNull null
            child.getValue(BookingModel::class.java)?.also { it.bookingId = child.key }
        }.sortedByDescending { it.createdAt }.take(3)

        txtNoRecent?.visibility = if (rides.isEmpty()) View.VISIBLE else View.GONE
        recyclerRecent?.adapter = BookingHistoryAdapter(rides) { ride ->
            val fare = if (ride.finalFare > 0.0) ride.finalFare else (ride.fare ?: 0.0)
            AlertDialog.Builder(requireContext())
                .setTitle(if (ride.status == "COMPLETED") "Completed trip" else "Cancelled trip")
                .setMessage(
                    "From: ${ride.pickupAddress ?: "N/A"}\nTo: ${ride.dropoffAddress ?: "N/A"}\n" +
                        "Fare: ₱${String.format("%.2f", fare)}"
                )
                .setPositiveButton("Close", null)
                .show()
        }
    }

    private fun listenForActiveTrip() {
        val passengerId = FirebaseAuth.getInstance().currentUser?.uid ?: return

        detachActiveTripListener()

        // Read this passenger's bookings and pick the active one explicitly.
        // limitToLast(1) used to hide the real active trip whenever a newer
        // completed booking existed; status filtering needs no extra DB index.
        activeTripQueryRef = database.child("bookings")
            .orderByChild("passengerId")
            .equalTo(passengerId)

        activeTripListener = object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                var activeBookingId: String? = null
                var currentStatus = ""
                var driverId = ""
                var distanceText = ""
                var durationText = ""

                for (child in snapshot.children) {
                    val status = child.child("status").getValue(String::class.java) ?: ""

                    if (status in listOf("MATCHING", "MATCHED", "REQUESTED", "SEARCHING", "ACCEPTED", "ARRIVED", "IN_PROGRESS")) {
                        activeBookingId = child.key
                        currentStatus = status
                        driverId = child.child("driverId").getValue(String::class.java) ?: ""

                        val distMeters = child.child("distanceMeters").getValue(Int::class.java) ?: 0
                        val durSeconds = child.child("durationSeconds").getValue(Int::class.java) ?: 0
                        distanceText = String.format("%.1f km", distMeters / 1000.0)
                        durationText = "${durSeconds / 60} mins"
                    }
                }

                currentActiveBookingId = activeBookingId
                if (isAdded) showRecentRides(snapshot)

                if (activeBookingId != null) {
                    cardActiveTripEta.visibility = View.VISIBLE
                    txtActiveTripEta.text = "$durationText • $distanceText"

                    when (currentStatus) {
                        "MATCHING" -> {
                            txtActiveTripStatus.text = "Matching co-passenger..."
                            txtDriverInfoHome?.text = "Finding another passenger along your route..."
                        }
                        "MATCHED" -> {
                            txtActiveTripStatus.text = "Co-passenger Matched"
                            txtDriverInfoHome?.text = "Connecting to driver..."
                        }
                        "REQUESTED", "SEARCHING" -> {
                            txtActiveTripStatus.text = "Searching for driver..."
                            txtDriverInfoHome?.text = "Connecting to nearest driver..."
                        }
                        "ACCEPTED", "ARRIVED", "IN_PROGRESS" -> {
                            txtActiveTripStatus.text = if (currentStatus == "IN_PROGRESS") "Trip in progress" else "Driver en route"
                            fetchDriverDetails(driverId) { driverName ->
                                txtDriverInfoHome?.text = "Driver: $driverName"
                            }
                        }
                        else -> {
                            txtActiveTripStatus.text = "Active Ride"
                            txtDriverInfoHome?.text = "Tap to view details"
                        }
                    }

                    val finalBookingId = activeBookingId
                    cardActiveTripEta.setOnClickListener {
                        navigateToActiveBooking(finalBookingId)
                    }
                } else {
                    // Clears UI immediately on COMPLETED or CANCELLED
                    cardActiveTripEta.visibility = View.GONE
                }
            }

            override fun onCancelled(error: DatabaseError) {}
        }

        activeTripQueryRef?.addValueEventListener(activeTripListener!!)
    }

    private fun navigateToActiveBooking(bookingId: String) {
        val intent = Intent(requireContext(), LocationSelectionActivity::class.java).apply {
            putExtra("BOOKING_ID", bookingId)
            putExtra("TRIP_ID", bookingId)
            putExtra("IS_ACTIVE_BOOKING", true)
        }
        startActivity(intent)
    }

    private fun fetchDriverDetails(driverId: String, callback: (String) -> Unit) {
        if (driverId.isEmpty()) {
            callback("Assigned Driver")
            return
        }
        database.child("users").child(driverId).child("name")
            .addListenerForSingleValueEvent(object : ValueEventListener {
                override fun onDataChange(snapshot: DataSnapshot) {
                    callback(snapshot.getValue(String::class.java) ?: "Assigned Driver")
                }
                override fun onCancelled(error: DatabaseError) {
                    callback("Assigned Driver")
                }
            })
    }

    private fun detachActiveTripListener() {
        activeTripListener?.let { listener ->
            activeTripQueryRef?.removeEventListener(listener)
        }
        activeTripListener = null
        activeTripQueryRef = null
    }

    private fun loadUserName() {
        val firebaseUser = FirebaseAuth.getInstance().currentUser ?: return
        val sharedPref = requireContext().getSharedPreferences("USER_SESSION", Context.MODE_PRIVATE)

        val cachedName = sharedPref.getString("USER_NAME", null)
        if (!cachedName.isNullOrEmpty()) {
            txtGreeting.text = "Hi $cachedName!"
        }

        database.child("users").child(firebaseUser.uid)
            .addListenerForSingleValueEvent(object : ValueEventListener {
                override fun onDataChange(snapshot: DataSnapshot) {
                    val firstName = snapshot.child("firstName").getValue(String::class.java)
                        ?: snapshot.child("name").getValue(String::class.java)

                    if (!firstName.isNullOrEmpty()) {
                        txtGreeting.text = "Hi $firstName!"
                        sharedPref.edit().putString("USER_NAME", firstName).apply()
                    }
                }

                override fun onCancelled(error: DatabaseError) {}
            })
    }
}