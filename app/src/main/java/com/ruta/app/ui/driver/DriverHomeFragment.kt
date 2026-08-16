package com.ruta.app.ui.driver

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.fragment.app.Fragment
import com.google.android.gms.maps.CameraUpdateFactory
import com.google.android.gms.maps.GoogleMap
import com.google.android.gms.maps.OnMapReadyCallback
import com.google.android.gms.maps.SupportMapFragment
import com.google.android.gms.maps.model.LatLng
import com.google.android.gms.maps.model.MarkerOptions
import com.google.android.material.card.MaterialCardView
import com.google.android.material.switchmaterial.SwitchMaterial
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.database.DataSnapshot
import com.google.firebase.database.DatabaseError
import com.google.firebase.database.DatabaseReference
import com.google.firebase.database.FirebaseDatabase
import com.google.firebase.database.ValueEventListener
import com.ruta.app.R
import com.ruta.app.ui.LoginActivity

class DriverHomeFragment : Fragment(), OnMapReadyCallback {

    private lateinit var mMap: GoogleMap
    private lateinit var database: DatabaseReference

    private lateinit var txtDriverStatus: TextView
    private lateinit var switchGoOnline: SwitchMaterial
    private lateinit var cardIncomingRequest: MaterialCardView
    private lateinit var btnDriverLogout: Button

    private lateinit var txtServiceType: TextView
    private lateinit var txtPassengerName: TextView
    private lateinit var txtPickupLocation: TextView
    private lateinit var txtDropoffLocation: TextView
    private lateinit var txtEstimatedFare: TextView
    private lateinit var btnDeclineRide: Button
    private lateinit var btnAcceptRide: Button

    private var activeBookingId: String? = null
    private var isOnline = false
    private var bookingsListener: ValueEventListener? = null

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View? {
        return inflater.inflate(R.layout.fragment_driver_home, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        database = FirebaseDatabase.getInstance().reference

        // Initialize Views
        txtDriverStatus = view.findViewById(R.id.txtDriverStatus)
        switchGoOnline = view.findViewById(R.id.switchGoOnline)
        cardIncomingRequest = view.findViewById(R.id.cardIncomingRequest)
        btnDriverLogout = view.findViewById(R.id.btnDriverLogout)

        txtServiceType = view.findViewById(R.id.txtServiceType)
        txtPassengerName = view.findViewById(R.id.txtPassengerName)
        txtPickupLocation = view.findViewById(R.id.txtPickupLocation)
        txtDropoffLocation = view.findViewById(R.id.txtDropoffLocation)
        txtEstimatedFare = view.findViewById(R.id.txtEstimatedFare)
        btnDeclineRide = view.findViewById(R.id.btnDeclineRide)
        btnAcceptRide = view.findViewById(R.id.btnAcceptRide)

        // Setup Map
        val mapFragment = childFragmentManager.findFragmentById(R.id.driverMap) as SupportMapFragment?
        mapFragment?.getMapAsync(this)

        // Online / Offline Switch Listener
        switchGoOnline.setOnCheckedChangeListener { _, isChecked ->
            isOnline = isChecked
            val driverUid = FirebaseAuth.getInstance().currentUser?.uid ?: return@setOnCheckedChangeListener

            database.child("users").child(driverUid).child("isOnline").setValue(isChecked)

            if (isChecked) {
                txtDriverStatus.text = "Status: ONLINE (Waiting for rides)"
                txtDriverStatus.setTextColor(resources.getColor(android.R.color.holo_green_dark, null))
                startListeningForBookings()
            } else {
                txtDriverStatus.text = "Status: OFFLINE"
                txtDriverStatus.setTextColor(resources.getColor(android.R.color.black, null))
                stopListeningForBookings()
                cardIncomingRequest.visibility = View.GONE
            }
        }

        // Action Buttons
        btnAcceptRide.setOnClickListener { acceptCurrentBooking() }
        btnDeclineRide.setOnClickListener { declineCurrentBooking() }
        btnDriverLogout.setOnClickListener { performDriverLogout() }
    }

    override fun onMapReady(googleMap: GoogleMap) {
        mMap = googleMap
        val tarlacCity = LatLng(15.4802, 120.5979)
        mMap.addMarker(MarkerOptions().position(tarlacCity).title("Tarlac City Driver Hub"))
        mMap.moveCamera(CameraUpdateFactory.newLatLngZoom(tarlacCity, 14f))
    }

    private fun startListeningForBookings() {
        stopListeningForBookings() // Prevent duplicate listeners

        val bookingsQuery = database.child("bookings").orderByChild("status").equalTo("REQUESTED")

        bookingsListener = object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                if (!isOnline) return

                var foundRequest = false
                for (bookingSnap in snapshot.children) {
                    activeBookingId = bookingSnap.key

                    val serviceType = bookingSnap.child("serviceType").getValue(String::class.java) ?: "REGULAR"
                    val isShared = bookingSnap.child("isShared").getValue(Boolean::class.java) ?: false
                    val passengerName = bookingSnap.child("passengerName").getValue(String::class.java) ?: "Passenger"
                    val pickupAddress = bookingSnap.child("pickupAddress").getValue(String::class.java) ?: "Pickup Location"
                    val dropoffAddress = bookingSnap.child("dropoffAddress").getValue(String::class.java) ?: "Dropoff Location"
                    val fare = bookingSnap.child("fare").getValue(Double::class.java) ?: 0.0

                    // Update UI with incoming details matching LocationSelectionActivity structure
                    txtServiceType.text = if (isShared) "New Ride Request! ($serviceType)" else "New Ride Request! ($serviceType)"
                    txtPassengerName.text = passengerName
                    txtPickupLocation.text = "Pickup: $pickupAddress"
                    txtDropoffLocation.text = "Dropoff: $dropoffAddress"
                    txtEstimatedFare.text = "Estimated Fare: ₱${fare.toInt()}"

                    cardIncomingRequest.visibility = View.VISIBLE
                    foundRequest = true
                    break // Focus on the latest pending request
                }

                if (!foundRequest) {
                    cardIncomingRequest.visibility = View.GONE
                }
            }

            override fun onCancelled(error: DatabaseError) {
                Toast.makeText(context, "Error reading rides: ${error.message}", Toast.LENGTH_SHORT).show()
            }
        }

        bookingsQuery.addValueEventListener(bookingsListener as ValueEventListener)
    }

    private fun stopListeningForBookings() {
        bookingsListener?.let {
            database.child("bookings").removeEventListener(it)
            bookingsListener = null
        }
    }

    private fun acceptCurrentBooking() {
        val bookingId = activeBookingId ?: return
        val currentDriverUid = FirebaseAuth.getInstance().currentUser?.uid ?: return

        val updates = hashMapOf<String, Any>(
            "status" to "ACCEPTED",
            "driverId" to currentDriverUid
        )

        database.child("bookings").child(bookingId).updateChildren(updates).addOnSuccessListener {
            Toast.makeText(requireContext(), "Ride Accepted! Navigating...", Toast.LENGTH_SHORT).show()
            cardIncomingRequest.visibility = View.GONE
            txtDriverStatus.text = "Status: ON TRIP WITH PASSENGER"
        }.addOnFailureListener {
            Toast.makeText(requireContext(), "Failed to accept ride.", Toast.LENGTH_SHORT).show()
        }
    }

    private fun declineCurrentBooking() {
        cardIncomingRequest.visibility = View.GONE
        activeBookingId = null
        Toast.makeText(requireContext(), "Ride declined", Toast.LENGTH_SHORT).show()
    }

    private fun performDriverLogout() {
        val driverUid = FirebaseAuth.getInstance().currentUser?.uid
        if (driverUid != null) {
            database.child("users").child(driverUid).child("isOnline").setValue(false)
        }

        FirebaseAuth.getInstance().signOut()

        val sharedPref = requireContext().getSharedPreferences("USER_SESSION", Context.MODE_PRIVATE)
        sharedPref.edit().clear().apply()

        Toast.makeText(requireContext(), "Driver logged out successfully", Toast.LENGTH_SHORT).show()

        val intent = Intent(requireContext(), LoginActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
        }
        startActivity(intent)
    }

    override fun onDestroyView() {
        super.onDestroyView()
        stopListeningForBookings()
    }
}