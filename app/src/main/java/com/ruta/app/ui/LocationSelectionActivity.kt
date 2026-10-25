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
import com.ruta.app.util.FareDisplay
import com.ruta.app.util.MapsApi
import com.ruta.app.util.RideshareFareCalculator
import com.ruta.app.util.RideshareFlowManager
import com.ruta.app.util.RideshareNotifier
import com.ruta.app.util.RideshareManager
import com.ruta.app.util.RideshareRouteOptimizer
import com.ruta.app.util.RouteDeviationWatcher
import com.ruta.app.util.VehicleType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.util.Calendar
import java.util.Locale
import android.text.Html
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
    private lateinit var btnCancelSearch: MaterialButton
    private lateinit var btnCancelTrip: MaterialButton

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
    private var currentFareBeforeDiscount: Double = 0.0
    private var currentDiscountAmount: Double = 0.0
    private var currentCalculatedFare: Double = 0.0
    private var routeDistanceMeters: Int = 0
    private var routeDurationSeconds: Int = 0
    private var currentSurgeMultiplier: Double = 1.0
    // Admin-approved Student/PWD/Senior flag for the signed-in passenger. Loaded
    // once per route calculation and reused for both the displayed and stored fare
    // so the two can never disagree.
    private var currentUserHasDiscount: Boolean = false

    // Whether the booking currently being tracked is a shared one. Restored from the
    // booking snapshot so the fare banner is not mistakenly rendered as solo after a
    // resume (the banner defaults to solo before the snapshot lands).
    private var activeBookingIsShared: Boolean = false

    // Set once a settled final fare has been painted on the card, so a late quote
    // redraw (e.g. the route request finishing after a resume) cannot overwrite it.
    private var settledFinalFareShown: Boolean = false

    private var driverMarker: Marker? = null
    private var activeMarker: Marker? = null
    private var pickupMarker: Marker? = null
    private var dropoffMarker: Marker? = null
    private var currentPolyline: Polyline? = null
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
    private var rideshareUi: RideshareMatchUi? = null
    private var rideshareOverlay: RideshareMapOverlay? = null
    private var inForeground = false
    private var pendingSoloFare = 0.0
    private var pendingSoloBefore = 0.0

    // Ride/location trackers attached for the active booking. Held in fields so
    // they can be detached in onDestroy and stop updating once the screen is gone.
    private var bookingTrackingRef: DatabaseReference? = null
    private var bookingTrackingListener: ValueEventListener? = null
    private var rideStatusRef: DatabaseReference? = null
    private var rideStatusListener: ValueEventListener? = null
    private var cancelDialogShown = false

    private val placesApiKey: String by lazy { MapsApi.key(this) }
    private val locationPermissionRequestCode = 1001
    private var currentNoShowFee = 0.0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        setContentView(R.layout.activity_location_selection)

        database = FirebaseDatabase.getInstance().reference

        if (!Places.isInitialized()) {
            Places.initialize(applicationContext, placesApiKey)
        }
        loadNoShowDebt()
        placesClient = Places.createClient(this)
        fusedLocationClient = LocationServices.getFusedLocationProviderClient(this)

        etSearchLocation = findViewById(R.id.etSearchLocation)
        rvSuggestions = findViewById(R.id.rvSearchSuggestions)
        rvSuggestions.layoutManager = LinearLayoutManager(this)

        txtPickupDisplay = findViewById(R.id.txtPickupDisplay)
        txtDropoffDisplay = findViewById(R.id.txtDropoffDisplay)
        btnConfirmStage = findViewById(R.id.btnConfirmStage)
        btnCancelSearch = findViewById(R.id.btnCancelSearch)
        btnCancelTrip = findViewById(R.id.btnCancelTrip)
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

        btnCancelSearch.setOnClickListener {
            cancelActiveRide()
        }
        btnCancelTrip.setOnClickListener {
            cancelActiveRide()
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

    private fun updateSearchingUi(isSearching: Boolean) {
        if (isSearching) {
            btnConfirmStage.isEnabled = false
            btnCancelSearch.visibility = View.VISIBLE
        } else {
            btnConfirmStage.isEnabled = true
            btnCancelSearch.visibility = View.GONE
        }
    }

    private fun cancelActiveRide() {
        val bookingId = currentRequestId ?: return

        database.child("bookings").child(bookingId).child("status").get()
            .addOnSuccessListener { snapshot ->
                val status = snapshot.getValue(String::class.java) ?: ""
                if (status == "IN_PROGRESS") {
                    Toast.makeText(this, "Cannot cancel a ride already in progress.", Toast.LENGTH_LONG).show()
                    return@addOnSuccessListener
                }

                val updates = mapOf<String, Any>(
                    "status" to "CANCELLED",
                    "cancelledAt" to System.currentTimeMillis()
                )

                database.child("bookings").child(bookingId).updateChildren(updates)
                    .addOnSuccessListener {
                        Toast.makeText(this, "Ride request cancelled.", Toast.LENGTH_SHORT).show()
                        rideshareFlow?.stop()
                        rideshareFlow = null
                        updateSearchingUi(false)
                        finish()
                    }
                    .addOnFailureListener { e ->
                        Toast.makeText(this, "Error cancelling ride: ${e.message}", Toast.LENGTH_SHORT).show()
                    }
            }
    }

    private fun setupPassengerRideTracking(bookingId: String) {
        val bookingRef = FirebaseDatabase.getInstance().reference.child("bookings").child(bookingId)

        val listener = object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                val status = snapshot.child("status").getValue(String::class.java) ?: return

                val driverLat = snapshot.child("driverLat").getValue(Double::class.java)
                val driverLng = snapshot.child("driverLng").getValue(Double::class.java)

                if (driverLat != null && driverLng != null && driverLat != 0.0 && driverLng != 0.0) {
                    val driverPos = LatLng(driverLat, driverLng)
                    updateLiveDriverMarker(driverLat, driverLng)
                    trimPassengerPolyline(driverPos)
                }

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
        }
        bookingTrackingRef = bookingRef
        bookingTrackingListener = listener
        bookingRef.addValueEventListener(listener)
    }
    private fun loadNoShowDebt() {
        val uid = FirebaseAuth.getInstance().currentUser?.uid ?: return
        database.child("users").child(uid).child("noShowDebt").get()
            .addOnSuccessListener { currentNoShowFee = it.getValue(Double::class.java) ?: 0.0 }
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
                    cardDriverInfo.visibility = View.VISIBLE
                    txtPassengerTripStatus.text = if (status == "IN_PROGRESS") "Trip in progress" else "Driver en route"
                }
                "COMPLETED", "CANCELLED" -> {
                    updateSearchingUi(false)
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

    private fun updateFareBanner(
        userFare: Double,
        isRideshare: Boolean = false,
        coMatchFare: Double? = null
    ) {
        // A settled fare is final for this screen; don't let a quote redraw clobber it.
        if (settledFinalFareShown) return
        val cardFareDetails = findViewById<MaterialCardView>(R.id.cardFareDetails)
        val txtUserFare = findViewById<TextView>(R.id.txtUserFare)
        val txtTripCalculation = findViewById<TextView>(R.id.txtTripCalculation)

        cardFareDetails?.visibility = View.VISIBLE

        if (userFare <= 0.0) {
            txtUserFare?.text = "Calculating Fare..."
            txtTripCalculation?.text = "Fetching route distance..."
            return
        }

        if (isRideshare) {
            if (coMatchFare != null && coMatchFare > 0.0) {
                val overallTripTotal = userFare + coMatchFare
                txtUserFare?.text = String.format(Locale.getDefault(), "Your Fare: ₱%.2f", userFare)
                txtTripCalculation?.text = String.format(
                    Locale.getDefault(),
                    "Overall Trip Total: ₱%.2f (Co-matched Split)",
                    overallTripTotal
                )
            } else {
                txtUserFare?.text = String.format(Locale.getDefault(), "Est. Share: ₱%.2f", userFare)
                txtTripCalculation?.text = "Overall calculation updates upon co-match."
            }
        } else {
            if (currentDiscountAmount > 0.0) {
                val originalFare = if (currentFareBeforeDiscount > 0.0) currentFareBeforeDiscount else (userFare + currentDiscountAmount)
                val htmlText = String.format(
                    Locale.getDefault(),
                    "Total Fare: <font color='#D1D5DB'><small><s>₱%.2f</s></small></font>  <b>₱%.2f</b>",
                    originalFare,
                    userFare
                )
                txtUserFare?.text = Html.fromHtml(htmlText, Html.FROM_HTML_MODE_COMPACT)
                txtTripCalculation?.text = String.format(
                    Locale.getDefault(),
                    "Solo Ride • Discount applied (-₱%.2f)",
                    currentDiscountAmount
                )
            } else {
                txtUserFare?.text = String.format(Locale.getDefault(), "Total Fare: ₱%.2f", userFare)
                txtTripCalculation?.text = "Solo Ride • Direct route fare"
            }
        }

        if (currentNoShowFee > 0.0) {
            txtTripCalculation?.append(
                String.format(Locale.getDefault(), "\n+ ₱%.2f unpaid no-show fee", currentNoShowFee)
            )
        }
    }

    /**
     * Renders the settled fare (and the running-shared / finalising hints) onto the
     * passenger fare card. All wording and numeric formatting lives in [FareDisplay];
     * this only reads the snapshot values and maps the helper's output onto the views.
     */
    private fun updateFinalFareDisplay(snapshot: DataSnapshot, status: String, isShared: Boolean) {
        val rendered = FareDisplay.render(
            FareDisplay.Snapshot(
                finalFare = snapshot.child("finalFare").getValue(Double::class.java) ?: 0.0,
                fareBase = snapshot.child("fareBase").getValue(Double::class.java) ?: 0.0,
                fareSoloCharge = snapshot.child("fareSoloCharge").getValue(Double::class.java) ?: 0.0,
                fareSharedCharge = snapshot.child("fareSharedCharge").getValue(Double::class.java) ?: 0.0,
                fareDetourSurcharge = snapshot.child("fareDetourSurcharge").getValue(Double::class.java) ?: 0.0,
                fareInconvenienceCredit = snapshot.child("fareInconvenienceCredit").getValue(Double::class.java) ?: 0.0,

                // --- ADD THIS LINE HERE ---
                fareDiscount = snapshot.child("discountAmount").getValue(Double::class.java) ?: 0.0,

                quotedFare = snapshot.child("fare").getValue(Double::class.java) ?: 0.0,
                status = status,
                isShared = isShared,
                fareLocked = snapshot.child("fareLocked").getValue(Boolean::class.java) ?: false
            ),
            fareDisplayLabels()
        )

        // Once the match is confirmed the passenger's price is fixed: say so on the
        // banner instead of leaving the pre-match estimate there.
        val lockedFare = snapshot.child("fare").getValue(Double::class.java) ?: 0.0
        if (isShared && status !in listOf("COMPLETED", "CANCELLED") &&
            (snapshot.child("fareLocked").getValue(Boolean::class.java) ?: false) &&
            lockedFare > 0.0 &&
            (snapshot.child("finalFare").getValue(Double::class.java) ?: 0.0) <= 0.0
        ) {
            findViewById<MaterialCardView>(R.id.cardFareDetails)?.visibility = View.VISIBLE
            findViewById<TextView>(R.id.txtUserFare)?.text =
                String.format(Locale.getDefault(), "Your Fare: ₱%.2f", lockedFare)
            findViewById<TextView>(R.id.txtTripCalculation)?.text = getString(R.string.fare_banner_locked_note)
            settledFinalFareShown = true
        }

        rendered.headline?.let { findViewById<TextView>(R.id.txtUserFare)?.text = it }
        if (rendered.headline != null) settledFinalFareShown = true

        val txtFinalFareDetail = findViewById<TextView>(R.id.txtFinalFareDetail) ?: return
        val detail = rendered.detail
        if (detail.isNullOrBlank()) {
            txtFinalFareDetail.visibility = View.GONE
        } else {
            txtFinalFareDetail.text = detail
            txtFinalFareDetail.visibility = View.VISIBLE
        }
    }

    private fun fareDisplayLabels(): FareDisplay.Labels = FareDisplay.Labels(
        headlineFinal = getString(R.string.fare_headline_final),
        detailBase = getString(R.string.fare_detail_base),
        detailYourRide = getString(R.string.fare_detail_your_ride),
        detailShared = getString(R.string.fare_detail_shared),
        detailDetour = getString(R.string.fare_detail_detour),
        detailDiscount = getString(R.string.fare_detail_discount),
        detailCredit = getString(R.string.fare_detail_credit),
        detailAdjustedFrom = getString(R.string.fare_detail_adjusted_from),
        detailFinalising = getString(R.string.fare_detail_finalising),
        detailAwaitingFinalShare = getString(R.string.fare_detail_awaiting_final_share),
        moneyFormat = getString(R.string.fare_money_format),
        detailLockedFare = getString(R.string.fare_detail_locked)
    )

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
        loadNoShowDebt()

        currentStage = SelectionStage.ROUTE_READY
        activeMarker?.isDraggable = false

        val bounds = LatLngBounds.Builder()
            .include(origin)
            .include(destination)
            .build()
        mMap.animateCamera(CameraUpdateFactory.newLatLngBounds(bounds, 180))

        val url = DirectionsHelper.getDirectionsUrl(origin, destination, placesApiKey)

        withUserDiscount {
            lifecycleScope.launch(Dispatchers.IO) {
                val jsonData = DirectionsHelper.downloadUrl(url)
                val routePoints = DirectionsHelper.parseDirections(jsonData)

                val (distMeters, durSeconds) = parseDistanceAndDuration(jsonData)
                routeDistanceMeters = distMeters
                routeDurationSeconds = durSeconds

                currentSurgeMultiplier = FareCalculator.getSurgeMultiplier(
                    Calendar.getInstance().get(Calendar.HOUR_OF_DAY)
                )

                // Fare calculation
                currentFareBeforeDiscount = calculateExactFare(
                    routeDistanceMeters,
                    routeDurationSeconds,
                    currentSurgeMultiplier,
                    applyDiscount = false
                )

                currentCalculatedFare = calculateExactFare(
                    routeDistanceMeters,
                    routeDurationSeconds,
                    currentSurgeMultiplier,
                    applyDiscount = currentUserHasDiscount
                )

                currentDiscountAmount = currentFareBeforeDiscount - currentCalculatedFare

                withContext(Dispatchers.Main) {
                    currentPolyline?.remove()

                    if (routePoints.isNotEmpty()) {
                        passengerPolylinePoints = routePoints.toMutableList()

                        currentPolyline = mMap.addPolyline(
                            PolylineOptions()
                                .addAll(routePoints)
                                .width(14f)
                                .color(ContextCompat.getColor(this@LocationSelectionActivity, R.color.ruta_primary))
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

                    // Updates fare display and appends unpaid no-show fee if present
                    updateFareBanner(currentCalculatedFare, isRideshare = activeBookingIsShared)

                    val surgeText = if (currentSurgeMultiplier > 1.0) " (${currentSurgeMultiplier}x Surge)" else ""
                    if (!suppressRouteButtonUpdate) {
                        btnConfirmStage.text = "CONFIRM DESTINATION - ₱${currentCalculatedFare.toInt()}$surgeText"
                    }
                }
            }
        }
    }
    /**
     * Loads users/<uid>/hasDiscount once and hands back control. The value is
     * cached on currentUserHasDiscount and used for the displayed fare, the stored
     * booking fare and the booking's own hasDiscount field, so those stay aligned.
     */
    private fun withUserDiscount(onReady: () -> Unit) {
        val userId = FirebaseAuth.getInstance().currentUser?.uid
        if (userId.isNullOrEmpty()) {
            currentUserHasDiscount = false
            onReady()
            return
        }

        database.child("users").child(userId).child("hasDiscount").get()
            .addOnSuccessListener { snapshot ->
                currentUserHasDiscount = snapshot.getValue(Boolean::class.java) ?: false
                onReady()
            }
            .addOnFailureListener {
                currentUserHasDiscount = false
                onReady()
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

    private fun calculateExactFare(
        distanceMeters: Int, durationSeconds: Int, surgeMultiplier: Double, applyDiscount: Boolean
    ): Double = FareCalculator.calculateFare(
        distanceKm = distanceMeters / 1000.0,
        durationMin = durationSeconds / 60.0,
        vehicleType = VehicleType.SEDAN,
        hasDiscount = applyDiscount,
        surgeMultiplierOverride = surgeMultiplier
    )

    private fun showRideOptionsDialog(baseCalculatedFare: Double) {
        val dialog = BottomSheetDialog(this)
        val view = layoutInflater.inflate(R.layout.dialog_ride_options, null)
        dialog.setContentView(view)

        val fareRegular = baseCalculatedFare
        // The shared ride is quoted with the unified model. Before any co-passenger
        // exists there is no counterpart, so the quote is the model's base-plus-own-
        // distance case — numerically identical to the solo fare for the same trip.
        val fareShared = RideshareFareCalculator.quoteSoloFare(
            distanceMeters = routeDistanceMeters,
            durationSeconds = routeDurationSeconds,
            surgeMultiplier = currentSurgeMultiplier,

        )
        view.findViewById<TextView>(R.id.txtFareRegular)?.text =
            if (currentDiscountAmount > 0) "₱${fareRegular.toInt()} • 20% off" else "₱${fareRegular.toInt()}"
        val distanceKm = String.format("%.1f", routeDistanceMeters / 1000.0)
        val durationMin = (routeDurationSeconds / 60)

        view.findViewById<TextView>(R.id.txtTripSummaryDetails)?.text = "$distanceKm km • ~$durationMin mins"
        view.findViewById<TextView>(R.id.txtFareRegular)?.text = "₱${fareRegular.toInt()}"
        view.findViewById<TextView>(R.id.txtFareShared)?.text = "₱${fareShared.toInt()}"

        view.findViewById<View>(R.id.btnOptionRegular)?.setOnClickListener {
            dialog.dismiss()
            updateFareBanner(fareRegular, isRideshare = false)
            submitRegularBooking(fareRegular)
        }

        view.findViewById<View>(R.id.btnOptionShared)?.setOnClickListener {
            dialog.dismiss()
            updateFareBanner(fareShared, isRideshare = true)
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
            updateSearchingUi(true)
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
            updateSearchingUi(true)
            startRideshareFlow(bookingId, userId, pickup, dropoff)
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
        // New booking — a previous settled fare no longer applies to this card.
        settledFinalFareShown = false

        val pLat = pickupLatLng?.latitude ?: return
        val pLng = pickupLatLng?.longitude ?: return
        val dLat = dropoffLatLng?.latitude ?: return
        val dLng = dropoffLatLng?.longitude ?: return

        val currentUser = FirebaseAuth.getInstance().currentUser ?: return
        val currentUserId = currentUser.uid

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
                        "noShowFee" to currentNoShowFee,
                        "fare" to fare,
                        "fareBeforeDiscount" to if (isShared) fare else currentFareBeforeDiscount,
                        "discountAmount" to if (isShared) 0.0 else currentDiscountAmount,
                        "hasDiscount" to currentUserHasDiscount,
                        "serviceType" to serviceType,
                        "isShared" to isShared,
                        "maxPassengersAllowed" to maxPax,
                        "tripGroupId" to null,
                        "status" to initialStatus,
                        "routeStatus" to "NORMAL",
                        "createdAt" to System.currentTimeMillis()
                    )

                    ref.setValue(bookingData).addOnSuccessListener {
                        currentStage = SelectionStage.BOOKING_SUBMITTED
                        Toast.makeText(this@LocationSelectionActivity, "Booking submitted!", Toast.LENGTH_SHORT).show()
                        listenForRideStatusUpdates(key)
                        onWritten(key)
                    }
                }

                override fun onCancelled(error: DatabaseError) {
                    updateSearchingUi(false)
                }
            })
    }

    private fun restoreAndTrackActiveBooking(bookingId: String) {
        currentRequestId = bookingId
        currentStage = SelectionStage.BOOKING_SUBMITTED
        suppressRouteButtonUpdate = true
        calculateAndDrawRoute()
        database.child("bookings").child(bookingId).addListenerForSingleValueEvent(object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                if (!snapshot.exists()) return

                // Restore the shared flag before the route/fare banner is redrawn so a
                // resumed shared booking is never rendered as solo.
                activeBookingIsShared = snapshot.child("isShared").getValue(Boolean::class.java) ?: false

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
        detachRideTrackingListeners()
        setupPassengerRideTracking(bookingId)

        val rideRef = database.child("bookings").child(bookingId)
        val listener = object : ValueEventListener {
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
                activeBookingIsShared = isShared

                // Keep the fare card in step with the booking on every update.
                updateFinalFareDisplay(snapshot, status, isShared)

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
                        updateSearchingUi(true)
                        // The text is owned by the rideshare countdown (RideshareMatchUi).
                        btnConfirmStage.setBackgroundColor(Color.parseColor("#9E9E9E"))
                    }
                    "MATCHED", "REQUESTED", "SEARCHING" -> {
                        cardDriverInfo.visibility = View.GONE
                        btnConfirmStage.visibility = View.VISIBLE
                        updateSearchingUi(true)
                        btnConfirmStage.setBackgroundColor(Color.parseColor("#9E9E9E"))

                        if (isShared && !tripGroupId.isNullOrEmpty()) {
                            showCoPassengerNameOnce(tripGroupId, bookingId)
                            drawGroupRouteForPassenger(tripGroupId, null, null)
                        } else if (isShared) {
                            btnConfirmStage.text = "Searching for driver (Solo)..."
                        } else {
                            btnConfirmStage.text = "Searching for driver..."
                        }
                    }
                    "ACCEPTED", "ARRIVED", "IN_PROGRESS" -> {
                        btnConfirmStage.visibility = View.GONE
                        updateSearchingUi(false)
                        cardDriverInfo.visibility = View.VISIBLE
                        cardDriverInfo.bringToFront()

                        // FIX: Hide cancel button once a driver accepts, arrives, or starts trip
                        btnCancelTrip.visibility = View.GONE

                        val driverLat = snapshot.child("driverLat").getValue(Double::class.java)
                        val driverLng = snapshot.child("driverLng").getValue(Double::class.java)
                        updateLiveDriverMarker(driverLat, driverLng)

                        val arrivedAt = snapshot.child("arrivedAt").getValue(Long::class.java) ?: 0L
                        val minsLeft = if (arrivedAt > 0) {
                            ((arrivedAt + 5 * 60_000L - System.currentTimeMillis()) / 60_000L + 1).coerceAtLeast(0)
                        } else -1

                        if (status == "IN_PROGRESS" && driverLat != null && driverLng != null && driverLat != 0.0 && driverLng != 0.0) {
                            mMap.animateCamera(CameraUpdateFactory.newLatLngZoom(LatLng(driverLat, driverLng), 16.5f))
                        } else if (driverLat != null && driverLng != null && driverLat != 0.0 && driverLng != 0.0) {
                            fitMapBoundsSafely(LatLng(driverLat, driverLng), pickupLatLng, dropoffLatLng)
                        }

                        txtPassengerTripStatus.text = when {
                            routeStatus == "DEVIATED" -> "⚠️ Route Deviation Detected!"
                            (status == "ACCEPTED" || status == "ARRIVED") && arrivedAt > 0 ->
                                "Driver has arrived! Please board within $minsLeft min"
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
                        updateSearchingUi(false)

                        if (status == "COMPLETED") {
                            btnConfirmStage.text = "Trip Completed"
                            btnConfirmStage.setBackgroundColor(Color.parseColor("#4CAF50"))
                        } else {
                            btnConfirmStage.text = "Ride Cancelled"
                            btnConfirmStage.setBackgroundColor("#E53935".toColorInt())

                            if (!cancelDialogShown) {
                                cancelDialogShown = true
                                val reason = snapshot.child("cancelReason").getValue(String::class.java)
                                val owed = snapshot.child("noShowFeeOwed").getValue(Double::class.java) ?: 0.0
                                val msg = when (reason) {
                                    "CO_PASSENGER_NO_SHOW" -> "Your co-passenger didn't show up, so this shared ride was cancelled. You won't be charged."
                                    "NO_SHOW" -> "You weren't at the pickup point in time. ₱%.2f (fare + ₱20 inconvenience fee) will be added to your next booking.".format(owed)
                                    else -> null
                                }
                                msg?.let {
                                    androidx.appcompat.app.AlertDialog.Builder(this@LocationSelectionActivity)
                                        .setMessage(it)
                                        .setPositiveButton("OK") { _, _ -> finish() }
                                        .setCancelable(false)
                                        .show()
                                }
                            }
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
        listOf(pickupMarker, dropoffMarker, passengerPickupMarker, passengerDropoffMarker)
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
                    currentDiscountAmount = (before - fare).coerceAtLeast(0.0)
                    currentFareBeforeDiscount = before
                    activeBookingIsShared = false
                    settledFinalFareShown = false
                    updateFareBanner(fare, isRideshare = false)
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

    override fun onDestroy() {
        super.onDestroy()
        rideshareFlow?.stop()
        rideshareFlow = null
        rideshareUi?.dismissAll()
        rideshareUi = null
        rideshareOverlay?.clear()
        rideshareOverlay = null
        stopPassengerLocationUpdates()
        deviationWatcher?.stop()
        detachRideTrackingListeners()
    }

    // Removes the booking ride/location listeners so no database updates keep
    // firing (and no UI/markers get touched) after the activity is destroyed.
    private fun detachRideTrackingListeners() {
        bookingTrackingListener?.let { bookingTrackingRef?.removeEventListener(it) }
        bookingTrackingListener = null
        bookingTrackingRef = null
        rideStatusListener?.let { rideStatusRef?.removeEventListener(it) }
        rideStatusListener = null
        rideStatusRef = null
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