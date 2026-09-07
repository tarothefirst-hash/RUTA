package com.ruta.app.ui

import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.viewpager2.widget.ViewPager2
import com.google.android.gms.maps.CameraUpdateFactory
import com.google.android.gms.maps.GoogleMap
import com.google.android.gms.maps.OnMapReadyCallback
import com.google.android.gms.maps.model.BitmapDescriptorFactory
import com.google.android.gms.maps.model.LatLng
import com.google.android.gms.maps.model.LatLngBounds
import com.google.android.gms.maps.model.Marker
import com.google.android.gms.maps.model.MarkerOptions
import com.google.android.gms.maps.model.PolylineOptions
import com.google.android.material.card.MaterialCardView
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.database.DataSnapshot
import com.google.firebase.database.DatabaseError
import com.google.firebase.database.FirebaseDatabase
import com.google.firebase.database.ValueEventListener
import com.ruta.app.R
import com.ruta.app.data.AuthRepository
import com.ruta.app.util.FareCalculator
import com.ruta.app.util.VehicleType
import org.json.JSONObject

class PassengerHomeActivity : AppCompatActivity(), OnMapReadyCallback {

    private lateinit var mMap: GoogleMap
    private lateinit var cardDriverInfo: MaterialCardView
    private lateinit var txtPassengerTripStatus: TextView
    private lateinit var txtDriverName: TextView
    private lateinit var txtVehicleDetails: TextView
    private lateinit var btnOpenChat: Button
    private lateinit var btnCancelRide: Button
    private lateinit var viewPager: ViewPager2

    private lateinit var txtTripEta: TextView
    private lateinit var txtTripFare: TextView
    private lateinit var layoutDriverDetails: View

    // Default Starting Location (Macabulos, Tarlac City)
    private var currentPickupLoc = LatLng(15.4855, 120.5920)
    private var currentPickupAddress = "Macabulos, Tarlac City"

    // Default Selected Destination
    private var currentDropoffLoc = LatLng(15.4820, 120.5975)
    private var currentDropoffAddress = "SM Tarlac, Tarlac City"

    private var userHomeLoc: LatLng? = null
    private var userWorkLoc: LatLng? = null
    private var userFavoriteLoc: LatLng? = null

    private var currentCalculatedFare: Double = 0.0
    private var currentPassengerName: String = "Passenger"
    private var isVerifiedStudent: Boolean = false

    private var currentActiveBookingId: String? = null
    private var bookingValueListener: ValueEventListener? = null
    private var driverMarker: Marker? = null

    private val placesApiKey = "AIzaSyA_aZwg2zvItoG12d_kmMtPZGB0f8PxChk"

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_passenger_home)

        // Initialize Floating Card Views
        viewPager = findViewById(R.id.viewPager)
        cardDriverInfo = findViewById(R.id.cardDriverInfo)
        txtPassengerTripStatus = findViewById(R.id.txtPassengerTripStatus)
        txtDriverName = findViewById(R.id.txtDriverName)
        txtVehicleDetails = findViewById(R.id.txtVehicleDetails)
        btnOpenChat = findViewById(R.id.btnOpenChat)
        btnCancelRide = findViewById(R.id.btnCancelRide)

        // Initialize Subviews
        txtTripEta = findViewById(R.id.txtTripEta)
        txtTripFare = findViewById(R.id.txtTripFare)
        layoutDriverDetails = findViewById(R.id.layoutDriverDetails)

        // Fetch user active booking from node to prevent vanishing on return
        fetchUserActiveBookingAndListen()

        setupProfileButton()
        setupSearchBar()
        loadUserProfile()
        loadSavedLocationsFromFirebase()
        setupSavedLocationButtons()
        setupBookNowButton()

        // Connect ViewPager Adapter
        val adapter = PassengerPagerAdapter(this)
        viewPager.adapter = adapter

        val navHome = findViewById<ImageView>(R.id.navHome)
        val navPromos = findViewById<ImageView>(R.id.navPromos)
        val navSettings = findViewById<ImageView>(R.id.navSettings)
        val btnProfile = findViewById<ImageView>(R.id.btnProfile)

        navHome?.setOnClickListener { viewPager.currentItem = 0 }
        navPromos?.setOnClickListener { viewPager.currentItem = 1 }
        navSettings?.setOnClickListener { viewPager.currentItem = 2 }
        btnProfile?.setOnClickListener { viewPager.currentItem = 3 }

        btnOpenChat.setOnClickListener {
            val bookingId = currentActiveBookingId ?: return@setOnClickListener
            val intent = Intent(this, ChatActivity::class.java).apply {
                putExtra("BOOKING_ID", bookingId)
            }
            startActivity(intent)
        }

        btnCancelRide.setOnClickListener {
            cancelActiveBooking()
        }
    }

    override fun onResume() {
        super.onResume()
        // Re-check active booking whenever user returns to screen
        fetchUserActiveBookingAndListen()
    }

    private fun fetchUserActiveBookingAndListen() {
        val passengerUid = FirebaseAuth.getInstance().currentUser?.uid ?: run {
            cardDriverInfo.visibility = View.GONE
            return
        }

        val userRef = FirebaseDatabase.getInstance().reference.child("users").child(passengerUid)
        userRef.child("activeBookingId").get().addOnSuccessListener { snapshot ->
            val activeId = snapshot.getValue(String::class.java)
            if (!activeId.isNullOrEmpty()) {
                currentActiveBookingId = activeId
                listenToActiveBooking(activeId)
            } else {
                // Fallback check in case activeBookingId isn't set
                checkForExistingActiveBooking(passengerUid)
            }
        }.addOnFailureListener {
            cardDriverInfo.visibility = View.GONE
        }
    }

    private fun checkForExistingActiveBooking(passengerId: String) {
        FirebaseDatabase.getInstance().reference.child("bookings")
            .orderByChild("passengerId")
            .equalTo(passengerId)
            .addListenerForSingleValueEvent(object : ValueEventListener {
                override fun onDataChange(snapshot: DataSnapshot) {
                    var activeBookingFound = false

                    for (child in snapshot.children) {
                        val status = child.child("status").getValue(String::class.java)
                        if (status == "REQUESTED" || status == "ACCEPTED" || status == "IN_PROGRESS") {
                            val bookingId = child.key ?: continue
                            activeBookingFound = true

                            // Save back to user node for fast recovery next time
                            FirebaseDatabase.getInstance().reference.child("users")
                                .child(passengerId).child("activeBookingId").setValue(bookingId)

                            listenToActiveBooking(bookingId)
                            break
                        }
                    }

                    if (!activeBookingFound) {
                        cardDriverInfo.visibility = View.GONE
                        currentActiveBookingId = null
                    }
                }

                override fun onCancelled(error: DatabaseError) {
                    cardDriverInfo.visibility = View.GONE
                }
            })
    }

    private fun listenToActiveBooking(bookingId: String) {
        currentActiveBookingId = bookingId
        val bookingRef = FirebaseDatabase.getInstance().reference.child("bookings").child(bookingId)

        // Remove old listener if re-binding
        bookingValueListener?.let { bookingRef.removeEventListener(it) }

        bookingValueListener = object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                if (!snapshot.exists()) {
                    clearActiveBookingState()
                    return
                }

                val status = snapshot.child("status").getValue(String::class.java) ?: return
                val driverId = snapshot.child("driverId").getValue(String::class.java)
                val fare = snapshot.child("totalFare").getValue(Double::class.java) ?: 0.0

                when (status) {
                    "REQUESTED" -> {
                        cardDriverInfo.visibility = View.VISIBLE
                        layoutDriverDetails.visibility = View.GONE

                        txtPassengerTripStatus.text = "Finding your RUTA driver..."
                        txtTripEta.text = "Searching..."

                        btnOpenChat.visibility = View.GONE
                        btnCancelRide.visibility = View.VISIBLE
                    }

                    "ACCEPTED" -> {
                        cardDriverInfo.visibility = View.VISIBLE

                        if (driverId.isNullOrEmpty()) {
                            layoutDriverDetails.visibility = View.GONE
                            txtPassengerTripStatus.text = "Assigning driver details..."
                            btnOpenChat.visibility = View.GONE
                            btnCancelRide.visibility = View.VISIBLE
                        } else {
                            layoutDriverDetails.visibility = View.VISIBLE
                            txtPassengerTripStatus.text = "Driver is en route to pickup point"
                            txtPassengerTripStatus.setTextColor(resources.getColor(android.R.color.holo_orange_dark, null))
                            txtTripFare.text = "Fare: ₱${fare.toInt()}"

                            btnOpenChat.visibility = View.VISIBLE
                            btnCancelRide.visibility = View.VISIBLE

                            fetchDriverProfile(driverId)
                        }
                    }

                    "IN_PROGRESS" -> {
                        cardDriverInfo.visibility = View.VISIBLE

                        if (driverId.isNullOrEmpty()) {
                            layoutDriverDetails.visibility = View.GONE
                            txtPassengerTripStatus.text = "Trip starting..."
                            btnOpenChat.visibility = View.GONE
                            btnCancelRide.visibility = View.GONE
                        } else {
                            layoutDriverDetails.visibility = View.VISIBLE
                            txtPassengerTripStatus.text = "Trip in progress. Heading to destination!"
                            txtPassengerTripStatus.setTextColor(resources.getColor(android.R.color.holo_green_dark, null))
                            txtTripFare.text = "Fare: ₱${fare.toInt()}"

                            btnOpenChat.visibility = View.VISIBLE
                            btnCancelRide.visibility = View.GONE

                            fetchDriverProfile(driverId)
                        }
                    }

                    "COMPLETED", "CANCELLED" -> {
                        clearActiveBookingState()
                    }
                }

                // Calculate ETA & update driver marker on map
                val driverLat = snapshot.child("driverCurrentLocation/lat").getValue(Double::class.java)
                val driverLng = snapshot.child("driverCurrentLocation/lng").getValue(Double::class.java)

                if (driverLat != null && driverLng != null && driverLat != 0.0 && driverLng != 0.0) {
                    val driverLoc = LatLng(driverLat, driverLng)
                    updateDriverMarkerOnMap(driverLoc)

                    val targetLoc = if (status == "ACCEPTED") currentPickupLoc else currentDropoffLoc
                    calculateAndDisplayEta(driverLoc, targetLoc)
                }
            }

            override fun onCancelled(error: DatabaseError) {
                Toast.makeText(this@PassengerHomeActivity, "Error: ${error.message}", Toast.LENGTH_SHORT).show()
            }
        }

        bookingRef.addValueEventListener(bookingValueListener as ValueEventListener)
    }

    private fun calculateAndDisplayEta(from: LatLng, to: LatLng) {
        val results = FloatArray(1)
        android.location.Location.distanceBetween(
            from.latitude, from.longitude,
            to.latitude, to.longitude,
            results
        )
        val distanceMeters = results[0]
        val minutes = ((distanceMeters / 6.94) / 60).toInt().coerceAtLeast(1)
        txtTripEta.text = "ETA: ~$minutes min"
    }

    private fun clearActiveBookingState() {
        val passengerUid = FirebaseAuth.getInstance().currentUser?.uid
        if (passengerUid != null) {
            FirebaseDatabase.getInstance().reference.child("users")
                .child(passengerUid).child("activeBookingId").removeValue()
        }

        cardDriverInfo.visibility = View.GONE
        driverMarker?.remove()
        driverMarker = null

        currentActiveBookingId?.let { id ->
            bookingValueListener?.let { listener ->
                FirebaseDatabase.getInstance().reference.child("bookings").child(id).removeEventListener(listener)
            }
        }
        currentActiveBookingId = null
    }

    private fun cancelActiveBooking() {
        val bookingId = currentActiveBookingId ?: return

        val updates = hashMapOf<String, Any>(
            "status" to "CANCELLED",
            "cancelledAt" to System.currentTimeMillis()
        )

        FirebaseDatabase.getInstance().reference.child("bookings").child(bookingId)
            .updateChildren(updates)
            .addOnSuccessListener {
                Toast.makeText(this, "Ride request cancelled.", Toast.LENGTH_SHORT).show()
                clearActiveBookingState()
            }
            .addOnFailureListener { e ->
                Toast.makeText(this, "Failed to cancel: ${e.message}", Toast.LENGTH_SHORT).show()
            }
    }

    private fun updateDriverMarkerOnMap(driverLocation: LatLng) {
        if (!::mMap.isInitialized) return

        if (driverMarker == null) {
            driverMarker = mMap.addMarker(
                MarkerOptions()
                    .position(driverLocation)
                    .title("Your Driver")
                    .icon(BitmapDescriptorFactory.defaultMarker(BitmapDescriptorFactory.HUE_AZURE))
            )
        } else {
            driverMarker?.position = driverLocation
        }
    }

    private fun fetchDriverProfile(driverId: String) {
        FirebaseDatabase.getInstance().reference.child("users").child(driverId)
            .get().addOnSuccessListener { snapshot ->
                val name = snapshot.child("fullName").getValue(String::class.java)
                    ?: snapshot.child("name").getValue(String::class.java)
                    ?: "Driver"
                val model = snapshot.child("vehicleModel").getValue(String::class.java)
                    ?: snapshot.child("carModel").getValue(String::class.java)
                    ?: "Vehicle"
                val plate = snapshot.child("plateNumber").getValue(String::class.java) ?: "N/A"

                txtDriverName.text = name
                txtVehicleDetails.text = "$model • $plate"
            }
    }

    private fun setupProfileButton() {
        val btnProfile = findViewById<View>(R.id.btnProfile)
        btnProfile?.setOnClickListener {
            Toast.makeText(this, "Profile clicked!", Toast.LENGTH_SHORT).show()
        }
    }

    private fun setupSearchBar() {
        val layoutSearchBar = findViewById<LinearLayout>(R.id.layoutSearchBar)
        layoutSearchBar?.setOnClickListener {
            val intent = Intent(this, LocationSelectionActivity::class.java)
            startActivity(intent)
        }
    }

    private fun loadUserProfile() {
        val currentUser = FirebaseAuth.getInstance().currentUser ?: return
        val userRef = FirebaseDatabase.getInstance().getReference("users").child(currentUser.uid)

        userRef.get().addOnSuccessListener { snapshot ->
            val firstName = snapshot.child("firstName").getValue(String::class.java)
            val fullName = snapshot.child("fullName").getValue(String::class.java)
                ?: snapshot.child("name").getValue(String::class.java)

            isVerifiedStudent = snapshot.child("isStudent").getValue(Boolean::class.java) ?: false

            if (!fullName.isNullOrEmpty()) {
                currentPassengerName = fullName
            }

            if (!firstName.isNullOrEmpty()) {
                val txtGreeting = findViewById<TextView>(R.id.txtGreeting)
                txtGreeting?.text = "Hi $firstName!"
            }
        }
    }

    private fun setupSavedLocationButtons() {
        val btnHome = findViewById<View>(R.id.btnHome)
        val btnWork = findViewById<View>(R.id.btnWork)
        val btnFavorite = findViewById<View>(R.id.btnFavorite)

        btnHome?.setOnClickListener {
            userHomeLoc?.let { dest ->
                updateDestinationRoute("Home", dest)
            } ?: run {
                saveDefaultAndRoute("home", "Home", LatLng(15.4855, 120.5920))
            }
        }

        btnWork?.setOnClickListener {
            userWorkLoc?.let { dest ->
                updateDestinationRoute("Work", dest)
            } ?: run {
                saveDefaultAndRoute("work", "Work", LatLng(15.4820, 120.5975))
            }
        }

        btnFavorite?.setOnClickListener {
            userFavoriteLoc?.let { dest ->
                updateDestinationRoute("Favorite", dest)
            } ?: run {
                saveDefaultAndRoute("favorite", "TSU Main", LatLng(15.4868, 120.5891))
            }
        }
    }

    private fun loadSavedLocationsFromFirebase() {
        val currentUser = FirebaseAuth.getInstance().currentUser ?: return
        val userRef = FirebaseDatabase.getInstance().getReference("users").child(currentUser.uid).child("savedLocations")

        userRef.get().addOnSuccessListener { snapshot ->
            snapshot.child("home").let {
                val lat = it.child("latitude").getValue(Double::class.java)
                val lng = it.child("longitude").getValue(Double::class.java)
                if (lat != null && lng != null) userHomeLoc = LatLng(lat, lng)
            }
            snapshot.child("work").let {
                val lat = it.child("latitude").getValue(Double::class.java)
                val lng = it.child("longitude").getValue(Double::class.java)
                if (lat != null && lng != null) userWorkLoc = LatLng(lat, lng)
            }
            snapshot.child("favorite").let {
                val lat = it.child("latitude").getValue(Double::class.java)
                val lng = it.child("longitude").getValue(Double::class.java)
                if (lat != null && lng != null) userFavoriteLoc = LatLng(lat, lng)
            }
        }
    }

    private fun updateDestinationRoute(title: String, destination: LatLng) {
        if (!::mMap.isInitialized) return

        currentDropoffLoc = destination
        currentDropoffAddress = title
        mMap.clear()

        mMap.addMarker(
            MarkerOptions()
                .position(currentPickupLoc)
                .title("Pickup: $currentPickupAddress")
                .icon(BitmapDescriptorFactory.defaultMarker(BitmapDescriptorFactory.HUE_GREEN))
        )

        mMap.addMarker(
            MarkerOptions()
                .position(destination)
                .title(title)
                .icon(BitmapDescriptorFactory.defaultMarker(BitmapDescriptorFactory.HUE_RED))
        )

        val bounds = LatLngBounds.Builder()
            .include(currentPickupLoc)
            .include(destination)
            .build()

        mMap.animateCamera(CameraUpdateFactory.newLatLngBounds(bounds, 150))
        fetchRoadRoute(currentPickupLoc, destination)
    }

    private fun saveDefaultAndRoute(type: String, title: String, location: LatLng) {
        val authRepo = AuthRepository()
        authRepo.saveUserLocation(type, title, title, location.latitude, location.longitude) { success ->
            if (success) {
                when (type) {
                    "home" -> userHomeLoc = location
                    "work" -> userWorkLoc = location
                    "favorite" -> userFavoriteLoc = location
                }
            }
        }
        updateDestinationRoute(title, location)
    }

    override fun onMapReady(googleMap: GoogleMap) {
        mMap = googleMap
        updateDestinationRoute("SM Tarlac", currentDropoffLoc)
    }

    private fun fetchRoadRoute(origin: LatLng, destination: LatLng) {
        val url = "https://maps.googleapis.com/maps/api/directions/json?" +
                "origin=${origin.latitude},${origin.longitude}" +
                "&destination=${destination.latitude},${destination.longitude}" +
                "&key=$placesApiKey"

        Thread {
            val jsonData = DirectionsHelper.downloadUrl(url)
            val routePoints = DirectionsHelper.parseDirections(jsonData)

            val (distanceMeters, durationSeconds) = parseDistanceAndDuration(jsonData)

            val distanceKm = distanceMeters / 1000.0
            val durationMin = durationSeconds / 60.0

            currentCalculatedFare = FareCalculator.calculateFare(
                distanceKm = distanceKm,
                durationMin = durationMin,
                vehicleType = VehicleType.SEDAN,
                isShared = true,
                hasDiscount = isVerifiedStudent
            )

            runOnUiThread {
                if (routePoints.isNotEmpty()) {
                    val lineOptions = PolylineOptions()
                        .addAll(routePoints)
                        .width(12f)
                        .color(Color.RED)
                        .geodesic(true)

                    mMap.addPolyline(lineOptions)
                }

                Toast.makeText(this, "Estimated Shared Fare: ₱${currentCalculatedFare.toInt()}", Toast.LENGTH_SHORT).show()
            }
        }.start()
    }

    private fun parseDistanceAndDuration(jsonData: String): Pair<Int, Int> {
        return try {
            val jsonObject = JSONObject(jsonData)
            val routes = jsonObject.getJSONArray("routes")
            if (routes.length() > 0) {
                val legs = routes.getJSONObject(0).getJSONArray("legs")
                if (legs.length() > 0) {
                    val leg = legs.getJSONObject(0)
                    val distanceMeters = leg.getJSONObject("distance").getInt("value")
                    val durationSeconds = leg.getJSONObject("duration").getInt("value")
                    Pair(distanceMeters, durationSeconds)
                } else Pair(3000, 600)
            } else Pair(3000, 600)
        } catch (e: Exception) {
            Pair(3000, 600)
        }
    }

    private fun setupBookNowButton() {
        val btnBookNow = findViewById<View>(R.id.btnBookNow)
        btnBookNow?.setOnClickListener {
            val currentUser = FirebaseAuth.getInstance().currentUser
            if (currentUser == null) {
                Toast.makeText(this, "Please log in first.", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            if (currentActiveBookingId != null) {
                Toast.makeText(this, "You already have an active booking request!", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            btnBookNow.isEnabled = false
            Toast.makeText(this, "Requesting RUTA ride...", Toast.LENGTH_SHORT).show()

            createDynamicBooking(currentUser.uid) { bookingId ->
                btnBookNow.isEnabled = true
                Toast.makeText(this, "Ride requested! Searching for nearby drivers...", Toast.LENGTH_LONG).show()
                listenToActiveBooking(bookingId)
            }
        }
    }

    private fun createDynamicBooking(passengerUid: String, onSuccess: (String) -> Unit) {
        val database = FirebaseDatabase.getInstance().reference
        val bookingRef = database.child("bookings").push()
        val uniqueBookingId = bookingRef.key ?: return

        val stopsList = listOf(
            hashMapOf(
                "stopOrder" to 1,
                "address" to currentPickupAddress,
                "lat" to currentPickupLoc.latitude,
                "lng" to currentPickupLoc.longitude,
                "type" to "PICKUP"
            ),
            hashMapOf(
                "stopOrder" to 2,
                "address" to currentDropoffAddress,
                "lat" to currentDropoffLoc.latitude,
                "lng" to currentDropoffLoc.longitude,
                "type" to "DROPOFF",
                "fareShare" to currentCalculatedFare
            )
        )

        val bookingData = hashMapOf<String, Any?>(
            "bookingId" to uniqueBookingId,
            "passengerId" to passengerUid,
            "driverId" to null,
            "status" to "REQUESTED",
            "serviceType" to "SHARED",
            "totalFare" to currentCalculatedFare,
            "fare" to currentCalculatedFare,
            "passengerName" to currentPassengerName,
            "pickupAddress" to currentPickupAddress,
            "pickupLat" to currentPickupLoc.latitude,
            "pickupLng" to currentPickupLoc.longitude,
            "dropoffAddress" to currentDropoffAddress,
            "dropoffLat" to currentDropoffLoc.latitude,
            "dropoffLng" to currentDropoffLoc.longitude,
            "stops" to stopsList,
            "createdAt" to System.currentTimeMillis()
        )

        bookingRef.setValue(bookingData).addOnSuccessListener {
            // Write unique booking ID directly to user's node so return/back press restores it instantly
            database.child("users").child(passengerUid).child("activeBookingId").setValue(uniqueBookingId)
                .addOnSuccessListener {
                    onSuccess(uniqueBookingId)
                }
        }.addOnFailureListener { e ->
            findViewById<View>(R.id.btnBookNow)?.isEnabled = true
            Toast.makeText(this, "Failed to create booking: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        val bookingId = currentActiveBookingId
        if (bookingId != null && bookingValueListener != null) {
            FirebaseDatabase.getInstance().reference.child("bookings").child(bookingId)
                .removeEventListener(bookingValueListener!!)
        }
    }
}