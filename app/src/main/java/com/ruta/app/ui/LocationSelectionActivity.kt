package com.ruta.app.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.location.Address
import android.location.Geocoder
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.graphics.toColorInt
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationServices
import com.google.android.gms.maps.CameraUpdateFactory
import com.google.android.gms.maps.GoogleMap
import com.google.android.gms.maps.OnMapReadyCallback
import com.google.android.gms.maps.SupportMapFragment
import com.google.android.gms.maps.model.BitmapDescriptorFactory
import com.google.android.gms.maps.model.LatLng
import com.google.android.gms.maps.model.LatLngBounds
import com.google.android.gms.maps.model.Marker
import com.google.android.gms.maps.model.MarkerOptions
import com.google.android.gms.maps.model.Polyline
import com.google.android.gms.maps.model.PolylineOptions
import com.google.android.gms.maps.model.RoundCap
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.card.MaterialCardView
import com.google.android.libraries.places.api.Places
import com.google.android.libraries.places.api.net.FindAutocompletePredictionsRequest
import com.google.android.libraries.places.api.net.PlacesClient
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.database.DataSnapshot
import com.google.firebase.database.DatabaseError
import com.google.firebase.database.DatabaseReference
import com.google.firebase.database.FirebaseDatabase
import com.google.firebase.database.ValueEventListener
import com.ruta.app.R
import com.ruta.app.model.LocationItem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.util.Calendar
import java.util.Locale

class LocationSelectionActivity : AppCompatActivity(), OnMapReadyCallback {

    private lateinit var mMap: GoogleMap
    private lateinit var fusedLocationClient: FusedLocationProviderClient
    private lateinit var placesClient: PlacesClient
    private lateinit var database: DatabaseReference

    private lateinit var etSearchLocation: EditText
    private lateinit var rvSuggestions: RecyclerView
    private lateinit var txtPickupDisplay: TextView
    private lateinit var txtDropoffDisplay: TextView
    private lateinit var btnConfirmStage: Button

    // Card Overlay Driver Info Views (from XML)
    private lateinit var cardDriverInfo: MaterialCardView
    private lateinit var txtPassengerTripStatus: TextView
    private lateinit var txtDriverName: TextView
    private lateinit var txtVehicleDetails: TextView
    private lateinit var btnOpenChat: Button

    private enum class SelectionStage { PICKUP, DROPOFF, ROUTE_READY, BOOKING_SUBMITTED }
    private var currentStage = SelectionStage.PICKUP

    private var pickupLatLng: LatLng? = null
    private var dropoffLatLng: LatLng? = null
    private var pickupAddress: String = "Detecting location..."
    private var dropoffAddress: String = "Add Location"

    private var currentCalculatedFare: Double = 0.0
    private var routeDistanceMeters: Int = 0
    private var routeDurationSeconds: Int = 0
    private var currentSurgeMultiplier: Double = 1.0

    private var activeMarker: Marker? = null
    private var pickupMarker: Marker? = null
    private var dropoffMarker: Marker? = null
    private var currentPolyline: Polyline? = null

    private var currentRequestId: String? = null
    private var activeDriverId: String? = null
    private var isProgrammaticTextUpdate = false

    private val placesApiKey = "AIzaSyD_CZcqO8veYRbJxyD9EPNBcmGK3IoeDMQ"
    private val locationPermissionRequestCode = 1001

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_location_selection)

        database = FirebaseDatabase.getInstance().reference

        if (!Places.isInitialized()) {
            Places.initialize(applicationContext, placesApiKey)
        }
        placesClient = Places.createClient(this)
        fusedLocationClient = LocationServices.getFusedLocationProviderClient(this)

        etSearchLocation = findViewById(R.id.etSearchLocation)
        rvSuggestions = findViewById(R.id.rvSearchSuggestions)
        rvSuggestions.layoutManager = LinearLayoutManager(this)

        txtPickupDisplay = findViewById(R.id.txtPickupDisplay)
        txtDropoffDisplay = findViewById(R.id.txtDropoffDisplay)
        btnConfirmStage = findViewById(R.id.btnConfirmStage)

        // Bind Overlay XML Views
        cardDriverInfo = findViewById(R.id.cardDriverInfo)
        txtPassengerTripStatus = findViewById(R.id.txtPassengerTripStatus)
        txtDriverName = findViewById(R.id.txtDriverName)
        txtVehicleDetails = findViewById(R.id.txtVehicleDetails)
        btnOpenChat = findViewById(R.id.btnOpenChat)

        findViewById<View>(R.id.btnReturn)?.setOnClickListener { finish() }

        btnOpenChat.setOnClickListener {
            activeDriverId?.let { driverId ->
                Toast.makeText(this, "Opening chat with driver...", Toast.LENGTH_SHORT).show()
            }
        }

        setupSearchAutocomplete()

        val mapFragment = supportFragmentManager
            .findFragmentById(R.id.mapFragment) as SupportMapFragment
        mapFragment.getMapAsync(this)

        setupConfirmButton()
    }

    override fun onMapReady(googleMap: GoogleMap) {
        mMap = googleMap

        mMap.uiSettings.isZoomControlsEnabled = true
        mMap.uiSettings.isZoomGesturesEnabled = true
        mMap.uiSettings.isScrollGesturesEnabled = true
        mMap.uiSettings.isRotateGesturesEnabled = true

        mMap.setOnMarkerDragListener(object : GoogleMap.OnMarkerDragListener {
            override fun onMarkerDragStart(marker: Marker) {}
            override fun onMarkerDrag(marker: Marker) {}
            override fun onMarkerDragEnd(marker: Marker) {
                reverseGeocodeLocation(marker.position)
            }
        })

        mMap.setOnMapClickListener { latLng ->
            if (currentStage == SelectionStage.PICKUP || currentStage == SelectionStage.DROPOFF) {
                activeMarker?.position = latLng
                reverseGeocodeLocation(latLng)
            }
        }

        val bookingId = intent.getStringExtra("BOOKING_ID") ?: intent.getStringExtra("TRIP_ID")
        val isActiveBooking = intent.getBooleanExtra("IS_ACTIVE_BOOKING", false)

        if (isActiveBooking || !bookingId.isNullOrEmpty()) {
            bookingId?.let { restoreAndTrackActiveBooking(it) }
        } else {
            checkLocationPermissionAndFetch()
        }
    }

    private fun checkLocationPermissionAndFetch() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED) {
            fetchUserCurrentLocation()
        } else {
            ActivityCompat.requestPermissions(
                this,
                arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION),
                locationPermissionRequestCode
            )
        }
    }

    private fun fetchUserCurrentLocation() {
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) return

        fusedLocationClient.lastLocation.addOnSuccessListener { location ->
            if (location != null) {
                val isInsidePH = location.latitude in 4.0..21.0 && location.longitude in 116.0..127.0

                if (isInsidePH) {
                    val currentLatLng = LatLng(location.latitude, location.longitude)
                    setStagePickup(currentLatLng, "Fetching address...")
                    mMap.animateCamera(CameraUpdateFactory.newLatLngZoom(currentLatLng, 16f))
                    reverseGeocodeLocation(currentLatLng)
                } else {
                    fallbackToDefaultLocation()
                }
            } else {
                fallbackToDefaultLocation()
            }
        }.addOnFailureListener {
            fallbackToDefaultLocation()
        }
    }

    private fun fallbackToDefaultLocation() {
        val defaultLoc = LatLng(15.4855, 120.5920)
        setStagePickup(defaultLoc, "Macabulos, Tarlac City, Philippines")
        mMap.moveCamera(CameraUpdateFactory.newLatLngZoom(defaultLoc, 16f))
        reverseGeocodeLocation(defaultLoc)
    }

    private fun setupSearchAutocomplete() {
        etSearchLocation.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                if (isProgrammaticTextUpdate) return

                if (!s.isNullOrEmpty() && s.length > 2) {
                    fetchPlaceSuggestions(s.toString())
                } else {
                    rvSuggestions.visibility = View.GONE
                }
            }
            override fun afterTextChanged(s: Editable?) {}
        })
    }

    private fun fetchPlaceSuggestions(query: String) {
        val request = FindAutocompletePredictionsRequest.builder()
            .setQuery(query)
            .setCountries("PH")
            .build()

        placesClient.findAutocompletePredictions(request)
            .addOnSuccessListener { response ->
                val predictions = response.autocompletePredictions
                if (predictions.isNotEmpty()) {
                    val items = predictions.map {
                        LocationItem(
                            name = it.getPrimaryText(null).toString(),
                            address = it.getSecondaryText(null).toString(),
                            placeId = it.placeId
                        )
                    }
                    rvSuggestions.adapter = LocationSuggestionsAdapter(items) { selectedItem ->
                        geocodeAddressName("${selectedItem.name}, ${selectedItem.address}")
                        rvSuggestions.visibility = View.GONE
                    }
                    rvSuggestions.visibility = View.VISIBLE
                } else {
                    rvSuggestions.visibility = View.GONE
                }
            }
            .addOnFailureListener {
                rvSuggestions.visibility = View.GONE
            }
    }

    private fun geocodeAddressName(addressName: String) {
        Thread {
            try {
                val geocoder = Geocoder(this, Locale.getDefault())
                @Suppress("DEPRECATION")
                val addresses: List<Address>? = geocoder.getFromLocationName(addressName, 1)
                if (!addresses.isNullOrEmpty()) {
                    val location = addresses[0]
                    val latLng = LatLng(location.latitude, location.longitude)
                    runOnUiThread {
                        mMap.animateCamera(CameraUpdateFactory.newLatLngZoom(latLng, 16f))
                        activeMarker?.position = latLng
                        reverseGeocodeLocation(latLng)
                    }
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }.start()
    }

    private fun setStagePickup(latLng: LatLng, address: String) {
        currentStage = SelectionStage.PICKUP
        pickupLatLng = latLng
        pickupAddress = address
        txtPickupDisplay.text = address

        activeMarker?.remove()
        activeMarker = mMap.addMarker(
            MarkerOptions()
                .position(latLng)
                .title("Pickup Location")
                .snippet("Drag pin to adjust")
                .draggable(true)
                .icon(BitmapDescriptorFactory.defaultMarker(BitmapDescriptorFactory.HUE_GREEN))
        )
        btnConfirmStage.text = "Confirm Pickup"
    }

    private fun reverseGeocodeLocation(latLng: LatLng) {
        Thread {
            try {
                val geocoder = Geocoder(this, Locale.getDefault())
                @Suppress("DEPRECATION")
                val addresses: List<Address>? = geocoder.getFromLocation(latLng.latitude, latLng.longitude, 1)
                if (!addresses.isNullOrEmpty()) {
                    val addressName = addresses[0].getAddressLine(0)
                    runOnUiThread {
                        isProgrammaticTextUpdate = true
                        etSearchLocation.setText(addressName)
                        isProgrammaticTextUpdate = false

                        if (currentStage == SelectionStage.PICKUP) {
                            pickupAddress = addressName
                            pickupLatLng = latLng
                            txtPickupDisplay.text = addressName
                        } else if (currentStage == SelectionStage.DROPOFF) {
                            dropoffAddress = addressName
                            dropoffLatLng = latLng
                            txtDropoffDisplay.text = addressName
                        }
                    }
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }.start()
    }

    private fun setupConfirmButton() {
        btnConfirmStage.setOnClickListener {
            when (currentStage) {
                SelectionStage.PICKUP -> {
                    currentStage = SelectionStage.DROPOFF
                    pickupMarker = activeMarker
                    pickupMarker?.isDraggable = false

                    val defaultDropoff = LatLng(pickupLatLng!!.latitude + 0.003, pickupLatLng!!.longitude + 0.003)
                    dropoffLatLng = defaultDropoff

                    activeMarker = mMap.addMarker(
                        MarkerOptions()
                            .position(defaultDropoff)
                            .title("Drop-off Location")
                            .snippet("Drag pin to adjust destination")
                            .draggable(true)
                            .icon(BitmapDescriptorFactory.defaultMarker(BitmapDescriptorFactory.HUE_RED))
                    )

                    btnConfirmStage.text = "Confirm Destination"
                    txtDropoffDisplay.text = "Drag pin to set destination..."
                    txtDropoffDisplay.setTextColor(Color.BLACK)
                    reverseGeocodeLocation(defaultDropoff)
                }

                SelectionStage.DROPOFF -> {
                    calculateAndDrawRoute()
                }

                SelectionStage.ROUTE_READY -> {
                    showRideOptionsDialog(currentCalculatedFare)
                }

                SelectionStage.BOOKING_SUBMITTED -> {
                    Toast.makeText(this, "Searching for drivers nearby...", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    private fun calculateAndDrawRoute() {
        val origin = pickupLatLng ?: return
        val destination = dropoffLatLng ?: return

        currentStage = SelectionStage.ROUTE_READY
        activeMarker?.isDraggable = false

        val bounds = LatLngBounds.Builder()
            .include(origin)
            .include(destination)
            .build()
        mMap.animateCamera(CameraUpdateFactory.newLatLngBounds(bounds, 180))

        val url = DirectionsHelper.getDirectionsUrl(origin, destination, placesApiKey)

        lifecycleScope.launch(Dispatchers.IO) {
            val jsonData = DirectionsHelper.downloadUrl(url)
            val routePoints = DirectionsHelper.parseDirections(jsonData)

            val (distMeters, durSeconds) = parseDistanceAndDuration(jsonData)
            routeDistanceMeters = distMeters
            routeDurationSeconds = durSeconds

            currentSurgeMultiplier = calculateSurgeMultiplier()
            currentCalculatedFare = calculateExactFare(routeDistanceMeters, routeDurationSeconds, currentSurgeMultiplier)

            withContext(Dispatchers.Main) {
                currentPolyline?.remove()

                if (routePoints.isNotEmpty()) {
                    currentPolyline = mMap.addPolyline(
                        PolylineOptions()
                            .addAll(routePoints)
                            .width(14f)
                            .color(Color.parseColor("#A855F7"))
                            .startCap(RoundCap())
                            .endCap(RoundCap())
                            .geodesic(true)
                    )
                } else {
                    currentPolyline = mMap.addPolyline(
                        PolylineOptions()
                            .add(origin, destination)
                            .width(10f)
                            .color(Color.RED)
                            .geodesic(true)
                    )
                }

                val surgeText = if (currentSurgeMultiplier > 1.0) " (${currentSurgeMultiplier}x Surge)" else ""
                btnConfirmStage.text = "Select Ride - ₱${currentCalculatedFare.toInt()}$surgeText"
            }
        }
    }

    private fun parseDistanceAndDuration(jsonData: String): Pair<Int, Int> {
        return try {
            val jsonObject = JSONObject(jsonData)
            val routes = jsonObject.getJSONArray("routes")
            if (routes.length() > 0) {
                val leg = routes.getJSONObject(0).getJSONArray("legs").getJSONObject(0)
                val dist = leg.getJSONObject("distance").getInt("value")
                val dur = leg.getJSONObject("duration").getInt("value")
                Pair(dist, dur)
            } else {
                Pair(3000, 600)
            }
        } catch (e: Exception) {
            Pair(3000, 600)
        }
    }

    private fun calculateExactFare(distanceMeters: Int, durationSeconds: Int, surgeMultiplier: Double): Double {
        val baseFare = 45.0
        val perKmRate = 15.0
        val perMinuteRate = 2.0

        val distanceKm = distanceMeters / 1000.0
        val durationMinutes = durationSeconds / 60.0

        val distanceCharge = distanceKm * perKmRate
        val timeCharge = durationMinutes * perMinuteRate

        val totalUnsurged = baseFare + distanceCharge + timeCharge
        return totalUnsurged * surgeMultiplier
    }

    private fun calculateSurgeMultiplier(): Double {
        val currentHour = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)
        return when (currentHour) {
            7, 8 -> 1.25
            17, 18, 19 -> 1.30
            else -> 1.0
        }
    }

    private fun showRideOptionsDialog(baseCalculatedFare: Double) {
        val dialog = BottomSheetDialog(this)
        val view = layoutInflater.inflate(R.layout.dialog_ride_options, null)
        dialog.setContentView(view)

        val fareRegular4 = baseCalculatedFare
        val fareShared4 = baseCalculatedFare * 0.70
        val fareRegular6 = baseCalculatedFare * 1.40

        val distanceKm = String.format("%.1f", routeDistanceMeters / 1000.0)
        val durationMin = (routeDurationSeconds / 60)

        view.findViewById<TextView>(R.id.txtTripSummaryDetails)?.text = "$distanceKm km • ~$durationMin mins"

        view.findViewById<TextView>(R.id.txtFareRegular4)?.text = "₱${fareRegular4.toInt()}"
        view.findViewById<TextView>(R.id.txtFareShared4)?.text = "₱${fareShared4.toInt()}"
        view.findViewById<TextView>(R.id.txtFareRegular6)?.text = "₱${fareRegular6.toInt()}"

        view.findViewById<View>(R.id.btnOptionRegular4)?.setOnClickListener {
            dialog.dismiss()
            submitRideRequestToFirebase("REGULAR_4", fareRegular4, false, 1)
        }

        view.findViewById<View>(R.id.btnOptionShared4)?.setOnClickListener {
            dialog.dismiss()
            submitRideRequestToFirebase("SHARED_4", fareShared4, true, 2)
        }

        view.findViewById<View>(R.id.btnOptionRegular6)?.setOnClickListener {
            dialog.dismiss()
            submitRideRequestToFirebase("REGULAR_6", fareRegular6, false, 1)
        }

        dialog.show()
    }

    private fun submitRideRequestToFirebase(
        serviceType: String,
        finalFare: Double,
        isShared: Boolean,
        maxPax: Int
    ) {
        val pLat = pickupLatLng?.latitude ?: return
        val pLng = pickupLatLng?.longitude ?: return
        val dLat = dropoffLatLng?.latitude ?: return
        val dLng = dropoffLatLng?.longitude ?: return

        val currentUser = FirebaseAuth.getInstance().currentUser ?: return
        val currentUserId = currentUser.uid

        database.child("users").child(currentUserId).addListenerForSingleValueEvent(object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                val firstName = snapshot.child("firstName").getValue(String::class.java) ?: ""
                val lastName = snapshot.child("lastName").getValue(String::class.java) ?: ""
                val passengerName = "$firstName $lastName".trim().ifEmpty { "Passenger" }
                val passengerPhone = snapshot.child("phoneNumber").getValue(String::class.java) ?: ""

                val ref = database.child("bookings").push()
                val key = ref.key ?: return
                currentRequestId = key

                val bookingData = hashMapOf(
                    "bookingId" to key,
                    "passengerId" to currentUserId,
                    "passengerName" to passengerName,
                    "passengerPhone" to passengerPhone,
                    "pickupAddress" to pickupAddress,
                    "pickupLat" to pLat,
                    "pickupLng" to pLng,
                    "dropoffAddress" to dropoffAddress,
                    "dropoffLat" to dLat,
                    "dropoffLng" to dLng,
                    "distanceMeters" to routeDistanceMeters,
                    "durationSeconds" to routeDurationSeconds,
                    "surgeMultiplier" to currentSurgeMultiplier,
                    "fare" to finalFare,
                    "serviceType" to serviceType,
                    "isShared" to isShared,
                    "maxPassengersAllowed" to maxPax,
                    "status" to "REQUESTED",
                    "createdAt" to System.currentTimeMillis()
                )

                btnConfirmStage.isEnabled = false
                btnConfirmStage.text = if (isShared) "Matching co-passenger..." else "Searching for driver..."

                ref.setValue(bookingData).addOnSuccessListener {
                    currentStage = SelectionStage.BOOKING_SUBMITTED
                    Toast.makeText(this@LocationSelectionActivity, "Booking submitted!", Toast.LENGTH_SHORT).show()
                    listenForRideStatusUpdates(key)
                }
            }

            override fun onCancelled(error: DatabaseError) {}
        })
    }

    private fun restoreAndTrackActiveBooking(bookingId: String) {
        currentRequestId = bookingId
        currentStage = SelectionStage.BOOKING_SUBMITTED

        database.child("bookings").child(bookingId).addListenerForSingleValueEvent(object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                if (!snapshot.exists()) return

                val pLat = snapshot.child("pickupLat").getValue(Double::class.java) ?: return
                val pLng = snapshot.child("pickupLng").getValue(Double::class.java) ?: return
                val dLat = snapshot.child("dropoffLat").getValue(Double::class.java) ?: return
                val dLng = snapshot.child("dropoffLng").getValue(Double::class.java) ?: return

                pickupAddress = snapshot.child("pickupAddress").getValue(String::class.java) ?: "Pickup"
                dropoffAddress = snapshot.child("dropoffAddress").getValue(String::class.java) ?: "Destination"

                pickupLatLng = LatLng(pLat, pLng)
                dropoffLatLng = LatLng(dLat, dLng)

                txtPickupDisplay.text = pickupAddress
                txtDropoffDisplay.text = dropoffAddress

                pickupMarker = mMap.addMarker(
                    MarkerOptions().position(pickupLatLng!!).title("Pickup")
                        .icon(BitmapDescriptorFactory.defaultMarker(BitmapDescriptorFactory.HUE_GREEN))
                )

                dropoffMarker = mMap.addMarker(
                    MarkerOptions().position(dropoffLatLng!!).title("Destination")
                        .icon(BitmapDescriptorFactory.defaultMarker(BitmapDescriptorFactory.HUE_RED))
                )

                calculateAndDrawRoute()
                listenForRideStatusUpdates(bookingId)
            }

            override fun onCancelled(error: DatabaseError) {}
        })
    }

    private fun listenForRideStatusUpdates(bookingId: String) {
        database.child("bookings").child(bookingId)
            .addValueEventListener(object : ValueEventListener {
                override fun onDataChange(snapshot: DataSnapshot) {
                    val status = snapshot.child("status").getValue(String::class.java) ?: return
                    val driverId = snapshot.child("driverId").getValue(String::class.java) ?: ""
                    val distMeters = snapshot.child("distanceMeters").getValue(Int::class.java) ?: routeDistanceMeters
                    val durSeconds = snapshot.child("durationSeconds").getValue(Int::class.java) ?: routeDurationSeconds

                    val distKm = String.format("%.1f km", distMeters / 1000.0)
                    val durMins = "${durSeconds / 60} mins"

                    activeDriverId = driverId

                    when (status) {
                        "REQUESTED", "SEARCHING" -> {
                            cardDriverInfo.visibility = View.GONE
                            btnConfirmStage.isEnabled = false
                            btnConfirmStage.text = "Searching for driver..."
                            btnConfirmStage.setBackgroundColor(Color.parseColor("#9E9E9E"))
                        }
                        "MATCHED", "ACCEPTED", "ARRIVED" -> {
                            cardDriverInfo.visibility = View.VISIBLE
                            btnConfirmStage.isEnabled = true
                            btnConfirmStage.text = if (status == "ARRIVED") "Driver Has Arrived!" else "Driver En Route"
                            btnConfirmStage.setBackgroundColor(Color.parseColor("#4CAF50"))

                            txtPassengerTripStatus.text = if (status == "ARRIVED") "Driver has arrived at pickup!" else "Driver is on the way • ETA $durMins ($distKm)"

                            fetchDriverAndVehicleDetails(driverId, snapshot)
                        }
                        "IN_PROGRESS" -> {
                            cardDriverInfo.visibility = View.VISIBLE
                            btnConfirmStage.isEnabled = true
                            btnConfirmStage.text = "Trip in Progress"
                            btnConfirmStage.setBackgroundColor(Color.parseColor("#2196F3"))

                            txtPassengerTripStatus.text = "Trip in progress • Remaining: $durMins ($distKm)"

                            fetchDriverAndVehicleDetails(driverId, snapshot)
                        }
                        "COMPLETED" -> {
                            cardDriverInfo.visibility = View.GONE
                            btnConfirmStage.isEnabled = true
                            btnConfirmStage.text = "Trip Completed"
                            btnConfirmStage.setBackgroundColor(Color.parseColor("#4CAF50"))
                        }
                        "CANCELLED" -> {
                            cardDriverInfo.visibility = View.GONE
                            btnConfirmStage.isEnabled = true
                            btnConfirmStage.text = "Ride Cancelled"
                            btnConfirmStage.setBackgroundColor("#E53935".toColorInt())
                        }
                    }
                }

                override fun onCancelled(error: DatabaseError) {}
            })
    }

    private fun fetchDriverAndVehicleDetails(driverId: String, bookingSnapshot: DataSnapshot? = null) {
        // First attempt reading snapshot stored directly inside booking node
        val bookingDriverName = bookingSnapshot?.child("driverName")?.getValue(String::class.java)
        val bookingVehicleModel = bookingSnapshot?.child("vehicleModel")?.getValue(String::class.java)
        val bookingPlateNumber = bookingSnapshot?.child("plateNumber")?.getValue(String::class.java)
        val bookingVehicleColor = bookingSnapshot?.child("vehicleColor")?.getValue(String::class.java)

        if (!bookingDriverName.isNullOrEmpty() && !bookingVehicleModel.isNullOrEmpty()) {
            txtDriverName.text = bookingDriverName
            txtVehicleDetails.text = buildVehicleString(bookingVehicleColor ?: "", bookingVehicleModel, bookingPlateNumber ?: "")
            return
        }

        if (driverId.isEmpty()) {
            txtDriverName.text = "Assigned Driver"
            txtVehicleDetails.text = "Vehicle Details N/A"
            return
        }

        // Fallback: fetch directly from driver's user profile
        database.child("users").child(driverId).addListenerForSingleValueEvent(object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                val firstName = snapshot.child("firstName").getValue(String::class.java) ?: ""
                val lastName = snapshot.child("lastName").getValue(String::class.java) ?: ""
                var fullName = "$firstName $lastName".trim()

                if (fullName.isEmpty()) {
                    fullName = snapshot.child("name").getValue(String::class.java) ?: "Assigned Driver"
                }

                val vehicleModel = snapshot.child("vehicleModel").getValue(String::class.java)
                    ?: snapshot.child("carModel").getValue(String::class.java) ?: "Vehicle"
                val plateNumber = snapshot.child("plateNumber").getValue(String::class.java)
                    ?: snapshot.child("licensePlate").getValue(String::class.java) ?: ""
                val vehicleColor = snapshot.child("vehicleColor").getValue(String::class.java) ?: ""

                txtDriverName.text = fullName
                txtVehicleDetails.text = buildVehicleString(vehicleColor, vehicleModel, plateNumber)
            }

            override fun onCancelled(error: DatabaseError) {
                txtDriverName.text = "Assigned Driver"
                txtVehicleDetails.text = "Vehicle Details N/A"
            }
        })
    }

    private fun buildVehicleString(color: String, model: String, plate: String): String {
        return buildString {
            if (color.isNotEmpty()) append("$color ")
            append(model)
            if (plate.isNotEmpty()) append(" • $plate")
        }.ifEmpty { "Vehicle Details N/A" }
    }
}