package com.ruta.app.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.location.Address
import android.location.Geocoder
import android.location.Location
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
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
import com.google.android.libraries.places.api.Places
import com.google.android.libraries.places.api.net.FindAutocompletePredictionsRequest
import com.google.android.libraries.places.api.net.PlacesClient
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.card.MaterialCardView
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.database.DataSnapshot
import com.google.firebase.database.DatabaseError
import com.google.firebase.database.DatabaseReference
import com.google.firebase.database.FirebaseDatabase
import com.google.firebase.database.ValueEventListener
import com.google.maps.android.PolyUtil
import com.ruta.app.R
import com.ruta.app.model.LocationItem
import com.ruta.app.util.RideshareFlowManager
import com.ruta.app.util.RideshareManager
import com.ruta.app.util.RideshareRouteOptimizer
import com.ruta.app.util.RouteDeviationWatcher
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

    // Card Overlay Driver Info Views
    private lateinit var cardDriverInfo: MaterialCardView
    private lateinit var txtPassengerTripStatus: TextView
    private lateinit var txtDriverName: TextView
    private lateinit var txtVehicleDetails: TextView
    private lateinit var btnOpenChat: Button

    private enum class SelectionStage { PICKUP, DROPOFF, ROUTE_READY, BOOKING_SUBMITTED }
    private var suppressRouteButtonUpdate = false
    private var currentStage = SelectionStage.PICKUP
    private var pickupLatLng: LatLng? = null
    private var dropoffLatLng: LatLng? = null
    private var pickupAddress: String = "Detecting location..."
    private var dropoffAddress: String = "Add Location"

    private var currentCalculatedFare: Double = 0.0
    private var routeDistanceMeters: Int = 0
    private var routeDurationSeconds: Int = 0
    private var currentSurgeMultiplier: Double = 1.0

    private var driverMarker: Marker? = null
    private var activeMarker: Marker? = null
    private var pickupMarker: Marker? = null
    private var dropoffMarker: Marker? = null
    private var currentPolyline: Polyline? = null
    private var isDeviationAlertShowing = false
    private var userDismissedDeviation = false
    private var currentRequestId: String? = null
    private var activeDriverId: String? = null
    private var isProgrammaticTextUpdate = false
    private var lastFetchedGroupId: String? = null
    private var groupRouteGroupId: String? = null
    private var locationCallback: com.google.android.gms.location.LocationCallback? = null

    private var passengerPickupMarker: Marker? = null
    private var passengerDropoffMarker: Marker? = null
    private var routePolyline: Polyline? = null
    private var passengerPolylinePoints = mutableListOf<LatLng>()

    private var deviationWatcher: RouteDeviationWatcher? = null
    private var rideshareFlow: RideshareFlowManager? = null

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

        cardDriverInfo = findViewById(R.id.cardDriverInfo)
        txtPassengerTripStatus = findViewById(R.id.txtPassengerTripStatus)
        txtDriverName = findViewById(R.id.txtDriverName)
        txtVehicleDetails = findViewById(R.id.txtVehicleDetails)
        btnOpenChat = findViewById(R.id.btnOpenChat)

        findViewById<View>(R.id.btnReturn)?.setOnClickListener { finish() }

        btnOpenChat.setOnClickListener {
            val bookingId = currentRequestId ?: return@setOnClickListener
            val driverId = activeDriverId ?: return@setOnClickListener

            val intent = Intent(this, ChatActivity::class.java).apply {
                putExtra("BOOKING_ID", bookingId)
                putExtra("RECEIVER_ID", driverId)
                putExtra("RECEIVER_NAME", txtDriverName.text.toString())
            }
            startActivity(intent)
        }

        setupSearchAutocomplete()

        val mapFragment = supportFragmentManager
            .findFragmentById(R.id.mapFragment) as SupportMapFragment
        mapFragment.getMapAsync(this)

        val isActiveBooking = intent.getBooleanExtra("IS_ACTIVE_BOOKING", false)
        val existingBookingId = intent.getStringExtra("BOOKING_ID") ?: intent.getStringExtra("TRIP_ID")

        if (isActiveBooking && !existingBookingId.isNullOrEmpty()) {
            currentRequestId = existingBookingId
            resumeActiveBookingFlow(existingBookingId)
        } else {
            setupConfirmButton()
        }
    }

    private fun setupPassengerRideTracking(bookingId: String) {
        val bookingRef = FirebaseDatabase.getInstance().reference.child("bookings").child(bookingId)

        bookingRef.addValueEventListener(object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                val status = snapshot.child("status").getValue(String::class.java) ?: return

                // 1. Get Live Driver Coordinates
                val driverLat = snapshot.child("driverLat").getValue(Double::class.java)
                val driverLng = snapshot.child("driverLng").getValue(Double::class.java)

                if (driverLat != null && driverLng != null && driverLat != 0.0 && driverLng != 0.0) {
                    val driverPos = LatLng(driverLat, driverLng)

                    // Update car position on passenger map
                    updateLiveDriverMarker(driverLat, driverLng)

                    // Dynamic polyline trimming behind vehicle
                    trimPassengerPolyline(driverPos)
                }

                // 2. Handle Pickup Marker Removal
                when (status) {
                    "ACCEPTED", "ARRIVED" -> {
                        if (passengerPickupMarker == null && pickupLatLng != null) {
                            passengerPickupMarker = mMap.addMarker(
                                MarkerOptions()
                                    .position(pickupLatLng!!)
                                    .title("Pickup Location")
                                    .icon(BitmapDescriptorFactory.defaultMarker(BitmapDescriptorFactory.HUE_GREEN))
                            )
                        }
                    }

                    "IN_PROGRESS" -> {
                        // Green Pin vanishes immediately when trip begins
                        passengerPickupMarker?.remove()
                        passengerPickupMarker = null
                        pickupMarker?.remove()
                        pickupMarker = null
                    }

                    "COMPLETED", "CANCELLED" -> {
                        passengerPickupMarker?.remove()
                        passengerDropoffMarker?.remove()
                        routePolyline?.remove()
                    }
                }
            }

            override fun onCancelled(error: DatabaseError) {}
        })
    }

    private fun trimPassengerPolyline(driverPos: LatLng) {
        if (passengerPolylinePoints.size <= 1) return

        var closestIndex = -1
        var minDistance = Float.MAX_VALUE

        for (i in passengerPolylinePoints.indices) {
            val results = FloatArray(1)
            Location.distanceBetween(
                driverPos.latitude, driverPos.longitude,
                passengerPolylinePoints[i].latitude, passengerPolylinePoints[i].longitude,
                results
            )
            if (results[0] < minDistance) {
                minDistance = results[0]
                closestIndex = i
            }
        }

        if (closestIndex > 0 && minDistance < 40f) {
            val remainingPoints = mutableListOf(driverPos)
            remainingPoints.addAll(passengerPolylinePoints.subList(closestIndex, passengerPolylinePoints.size))

            passengerPolylinePoints = remainingPoints
            routePolyline?.points = passengerPolylinePoints
        }
    }

    private fun resumeActiveBookingFlow(bookingId: String) {
        database.child("bookings").child(bookingId).get().addOnSuccessListener { snapshot ->
            val status = snapshot.child("status").getValue(String::class.java) ?: ""

            when (status) {
                "MATCHING" -> {
                    btnConfirmStage.text = "Matching co-passenger..."

                    val pLat = snapshot.child("pickupLat").getValue(Double::class.java) ?: 0.0
                    val pLng = snapshot.child("pickupLng").getValue(Double::class.java) ?: 0.0
                    val dLat = snapshot.child("dropoffLat").getValue(Double::class.java) ?: 0.0
                    val dLng = snapshot.child("dropoffLng").getValue(Double::class.java) ?: 0.0
                    val passengerId = snapshot.child("passengerId").getValue(String::class.java) ?: ""

                    rideshareFlow = RideshareFlowManager(
                        myBookingId = bookingId,
                        myPassengerId = passengerId,
                        myPickup = LatLng(pLat, pLng),
                        myDropoff = LatLng(dLat, dLng),
                        apiKey = getString(R.string.google_maps_key)
                    ) { state ->
                        runOnUiThread {
                            when (state) {
                                RideshareFlowManager.RideshareState.SEARCHING_FOR_CO_PASSENGER -> {
                                    btnConfirmStage.text = "Matching co-passenger..."
                                }
                                RideshareFlowManager.RideshareState.MATCHED -> {
                                    Toast.makeText(this, "Co-passenger found! Searching for driver...", Toast.LENGTH_SHORT).show()
                                    btnConfirmStage.text = "Searching for driver..."
                                }
                                RideshareFlowManager.RideshareState.NO_MATCH_PROCEEDING_SOLO -> {
                                    Toast.makeText(this, "No co-passenger found — booking as a solo ride.", Toast.LENGTH_LONG).show()
                                    btnConfirmStage.text = "Searching for driver..."
                                }
                            }
                        }
                    }
                    rideshareFlow?.start()
                }
                "MATCHED", "REQUESTED", "SEARCHING" -> {
                    btnConfirmStage.text = "Searching for driver..."
                }
                "ACCEPTED", "ARRIVED", "IN_PROGRESS" -> {
                    cardDriverInfo.visibility = View.VISIBLE
                    txtPassengerTripStatus.text = if (status == "IN_PROGRESS") "Trip in progress" else "Driver en route"
                }
                "COMPLETED", "CANCELLED" -> {
                    Toast.makeText(this, "This trip has been $status.", Toast.LENGTH_SHORT).show()
                    finish()
                }
            }
        }
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
                    // Populate polyline points for trimming
                    passengerPolylinePoints = routePoints.toMutableList()

                    currentPolyline = mMap.addPolyline(
                        PolylineOptions()
                            .addAll(routePoints)
                            .width(14f)
                            .color(Color.parseColor("#A855F7"))
                            .startCap(RoundCap())
                            .endCap(RoundCap())
                            .geodesic(true)
                    )
                    routePolyline = currentPolyline
                } else {
                    currentPolyline = mMap.addPolyline(
                        PolylineOptions()
                            .add(origin, destination)
                            .width(10f)
                            .color(Color.RED)
                            .geodesic(true)
                    )
                    routePolyline = currentPolyline
                }

                val surgeText = if (currentSurgeMultiplier > 1.0) " (${currentSurgeMultiplier}x Surge)" else ""
                if (!suppressRouteButtonUpdate) {
                    btnConfirmStage.text = "Select Ride - ₱${currentCalculatedFare.toInt()}$surgeText"
                }
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

        val fareRegular = baseCalculatedFare
        val fareShared = baseCalculatedFare * 0.70

        val distanceKm = String.format("%.1f", routeDistanceMeters / 1000.0)
        val durationMin = (routeDurationSeconds / 60)

        view.findViewById<TextView>(R.id.txtTripSummaryDetails)?.text = "$distanceKm km • ~$durationMin mins"
        view.findViewById<TextView>(R.id.txtFareRegular)?.text = "₱${fareRegular.toInt()}"

        view.findViewById<View>(R.id.btnOptionRegular)?.setOnClickListener {
            dialog.dismiss()
            submitRegularBooking(fareRegular)
        }

        view.findViewById<View>(R.id.btnOptionShared)?.setOnClickListener {
            dialog.dismiss()
            submitRideshareBooking(fareShared)
        }

        dialog.show()
    }

    private fun submitRegularBooking(fare: Double) {
        writeBooking(
            fare = fare,
            serviceType = "REGULAR",
            isShared = false,
            maxPax = 1,
            initialStatus = "REQUESTED"
        ) {
            btnConfirmStage.text = "Searching for driver..."
        }
    }

    private fun submitRideshareBooking(fare: Double) {
        val pickup = pickupLatLng ?: return
        val dropoff = dropoffLatLng ?: return
        val userId = FirebaseAuth.getInstance().currentUser?.uid ?: return

        writeBooking(
            fare = fare,
            serviceType = "SHARED",
            isShared = true,
            maxPax = 2,
            initialStatus = "MATCHING"
        ) { bookingId ->
            btnConfirmStage.text = "Matching co-passenger..."

            rideshareFlow = RideshareFlowManager(
                myBookingId = bookingId,
                myPassengerId = userId,
                myPickup = pickup,
                myDropoff = dropoff,
                apiKey = getString(R.string.google_maps_key),
                maxPickupMinutesApart = 10.0,
                maxDropoffMinutesApart = 10.0
            ) { state ->
                runOnUiThread {
                    when (state) {
                        RideshareFlowManager.RideshareState.SEARCHING_FOR_CO_PASSENGER -> {
                            btnConfirmStage.text = "Matching co-passenger..."
                        }
                        RideshareFlowManager.RideshareState.MATCHED -> {
                            Toast.makeText(this, "Co-passenger found! Searching for driver...", Toast.LENGTH_SHORT).show()
                            btnConfirmStage.text = "Searching for driver..."
                        }
                        RideshareFlowManager.RideshareState.NO_MATCH_PROCEEDING_SOLO -> {
                            Toast.makeText(this, "No co-passenger found — booking as a solo ride.", Toast.LENGTH_LONG).show()
                            btnConfirmStage.text = "Searching for driver..."
                        }
                    }
                }
            }
            rideshareFlow?.start()
        }
    }

    private fun writeBooking(
        fare: Double,
        serviceType: String,
        isShared: Boolean,
        maxPax: Int,
        initialStatus: String,
        onWritten: (bookingId: String) -> Unit
    ) {
        val pLat = pickupLatLng?.latitude ?: return
        val pLng = pickupLatLng?.longitude ?: return
        val dLat = dropoffLatLng?.latitude ?: return
        val dLng = dropoffLatLng?.longitude ?: return

        val currentUser = FirebaseAuth.getInstance().currentUser ?: return
        val currentUserId = currentUser.uid

        btnConfirmStage.isEnabled = false

        database.child("users").child(currentUserId)
            .addListenerForSingleValueEvent(object : ValueEventListener {
                override fun onDataChange(snapshot: DataSnapshot) {
                    val firstName = snapshot.child("firstName").getValue(String::class.java) ?: ""
                    val lastName = snapshot.child("lastName").getValue(String::class.java) ?: ""
                    val passengerName = "$firstName $lastName".trim().ifEmpty { "Passenger" }
                    val passengerPhone = snapshot.child("phoneNumber").getValue(String::class.java) ?: ""

                    val ref = database.child("bookings").push()
                    val key = ref.key ?: return
                    currentRequestId = key

                    val bookingData = hashMapOf<String, Any?>(
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
                        "fare" to fare,
                        "serviceType" to serviceType,
                        "isShared" to isShared,
                        "maxPassengersAllowed" to maxPax,
                        "tripGroupId" to null,
                        "status" to initialStatus,
                        "routeStatus" to "NORMAL",
                        "timestamp" to System.currentTimeMillis()
                    )

                    ref.setValue(bookingData).addOnSuccessListener {
                        currentStage = SelectionStage.BOOKING_SUBMITTED
                        Toast.makeText(this@LocationSelectionActivity, "Booking submitted!", Toast.LENGTH_SHORT).show()
                        listenForRideStatusUpdates(key)
                        onWritten(key)
                    }
                }

                override fun onCancelled(error: DatabaseError) {
                    btnConfirmStage.isEnabled = true
                }
            })
    }

    fun onDriverLocationUpdated(
        currentDriverLatLng: LatLng,
        routePolyline: List<LatLng>
    ) {
        if (routePolyline.size < 2) return

        var minDistanceMeters = Double.MAX_VALUE
        for (i in 0 until routePolyline.size - 1) {
            val start = routePolyline[i]
            val end = routePolyline[i + 1]
            val distanceToSegment = PolyUtil.distanceToLine(currentDriverLatLng, start, end)
            if (distanceToSegment < minDistanceMeters) {
                minDistanceMeters = distanceToSegment
            }
        }

        val isOffRoute = minDistanceMeters > 200.0

        if (isOffRoute) {
            if (!isDeviationAlertShowing && !userDismissedDeviation) {
                isDeviationAlertShowing = true
                showRouteDeviationDialog()
            }
        } else {
            userDismissedDeviation = false
            isDeviationAlertShowing = false
        }
    }

    private fun showRouteDeviationDialog() {
        AlertDialog.Builder(this)
            .setTitle("⚠️ Route Deviation Warning")
            .setMessage("Your driver has moved off the designated path. Please verify your current route.")
            .setPositiveButton("I'M SAFE") { dialog, _ ->
                userDismissedDeviation = true
                isDeviationAlertShowing = false
                dialog.dismiss()
            }
            .setNegativeButton("EMERGENCY / SOS") { dialog, _ ->
                dialog.dismiss()
                val intent = Intent(Intent.ACTION_DIAL).apply {
                    data = android.net.Uri.parse("tel:911")
                }
                startActivity(intent)
            }
            .setCancelable(false)
            .show()
    }

    private fun restoreAndTrackActiveBooking(bookingId: String) {
        currentRequestId = bookingId
        currentStage = SelectionStage.BOOKING_SUBMITTED
        suppressRouteButtonUpdate = true
        calculateAndDrawRoute()
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

                pickupMarker?.remove()
                pickupMarker = mMap.addMarker(
                    MarkerOptions().position(pickupLatLng!!).title("Pickup")
                        .icon(BitmapDescriptorFactory.defaultMarker(BitmapDescriptorFactory.HUE_GREEN))
                )

                dropoffMarker?.remove()
                dropoffMarker = mMap.addMarker(
                    MarkerOptions().position(dropoffLatLng!!).title("Destination")
                        .icon(BitmapDescriptorFactory.defaultMarker(BitmapDescriptorFactory.HUE_RED))
                )

                calculateAndDrawRoute()
                fitMapBoundsSafely(pickupLatLng, dropoffLatLng)
                listenForRideStatusUpdates(bookingId)
            }

            override fun onCancelled(error: DatabaseError) {}
        })
    }

    private fun listenForRideStatusUpdates(bookingId: String) {
        // Start passenger ride tracking logic
        setupPassengerRideTracking(bookingId)

        database.child("bookings").child(bookingId)
            .addValueEventListener(object : ValueEventListener {
                override fun onDataChange(snapshot: DataSnapshot) {
                    val status = snapshot.child("status").getValue(String::class.java) ?: ""
                    val routeStatus = snapshot.child("routeStatus").getValue(String::class.java) ?: "NORMAL"
                    val tripGroupId = snapshot.child("tripGroupId").getValue(String::class.java)
                    val isShared = snapshot.child("isShared").getValue(Boolean::class.java) ?: false
                    val driverId = snapshot.child("driverId").getValue(String::class.java) ?: ""
                    val distMeters = snapshot.child("distanceMeters").getValue(Int::class.java) ?: routeDistanceMeters
                    val durSeconds = snapshot.child("durationSeconds").getValue(Int::class.java) ?: routeDurationSeconds
                    val distKm = String.format("%.1f km", distMeters / 1000.0)
                    val durMins = "${durSeconds / 60} mins"

                    activeDriverId = driverId

                    if (status == "IN_PROGRESS") {
                        startPassengerLocationUpdates(bookingId)
                        if (deviationWatcher == null) {
                            deviationWatcher = RouteDeviationWatcher(this@LocationSelectionActivity, bookingId) { isDeviated ->
                                txtPassengerTripStatus.setTextColor(
                                    if (isDeviated) Color.RED else Color.parseColor("#1A237E")
                                )
                            }
                            deviationWatcher?.start()
                        }
                    } else {
                        stopPassengerLocationUpdates()
                        deviationWatcher?.stop()
                        deviationWatcher = null
                    }

                    when (status) {
                        "MATCHING" -> {
                            cardDriverInfo.visibility = View.GONE
                            btnConfirmStage.visibility = View.VISIBLE
                            btnConfirmStage.isEnabled = false
                            btnConfirmStage.text = "Matching co-passenger..."
                            btnConfirmStage.setBackgroundColor(Color.parseColor("#9E9E9E"))
                        }
                        "REQUESTED", "SEARCHING" -> {
                            cardDriverInfo.visibility = View.GONE
                            btnConfirmStage.visibility = View.VISIBLE
                            btnConfirmStage.isEnabled = false
                            btnConfirmStage.setBackgroundColor(Color.parseColor("#9E9E9E"))

                            if (isShared && !tripGroupId.isNullOrEmpty()) {
                                showCoPassengerNameOnce(tripGroupId, bookingId)
                            } else if (isShared) {
                                btnConfirmStage.text = "Searching for driver (Solo)..."
                            } else {
                                btnConfirmStage.text = "Searching for driver..."
                            }
                        }
                        "ACCEPTED", "ARRIVED", "IN_PROGRESS" -> {
                            btnConfirmStage.visibility = View.GONE
                            cardDriverInfo.visibility = View.VISIBLE
                            cardDriverInfo.bringToFront()

                            val driverLat = snapshot.child("driverLat").getValue(Double::class.java)
                            val driverLng = snapshot.child("driverLng").getValue(Double::class.java)
                            updateLiveDriverMarker(driverLat, driverLng)

                            if (status == "IN_PROGRESS" && driverLat != null && driverLng != null && driverLat != 0.0 && driverLng != 0.0) {
                                mMap.animateCamera(CameraUpdateFactory.newLatLngZoom(LatLng(driverLat, driverLng), 16.5f))
                            } else if (driverLat != null && driverLng != null && driverLat != 0.0 && driverLng != 0.0) {
                                fitMapBoundsSafely(LatLng(driverLat, driverLng), pickupLatLng, dropoffLatLng)
                            }

                            txtPassengerTripStatus.text = when {
                                routeStatus == "DEVIATED" -> "⚠️ Route Deviation Detected!"
                                status == "ARRIVED" -> "Driver has arrived at pickup!"
                                status == "IN_PROGRESS" -> "Trip in progress • Remaining: $durMins ($distKm)"
                                else -> "Driver is on the way • ETA $durMins ($distKm)"
                            }

                            fetchDriverAndVehicleDetails(driverId, snapshot)
                            if (isShared && !tripGroupId.isNullOrEmpty()) {
                                drawGroupRouteForPassenger(tripGroupId, driverLat, driverLng)
                            }
                        }
                        "COMPLETED", "CANCELLED" -> {
                            driverMarker?.remove()
                            driverMarker = null
                            cardDriverInfo.visibility = View.GONE
                            btnConfirmStage.visibility = View.VISIBLE
                            btnConfirmStage.isEnabled = true

                            if (status == "COMPLETED") {
                                btnConfirmStage.text = "Trip Completed"
                                btnConfirmStage.setBackgroundColor(Color.parseColor("#4CAF50"))
                            } else {
                                btnConfirmStage.text = "Ride Cancelled"
                                btnConfirmStage.setBackgroundColor("#E53935".toColorInt())
                            }
                        }
                    }
                }

                override fun onCancelled(error: DatabaseError) {}
            })
    }

    private fun showCoPassengerNameOnce(tripGroupId: String, myBookingId: String) {
        if (lastFetchedGroupId == tripGroupId) return
        lastFetchedGroupId = tripGroupId

        database.child("bookings").orderByChild("tripGroupId").equalTo(tripGroupId)
            .addListenerForSingleValueEvent(object : ValueEventListener {
                override fun onDataChange(snapshot: DataSnapshot) {
                    val coPassengerName = snapshot.children
                        .firstOrNull { it.key != myBookingId }
                        ?.child("passengerName")?.getValue(String::class.java)
                        ?.ifEmpty { null }

                    btnConfirmStage.text = if (coPassengerName != null) {
                        "Matched with $coPassengerName! Searching for driver..."
                    } else {
                        "Matched! Searching for driver..."
                    }
                }
                override fun onCancelled(error: DatabaseError) {}
            })
    }

    private fun drawGroupRouteForPassenger(tripGroupId: String, driverLat: Double?, driverLng: Double?) {
        if (driverLat == null || driverLng == null || driverLat == 0.0 || driverLng == 0.0) return
        if (groupRouteGroupId == tripGroupId) return
        groupRouteGroupId = tripGroupId

        database.child("tripGroups").child(tripGroupId).get().addOnSuccessListener { snap ->
            val stops = snap.child("stopOrder").children.mapNotNull { s ->
                RideshareManager.RideshareStop(
                    bookingId = s.child("bookingId").getValue(String::class.java) ?: return@mapNotNull null,
                    type = RideshareManager.StopType.valueOf(s.child("type").getValue(String::class.java) ?: return@mapNotNull null),
                    location = LatLng(
                        s.child("lat").getValue(Double::class.java) ?: return@mapNotNull null,
                        s.child("lng").getValue(Double::class.java) ?: return@mapNotNull null
                    )
                )
            }

            if (stops.isEmpty()) {
                groupRouteGroupId = null
                return@addOnSuccessListener
            }

            stops.forEachIndexed { i, stop ->
                val isPickup = stop.type == RideshareManager.StopType.PICKUP
                mMap.addMarker(
                    MarkerOptions()
                        .position(stop.location)
                        .title("${i + 1}. ${if (isPickup) "Pickup" else "Dropoff"}")
                        .icon(BitmapDescriptorFactory.defaultMarker(
                            if (isPickup) BitmapDescriptorFactory.HUE_GREEN else BitmapDescriptorFactory.HUE_RED
                        ))
                )
            }

            lifecycleScope.launch(Dispatchers.IO) {
                val fullRoute = RideshareRouteOptimizer.buildFinalDriverRoute(
                    LatLng(driverLat, driverLng), stops, placesApiKey
                ) ?: return@launch

                withContext(Dispatchers.Main) {
                    currentPolyline?.remove()

                    passengerPolylinePoints = fullRoute.polyline.toMutableList()

                    currentPolyline = mMap.addPolyline(
                        PolylineOptions()
                            .addAll(fullRoute.polyline)
                            .width(12f)
                            .color(Color.parseColor("#A855F7"))
                            .geodesic(true)
                    )
                    routePolyline = currentPolyline
                }
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        rideshareFlow?.stop()
        rideshareFlow = null
        stopPassengerLocationUpdates()
        deviationWatcher?.stop()
    }

    private fun startPassengerLocationUpdates(bookingId: String) {
        if (locationCallback != null) return

        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED &&
            ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            return
        }

        val locationRequest = com.google.android.gms.location.LocationRequest.Builder(
            com.google.android.gms.location.Priority.PRIORITY_HIGH_ACCURACY, 3000
        ).setMinUpdateIntervalMillis(2000).build()

        locationCallback = object : com.google.android.gms.location.LocationCallback() {
            override fun onLocationResult(result: com.google.android.gms.location.LocationResult) {
                val loc = result.lastLocation ?: return
                val updates = hashMapOf<String, Any>(
                    "passengerLat" to loc.latitude,
                    "passengerLng" to loc.longitude
                )
                database.child("bookings").child(bookingId).updateChildren(updates)
            }
        }

        fusedLocationClient.requestLocationUpdates(
            locationRequest,
            locationCallback!!,
            mainLooper
        )
    }

    private fun stopPassengerLocationUpdates() {
        locationCallback?.let {
            fusedLocationClient.removeLocationUpdates(it)
            locationCallback = null
        }
    }

    private fun fitMapBoundsSafely(vararg points: LatLng?) {
        val builder = LatLngBounds.Builder()
        var validPointsCount = 0

        for (pt in points) {
            if (pt != null && pt.latitude != 0.0 && pt.longitude != 0.0) {
                builder.include(pt)
                validPointsCount++
            }
        }

        if (validPointsCount > 0) {
            try {
                val bounds = builder.build()
                mMap.animateCamera(CameraUpdateFactory.newLatLngBounds(bounds, 150))
            } catch (e: Exception) {
                points.firstOrNull { it != null && it.latitude != 0.0 }?.let {
                    mMap.animateCamera(CameraUpdateFactory.newLatLngZoom(it, 16f))
                }
            }
        }
    }

    private fun updateLiveDriverMarker(driverLat: Double?, driverLng: Double?) {
        if (driverLat == null || driverLng == null || driverLat == 0.0 || driverLng == 0.0) return

        val driverLatLng = LatLng(driverLat, driverLng)

        if (driverMarker == null) {
            val carIcon = createPaddedCarMarkerIcon(130, 130)

            driverMarker = mMap.addMarker(
                MarkerOptions()
                    .position(driverLatLng)
                    .title("Your Driver")
                    .icon(carIcon)
                    .anchor(0.5f, 0.5f)
                    .flat(true)
            )
        } else {
            driverMarker?.position = driverLatLng
        }
    }

    private fun createPaddedCarMarkerIcon(widthPx: Int, heightPx: Int): com.google.android.gms.maps.model.BitmapDescriptor {
        val unscaledBitmap = android.graphics.BitmapFactory.decodeResource(resources, R.drawable.carpin)

        val bitmapWithGlow = android.graphics.Bitmap.createBitmap(widthPx, heightPx, android.graphics.Bitmap.Config.ARGB_8888)
        val canvas = android.graphics.Canvas(bitmapWithGlow)

        val shadowPaint = android.graphics.Paint().apply {
            color = Color.parseColor("#40000000")
            isAntiAlias = true
        }

        canvas.drawOval(
            widthPx * 0.15f, heightPx * 0.65f,
            widthPx * 0.85f, heightPx * 0.90f,
            shadowPaint
        )

        val scaledCar = android.graphics.Bitmap.createScaledBitmap(unscaledBitmap, (widthPx * 0.9).toInt(), (heightPx * 0.9).toInt(), true)
        canvas.drawBitmap(scaledCar, (widthPx * 0.05).toFloat(), 0f, null)

        return BitmapDescriptorFactory.fromBitmap(bitmapWithGlow)
    }

    private fun fetchDriverAndVehicleDetails(driverId: String, bookingSnapshot: DataSnapshot? = null) {
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