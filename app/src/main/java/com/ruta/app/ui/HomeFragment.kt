package com.ruta.app.ui

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.fragment.app.Fragment
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.database.DataSnapshot
import com.google.firebase.database.DatabaseError
import com.google.firebase.database.DatabaseReference
import com.google.firebase.database.FirebaseDatabase
import com.google.firebase.database.ValueEventListener
import com.ruta.app.R

class HomeFragment : Fragment() {

    private lateinit var txtFromLocation: TextView
    private lateinit var txtToLocation: TextView
    private lateinit var btnBookNow: TextView
    private lateinit var cardActiveTripEta: View
    private lateinit var txtActiveTripStatus: TextView

    private lateinit var database: DatabaseReference
    private var activeTripListener: ValueEventListener? = null
    private var activeTripQueryRef: com.google.firebase.database.Query? = null

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View? {
        return inflater.inflate(R.layout.fragment_home, container, false)
    }
//comment sa driver
    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        database = FirebaseDatabase.getInstance().reference

        // 1. Bind Layout Views
        val txtGreeting = view.findViewById<TextView>(R.id.txtGreeting)
        val layoutSearchBar = view.findViewById<LinearLayout>(R.id.layoutSearchBar)
        val btnSwap = view.findViewById<View>(R.id.btnSwap)

        txtFromLocation = view.findViewById(R.id.txtFromLocation)
        txtToLocation = view.findViewById(R.id.txtToLocation)
        btnBookNow = view.findViewById(R.id.btnBookNow)
        cardActiveTripEta = view.findViewById(R.id.cardActiveTripEta)
        txtActiveTripStatus = view.findViewById(R.id.txtActiveTripStatus)

        // 2. Set Greeting Name (Instantly pulled from local cache)
        val firebaseUser = FirebaseAuth.getInstance().currentUser
        val sharedPref = requireContext().getSharedPreferences("USER_SESSION", Context.MODE_PRIVATE)

        val userName = sharedPref.getString("USER_NAME", null)
            ?: firebaseUser?.displayName
            ?: "Passenger"

        txtGreeting.text = "Hi $userName!"

        // 3. Listeners Setup
        layoutSearchBar.setOnClickListener {
            startActivity(Intent(requireContext(), LocationSelectionActivity::class.java))
        }

        btnSwap.setOnClickListener {
            val temp = txtFromLocation.text
            txtFromLocation.text = txtToLocation.text
            txtToLocation.text = temp
        }

        btnBookNow.setOnClickListener {
            submitBooking()
        }

        // 4. Start optimized query for active trip
        listenForActiveTrip()
    }

    private fun submitBooking() {
        val pickup = txtFromLocation.text.toString()
        val dropoff = txtToLocation.text.toString()

        if (pickup == "Select Pickup..." || dropoff == "Select Drop-off...") {
            Toast.makeText(requireContext(), "Please select pickup and drop-off points first", Toast.LENGTH_SHORT).show()
            return
        }

        val userId = FirebaseAuth.getInstance().currentUser?.uid ?: return
        val bookingRef = database.child("bookings").push()
        val bookingId = bookingRef.key ?: return

        // Replace hardcoded test lat/lng with your actual selected location variables
        val bookingData = hashMapOf<String, Any>(
            "bookingId" to bookingId,
            "passengerId" to userId,
            "driverId" to "",
            "status" to "REQUESTED",
            "pickupAddress" to pickup,
            "dropoffAddress" to dropoff,
            "pickupLat" to 15.1450,    // 📍 Pass actual double coordinates
            "pickupLng" to 120.5887,
            "dropoffLat" to 15.1500,
            "dropoffLng" to 120.5900,
            "timestamp" to System.currentTimeMillis()
        )

        bookingRef.setValue(bookingData)
            .addOnSuccessListener {
                Toast.makeText(requireContext(), "Booking Submitted!", Toast.LENGTH_SHORT).show()
            }
            .addOnFailureListener { e ->
                Toast.makeText(requireContext(), "Failed: ${e.message}", Toast.LENGTH_SHORT).show()
            }
    }
    private fun cancelBooking(bookingId: String) {
        val updates = hashMapOf<String, Any>(
            "status" to "CANCELLED",
            "cancelledAt" to System.currentTimeMillis()
        )

        database.child("bookings").child(bookingId).updateChildren(updates)
            .addOnSuccessListener {
                Toast.makeText(requireContext(), "Ride cancelled.", Toast.LENGTH_SHORT).show()
                cardActiveTripEta.visibility = View.GONE
            }
            .addOnFailureListener { e ->
                Toast.makeText(requireContext(), "Failed to cancel: ${e.message}", Toast.LENGTH_SHORT).show()
            }
    }

    private fun listenForActiveTrip() {
        val passengerId = FirebaseAuth.getInstance().currentUser?.uid ?: return

        // 🚀 OPTIMIZATION: Query only the single latest booking for this passenger
        activeTripQueryRef = database.child("bookings")
            .orderByChild("passengerId")
            .equalTo(passengerId)
            .limitToLast(1)

        activeTripListener = object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                var activeBookingId: String? = null
                var currentStatus = ""

                for (child in snapshot.children) {
                    val status = child.child("status").getValue(String::class.java) ?: ""
                    if (status == "REQUESTED" || status == "ACCEPTED" || status == "IN_PROGRESS") {
                        activeBookingId = child.key
                        currentStatus = status
                    }
                }

                if (activeBookingId != null) {
                    cardActiveTripEta.visibility = View.VISIBLE
                    txtActiveTripStatus.text = when (currentStatus) {
                        "REQUESTED" -> "Searching for Driver..."
                        "ACCEPTED" -> "Driver En Route"
                        "IN_PROGRESS" -> "Trip in Progress"
                        else -> "Active Ride"
                    }

                    cardActiveTripEta.setOnClickListener {
                        cancelBooking(activeBookingId)
                    }
                } else {
                    cardActiveTripEta.visibility = View.GONE
                }
            }

            override fun onCancelled(error: DatabaseError) {}
        }

        activeTripQueryRef?.addValueEventListener(activeTripListener!!)
    }

    override fun onDestroyView() {
        super.onDestroyView()
        // Cleanup listener to prevent memory leaks and background CPU usage
        activeTripListener?.let { listener ->
            activeTripQueryRef?.removeEventListener(listener)
        }
    }
}