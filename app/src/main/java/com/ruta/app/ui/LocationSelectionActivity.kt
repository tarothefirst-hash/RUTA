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
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
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
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.database.DataSnapshot
import com.google.firebase.database.DatabaseError
import com.google.firebase.database.DatabaseReference
import com.google.firebase.database.FirebaseDatabase
import com.google.firebase.database.ValueEventListener
import com.ruta.app.R
import com.ruta.app.model.LocationItem
import com.ruta.app.util.FareCalculator
import com.ruta.app.util.MapsApi
import com.ruta.app.util.RideshareFareCalculator
import com.ruta.app.util.RideshareFlowManager
import com.ruta.app.util.RideshareManager
import com.ruta.app.util.RideshareNotifier
import com.ruta.app.util.RideshareRouteOptimizer
import com.ruta.app.util.RouteDeviationWatcher
import com.ruta.app.util.VehicleType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.util.Calendar
import java.util.Locale

import android.util.Log

class LocationSelectionActivity : AppCompatActivity(), OnMapReadyCallback {

    private lateinit var mMap: GoogleMap
    private lateinit var fusedLocationClient: FusedLocationProviderClient
    private lateinit var placesClient: PlacesClient
    private lateinit var database: DatabaseReference

    private lateinit var imgCenterPin: ImageView
    private lateinit var layoutTopHeader: View
    private lateinit var cardFloatingBooking: View
    private lateinit var etPickupLocation: EditText
    private lateinit var etDropoffLocation: EditText
    private lateinit var rvSuggestions: RecyclerView
    private lateinit var txtStageHeaderTitle: TextView
    private lateinit var txtActivePinAddress: TextView
    private lateinit var btnConfirmStage: MaterialButton
    private lateinit var btnCancelSearch: MaterialButton
    private lateinit var btnCancelTrip: MaterialButton

    private lateinit var layoutRideOptions: LinearLayout
    private lateinit var cardOptionSolo: MaterialCardView
    private lateinit var cardOptionShared: MaterialCardView
    private lateinit var txtFareSolo: TextView
    private lateinit var txtFareShared: TextView

    private lateinit var cardDriverInfo: MaterialCardView
    private lateinit var txtPassengerTripStatus: TextView
    private lateinit var txtDriverName: TextView
    private lateinit var txtVehicleDetails: TextView
    private lateinit var btnOpenChat: MaterialButton

    private enum class SelectionStage { PICKUP, DROPOFF, ROUTE_READY, BOOKING_SUBMITTED }
    private var currentStage = SelectionStage.PICKUP
    private var selectedRideType: String = "SOLO"

    private var pickupLatLng: LatLng? = null
    private var dropoffLatLng: LatLng? = null
    private var pickupAddress: String = "Detecting location..."
    private var dropoffAddress: String = "Add Location"
    private var soloCalculatedFare: Double = 0.0
    private var activeBookedFare: Double = 0.0

    private var routeDistanceMeters: Int = 0
    private var routeDurationSeconds: Int = 0
    private var currentSurgeMultiplier: Double = 1.0
    private var currentUserHasDiscount: Boolean = false

    private var driverMarker: Marker? = null
    private var pickupMarker: Marker? = null
    private var dropoffMarker: Marker? = null
    private var currentPolyline: Polyline? = null
    private var driverRoutePolyline: Polyline? = null
    private var currentRequestId: String? = null
    private var activeDriverId: String? = null
    private var isProgrammaticTextUpdate = false

    private var passengerPolylinePoints = mutableListOf<LatLng>()
    private var routePolyline: Polyline? = null
    private var deviationWatcher: RouteDeviationWatcher? = null
    private var rideshareFlow: RideshareFlowManager? = null
    private var rideshareUi: RideshareMatchUi? = null
    private var rideshareOverlay: RideshareMapOverlay? = null
    private var inForeground = false
    private var pendingSoloFare = 0.0
    private var pendingSoloBefore = 0.0
    private var groupRouteGroupId: String? = null
    private var lastFetchedGroupId: String? = null
    private var lastPartnerName = "your co-passenger"

    private var rideStatusRef: DatabaseReference? = null
    private var rideStatusListener: ValueEventListener? = null

    private val placesApiKey: String by lazy { MapsApi.key(this) }
    private val locationPermissionRequestCode = 1001
    private var currentNoShowFee = 0.0
    private var syncOffsetMs = 0L
    private var lastSyncStatus = ""

    // Solo ride: how long we search before asking whether to keep waiting.
    private val soloWaitMs = 5 * 60 * 1000L
    private val soloHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private var soloTicker: Runnable? = null
    private var soloDeadline = 0L
    private var soloDialog: AlertDialog? = null

    private fun startSoloTimer(createdAt: Long) {
        if (soloTicker != null || soloDialog?.isShowing == true) return
        if (soloDeadline == 0L) {
            val start = if (createdAt > 0L) createdAt else System.currentTimeMillis()
            soloDeadline = start + soloWaitMs
        }
        val ticker = object : Runnable {
            override fun run() {
                val left = soloDeadline - System.currentTimeMillis()
                if (left <= 0L) {
                    soloTicker = null
                    showSoloTimeoutDialog()
                    return
                }
                val secs = (left / 1000).toInt()
                btnConfirmStage.text = String.format(Locale.US, "Searching for driver... %d:%02d", secs / 60, secs % 60)
                soloHandler.postDelayed(this, 1000)
            }
        }
        soloTicker = ticker
        ticker.run()
    }

    private fun stopSoloTimer() {
        soloTicker?.let { soloHandler.removeCallbacks(it) }
        soloTicker = null
        soloDeadline = 0L
        soloDialog?.dismiss()
        soloDialog = null
    }

    private fun showSoloTimeoutDialog() {
        if (isFinishing || isDestroyed || soloDialog?.isShowing == true) return
        btnConfirmStage.text = "No driver yet"
        soloDialog = AlertDialog.Builder(this)
            .setTitle("No driver found yet")
            .setMessage("Nobody has accepted your ride in 5 minutes. Wait a bit longer, or cancel and try again later?")
            .setCancelable(false)
            .setPositiveButton("Wait longer") { _, _ ->
                soloDialog = null
                soloDeadline = System.currentTimeMillis() + soloWaitMs
                startSoloTimer(0L)
            }
            .setNegativeButton("Cancel booking") { _, _ ->
                soloDialog = null
                cancelActiveRide()
            }
            .show()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_location_selection)

        database = FirebaseDatabase.getInstance().reference
        FirebaseDatabase.getInstance().getReference(".info/serverTimeOffset")
            .addValueEventListener(object : ValueEventListener {
                override fun onDataChange(s: DataSnapshot) { syncOffsetMs = s.getValue(Long::class.java) ?: 0L }
                override fun onCancelled(e: DatabaseError) {}
            })

        if (!Places.isInitialized()) {
            Places.initialize(applicationContext, placesApiKey)
        }
        loadNoShowDebt()
        placesClient = Places.createClient(this)
        fusedLocationClient = LocationServices.getFusedLocationProviderClient(this)

        initViews()
        setupListeners()

        val mapFragment = supportFragmentManager.findFragmentById(R.id.mapFragment) as SupportMapFragment
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

    private fun initViews() {
        imgCenterPin = findViewById(R.id.imgCenterPin)
        layoutTopHeader = findViewById(R.id.layoutTopHeader)
        cardFloatingBooking = findViewById(R.id.cardFloatingBooking)

        etPickupLocation = findViewById(R.id.etPickupLocation)
        etDropoffLocation = findViewById(R.id.etDropoffLocation)
        rvSuggestions = findViewById(R.id.rvSearchSuggestions)
        rvSuggestions.layoutManager = LinearLayoutManager(this)

        txtStageHeaderTitle = findViewById(R.id.txtStageHeaderTitle)
        txtActivePinAddress = findViewById(R.id.txtActivePinAddress)
        btnConfirmStage = findViewById(R.id.btnConfirmStage)
        btnCancelSearch = findViewById(R.id.btnCancelSearch)
        btnCancelTrip = findViewById(R.id.btnCancelTrip)

        layoutRideOptions = findViewById(R.id.layoutRideOptions)
        cardOptionSolo = findViewById(R.id.cardOptionSolo)
        cardOptionShared = findViewById(R.id.cardOptionShared)
        txtFareSolo = findViewById(R.id.txtFareSolo)
        txtFareShared = findViewById(R.id.txtFareShared)

        cardDriverInfo = findViewById(R.id.cardDriverInfo)
        txtPassengerTripStatus = findViewById(R.id.txtPassengerTripStatus)
        txtDriverName = findViewById(R.id.txtDriverName)
        txtVehicleDetails = findViewById(R.id.txtVehicleDetails)
        btnOpenChat = findViewById(R.id.btnOpenChat)

        updateCenterPinColor(isPickup = true)
    }

    private fun setupListeners() {
        findViewById<View>(R.id.btnReturn)?.setOnClickListener { finish() }

        btnCancelSearch.setOnClickListener { cancelActiveRide() }
        btnCancelTrip.setOnClickListener { cancelActiveRide() }

        findViewById<View>(R.id.btnRecenterLocation)?.setOnClickListener {
            fetchUserCurrentLocation()
        }

        etPickupLocation.setOnFocusChangeListener { _, hasFocus ->
            if (hasFocus) {
                currentStage = SelectionStage.PICKUP
                updateCenterPinColor(isPickup = true)
                txtStageHeaderTitle.text = "Select Pick-up Point"
                txtStageHeaderTitle.visibility = View.VISIBLE
                txtActivePinAddress.visibility = View.VISIBLE
                etPickupLocation.setText("")
            }
        }

        etDropoffLocation.setOnFocusChangeListener { _, hasFocus ->
            if (hasFocus) {
                currentStage = SelectionStage.DROPOFF
                updateCenterPinColor(isPickup = false)
                txtStageHeaderTitle.text = "Select Destination"
                txtStageHeaderTitle.visibility = View.VISIBLE
                txtActivePinAddress.visibility = View.VISIBLE
                etDropoffLocation.setText("")
            }
        }

        setupSearchAutocomplete(etPickupLocation)
        setupSearchAutocomplete(etDropoffLocation)

        cardOptionSolo.setOnClickListener {
            selectedRideType = "SOLO"
            highlightRideOptionCard(isSoloSelected = true)
        }

        cardOptionShared.setOnClickListener {
            selectedRideType = "SHARED"
            highlightRideOptionCard(isSoloSelected = false)
        }

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
    }

    private fun updateCenterPinColor(isPickup: Boolean) {
        if (isPickup) {
            imgCenterPin.setColorFilter(Color.parseColor("#6D5EFC"))
        } else {
            imgCenterPin.setColorFilter(Color.parseColor("#3B2E92"))
        }
    }

    private fun highlightRideOptionCard(isSoloSelected: Boolean) {
        txtFareShared.text = "Price shown when matched"

        if (isSoloSelected) {
            cardOptionSolo.setCardBackgroundColor(ContextCompat.getColor(this, R.color.ruta_primary_light))
            cardOptionSolo.strokeColor = ContextCompat.getColor(this, R.color.ruta_primary)
            cardOptionSolo.strokeWidth = 4

            cardOptionShared.setCardBackgroundColor(ContextCompat.getColor(this, R.color.ruta_card_bg))
            cardOptionShared.strokeColor = Color.parseColor("#CBD5E1")
            cardOptionShared.strokeWidth = 2
            btnConfirmStage.text = String.format(Locale.getDefault(), "CONFIRM SOLO RIDE - ₱%.2f", soloCalculatedFare)
        } else {
            cardOptionShared.setCardBackgroundColor(ContextCompat.getColor(this, R.color.ruta_primary_light))
            cardOptionShared.strokeColor = ContextCompat.getColor(this, R.color.ruta_primary)
            cardOptionShared.strokeWidth = 4

            cardOptionSolo.setCardBackgroundColor(ContextCompat.getColor(this, R.color.ruta_card_bg))
            cardOptionSolo.strokeColor = Color.parseColor("#CBD5E1")
            cardOptionSolo.strokeWidth = 2
            btnConfirmStage.text = "CONFIRM SHARED RIDE"
        }
    }

    override fun onMapReady(googleMap: GoogleMap) {
        mMap = googleMap
        mMap.uiSettings.isZoomControlsEnabled = true

        mMap.setOnCameraMoveStartedListener { reason ->
            if (reason == GoogleMap.OnCameraMoveStartedListener.REASON_GESTURE) {
                layoutTopHeader.animate().alpha(0f).setDuration(200).start()
                cardFloatingBooking.animate().alpha(0f).setDuration(200).start()
            }
        }

        mMap.setOnCameraIdleListener {
            layoutTopHeader.animate().alpha(1f).setDuration(200).start()
            cardFloatingBooking.animate().alpha(1f).setDuration(200).start()

            if (currentStage == SelectionStage.PICKUP || currentStage == SelectionStage.DROPOFF) {
                val centerLatLng = mMap.cameraPosition.target
                reverseGeocodeLocation(centerLatLng)
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
                        txtActivePinAddress.text = addressName

                        if (currentStage == SelectionStage.PICKUP) {
                            pickupAddress = addressName
                            pickupLatLng = latLng
                            etPickupLocation.setText(addressName)
                        } else if (currentStage == SelectionStage.DROPOFF) {
                            dropoffAddress = addressName
                            dropoffLatLng = latLng
                            etDropoffLocation.setText(addressName)
                        }
                        isProgrammaticTextUpdate = false
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
                    pickupLatLng = mMap.cameraPosition.target
                    pickupMarker?.remove()
                    pickupMarker = mMap.addMarker(
                        MarkerOptions().position(pickupLatLng!!)
                            .title("Pick-up")
                            .icon(BitmapDescriptorFactory.defaultMarker(BitmapDescriptorFactory.HUE_GREEN))
                    )

                    currentStage = SelectionStage.DROPOFF
                    updateCenterPinColor(isPickup = false)
                    txtStageHeaderTitle.text = "Select Destination"
                    txtStageHeaderTitle.visibility = View.VISIBLE
                    txtActivePinAddress.visibility = View.VISIBLE
                    btnConfirmStage.text = "Confirm Destination"

                    val defaultDropoff = LatLng(pickupLatLng!!.latitude + 0.003, pickupLatLng!!.longitude + 0.003)
                    mMap.animateCamera(CameraUpdateFactory.newLatLngZoom(defaultDropoff, 16f))
                }

                SelectionStage.DROPOFF -> {
                    dropoffLatLng = mMap.cameraPosition.target
                    dropoffMarker?.remove()
                    dropoffMarker = mMap.addMarker(
                        MarkerOptions().position(dropoffLatLng!!)
                            .title("Destination")
                            .icon(BitmapDescriptorFactory.defaultMarker(BitmapDescriptorFactory.HUE_RED))
                    )

                    imgCenterPin.visibility = View.GONE
                    calculateAndDrawRoute()
                }

                SelectionStage.ROUTE_READY -> {
                    if (selectedRideType == "SOLO") {
                        submitRegularBooking(soloCalculatedFare)
                    } else {
                        submitRideshareBooking(0.0) // no price until a co-passenger is matched
                    }
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
        loadNoShowDebt()

        if (currentStage != SelectionStage.BOOKING_SUBMITTED) {
            currentStage = SelectionStage.ROUTE_READY
        }

        val bounds = LatLngBounds.Builder().include(origin).include(destination).build()
        mMap.animateCamera(CameraUpdateFactory.newLatLngBounds(bounds, 180))

        val url = DirectionsHelper.getDirectionsUrl(origin, destination, placesApiKey)

        withUserDiscount {
            lifecycleScope.launch(Dispatchers.IO) {
                val jsonData = DirectionsHelper.downloadUrl(url)
                val routePoints = DirectionsHelper.parseDirections(jsonData)

                val (distMeters, durSeconds) = parseDistanceAndDuration(jsonData)
                routeDistanceMeters = distMeters
                routeDurationSeconds = durSeconds

                currentSurgeMultiplier = FareCalculator.getSurgeMultiplier(Calendar.getInstance().get(Calendar.HOUR_OF_DAY))

                soloCalculatedFare = calculateExactFare(routeDistanceMeters, routeDurationSeconds, currentSurgeMultiplier, currentUserHasDiscount)

                withContext(Dispatchers.Main) {
                    currentPolyline?.remove()

                    if (routePoints.isNotEmpty()) {
                        passengerPolylinePoints = routePoints.toMutableList()
                        currentPolyline = mMap.addPolyline(
                            PolylineOptions().addAll(routePoints).width(14f)
                                .color(Color.parseColor("#6D5EFC"))
                                .startCap(RoundCap()).endCap(RoundCap()).geodesic(true)
                        )
                        routePolyline = currentPolyline
                    }

                    txtFareSolo.text = String.format(Locale.getDefault(), "₱%.2f", soloCalculatedFare)
                    val soloBadge = findViewById<TextView>(R.id.txtSoloDiscount)
                    if (currentUserHasDiscount) {
                        val full = calculateExactFare(routeDistanceMeters, routeDurationSeconds, currentSurgeMultiplier, false)
                        soloBadge.text = String.format(Locale.getDefault(), "20%% ID discount applied (was ₱%.2f)", full)
                        soloBadge.visibility = View.VISIBLE
                    } else {
                        soloBadge.visibility = View.GONE
                    }
                    txtFareShared.text = "Price shown when matched"

                    if (currentStage == SelectionStage.ROUTE_READY) {
                        txtStageHeaderTitle.visibility = View.VISIBLE
                        txtActivePinAddress.visibility = View.VISIBLE
                        layoutRideOptions.visibility = View.VISIBLE
                        highlightRideOptionCard(isSoloSelected = (selectedRideType == "SOLO"))
                    } else {
                        layoutRideOptions.visibility = View.GONE
                    }
                }
            }
        }
    }

    private fun setupSearchAutocomplete(editText: EditText) {
        editText.addTextChangedListener(object : TextWatcher {
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
        val request = FindAutocompletePredictionsRequest.builder().setQuery(query).setCountries("PH").build()
        placesClient.findAutocompletePredictions(request).addOnSuccessListener { response ->
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
        }.addOnFailureListener { rvSuggestions.visibility = View.GONE }
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
                        reverseGeocodeLocation(latLng)
                    }
                }
            } catch (e: Exception) { e.printStackTrace() }
        }.start()
    }

    private fun submitRegularBooking(fare: Double) {
        writeBooking(fare = fare, serviceType = "REGULAR", isShared = false, maxPax = 1, initialStatus = "REQUESTED") {
            updateSearchingUi(true)
            startSoloTimer(System.currentTimeMillis())
        }
    }

    private fun submitRideshareBooking(fare: Double) {
        val pickup = pickupLatLng ?: return
        val dropoff = dropoffLatLng ?: return
        val userId = FirebaseAuth.getInstance().currentUser?.uid ?: return

        writeBooking(fare = fare, serviceType = "SHARED", isShared = true, maxPax = 2, initialStatus = "MATCHING") { bookingId ->
            updateSearchingUi(true)
            startRideshareFlow(bookingId, userId, pickup, dropoff)
        }
    }

    private fun writeBooking(fare: Double, serviceType: String, isShared: Boolean, maxPax: Int, initialStatus: String, onWritten: (bookingId: String) -> Unit) {
        val pLat = pickupLatLng?.latitude ?: return
        val pLng = pickupLatLng?.longitude ?: return
        val dLat = dropoffLatLng?.latitude ?: return
        val dLng = dropoffLatLng?.longitude ?: return
        val currentUserId = FirebaseAuth.getInstance().currentUser?.uid ?: return

        database.child("users").child(currentUserId).addListenerForSingleValueEvent(object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                val firstName = snapshot.child("firstName").getValue(String::class.java) ?: ""
                val lastName = snapshot.child("lastName").getValue(String::class.java) ?: ""
                val passengerName = "$firstName $lastName".trim().ifEmpty { "Passenger" }
                val passengerPhone = snapshot.child("phoneNumber").getValue(String::class.java) ?: ""

                val ref = database.child("bookings").push()
                val key = ref.key ?: return
                currentRequestId = key

                val bookingData = hashMapOf<String, Any?>(
                    "bookingId" to key, "passengerId" to currentUserId, "passengerName" to passengerName, "passengerPhone" to passengerPhone,
                    "pickupAddress" to pickupAddress, "pickupLat" to pLat, "pickupLng" to pLng, "dropoffAddress" to dropoffAddress,
                    "dropoffLat" to dLat, "dropoffLng" to dLng, "distanceMeters" to routeDistanceMeters, "durationSeconds" to routeDurationSeconds,
                    "surgeMultiplier" to currentSurgeMultiplier, "noShowFee" to currentNoShowFee, "fare" to fare, "hasDiscount" to currentUserHasDiscount,
                    "serviceType" to serviceType, "isShared" to isShared, "maxPassengersAllowed" to maxPax, "status" to initialStatus,
                    "routeStatus" to "NORMAL", "createdAt" to System.currentTimeMillis()
                )
                // Solo rides keep the discount breakdown so the driver sees it too.
                if (!isShared && currentUserHasDiscount) {
                    val full = calculateExactFare(routeDistanceMeters, routeDurationSeconds, currentSurgeMultiplier, false)
                    bookingData["fareBeforeDiscount"] = full
                    bookingData["discountAmount"] = (full - fare).coerceAtLeast(0.0)
                }

                ref.setValue(bookingData).addOnSuccessListener {
                    currentStage = SelectionStage.BOOKING_SUBMITTED
                    Toast.makeText(this@LocationSelectionActivity, "Booking submitted!", Toast.LENGTH_SHORT).show()
                    listenForRideStatusUpdates(key)
                    onWritten(key)
                }
            }
            override fun onCancelled(error: DatabaseError) { updateSearchingUi(false) }
        })
    }

    private fun updateSearchingUi(isSearching: Boolean) {
        if (isSearching) {
            btnConfirmStage.isEnabled = false
            btnCancelSearch.visibility = View.VISIBLE
            layoutRideOptions.visibility = View.GONE
        } else {
            btnConfirmStage.isEnabled = true
            btnCancelSearch.visibility = View.GONE
        }
    }

    private fun cancelActiveRide() {
        val bookingId = currentRequestId ?: return
        database.child("bookings").child(bookingId).child("status").get().addOnSuccessListener { snapshot ->
            val status = snapshot.getValue(String::class.java) ?: ""
            if (status == "IN_PROGRESS") {
                Toast.makeText(this, "Cannot cancel a ride already in progress.", Toast.LENGTH_SHORT).show()
                return@addOnSuccessListener
            }
            val updates = mapOf<String, Any>("status" to "CANCELLED", "cancelledAt" to System.currentTimeMillis())
            database.child("bookings").child(bookingId).updateChildren(updates).addOnSuccessListener {
                rideshareFlow?.stop()
                rideshareFlow = null
                updateSearchingUi(false)
                finish()
            }
        }
    }

    private fun checkLocationPermissionAndFetch() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED) {
            fetchUserCurrentLocation()
        } else {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION), locationPermissionRequestCode)
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == locationPermissionRequestCode) {
            if (grantResults.any { it == PackageManager.PERMISSION_GRANTED }) {
                fetchUserCurrentLocation()
            } else {
                Toast.makeText(this, "Location is off, so the map starts at the default area. Allow it in Settings for accurate pickup.", Toast.LENGTH_LONG).show()
                fallbackToDefaultLocation()
            }
        }
    }

    private fun fetchUserCurrentLocation() {
        val permission = Manifest.permission.ACCESS_FINE_LOCATION
        if (ContextCompat.checkSelfPermission(this, permission) != PackageManager.PERMISSION_GRANTED) return

        val cancelToken = com.google.android.gms.tasks.CancellationTokenSource()
        fusedLocationClient.getCurrentLocation(
            Priority.PRIORITY_HIGH_ACCURACY,
            cancelToken.token
        ).addOnSuccessListener { location ->
            if (location != null && location.latitude in 4.0..21.0 && location.longitude in 116.0..127.0) {
                val currentLatLng = LatLng(location.latitude, location.longitude)
                mMap.animateCamera(CameraUpdateFactory.newLatLngZoom(currentLatLng, 17f))
                reverseGeocodeLocation(currentLatLng)
            } else {
                fallbackToDefaultLocation()
            }
        }.addOnFailureListener {
            fallbackToDefaultLocation()
        }
    }

    private fun fallbackToDefaultLocation() {
        val defaultLoc = LatLng(15.4828, 120.5898)
        mMap.moveCamera(CameraUpdateFactory.newLatLngZoom(defaultLoc, 17f))
        reverseGeocodeLocation(defaultLoc)
    }

    private fun withUserDiscount(onReady: () -> Unit) {
        val userId = FirebaseAuth.getInstance().currentUser?.uid
        if (userId.isNullOrEmpty()) { currentUserHasDiscount = false; onReady(); return }
        database.child("users").child(userId).child("hasDiscount").get().addOnSuccessListener { snapshot ->
            currentUserHasDiscount = snapshot.getValue(Boolean::class.java) ?: false
            onReady()
        }.addOnFailureListener { currentUserHasDiscount = false; onReady() }
    }

    private fun parseDistanceAndDuration(jsonData: String): Pair<Int, Int> {
        return try {
            val routes = JSONObject(jsonData).getJSONArray("routes")
            if (routes.length() > 0) {
                val leg = routes.getJSONObject(0).getJSONArray("legs").getJSONObject(0)
                Pair(leg.getJSONObject("distance").getInt("value"), leg.getJSONObject("duration").getInt("value"))
            } else Pair(3000, 600)
        } catch (e: Exception) { Pair(3000, 600) }
    }

    private fun calculateExactFare(
        distanceMeters: Int,
        durationSeconds: Int,
        surgeMultiplier: Double,
        applyDiscount: Boolean
    ): Double = FareCalculator.calculateFare(
        distanceKm = distanceMeters / 1000.0,
        durationMin = durationSeconds / 60.0,
        vehicleType = VehicleType.SEDAN,
        hasDiscount = applyDiscount,
        surgeMultiplierOverride = surgeMultiplier
    )

    private fun restoreAndTrackActiveBooking(bookingId: String) {
        currentRequestId = bookingId
        currentStage = SelectionStage.BOOKING_SUBMITTED
        imgCenterPin.visibility = View.GONE

        txtStageHeaderTitle.visibility = View.GONE
        txtActivePinAddress.visibility = View.GONE

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

                etPickupLocation.setText(pickupAddress)
                etDropoffLocation.setText(dropoffAddress)

                // Coming back to a booking: the pins are gone with the old map, so draw them again.
                pickupMarker?.remove()
                pickupMarker = mMap.addMarker(
                    MarkerOptions().position(pickupLatLng!!).title("Pick-up")
                        .icon(BitmapDescriptorFactory.defaultMarker(BitmapDescriptorFactory.HUE_GREEN))
                )
                dropoffMarker?.remove()
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

    private fun resumeActiveBookingFlow(bookingId: String) {
        txtStageHeaderTitle.visibility = View.GONE
        txtActivePinAddress.visibility = View.GONE

        database.child("bookings").child(bookingId).get().addOnSuccessListener { snapshot ->
            val status = snapshot.child("status").getValue(String::class.java) ?: ""
            activeBookedFare = snapshot.child("fare").getValue(Double::class.java) ?: 0.0

            when (status) {
                "MATCHING" -> {
                    updateSearchingUi(true)
                    val pLat = snapshot.child("pickupLat").getValue(Double::class.java) ?: 0.0
                    val pLng = snapshot.child("pickupLng").getValue(Double::class.java) ?: 0.0
                    val dLat = snapshot.child("dropoffLat").getValue(Double::class.java) ?: 0.0
                    val dLng = snapshot.child("dropoffLng").getValue(Double::class.java) ?: 0.0
                    val passengerId = snapshot.child("passengerId").getValue(String::class.java) ?: ""
                    startRideshareFlow(bookingId, passengerId, LatLng(pLat, pLng), LatLng(dLat, dLng))
                }
                "MATCHED", "REQUESTED", "SEARCHING" -> {
                    updateSearchingUi(true)
                    btnConfirmStage.text = "Searching for driver..."
                }
                "ACCEPTED", "ARRIVED", "IN_PROGRESS" -> {
                    updateSearchingUi(false)
                    layoutRideOptions.visibility = View.GONE
                    cardDriverInfo.visibility = View.VISIBLE
                    txtPassengerTripStatus.text = if (status == "IN_PROGRESS") "Trip in progress" else "Driver en route"
                }
                "COMPLETED", "CANCELLED" -> {
                    updateSearchingUi(false)
                    finish()
                }
            }
        }
    }

    private fun listenForRideStatusUpdates(bookingId: String) {
        detachRideTrackingListeners()
        val rideRef = database.child("bookings").child(bookingId)
        val listener = object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                val status = snapshot.child("status").getValue(String::class.java) ?: ""
                if (status != lastSyncStatus) {
                    lastSyncStatus = status
                    Log.d("SYNC", "RECEIVED $status t=${System.currentTimeMillis() + syncOffsetMs}")
                }
                val driverId = snapshot.child("driverId").getValue(String::class.java) ?: ""
                val driverLat = snapshot.child("driverLat").getValue(Double::class.java)
                val driverLng = snapshot.child("driverLng").getValue(Double::class.java)
                activeDriverId = driverId
                val isShared = snapshot.child("isShared").getValue(Boolean::class.java) ?: false
                val tripGroupId = snapshot.child("tripGroupId").getValue(String::class.java)

                val fareFromDb = snapshot.child("fare").getValue(Double::class.java)
                if (fareFromDb != null && fareFromDb > 0.0) {
                    activeBookedFare = fareFromDb
                }

                if (status != "REQUESTED" && status != "SEARCHING" && status != "MATCHED" || isShared) stopSoloTimer()

                when (status) {
                    "MATCHING" -> {
                        cardDriverInfo.visibility = View.GONE
                        btnConfirmStage.visibility = View.VISIBLE
                        updateSearchingUi(true)
                        // The text is owned by the rideshare countdown (RideshareMatchUi).
                    }
                    "MATCHED", "REQUESTED", "SEARCHING" -> {
                        cardDriverInfo.visibility = View.GONE
                        btnConfirmStage.visibility = View.VISIBLE
                        updateSearchingUi(true)
                        if (isShared && !tripGroupId.isNullOrEmpty()) {
                            showMatchedStatus(tripGroupId, bookingId)
                            drawGroupRouteForPassenger(tripGroupId, null, null)
                        } else if (isShared) {
                            btnConfirmStage.text = "Searching for driver (Solo)..."
                        } else {
                            startSoloTimer(
                                maxOf(
                                    snapshot.child("createdAt").getValue(Long::class.java) ?: 0L,
                                    snapshot.child("switchedToSoloAt").getValue(Long::class.java) ?: 0L
                                )
                            )
                        }
                    }
                    "ACCEPTED", "ARRIVED", "IN_PROGRESS" -> {
                        txtStageHeaderTitle.visibility = View.GONE
                        txtActivePinAddress.visibility = View.GONE

                        btnConfirmStage.visibility = View.GONE
                        layoutRideOptions.visibility = View.GONE
                        updateSearchingUi(false)
                        cardDriverInfo.visibility = View.VISIBLE
                        btnCancelTrip.visibility = View.GONE

                        if (driverLat != null && driverLng != null && driverLat != 0.0 && driverLng != 0.0) {
                            updateDriverMarkerAndRoute(driverLat, driverLng, status)
                        }

                        fetchDriverAndVehicleDetails(driverId, snapshot)
                        if (isShared && !tripGroupId.isNullOrEmpty()) {
                            drawGroupRouteForPassenger(tripGroupId, driverLat, driverLng)
                        }
                    }
                    "COMPLETED", "CANCELLED" -> {
                        driverMarker?.remove()
                        driverMarker = null
                        driverRoutePolyline?.remove()
                        driverRoutePolyline = null
                        cardDriverInfo.visibility = View.GONE
                        btnConfirmStage.visibility = View.VISIBLE
                        updateSearchingUi(false)
                        if (status == "COMPLETED") {
                            btnConfirmStage.text = "Trip Completed"
                        } else {
                            btnConfirmStage.text = "Ride Cancelled"
                            finish()
                        }
                    }
                }
            }
            override fun onCancelled(error: DatabaseError) {}
        }
        rideStatusRef = rideRef
        rideStatusListener = listener
        rideRef.addValueEventListener(listener)
    }

    private fun updateDriverMarkerAndRoute(driverLat: Double, driverLng: Double, status: String) {
        val driverPos = LatLng(driverLat, driverLng)

        if (driverMarker == null) {
            val carIcon = createPaddedCarMarkerIcon(120, 120)
            driverMarker = mMap.addMarker(
                MarkerOptions()
                    .position(driverPos)
                    .title("Your Driver")
                    .icon(carIcon)
                    .anchor(0.5f, 0.5f)
                    .flat(true)
            )
        } else {
            driverMarker?.position = driverPos
        }

        val targetDest = if (status == "IN_PROGRESS") dropoffLatLng else pickupLatLng

        if (targetDest != null) {
            val url = DirectionsHelper.getDirectionsUrl(driverPos, targetDest, placesApiKey)

            lifecycleScope.launch(Dispatchers.IO) {
                val jsonData = DirectionsHelper.downloadUrl(url)
                val routePoints = DirectionsHelper.parseDirections(jsonData)
                val (_, durSeconds) = parseDistanceAndDuration(jsonData)
                val etaMinutes = (durSeconds / 60).coerceAtLeast(1)

                withContext(Dispatchers.Main) {
                    driverRoutePolyline?.remove()

                    if (routePoints.isNotEmpty()) {
                        driverRoutePolyline = mMap.addPolyline(
                            PolylineOptions()
                                .addAll(routePoints)
                                .width(12f)
                                .color(Color.parseColor("#16A34A"))
                                .zIndex(5f) // green driver line stays above the passenger route colours
                                .geodesic(true)
                        )
                    }

                    txtPassengerTripStatus.text = when (status) {
                        "ARRIVED" -> "Driver has arrived at pickup!"
                        "IN_PROGRESS" -> "Trip in progress • ~$etaMinutes min to destination"
                        else -> "Driver en route • ETA ~$etaMinutes min"
                    }

                    val bounds = LatLngBounds.Builder()
                        .include(driverPos)
                        .include(targetDest)
                        .build()
                    mMap.animateCamera(CameraUpdateFactory.newLatLngBounds(bounds, 150))
                }
            }
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

        val fareDisplay = if (activeBookedFare > 0.0) {
            String.format(Locale.getDefault(), " • Fare: ₱%.2f", activeBookedFare)
        } else ""

        if (!bookingDriverName.isNullOrEmpty() && !bookingVehicleModel.isNullOrEmpty()) {
            txtDriverName.text = bookingDriverName
            txtVehicleDetails.text = "$bookingVehicleColor $bookingVehicleModel • $bookingPlateNumber$fareDisplay"
            return
        }

        database.child("users").child(driverId).addListenerForSingleValueEvent(object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                val firstName = snapshot.child("firstName").getValue(String::class.java) ?: ""
                val lastName = snapshot.child("lastName").getValue(String::class.java) ?: ""
                txtDriverName.text = "$firstName $lastName".trim().ifEmpty { "Assigned Driver" }
                txtVehicleDetails.text = "Assigned Vehicle$fareDisplay"
            }
            override fun onCancelled(error: DatabaseError) {}
        })
    }

    private fun loadNoShowDebt() {
        val uid = FirebaseAuth.getInstance().currentUser?.uid ?: return
        database.child("users").child(uid).child("noShowDebt").get().addOnSuccessListener { currentNoShowFee = it.getValue(Double::class.java) ?: 0.0 }
    }

    private fun detachRideTrackingListeners() {
        rideStatusListener?.let { rideStatusRef?.removeEventListener(it) }
        rideStatusListener = null
        rideStatusRef = null
    }

    /**
     * Draws the matched trip: the driver's route coloured per passenger (you =
     * lavender, co-passenger = blue, both = blue under lavender) with labeled pins.
     * Before a driver has accepted there is no driver position, so the route starts
     * at the first stop.
     */
    private fun drawGroupRouteForPassenger(tripGroupId: String, driverLat: Double?, driverLng: Double?) {
        val driverKnown = driverLat != null && driverLng != null && driverLat != 0.0 && driverLng != 0.0
        val drawKey = "$tripGroupId|$driverKnown"
        if (groupRouteGroupId == drawKey) return
        groupRouteGroupId = drawKey
        val myBookingId = currentRequestId ?: return

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

            database.child("bookings").orderByChild("tripGroupId").equalTo(tripGroupId)
                .addListenerForSingleValueEvent(object : ValueEventListener {
                    override fun onDataChange(bookings: DataSnapshot) {
                        val partnerName = bookings.children
                            .firstOrNull { it.key != myBookingId }
                            ?.child("passengerName")?.getValue(String::class.java)

                        val origin = if (driverKnown) LatLng(driverLat!!, driverLng!!) else stops.first().location
                        lifecycleScope.launch(Dispatchers.IO) {
                            val fullRoute = RideshareRouteOptimizer.buildFinalDriverRoute(
                                origin, stops, placesApiKey
                            ) ?: return@launch

                            withContext(Dispatchers.Main) {
                                if (isDestroyed) return@withContext
                                // The single-colour solo line is replaced by the per-passenger route.
                                currentPolyline?.remove()
                                currentPolyline = null
                                routePolyline = null
                                passengerPolylinePoints = mutableListOf()
                                setOwnPinsHidden(true)
                                rideshareOverlay().showGroupRoute(fullRoute, myBookingId, partnerName)
                            }
                        }
                    }

                    override fun onCancelled(error: DatabaseError) {}
                })
        }
    }

    // ------------------------------------------------------------------
    // Rideshare matching: search timer, co-passenger confirm, solo fallback
    // ------------------------------------------------------------------

    /** The plain booking pins hide while the labeled per-passenger pins are on the map. */
    private fun setOwnPinsHidden(hidden: Boolean) {
        listOf(pickupMarker, dropoffMarker)
            .forEach { it?.isVisible = !hidden }
    }

    private fun rideshareOverlay(): RideshareMapOverlay =
        rideshareOverlay ?: RideshareMapOverlay(this, mMap, lifecycleScope, placesApiKey)
            .also { rideshareOverlay = it }

    private fun startRideshareFlow(bookingId: String, passengerId: String, pickup: LatLng, dropoff: LatLng) {
        rideshareFlow?.stop()
        rideshareUi?.dismissAll()

        rideshareUi = RideshareMatchUi(this, btnConfirmStage, object : RideshareMatchUi.Callbacks {
            override fun onConfirmMatch() { rideshareFlow?.confirmMatch() }
            override fun onDeclineMatch() { rideshareFlow?.declineMatch() }
            override fun onWantsSolo() { showSoloSummary(bookingId) }
            override fun onKeepSearching() { rideshareFlow?.keepSearching() }
            override fun onConfirmSolo() { confirmSolo() }
        })

        rideshareFlow = RideshareFlowManager(
            myBookingId = bookingId,
            myPassengerId = passengerId,
            myPickup = pickup,
            myDropoff = dropoff,
            apiKey = placesApiKey
        ) { event -> runOnUiThread { handleRideshareEvent(event, bookingId, pickup, dropoff) } }
        rideshareFlow?.start()
    }

    private fun handleRideshareEvent(
        event: RideshareFlowManager.Event,
        bookingId: String,
        pickup: LatLng,
        dropoff: LatLng
    ) {
        if (isFinishing || isDestroyed) return
        val ui = rideshareUi ?: return

        when (event) {
            is RideshareFlowManager.Event.Searching -> {
                updateSearchingUi(true)
                rideshareOverlay?.clear()
                setOwnPinsHidden(false)
                RideshareNotifier.cancel(this, RideshareNotifier.ID_MATCH_FOUND)
                ui.onSearching(event.expiresAtLocalMs)
            }
            is RideshareFlowManager.Event.ProposalReady -> {
                ui.onProposalReady(event.proposal)
                setOwnPinsHidden(true)
                rideshareOverlay().showProposal(event.proposal, pickup, dropoff)
                if (!inForeground) {
                    RideshareNotifier.notify(
                        this,
                        RideshareNotifier.ID_MATCH_FOUND,
                        getString(R.string.notif_match_found_title),
                        getString(R.string.notif_match_found_text),
                        bookingId
                    )
                }
            }
            is RideshareFlowManager.Event.ProposalEnded -> {
                rideshareOverlay?.clear()
                setOwnPinsHidden(false)
                RideshareNotifier.cancel(this, RideshareNotifier.ID_MATCH_FOUND)
                ui.onProposalEnded(event.reason)
            }
            is RideshareFlowManager.Event.Matched -> {
                ui.dismissAll()
                rideshareOverlay?.clear()
                setOwnPinsHidden(true) // the matched route is drawn with its own labeled pins
                RideshareNotifier.cancel(this, RideshareNotifier.ID_MATCH_FOUND)
                Toast.makeText(this, R.string.match_confirmed_toast, Toast.LENGTH_SHORT).show()
                btnConfirmStage.text = "Searching for driver..."
                rideshareFlow?.stop()
                rideshareFlow = null
            }
            RideshareFlowManager.Event.SearchExpired -> {
                ui.onSearchExpired()
                if (!inForeground) {
                    RideshareNotifier.notify(
                        this,
                        RideshareNotifier.ID_NO_MATCH,
                        getString(R.string.no_copassenger_title),
                        getString(R.string.no_copassenger_message),
                        bookingId
                    )
                }
            }
            RideshareFlowManager.Event.SwitchedToSolo -> {
                ui.dismissAll()
                rideshareOverlay?.clear()
                setOwnPinsHidden(false)
                RideshareNotifier.cancel(this, RideshareNotifier.ID_NO_MATCH)
                btnConfirmStage.text = "Searching for driver (Solo)..."
                rideshareFlow?.stop()
                rideshareFlow = null
            }
            RideshareFlowManager.Event.BookingEnded -> {
                ui.dismissAll()
                rideshareOverlay?.clear()
                rideshareFlow?.stop()
                rideshareFlow = null
            }
        }
    }

    /** Solo price from the booking itself, so nobody retypes a location. */
    private fun showSoloSummary(bookingId: String) {
        RideshareNotifier.cancel(this, RideshareNotifier.ID_NO_MATCH)
        database.child("bookings").child(bookingId).get().addOnSuccessListener { snap ->
            if (isFinishing || isDestroyed) return@addOnSuccessListener
            val dist = snap.child("distanceMeters").getValue(Int::class.java) ?: routeDistanceMeters
            val dur = snap.child("durationSeconds").getValue(Int::class.java) ?: routeDurationSeconds
            val surge = snap.child("surgeMultiplier").getValue(Double::class.java) ?: currentSurgeMultiplier
            val hasDiscount = snap.child("hasDiscount").getValue(Boolean::class.java) ?: currentUserHasDiscount

            val before = calculateExactFare(dist, dur, surge, applyDiscount = false)
            val fare = calculateExactFare(dist, dur, surge, applyDiscount = hasDiscount)
            pendingSoloFare = fare
            pendingSoloBefore = before

            rideshareUi?.showSoloSummary(
                RideshareMatchUi.SoloSummary(
                    pickupAddress = snap.child("pickupAddress").getValue(String::class.java) ?: pickupAddress,
                    dropoffAddress = snap.child("dropoffAddress").getValue(String::class.java) ?: dropoffAddress,
                    distanceKm = dist / 1000.0,
                    durationMin = dur / 60,
                    fare = fare,
                    discountAmount = (before - fare).coerceAtLeast(0.0)
                )
            )
        }.addOnFailureListener {
            rideshareUi?.onSoloSwitchFailed()
        }
    }

    private fun confirmSolo() {
        val fare = pendingSoloFare
        val before = pendingSoloBefore
        rideshareFlow?.switchToSolo(fare, before, (before - fare).coerceAtLeast(0.0)) { ok ->
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                if (ok) {
                    rideshareUi?.dismissAll()
                    activeBookedFare = fare
                    btnConfirmStage.text = "Searching for driver (Solo)..."
                } else {
                    rideshareUi?.onSoloSwitchFailed()
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        inForeground = true
        RideshareNotifier.cancel(this, RideshareNotifier.ID_MATCH_FOUND)
        RideshareNotifier.cancel(this, RideshareNotifier.ID_NO_MATCH)
    }

    override fun onPause() {
        inForeground = false
        super.onPause()
    }

    /** "Matched with Maria • ₱96.00 locked" while waiting for a driver. */
    private fun showMatchedStatus(tripGroupId: String, myBookingId: String) {
        val fareText = if (activeBookedFare > 0.0) String.format(Locale.getDefault(), " • ₱%.2f locked", activeBookedFare) else ""
        if (lastFetchedGroupId == tripGroupId) {
            btnConfirmStage.text = "Matched with $lastPartnerName$fareText • Finding driver..."
            return
        }
        lastFetchedGroupId = tripGroupId
        btnConfirmStage.text = "Matched!$fareText • Finding driver..."

        database.child("bookings").orderByChild("tripGroupId").equalTo(tripGroupId)
            .addListenerForSingleValueEvent(object : ValueEventListener {
                override fun onDataChange(snapshot: DataSnapshot) {
                    val name = snapshot.children.firstOrNull { it.key != myBookingId }
                        ?.child("passengerName")?.getValue(String::class.java)
                        ?.trim()?.substringBefore(' ')?.takeIf { it.isNotBlank() }
                    if (name != null) {
                        lastPartnerName = name
                        btnConfirmStage.text = "Matched with $name$fareText • Finding driver..."
                    }
                }
                override fun onCancelled(error: DatabaseError) {}
            })
    }

    override fun onDestroy() {
        super.onDestroy()
        stopSoloTimer()
        rideshareFlow?.stop()
        rideshareFlow = null
        rideshareUi?.dismissAll()
        rideshareUi = null
        rideshareOverlay?.clear()
        rideshareOverlay = null
        deviationWatcher?.stop()
        detachRideTrackingListeners()
    }
}