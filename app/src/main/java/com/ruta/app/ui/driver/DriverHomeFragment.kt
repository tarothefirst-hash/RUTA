package com.ruta.app.ui.driver

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.location.Location
import android.os.Bundle
import android.os.Looper
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
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
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.database.DataSnapshot
import com.google.firebase.database.DatabaseError
import com.google.firebase.database.DatabaseReference
import com.google.firebase.database.FirebaseDatabase
import com.google.firebase.database.ValueEventListener
import com.ruta.app.R
import com.ruta.app.databinding.FragmentDriverHomeBinding
import com.ruta.app.model.Booking
import com.ruta.app.ui.ChatActivity
import com.ruta.app.ui.DirectionsHelper
import com.ruta.app.ui.LoginActivity
import com.ruta.app.util.RouteDeviationManager
import java.util.Locale
import java.util.concurrent.Executors
import com.ruta.app.util.RideshareManager
import com.ruta.app.util.RideshareRouteOptimizer
import com.ruta.app.util.RideshareFareCalculator

class DriverHomeFragment : Fragment(), OnMapReadyCallback {

    companion object {
        private const val PICKUP_RADIUS_METERS = 100.0   // looser — driver can also override manually anytime
        private const val DROPOFF_RADIUS_METERS = 60.0   // strict — no manual override, must be within range
    }

    private enum class DriverRideState {
        IDLE,               // Looking for requests
        REQUEST_RECEIVED,   // Incoming booking banner shown (Pickup <-> Dropoff preview)
        ACCEPTED,           // On the way to Pickup (Driver -> Pickup route)
        IN_PROGRESS         // On the way to Dropoff (Driver -> Dropoff route)
    }

    private var _binding: FragmentDriverHomeBinding? = null
    private val binding get() = _binding!!

    private lateinit var mMap: GoogleMap
    private lateinit var fusedLocationClient: FusedLocationProviderClient
    private lateinit var locationCallback: LocationCallback

    private lateinit var database: DatabaseReference
    private lateinit var auth: FirebaseAuth

    private var driverMarker: Marker? = null
    private var pickupMarker: Marker? = null
    private var dropoffMarker: Marker? = null

    private var activePolyline: Polyline? = null
    private var rawRoutePoints: List<LatLng> = emptyList()

    private var currentLatLng: LatLng? = null
    private var activeBookingId: String? = null
    private var currentBooking: Booking? = null
    private var currentRideState = DriverRideState.IDLE

    private var pendingRequestsListener: ValueEventListener? = null
    private var activeBookingListener: ValueEventListener? = null

    private var isOnline = false
    private val locationPermissionCode = 1002
    private val executor = Executors.newSingleThreadExecutor()
    private var wasDeviated = false

    // Multi-stop shared-ride progress tracking
    private var currentGroupStops: List<RideshareManager.RideshareStop> = emptyList()
    private var currentStopIndex: Int = 0
    private var autoConfirmedThisStop: Boolean = false

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentDriverHomeBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        database = FirebaseDatabase.getInstance().reference
        auth = FirebaseAuth.getInstance()
        fusedLocationClient = LocationServices.getFusedLocationProviderClient(requireActivity())

        val mapFragment = childFragmentManager.findFragmentById(R.id.driverMap) as SupportMapFragment?
        mapFragment?.getMapAsync(this)

        setupOnlineSwitch()
        setupLocationCallback()
        setupBookingActionListeners()
        setupLogoutButton()
    }

    override fun onResume() {
        super.onResume()
        val sharedPref = requireContext().getSharedPreferences("DRIVER_SESSION", Context.MODE_PRIVATE)
        val savedOnlineState = sharedPref.getBoolean("IS_ONLINE", false)

        if (savedOnlineState) {
            binding.switchOnline.isChecked = true
            isOnline = true
            binding.txtDriverStatus.text = "Status: Online"
            startLocationUpdates()
            checkAndRestoreActiveDriverSession()
        }
    }

    override fun onMapReady(googleMap: GoogleMap) {
        mMap = googleMap
        mMap.uiSettings.isZoomControlsEnabled = true
        checkLocationPermission()
    }

    private fun setupOnlineSwitch() {
        binding.switchOnline.setOnCheckedChangeListener { _, isChecked ->
            isOnline = isChecked

            val sharedPref = requireContext().getSharedPreferences("DRIVER_SESSION", Context.MODE_PRIVATE)
            sharedPref.edit().putBoolean("IS_ONLINE", isChecked).apply()

            updateDriverOnlineStatus(isChecked)

            if (isChecked) {
                binding.txtDriverStatus.text = "Status: Online"
                startLocationUpdates()
                checkAndRestoreActiveDriverSession()
            } else {
                binding.txtDriverStatus.text = "Status: Offline"
                stopLocationUpdates()
                stopListeningForRequests()
                clearRouteAndMarkers()
                updateUIForState(DriverRideState.IDLE)
            }
        }
    }

    private fun setupLogoutButton() {
        binding.btnDriverLogout.setOnClickListener {
            updateDriverOnlineStatus(false)
            stopLocationUpdates()
            stopListeningForRequests()
            auth.signOut()

            val intent = Intent(requireContext(), LoginActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
            }
            startActivity(intent)
        }
    }

    private fun checkAndRestoreActiveDriverSession() {
        val driverId = auth.currentUser?.uid ?: return

        database.child("bookings")
            .orderByChild("driverId")
            .equalTo(driverId)
            .addListenerForSingleValueEvent(object : ValueEventListener {
                override fun onDataChange(snapshot: DataSnapshot) {
                    var restoredBooking: Booking? = null
                    var restoredStatus = ""

                    for (child in snapshot.children) {
                        val status = child.child("status").getValue(String::class.java) ?: ""
                        if (status == "ACCEPTED" || status == "IN_PROGRESS") {
                            restoredBooking = child.getValue(Booking::class.java)
                            restoredStatus = status
                            break
                        }
                    }

                    if (restoredBooking != null) {
                        activeBookingId = restoredBooking.bookingId
                        currentBooking = restoredBooking

                        // NOTE: known gap — this does not yet restore currentGroupStops /
                        // currentStopIndex for a shared trip resumed after app restart.
                        // Solo restore below is unaffected.
                        binding.txtPassengerName.text = restoredBooking.passengerName.ifEmpty { "Passenger" }
                        binding.txtPickupLocation.text = "Pickup: ${restoredBooking.pickupAddress.ifEmpty { "N/A" }}"
                        binding.txtDropoffLocation.text = "Dropoff: ${restoredBooking.dropoffAddress.ifEmpty { "N/A" }}"
                        binding.txtEstimatedFare.text = String.format(Locale.getDefault(), "Estimated Fare: ₱%.2f", restoredBooking.fare)

                        if (restoredStatus == "ACCEPTED") {
                            updateUIForState(DriverRideState.ACCEPTED)
                            if (currentLatLng != null) {
                                drawDriverToPickupRoute(restoredBooking)
                            }
                        } else if (restoredStatus == "IN_PROGRESS") {
                            updateUIForState(DriverRideState.IN_PROGRESS)
                            if (currentLatLng != null) {
                                drawDriverToDropoffRoute(restoredBooking)
                            }
                        }

                        monitorSingleBookingForCancellation(restoredBooking.bookingId)
                    } else {
                        listenForPendingRideRequests()
                    }
                }

                override fun onCancelled(error: DatabaseError) {}
            })
    }

    private fun setupLocationCallback() {
        locationCallback = object : LocationCallback() {
            override fun onLocationResult(locationResult: LocationResult) {
                for (location in locationResult.locations) {
                    updateDriverPinAndProgress(location)
                }
            }
        }
    }

    private fun updateDriverPinAndProgress(location: Location) {
        val latLng = LatLng(location.latitude, location.longitude)
        currentLatLng = latLng

        if (driverMarker == null) {
            driverMarker = mMap.addMarker(
                MarkerOptions()
                    .position(latLng)
                    .title("Your Location")
                    .icon(BitmapDescriptorFactory.defaultMarker(BitmapDescriptorFactory.HUE_AZURE))
            )
            mMap.animateCamera(CameraUpdateFactory.newLatLngZoom(latLng, 16f))
        } else {
            driverMarker?.position = latLng
        }

        // Draw destination route on initial location fix if route hasn't been rendered yet
        if (activePolyline == null && currentBooking != null) {
            val booking = currentBooking!!
            if (currentRideState == DriverRideState.ACCEPTED) {
                drawDriverToPickupRoute(booking)
            } else if (currentRideState == DriverRideState.IN_PROGRESS) {
                drawDriverToDropoffRoute(booking)
            }
        }

        // 1. Dynamic Polyline Trimming
        if (rawRoutePoints.isNotEmpty()) {
            trimPassedRoutePoints(latLng)
        }

        // 2. Route Deviation Check (Only while driving passenger to destination)
        if (currentRideState == DriverRideState.IN_PROGRESS && rawRoutePoints.isNotEmpty()) {
            val isDeviated = RouteDeviationManager.isDriverDeviated(location, rawRoutePoints)
            if (isDeviated && !wasDeviated) {
                wasDeviated = true
                flagTripAsDeviated()
            } else if (!isDeviated && wasDeviated) {
                wasDeviated = false
                activeBookingId?.let { id ->
                    database.child("bookings").child(id).child("routeStatus").setValue("NORMAL")
                }
            }
        }

        // 3. Auto-confirm pickup when close enough (shared: current stop; solo: the one pickup)
        checkAutoConfirmPickup(latLng)

        // 4. Update driver location in Firebase for passenger real-time tracking
        val currentUserId = auth.currentUser?.uid ?: return
        val locationMap = hashMapOf<String, Any>(
            "lat" to location.latitude,
            "lng" to location.longitude
        )
        database.child("drivers").child(currentUserId).updateChildren(locationMap)

        activeBookingId?.let { bookingId ->
            if (currentRideState == DriverRideState.ACCEPTED || currentRideState == DriverRideState.IN_PROGRESS) {
                val driverLocMap = hashMapOf<String, Any>(
                    "driverLat" to location.latitude,
                    "driverLng" to location.longitude
                )
                database.child("bookings").child(bookingId).updateChildren(driverLocMap)
            }
        }
    }

    private fun checkAutoConfirmPickup(driverLoc: LatLng) {
        if (autoConfirmedThisStop) return
        val booking = currentBooking ?: return
        val tripGroupId = booking.tripGroupId

        if (!tripGroupId.isNullOrEmpty()) {
            val stops = currentGroupStops
            if (currentStopIndex !in stops.indices) return
            val stop = stops[currentStopIndex]
            if (stop.type != RideshareManager.StopType.PICKUP) return
            if (distanceMeters(driverLoc, stop.location) <= PICKUP_RADIUS_METERS) {
                autoConfirmedThisStop = true
                confirmSharedStop(isAuto = true)
            }
        } else {
            if (currentRideState != DriverRideState.ACCEPTED) return
            val pickupLoc = LatLng(booking.pickupLat, booking.pickupLng)
            if (distanceMeters(driverLoc, pickupLoc) <= PICKUP_RADIUS_METERS) {
                autoConfirmedThisStop = true
                confirmPassengerPickup()
            }
        }
    }

    private fun flagTripAsDeviated() {
        val bookingId = activeBookingId ?: return

        val deviationUpdates = hashMapOf<String, Any>(
            "routeStatus" to "DEVIATED",
            "lastDeviatedLat" to (currentLatLng?.latitude ?: 0.0),
            "lastDeviatedLng" to (currentLatLng?.longitude ?: 0.0),
            "deviatedTimestamp" to System.currentTimeMillis()
        )

        database.child("bookings").child(bookingId).updateChildren(deviationUpdates)
    }

    private fun trimPassedRoutePoints(driverPos: LatLng) {
        if (rawRoutePoints.size <= 1) return

        var closestIndex = 0
        var minDistance = Float.MAX_VALUE

        for (i in rawRoutePoints.indices) {
            val results = FloatArray(1)
            Location.distanceBetween(
                driverPos.latitude, driverPos.longitude,
                rawRoutePoints[i].latitude, rawRoutePoints[i].longitude,
                results
            )
            if (results[0] < minDistance) {
                minDistance = results[0]
                closestIndex = i
            }
        }

        if (closestIndex > 0 && minDistance < 50) {
            val remainingPoints = mutableListOf(driverPos)
            remainingPoints.addAll(rawRoutePoints.subList(closestIndex, rawRoutePoints.size))
            rawRoutePoints = remainingPoints

            activePolyline?.points = rawRoutePoints
        }
    }

    private fun drawRoute(origin: LatLng, destination: LatLng) {
        executor.execute {
            val apiKey = getString(R.string.google_maps_key)
            val urlString = DirectionsHelper.getDirectionsUrl(origin, destination, apiKey)
            val jsonResponse = DirectionsHelper.downloadUrl(urlString)
            val pathPoints = DirectionsHelper.parseDirections(jsonResponse)

            activity?.runOnUiThread {
                if (pathPoints.isNotEmpty()) {
                    activePolyline?.remove()
                    rawRoutePoints = pathPoints

                    val polylineOptions = PolylineOptions()
                        .addAll(pathPoints)
                        .width(12f)
                        .color(Color.parseColor("#1976D2"))
                        .geodesic(true)

                    activePolyline = mMap.addPolyline(polylineOptions)
                }
            }
        }
    }

    private fun setupBookingActionListeners() {
        binding.btnAcceptRide.setOnClickListener {
            val driverId = auth.currentUser?.uid ?: return@setOnClickListener
            val tripGroupId = currentBooking?.tripGroupId
            if (!tripGroupId.isNullOrEmpty()) acceptRideGroup(driverId) else acceptCurrentRide()
        }
        binding.btnDeclineRide.setOnClickListener { declineCurrentRide() }
        binding.btnConfirmPickup.setOnClickListener { onConfirmStopTapped() }
        binding.btnCompleteRide.setOnClickListener { attemptCompleteRide() }
    }

    private fun onConfirmStopTapped() {
        val booking = currentBooking ?: return
        if (booking.tripGroupId.isNullOrEmpty()) {
            confirmPassengerPickup()   // solo: unchanged, always allowed manually
        } else {
            confirmSharedStop(isAuto = false)
        }
    }

    private fun openChatWithPassenger() {
        val booking = currentBooking ?: return
        val intent = Intent(requireContext(), ChatActivity::class.java).apply {
            putExtra("BOOKING_ID", booking.bookingId)
            putExtra("RECEIVER_ID", booking.passengerId)
            putExtra("RECEIVER_NAME", booking.passengerName)
        }
        startActivity(intent)
    }

    private fun showGroupRequestPreview(booking: Booking) {
        val tripGroupId = booking.tripGroupId ?: return

        database.child("bookings").orderByChild("tripGroupId").equalTo(tripGroupId)
            .addListenerForSingleValueEvent(object : ValueEventListener {
                override fun onDataChange(snapshot: DataSnapshot) {
                    val group = snapshot.children.mapNotNull { it.getValue(Booking::class.java) }

                    if (group.size < 2) {
                        // Co-passenger's own booking node hasn't synced to this client yet —
                        // fall back to single-leg preview rather than show nothing.
                        binding.txtPassengerName.text = "${booking.passengerName.ifEmpty { "Passenger" }} (Shared Ride)"
                        binding.txtPickupLocation.text = "Pickup: ${booking.pickupAddress.ifEmpty { "N/A" }}"
                        binding.txtDropoffLocation.text = "Dropoff: ${booking.dropoffAddress.ifEmpty { "N/A" }}"
                        binding.txtEstimatedFare.text = "Estimated Fare: ${String.format(Locale.getDefault(), "₱%.2f", booking.fare)}"
                        previewBookingRoute(booking)
                        return
                    }

                    val names = group.joinToString(" & ") { it.passengerName.ifEmpty { "Passenger" } }
                    binding.txtPassengerName.text = "$names (Shared Ride)"
                    binding.txtPickupLocation.text = "Pickups: ${group.joinToString(" + ") { it.pickupAddress.ifEmpty { "N/A" } }}"
                    binding.txtDropoffLocation.text = "Dropoffs: ${group.joinToString(" + ") { it.dropoffAddress.ifEmpty { "N/A" } }}"
                    binding.txtEstimatedFare.text = String.format(
                        Locale.getDefault(), "Estimated Fare: ₱%.2f (combined)", group.sumOf { it.fare }
                    )

                    previewGroupRoute(tripGroupId, group)
                }
                override fun onCancelled(error: DatabaseError) {}
            })
    }

    private fun previewGroupRoute(tripGroupId: String, group: List<Booking>) {
        clearRouteAndMarkers()

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
                // tripGroups/{id} is written asynchronously right after matching —
                // if this preview loads before that write lands, fall back gracefully.
                previewBookingRoute(group.first())
                return@addOnSuccessListener
            }

            stops.forEachIndexed { i, stop ->
                val isPickup = stop.type == RideshareManager.StopType.PICKUP
                mMap.addMarker(
                    MarkerOptions()
                        .position(stop.location)
                        .title("${i + 1}. ${if (isPickup) "Pickup" else "Dropoff"}")
                        .icon(BitmapDescriptorFactory.defaultMarker(
                            if (isPickup) BitmapDescriptorFactory.HUE_GREEN else BitmapDescriptorFactory.HUE_ORANGE
                        ))
                )
            }

            val bounds = LatLngBounds.Builder()
            stops.forEach { bounds.include(it.location) }
            currentLatLng?.let { bounds.include(it) }
            try { mMap.animateCamera(CameraUpdateFactory.newLatLngBounds(bounds.build(), 120)) } catch (e: Exception) { e.printStackTrace() }
        }
    }

    private fun updateUIForState(state: DriverRideState) {
        currentRideState = state
        when (state) {
            DriverRideState.IDLE -> {
                binding.cardIncomingRequest.visibility = View.GONE
                binding.btnAcceptRide.visibility = View.VISIBLE
                binding.btnDeclineRide.visibility = View.VISIBLE
                binding.btnConfirmPickup.visibility = View.GONE
                binding.btnCompleteRide.visibility = View.GONE
            }
            DriverRideState.REQUEST_RECEIVED -> {
                binding.cardIncomingRequest.visibility = View.VISIBLE
                binding.btnAcceptRide.visibility = View.VISIBLE
                binding.btnDeclineRide.visibility = View.VISIBLE
                binding.btnConfirmPickup.visibility = View.GONE
                binding.btnCompleteRide.visibility = View.GONE
            }
            DriverRideState.ACCEPTED -> {
                binding.cardIncomingRequest.visibility = View.VISIBLE
                binding.btnAcceptRide.visibility = View.GONE
                binding.btnDeclineRide.visibility = View.GONE
                binding.btnConfirmPickup.visibility = View.VISIBLE
                binding.btnCompleteRide.visibility = View.GONE
            }
            DriverRideState.IN_PROGRESS -> {
                binding.cardIncomingRequest.visibility = View.VISIBLE
                binding.btnAcceptRide.visibility = View.GONE
                binding.btnDeclineRide.visibility = View.GONE
                binding.btnConfirmPickup.visibility = View.GONE
                binding.btnCompleteRide.visibility = View.VISIBLE
            }
        }
    }

    private fun acceptCurrentRide() {
        val bookingId = activeBookingId ?: return
        val driverId = auth.currentUser?.uid ?: return

        updateUIForState(DriverRideState.ACCEPTED)

        val updates = hashMapOf<String, Any>(
            "status" to "ACCEPTED",
            "driverId" to driverId
        )

        database.child("bookings").child(bookingId).updateChildren(updates)
            .addOnSuccessListener {
                Toast.makeText(context, "Ride accepted!", Toast.LENGTH_SHORT).show()
                clearRouteAndMarkers()
                autoConfirmedThisStop = false

                val booking = currentBooking ?: return@addOnSuccessListener

                if (currentLatLng == null) {
                    if (ActivityCompat.checkSelfPermission(
                            requireContext(),
                            Manifest.permission.ACCESS_FINE_LOCATION
                        ) == PackageManager.PERMISSION_GRANTED
                    ) {
                        fusedLocationClient.lastLocation.addOnSuccessListener { loc ->
                            if (loc != null) {
                                currentLatLng = LatLng(loc.latitude, loc.longitude)
                                drawDriverToPickupRoute(booking)
                            }
                        }
                    }
                } else {
                    drawDriverToPickupRoute(booking)
                }
            }
            .addOnFailureListener { e ->
                updateUIForState(DriverRideState.REQUEST_RECEIVED)
                Toast.makeText(context, "Failed to accept ride: ${e.message}", Toast.LENGTH_SHORT).show()
            }
    }

    // Call this function when the Driver clicks the "ACCEPT RIDE" button on a matched group
    private fun acceptRideGroup(driverId: String) {
        val booking = currentBooking ?: return
        val tripGroupId = booking.tripGroupId

        if (!tripGroupId.isNullOrEmpty()) {
            database.child("bookings").orderByChild("tripGroupId").equalTo(tripGroupId)
                .addListenerForSingleValueEvent(object : ValueEventListener {
                    override fun onDataChange(snapshot: DataSnapshot) {
                        for (child in snapshot.children) {
                            child.ref.child("driverId").setValue(driverId)
                            child.ref.child("status").setValue("ACCEPTED")
                        }
                        currentStopIndex = 0
                        autoConfirmedThisStop = false
                        updateUIForState(DriverRideState.ACCEPTED)
                        buildGroupRoute(tripGroupId)
                    }
                    override fun onCancelled(error: DatabaseError) {
                        Toast.makeText(context, "Failed to accept trip group: ${error.message}", Toast.LENGTH_SHORT).show()
                    }
                })
        } else {
            database.child("bookings").child(booking.bookingId).child("driverId").setValue(driverId)
            database.child("bookings").child(booking.bookingId).child("status").setValue("ACCEPTED")
                .addOnSuccessListener {
                    autoConfirmedThisStop = false
                    updateUIForState(DriverRideState.ACCEPTED)
                }
        }
    }

    /**
     * Fetches the persisted stop order from `tripGroups`, prepends the driver's current position,
     * draws the active multi-stop polyline, and populates `rawRoutePoints` for deviation tracking.
     */
    private fun buildGroupRoute(tripGroupId: String, retryCount: Int = 0) {
        val driverLoc = currentLatLng ?: return
        val apiKey = getString(R.string.google_maps_key)

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
                if (retryCount < 3) {
                    android.os.Handler(Looper.getMainLooper()).postDelayed(
                        { buildGroupRoute(tripGroupId, retryCount + 1) }, 1500
                    )
                } else {
                    Toast.makeText(context, "Couldn't load the shared route — check your connection.", Toast.LENGTH_LONG).show()
                }
                return@addOnSuccessListener
            }

            executor.execute {
                val finalRoute = RideshareRouteOptimizer.buildFinalDriverRoute(driverLoc, stops, apiKey)
                    ?: return@execute

                activity?.runOnUiThread {
                    rawRoutePoints = finalRoute.polyline

                    activePolyline?.remove()
                    activePolyline = mMap.addPolyline(
                        PolylineOptions()
                            .addAll(finalRoute.polyline)
                            .width(12f)
                            .color(ContextCompat.getColor(requireContext(), R.color.ruta_primary))
                    )

                    stops.forEachIndexed { i, stop ->
                        val isPickup = stop.type == RideshareManager.StopType.PICKUP
                        val markerColor = if (isPickup) BitmapDescriptorFactory.HUE_GREEN else BitmapDescriptorFactory.HUE_ORANGE
                        val titleText = "${i + 1}. ${if (isPickup) "Pickup" else "Dropoff"}"

                        mMap.addMarker(
                            MarkerOptions()
                                .position(stop.location)
                                .title(titleText)
                                .icon(BitmapDescriptorFactory.defaultMarker(markerColor))
                        )
                    }

                    currentGroupStops = stops
                    updateCardForCurrentStop()
                }
            }
        }
    }

    private fun drawDriverToPickupRoute(booking: Booking) {
        val driverLoc = currentLatLng ?: return
        val pickupLatLng = LatLng(booking.pickupLat, booking.pickupLng)

        pickupMarker = mMap.addMarker(
            MarkerOptions().position(pickupLatLng).title("Pickup Point")
        )

        drawRoute(driverLoc, pickupLatLng)

        try {
            val bounds = LatLngBounds.Builder()
                .include(driverLoc)
                .include(pickupLatLng)
                .build()
            mMap.animateCamera(CameraUpdateFactory.newLatLngBounds(bounds, 120))
        } catch (e: Exception) {
            mMap.animateCamera(CameraUpdateFactory.newLatLngZoom(driverLoc, 15f))
        }
    }

    private fun drawDriverToDropoffRoute(booking: Booking) {
        val driverLoc = currentLatLng ?: return
        val dropoffLatLng = LatLng(booking.dropoffLat, booking.dropoffLng)

        dropoffMarker = mMap.addMarker(
            MarkerOptions().position(dropoffLatLng).title("Dropoff Point")
                .icon(BitmapDescriptorFactory.defaultMarker(BitmapDescriptorFactory.HUE_ORANGE))
        )

        drawRoute(driverLoc, dropoffLatLng)

        try {
            val bounds = LatLngBounds.Builder()
                .include(driverLoc)
                .include(dropoffLatLng)
                .build()
            mMap.animateCamera(CameraUpdateFactory.newLatLngBounds(bounds, 120))
        } catch (e: Exception) {
            mMap.animateCamera(CameraUpdateFactory.newLatLngZoom(driverLoc, 15f))
        }
    }

    // ---- SOLO ride pickup (unchanged path, either auto-triggered or manual tap) ----
    private fun confirmPassengerPickup() {
        val bookingId = activeBookingId ?: return

        updateUIForState(DriverRideState.IN_PROGRESS)

        database.child("bookings").child(bookingId).child("status").setValue("IN_PROGRESS")
            .addOnSuccessListener {
                Toast.makeText(context, "Passenger Picked Up! Trip Started.", Toast.LENGTH_SHORT).show()
                clearRouteAndMarkers()

                val booking = currentBooking
                if (booking != null) {
                    drawDriverToDropoffRoute(booking)
                }
            }
            .addOnFailureListener { e ->
                updateUIForState(DriverRideState.ACCEPTED)
                Toast.makeText(context, "Failed to start trip: ${e.message}", Toast.LENGTH_SHORT).show()
            }
    }

    // ---- SHARED ride: confirm whichever stop is currently active ----
    private fun confirmSharedStop(isAuto: Boolean = false) {
        val stops = currentGroupStops
        if (currentStopIndex !in stops.indices) return
        val stop = stops[currentStopIndex]

        if (stop.type == RideshareManager.StopType.DROPOFF) {
            val driverLoc = currentLatLng
            if (driverLoc == null) {
                Toast.makeText(context, "Waiting for GPS fix — try again shortly.", Toast.LENGTH_SHORT).show()
                return
            }
            val dist = distanceMeters(driverLoc, stop.location)
            if (dist > DROPOFF_RADIUS_METERS) {
                Toast.makeText(context, "You're ${dist.toInt()}m from this drop-off — move closer to confirm.", Toast.LENGTH_LONG).show()
                return
            }
        }
        // PICKUP: no distance gate on a manual tap — driver can override anytime.
        // An auto-triggered call already passed the PICKUP_RADIUS_METERS check.

        val newStatus = if (stop.type == RideshareManager.StopType.PICKUP) "IN_PROGRESS" else "COMPLETED"
        database.child("bookings").child(stop.bookingId).child("status").setValue(newStatus)
            .addOnSuccessListener { advanceToNextStop() }
    }

    private fun advanceToNextStop() {
        autoConfirmedThisStop = false
        currentStopIndex++

        val tripGroupId = currentBooking?.tripGroupId ?: return
        database.child("tripGroups").child(tripGroupId).child("currentStopIndex").setValue(currentStopIndex)

        if (currentStopIndex >= currentGroupStops.size) {
            finishSharedTrip(tripGroupId)
        } else {
            updateCardForCurrentStop()
        }
    }

    private fun updateCardForCurrentStop() {
        val stops = currentGroupStops
        if (currentStopIndex !in stops.indices) return
        val stop = stops[currentStopIndex]
        val isPickup = stop.type == RideshareManager.StopType.PICKUP
        val label = if (isPickup) "Pickup" else "Dropoff"

        database.child("bookings").child(stop.bookingId).get().addOnSuccessListener { snap ->
            val name = snap.child("passengerName").getValue(String::class.java)?.ifEmpty { null } ?: "Passenger"
            val address = if (isPickup)
                snap.child("pickupAddress").getValue(String::class.java) ?: "N/A"
            else
                snap.child("dropoffAddress").getValue(String::class.java) ?: "N/A"

            binding.txtPassengerName.text = "$name — Stop ${currentStopIndex + 1} of ${stops.size}"
            binding.txtPickupLocation.text = if (isPickup) "Pickup: $address" else ""
            binding.txtDropoffLocation.text = if (!isPickup) "Dropoff: $address" else ""
        }

        binding.btnAcceptRide.visibility = View.GONE
        binding.btnDeclineRide.visibility = View.GONE
        binding.btnCompleteRide.visibility = View.GONE   // shared trips finish through the last stop's confirm, not this
        binding.btnConfirmPickup.visibility = View.VISIBLE
        binding.btnConfirmPickup.text = "Confirm $label"
    }

    private fun finishSharedTrip(tripGroupId: String) {
        calculateAndSaveSharedFares(tripGroupId)
        Toast.makeText(context, "Ride Completed!", Toast.LENGTH_SHORT).show()

        clearRouteAndMarkers()
        activeBookingId = null
        currentBooking = null
        currentGroupStops = emptyList()
        currentStopIndex = 0
        updateUIForState(DriverRideState.IDLE)
        listenForPendingRideRequests()
    }

    // ---- SOLO ride completion (shared trips never show this button — see updateCardForCurrentStop) ----
    private fun attemptCompleteRide() {
        val booking = currentBooking ?: return
        val driverLoc = currentLatLng
        if (driverLoc == null) {
            Toast.makeText(context, "Waiting for GPS fix — try again in a moment.", Toast.LENGTH_SHORT).show()
            return
        }

        val destination = LatLng(booking.dropoffLat, booking.dropoffLng)
        val distance = distanceMeters(driverLoc, destination)

        if (distance > DROPOFF_RADIUS_METERS) {
            Toast.makeText(context, "You're ${distance.toInt()}m from the drop-off — move closer to complete the ride.", Toast.LENGTH_LONG).show()
            return
        }

        completeCurrentRide()
    }

    private fun completeCurrentRide() {
        val bookingId = activeBookingId ?: return

        database.child("bookings").child(bookingId).child("status").setValue("COMPLETED")
            .addOnSuccessListener {
                Toast.makeText(context, "Ride Completed!", Toast.LENGTH_SHORT).show()
                clearRouteAndMarkers()
                activeBookingId = null
                currentBooking = null
                updateUIForState(DriverRideState.IDLE)
                listenForPendingRideRequests()
            }
    }

    private fun calculateAndSaveSharedFares(tripGroupId: String) {
        val apiKey = getString(R.string.google_maps_key)

        database.child("bookings").orderByChild("tripGroupId").equalTo(tripGroupId)
            .addListenerForSingleValueEvent(object : ValueEventListener {
                override fun onDataChange(snapshot: DataSnapshot) {
                    val group = snapshot.children.mapNotNull { it.getValue(Booking::class.java) }
                    if (group.size != 2) return   // solo or malformed — nothing to split

                    val a = group[0]
                    val b = group[1]

                    val candA = RideshareManager.RideshareCandidate(a.bookingId, a.passengerId,
                        LatLng(a.pickupLat, a.pickupLng), LatLng(a.dropoffLat, a.dropoffLng))
                    val candB = RideshareManager.RideshareCandidate(b.bookingId, b.passengerId,
                        LatLng(b.pickupLat, b.pickupLng), LatLng(b.dropoffLat, b.dropoffLng))

                    executor.execute {
                        val route = RideshareRouteOptimizer.findOptimalSequence(candA, candB, apiKey) ?: return@execute

                        val breakdown = RideshareFareCalculator.calculate(
                            route = route,
                            bookingIdA = a.bookingId,
                            bookingIdB = b.bookingId,
                            directDistanceA = a.distanceMeters,
                            directDurationA = a.durationSeconds,
                            directDistanceB = b.distanceMeters,
                            directDurationB = b.durationSeconds,
                            surgeMultiplier = a.surgeMultiplier
                        )

                        listOf(breakdown.passengerA, breakdown.passengerB).forEach { f ->
                            database.child("bookings").child(f.bookingId).updateChildren(
                                mapOf(
                                    "finalFare" to f.total,
                                    "fareSoloCharge" to f.soloCharge,
                                    "fareSharedCharge" to f.sharedCharge,
                                    "fareDetourSurcharge" to f.detourSurcharge,
                                    "fareInconvenienceCredit" to f.inconvenienceCredit
                                )
                            )
                        }
                    }
                }

                override fun onCancelled(error: DatabaseError) {}
            })
    }

    private fun declineCurrentRide() {
        clearRouteAndMarkers()
        activeBookingId = null
        currentBooking = null
        updateUIForState(DriverRideState.IDLE)
        listenForPendingRideRequests()
    }

    private fun clearRouteAndMarkers() {
        activePolyline?.remove()
        activePolyline = null
        rawRoutePoints = emptyList()
        wasDeviated = false
        autoConfirmedThisStop = false

        pickupMarker?.remove()
        pickupMarker = null

        dropoffMarker?.remove()
        dropoffMarker = null

        // Wipe any lingering/ghost markers left on the map instance
        if (::mMap.isInitialized) {
            mMap.clear()

            // Re-add your driver marker after wiping
            currentLatLng?.let { latLng ->
                driverMarker = mMap.addMarker(
                    MarkerOptions()
                        .position(latLng)
                        .title("Your Location")
                        .icon(BitmapDescriptorFactory.defaultMarker(BitmapDescriptorFactory.HUE_AZURE))
                )
            }
        }
    }

    private fun listenForPendingRideRequests() {
        val bookingsRef = database.child("bookings")

        pendingRequestsListener = object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                if (!isOnline) return
                if (currentRideState == DriverRideState.ACCEPTED || currentRideState == DriverRideState.IN_PROGRESS) return

                val driverLoc = currentLatLng ?: return  // can't rank without knowing where we are

                val eligible = mutableListOf<Booking>()
                for (child in snapshot.children) {
                    val status = child.child("status").getValue(String::class.java)
                    if (status == "REQUESTED" || status == "MATCHED") {
                        child.getValue(Booking::class.java)?.let { eligible.add(it) }
                    }
                }

                // Nearest Neighbor: rank by straight-line distance from the driver's
                // current position to each booking's pickup point.
                val nearestBooking = eligible.minByOrNull { b ->
                    distanceMeters(driverLoc, LatLng(b.pickupLat, b.pickupLng))
                }

                if (nearestBooking != null) {
                    if (activeBookingId != nearestBooking.bookingId) {
                        activeBookingId = nearestBooking.bookingId
                        currentBooking = nearestBooking

                        val isGroup = !nearestBooking.tripGroupId.isNullOrEmpty()
                        if (isGroup) {
                            showGroupRequestPreview(nearestBooking)
                        } else {
                            binding.txtPassengerName.text = nearestBooking.passengerName.ifEmpty { "Passenger" }
                            binding.txtPickupLocation.text = "Pickup: ${nearestBooking.pickupAddress.ifEmpty { "N/A" }}"
                            binding.txtDropoffLocation.text = "Dropoff: ${nearestBooking.dropoffAddress.ifEmpty { "N/A" }}"
                            binding.txtEstimatedFare.text = "Estimated Fare: ${String.format(Locale.getDefault(), "₱%.2f", nearestBooking.fare)}"
                            previewBookingRoute(nearestBooking)
                        }

                        updateUIForState(DriverRideState.REQUEST_RECEIVED)
                        monitorSingleBookingForCancellation(nearestBooking.bookingId)
                    }
                } else {
                    if (currentRideState == DriverRideState.REQUEST_RECEIVED) {
                        updateUIForState(DriverRideState.IDLE)
                        clearRouteAndMarkers()
                        activeBookingId = null
                        currentBooking = null
                    }
                }
            }

            override fun onCancelled(error: DatabaseError) {
                Toast.makeText(context, "Realtime sync error: ${error.message}", Toast.LENGTH_SHORT).show()
            }
        }

        bookingsRef.addValueEventListener(pendingRequestsListener!!)
    }

    private fun distanceMeters(a: LatLng, b: LatLng): Double {
        val results = FloatArray(1)
        Location.distanceBetween(a.latitude, a.longitude, b.latitude, b.longitude, results)
        return results[0].toDouble()
    }

    private fun previewBookingRoute(booking: Booking) {
        val pLat = booking.pickupLat
        val pLng = booking.pickupLng
        val dLat = booking.dropoffLat
        val dLng = booking.dropoffLng

        val pickupLatLng = LatLng(pLat, pLng)
        val dropoffLatLng = LatLng(dLat, dLng)

        clearRouteAndMarkers()

        pickupMarker = mMap.addMarker(
            MarkerOptions().position(pickupLatLng).title("Pickup Point")
        )
        dropoffMarker = mMap.addMarker(
            MarkerOptions().position(dropoffLatLng).title("Dropoff Point")
                .icon(BitmapDescriptorFactory.defaultMarker(BitmapDescriptorFactory.HUE_ORANGE))
        )

        drawRoute(pickupLatLng, dropoffLatLng)

        try {
            val bounds = LatLngBounds.Builder()
                .include(pickupLatLng)
                .include(dropoffLatLng)

            currentLatLng?.let { bounds.include(it) }

            mMap.animateCamera(CameraUpdateFactory.newLatLngBounds(bounds.build(), 120))
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun startLocationUpdates() {
        if (ActivityCompat.checkSelfPermission(
                requireContext(),
                Manifest.permission.ACCESS_FINE_LOCATION
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            checkLocationPermission()
            return
        }

        val locationRequest = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, 3000)
            .setMinUpdateIntervalMillis(1500)
            .build()

        fusedLocationClient.requestLocationUpdates(
            locationRequest,
            locationCallback,
            Looper.getMainLooper()
        )
    }

    private fun stopLocationUpdates() {
        fusedLocationClient.removeLocationUpdates(locationCallback)
    }

    private fun checkLocationPermission() {
        if (ContextCompat.checkSelfPermission(
                requireContext(),
                Manifest.permission.ACCESS_FINE_LOCATION
            ) == PackageManager.PERMISSION_GRANTED
        ) {
            mMap.isMyLocationEnabled = true
            fusedLocationClient.lastLocation.addOnSuccessListener { location ->
                if (location != null) {
                    updateDriverPinAndProgress(location)
                }
            }
        } else {
            requestPermissions(
                arrayOf(Manifest.permission.ACCESS_FINE_LOCATION),
                locationPermissionCode
            )
        }
    }

    private fun updateDriverOnlineStatus(online: Boolean) {
        val currentUserId = auth.currentUser?.uid ?: return
        database.child("drivers").child(currentUserId).child("isOnline").setValue(online)
    }

    private fun stopListeningForRequests() {
        pendingRequestsListener?.let {
            database.child("bookings").removeEventListener(it)
        }
        activeBookingListener?.let {
            activeBookingId?.let { id -> database.child("bookings").child(id).removeEventListener(it) }
        }
    }

    private fun monitorSingleBookingForCancellation(bookingId: String) {
        val singleBookingRef = database.child("bookings").child(bookingId)

        activeBookingListener = object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                val status = snapshot.child("status").getValue(String::class.java) ?: return

                if (status == "CANCELLED") {
                    context?.let {
                        Toast.makeText(it, "Passenger cancelled the ride request.", Toast.LENGTH_LONG).show()
                    }
                    clearRouteAndMarkers()
                    activeBookingId = null
                    currentBooking = null
                    updateUIForState(DriverRideState.IDLE)
                    listenForPendingRideRequests()
                }
            }

            override fun onCancelled(error: DatabaseError) {}
        }

        singleBookingRef.addValueEventListener(activeBookingListener!!)
    }

    override fun onDestroyView() {
        super.onDestroyView()
        stopLocationUpdates()
        stopListeningForRequests()
        _binding = null
    }
}