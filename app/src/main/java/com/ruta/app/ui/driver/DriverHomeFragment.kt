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
import androidx.core.view.WindowCompat
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
import com.ruta.app.util.MapMarkerUtils
import com.ruta.app.util.RideshareFareCalculator
import com.ruta.app.util.RideshareManager
import com.ruta.app.util.RideshareRouteOptimizer
import com.ruta.app.util.RouteDeviationManager
import com.ruta.app.util.WalletManager
import com.ruta.app.util.keepClearOfKeyboard
import java.util.Locale
import java.util.concurrent.Executors

class DriverHomeFragment : Fragment(), OnMapReadyCallback {

    companion object {
        private const val PICKUP_RADIUS_METERS = 100.0
        private const val DROPOFF_RADIUS_METERS = 60.0
    }

    private enum class DriverRideState {
        IDLE,
        REQUEST_RECEIVED,
        ACCEPTED,
        IN_PROGRESS
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
        WindowCompat.setDecorFitsSystemWindows(requireActivity().window, false)
        requireActivity().keepClearOfKeyboard(view)

        val mapFragment = childFragmentManager.findFragmentById(R.id.driverMap) as SupportMapFragment?
        mapFragment?.getMapAsync(this)

        setupOnlineSwitch()
        setupLocationCallback()
        setupBookingActionListeners()
        setupLogoutButton()
    }

    override fun onResume() {
        super.onResume()
        val savedOnlineState = requireContext().getSharedPreferences("DRIVER_SESSION", Context.MODE_PRIVATE)
            .getBoolean("IS_ONLINE", false)
        if (savedOnlineState) {
            binding.switchOnline.isChecked = true
        }
    }

    override fun onMapReady(googleMap: GoogleMap) {
        mMap = googleMap
        mMap.uiSettings.isZoomControlsEnabled = true
        checkLocationPermission()
    }

    private fun setupOnlineSwitch() {
        binding.switchOnline.setOnCheckedChangeListener { switchView, isChecked ->
            if (isChecked) {
                val uid = auth.currentUser?.uid ?: return@setOnCheckedChangeListener

                database.child("users").child(uid).get()
                    .addOnSuccessListener { snap ->
                        val isActive = snap.child("isActive").getValue(Boolean::class.java) ?: true
                        val isApproved = snap.child("isApproved").getValue(Boolean::class.java) ?: false

                        if (!isApproved || !isActive) {
                            switchView.setOnCheckedChangeListener(null)
                            switchView.isChecked = false
                            setupOnlineSwitch()

                            if (!isApproved) {
                                val intent = Intent(requireContext(), DriverDocumentUploadActivity::class.java)
                                startActivity(intent)
                            } else {
                                Toast.makeText(context, "Your account has been deactivated by an admin.", Toast.LENGTH_LONG).show()
                            }
                        } else {
                            goOnline()
                        }
                    }
                    .addOnFailureListener {
                        switchView.setOnCheckedChangeListener(null)
                        switchView.isChecked = false
                        setupOnlineSwitch()

                        Toast.makeText(context, "Couldn't verify your account status — try again.", Toast.LENGTH_SHORT).show()
                    }
            } else {
                if (currentRideState == DriverRideState.ACCEPTED || currentRideState == DriverRideState.IN_PROGRESS) {
                    switchView.setOnCheckedChangeListener(null)
                    switchView.isChecked = true
                    setupOnlineSwitch()

                    Toast.makeText(
                        requireContext(),
                        "Cannot go offline during an active trip.",
                        Toast.LENGTH_LONG
                    ).show()
                    return@setOnCheckedChangeListener
                }

                goOffline()
            }
        }
    }

    private fun goOnline() {
        isOnline = true
        requireContext().getSharedPreferences("DRIVER_SESSION", Context.MODE_PRIVATE)
            .edit().putBoolean("IS_ONLINE", true).apply()
        updateDriverOnlineStatus(true)
        binding.txtDriverStatus.text = "Status: Online"
        startLocationUpdates()
        checkAndRestoreActiveDriverSession()
    }

    private fun goOffline() {
        isOnline = false
        requireContext().getSharedPreferences("DRIVER_SESSION", Context.MODE_PRIVATE)
            .edit().putBoolean("IS_ONLINE", false).apply()
        updateDriverOnlineStatus(false)
        binding.txtDriverStatus.text = "Status: Offline"
        stopLocationUpdates()
        stopListeningForRequests()
        clearRouteAndMarkers()
        updateUIForState(DriverRideState.IDLE)
    }

    private fun setupLogoutButton() {
        binding.btnDriverLogout.setOnClickListener {
            if (currentRideState == DriverRideState.ACCEPTED || currentRideState == DriverRideState.IN_PROGRESS) {
                Toast.makeText(
                    requireContext(),
                    "Cannot log out while a ride is active. Please complete or cancel the trip first.",
                    Toast.LENGTH_LONG
                ).show()
                return@setOnClickListener
            }

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

    private fun getFormattedServiceTitle(booking: Booking): String {
        val serviceType = booking.serviceType ?: ""
        return when {
            serviceType.equals("SOLO", ignoreCase = true) -> "Solo Ride"
            serviceType.equals("SHARED", ignoreCase = true) -> "Shared Ride"
            else -> if (serviceType.isNotEmpty()) serviceType else "Solo Ride"
        }
    }

    private fun bindPassengerInfoToView(booking: Booking, appendTitle: String = "") {
        val serviceTitle = getFormattedServiceTitle(booking)
        val nameText = booking.passengerName.ifEmpty { "Passenger" }
        binding.txtPassengerName.text = if (appendTitle.isNotEmpty()) "$nameText ($appendTitle)" else "$nameText • $serviceTitle"

        binding.txtPickupLocation.text = "Pickup: ${booking.pickupAddress.ifEmpty { "N/A" }}"
        binding.txtDropoffLocation.text = "Dropoff: ${booking.dropoffAddress.ifEmpty { "N/A" }}"

        val fare = booking.fare
        val commission = WalletManager.commissionFor(fare, booking.hasDiscount)
        val netEarnings = fare - commission

        binding.txtEstimatedFare.text = String.format(Locale.getDefault(), "Estimated Fare: ₱%.2f", fare)

        binding.txtWalletBalance.text = String.format(
            Locale.getDefault(),
            "Deduction: -₱%.2f (10%%) • Take-Home: ₱%.2f",
            commission,
            netEarnings
        )

        val passengerId = booking.passengerId
        if (passengerId.isNotEmpty()) {
            database.child("users").child(passengerId).get().addOnSuccessListener { snapshot ->
                val phone = snapshot.child("phone").value?.toString()
                    ?: snapshot.child("phoneNumber").value?.toString()
                    ?: ""
                if (phone.isNotEmpty()) {
                    binding.txtPassengerName.text = "${binding.txtPassengerName.text}\nContact: $phone"
                }
            }
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

                        val tripGroupId = restoredBooking.tripGroupId

                        if (!tripGroupId.isNullOrEmpty()) {
                            database.child("tripGroups").child(tripGroupId).get()
                                .addOnSuccessListener { groupSnap ->
                                    val savedIndex = groupSnap.child("currentStopIndex").getValue(Int::class.java) ?: 0
                                    loadGroupStopsLocally(tripGroupId) { stops ->
                                        currentGroupStops = stops
                                        currentStopIndex = savedIndex.coerceIn(0, (stops.size - 1).coerceAtLeast(0))
                                        updateUIForState(DriverRideState.IN_PROGRESS)
                                        updateCardForCurrentStop()
                                        drawGroupRoutePreviewOnly(stops)
                                    }
                                }
                        } else {
                            bindPassengerInfoToView(restoredBooking)
                            if (restoredStatus == "ACCEPTED") {
                                updateUIForState(DriverRideState.ACCEPTED)
                                if (currentLatLng != null) drawDriverToPickupRoute(restoredBooking)
                            } else if (restoredStatus == "IN_PROGRESS") {
                                updateUIForState(DriverRideState.IN_PROGRESS)
                                if (currentLatLng != null) drawDriverToDropoffRoute(restoredBooking)
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

        if (activePolyline == null && currentBooking != null) {
            val booking = currentBooking!!
            if (currentRideState == DriverRideState.ACCEPTED) {
                drawDriverToPickupRoute(booking)
            } else if (currentRideState == DriverRideState.IN_PROGRESS) {
                drawDriverToDropoffRoute(booking)
            }
        }

        if (rawRoutePoints.isNotEmpty()) {
            trimPassedRoutePoints(latLng)
        }

        val isTripActive = (currentRideState == DriverRideState.IN_PROGRESS) ||
                (!currentBooking?.tripGroupId.isNullOrEmpty() && currentRideState == DriverRideState.ACCEPTED)

        if (isTripActive && rawRoutePoints.isNotEmpty()) {
            val isDeviated = RouteDeviationManager.isDriverDeviated(location, rawRoutePoints)
            if (isDeviated && !wasDeviated) {
                wasDeviated = true
                flagTripAsDeviated()
            } else if (!isDeviated && wasDeviated) {
                wasDeviated = false
                clearTripDeviation()
            }
        }

        checkAutoConfirmPickup(latLng)

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
            if (stops.isEmpty() || currentStopIndex !in stops.indices) return

            val stop = stops[currentStopIndex]
            if (stop.type != RideshareManager.StopType.PICKUP) return

            val dist = distanceMeters(driverLoc, stop.location)
            if (dist <= PICKUP_RADIUS_METERS) {
                autoConfirmedThisStop = true
                confirmSharedStop(isAuto = true)
            }
        } else {
            if (currentRideState != DriverRideState.ACCEPTED) return
            val pickupLoc = LatLng(booking.pickupLat, booking.pickupLng)
            val dist = distanceMeters(driverLoc, pickupLoc)

            if (dist <= PICKUP_RADIUS_METERS) {
                autoConfirmedThisStop = true
                confirmPassengerPickup()
            }
        }
    }

    private fun flagTripAsDeviated() {
        val booking = currentBooking ?: return
        val tripGroupId = booking.tripGroupId

        val deviationUpdates = hashMapOf<String, Any>(
            "routeStatus" to "DEVIATED",
            "lastDeviatedLat" to (currentLatLng?.latitude ?: 0.0),
            "lastDeviatedLng" to (currentLatLng?.longitude ?: 0.0),
            "deviatedTimestamp" to System.currentTimeMillis()
        )

        if (!tripGroupId.isNullOrEmpty()) {
            database.child("bookings").orderByChild("tripGroupId").equalTo(tripGroupId)
                .addListenerForSingleValueEvent(object : ValueEventListener {
                    override fun onDataChange(snapshot: DataSnapshot) {
                        for (child in snapshot.children) {
                            child.ref.updateChildren(deviationUpdates)
                        }
                    }
                    override fun onCancelled(error: DatabaseError) {}
                })
        } else {
            activeBookingId?.let { id ->
                database.child("bookings").child(id).updateChildren(deviationUpdates)
            }
        }
    }

    private fun clearTripDeviation() {
        val booking = currentBooking ?: return
        val tripGroupId = booking.tripGroupId

        if (!tripGroupId.isNullOrEmpty()) {
            database.child("bookings").orderByChild("tripGroupId").equalTo(tripGroupId)
                .addListenerForSingleValueEvent(object : ValueEventListener {
                    override fun onDataChange(snapshot: DataSnapshot) {
                        for (child in snapshot.children) {
                            child.ref.child("routeStatus").setValue("NORMAL")
                        }
                    }
                    override fun onCancelled(error: DatabaseError) {}
                })
        } else {
            activeBookingId?.let { id ->
                database.child("bookings").child(id).child("routeStatus").setValue("NORMAL")
            }
        }
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
                        .color(Color.parseColor("#6D5EFC"))
                        .geodesic(true)

                    activePolyline = mMap.addPolyline(polylineOptions)
                }
            }
        }
    }

    private fun setupBookingActionListeners() {
        binding.btnAcceptRide.setOnClickListener {
            val bookingId = activeBookingId
            if (bookingId != null) {
                acceptBookingRequest(bookingId)
            }
            val driverId = auth.currentUser?.uid ?: return@setOnClickListener
            val tripGroupId = currentBooking?.tripGroupId
            if (!tripGroupId.isNullOrEmpty()) acceptRideGroup(driverId) else acceptCurrentRide()
        }
        binding.btnDeclineRide.setOnClickListener { declineCurrentRide() }
        binding.btnConfirmPickup.setOnClickListener { onConfirmStopTapped() }
        binding.btnCompleteRide.setOnClickListener { attemptCompleteRide() }
        binding.btnChatWithPassenger.setOnClickListener { openChatWithPassenger() }
    }

    private fun onConfirmStopTapped() {
        val booking = currentBooking ?: return
        if (booking.tripGroupId.isNullOrEmpty()) {
            confirmPassengerPickup()
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
                        .filter { it.status != "COMPLETED" && it.status != "CANCELLED" }

                    if (group.isEmpty()) {
                        updateUIForState(DriverRideState.IDLE)
                        clearRouteAndMarkers()
                        activeBookingId = null
                        currentBooking = null
                        return
                    }

                    if (group.size < 2) {
                        bindPassengerInfoToView(booking, "Shared Ride")
                        previewBookingRoute(booking)
                        return
                    }

                    val infoList = group.joinToString("\n---\n") { item ->
                        "• ${item.passengerName.ifEmpty { "Passenger" }}: Pickup at ${item.pickupAddress.ifEmpty { "N/A" }}"
                    }

                    val totalFare = group.sumOf { it.fare }

                    val totalCommission = group.sumOf { WalletManager.commissionFor(it.fare, it.hasDiscount) }
                    val totalNetEarnings = totalFare - totalCommission

                    binding.txtPassengerName.text = "Shared Ride (${group.size} Pax)"
                    binding.txtPickupLocation.text = infoList
                    binding.txtDropoffLocation.text = "Dropoffs: ${group.joinToString(" -> ") { it.dropoffAddress.ifEmpty { "N/A" } }}"

                    binding.txtEstimatedFare.text = String.format(
                        Locale.getDefault(), "Estimated Fare: ₱%.2f (combined)", totalFare
                    )

                    binding.txtWalletBalance.text = String.format(
                        Locale.getDefault(),
                        "Deduction: -₱%.2f • Take-Home: ₱%.2f",
                        totalCommission,
                        totalNetEarnings
                    )

                    previewGroupRoute(tripGroupId, group)
                }
                override fun onCancelled(error: DatabaseError) {}
            })
    }

    private fun previewGroupRoute(tripGroupId: String, group: List<Booking>) {
        if (::mMap.isInitialized) {
            mMap.clear()
            driverMarker = null
        }
        activePolyline?.remove()
        activePolyline = null

        val bounds = LatLngBounds.Builder()
        val candidates = group.map {
            RideshareManager.RideshareCandidate(
                it.bookingId, it.passengerId,
                LatLng(it.pickupLat, it.pickupLng), LatLng(it.dropoffLat, it.dropoffLng)
            )
        }
        val anchor = currentLatLng ?: candidates.first().pickup
        val sequencedStops = RideshareManager.Sequencer.sequenceStops(anchor, candidates)

        var pCount = 1
        var dCount = 1

        sequencedStops.forEach { stop ->
            val isPickup = stop.type == RideshareManager.StopType.PICKUP
            val tag = if (isPickup) "P${pCount++}" else "D${dCount++}"
            val color = if (isPickup) Color.parseColor("#4CAF50") else Color.parseColor("#E53935")

            mMap.addMarker(
                MarkerOptions()
                    .position(stop.location)
                    .title("Stop $tag")
                    .icon(MapMarkerUtils.createNumberedMarker(requireContext(), tag, color))
            )
            bounds.include(stop.location)
        }

        currentLatLng?.let { driverLoc ->
            driverMarker = mMap.addMarker(
                MarkerOptions()
                    .position(driverLoc)
                    .title("Your Location")
                    .icon(BitmapDescriptorFactory.defaultMarker(BitmapDescriptorFactory.HUE_AZURE))
            )
            bounds.include(driverLoc)
        }

        try {
            mMap.animateCamera(CameraUpdateFactory.newLatLngBounds(bounds.build(), 100))
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun updateUIForState(state: DriverRideState) {
        currentRideState = state
        val isTripActive = (state == DriverRideState.ACCEPTED || state == DriverRideState.IN_PROGRESS)

        binding.switchOnline.isEnabled = !isTripActive
        binding.btnDriverLogout.isEnabled = !isTripActive
        binding.btnDriverLogout.alpha = if (isTripActive) 0.5f else 1.0f

        when (state) {
            DriverRideState.IDLE -> {
                binding.cardIncomingRequest.visibility = View.GONE
                binding.btnAcceptRide.visibility = View.VISIBLE
                binding.btnDeclineRide.visibility = View.VISIBLE
                binding.btnConfirmPickup.visibility = View.GONE
                binding.btnCompleteRide.visibility = View.GONE
                binding.btnChatWithPassenger.visibility = View.GONE
            }
            DriverRideState.REQUEST_RECEIVED -> {
                binding.cardIncomingRequest.visibility = View.VISIBLE
                binding.btnAcceptRide.visibility = View.VISIBLE
                binding.btnDeclineRide.visibility = View.VISIBLE
                binding.btnConfirmPickup.visibility = View.GONE
                binding.btnCompleteRide.visibility = View.GONE
                binding.btnChatWithPassenger.visibility = View.GONE
            }
            DriverRideState.ACCEPTED -> {
                binding.cardIncomingRequest.visibility = View.VISIBLE
                binding.btnAcceptRide.visibility = View.GONE
                binding.btnDeclineRide.visibility = View.GONE
                binding.btnConfirmPickup.visibility = View.VISIBLE
                binding.btnCompleteRide.visibility = View.GONE
                binding.btnChatWithPassenger.visibility = View.VISIBLE
            }
            DriverRideState.IN_PROGRESS -> {
                binding.cardIncomingRequest.visibility = View.VISIBLE
                binding.btnAcceptRide.visibility = View.GONE
                binding.btnDeclineRide.visibility = View.GONE
                binding.btnConfirmPickup.visibility = View.GONE
                binding.btnCompleteRide.visibility = View.VISIBLE
                binding.btnChatWithPassenger.visibility = View.VISIBLE
            }
        }
    }

    private fun acceptBookingRequest(bookingId: String) {
        val currentDriverUid = auth.currentUser?.uid ?: return

        database.child("users").child(currentDriverUid).get().addOnSuccessListener { snapshot ->
            val firstName = snapshot.child("firstName").getValue(String::class.java) ?: ""
            val lastName = snapshot.child("lastName").getValue(String::class.java) ?: ""
            val driverName = "$firstName $lastName".trim().ifEmpty {
                snapshot.child("name").getValue(String::class.java) ?: "Driver"
            }

            val vehicleModel = snapshot.child("vehicleModel").getValue(String::class.java) ?: ""
            val plateNumber = snapshot.child("plateNumber").getValue(String::class.java) ?: ""
            val vehicleColor = snapshot.child("vehicleColor").getValue(String::class.java) ?: ""
            val driverPhone = snapshot.child("phoneNumber").getValue(String::class.java)
                ?: snapshot.child("phone").getValue(String::class.java) ?: ""

            val bookingUpdates = mapOf<String, Any?>(
                "status" to "ACCEPTED",
                "driverId" to currentDriverUid,
                "driverName" to driverName,
                "driverPhone" to driverPhone,
                "vehicleModel" to vehicleModel,
                "plateNumber" to plateNumber,
                "vehicleColor" to vehicleColor,
                "acceptedAt" to System.currentTimeMillis()
            )

            database.child("bookings").child(bookingId).updateChildren(bookingUpdates)
                .addOnSuccessListener {
                    Toast.makeText(requireContext(), "Ride accepted!", Toast.LENGTH_SHORT).show()
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

    private fun acceptRideGroup(driverId: String) {
        val booking = currentBooking ?: return
        val tripGroupId = booking.tripGroupId

        if (tripGroupId.isNullOrEmpty()) {
            database.child("bookings").child(booking.bookingId).child("driverId").setValue(driverId)
            database.child("bookings").child(booking.bookingId).child("status").setValue("ACCEPTED")
                .addOnSuccessListener {
                    autoConfirmedThisStop = false
                    updateUIForState(DriverRideState.ACCEPTED)
                }
            return
        }

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

                    loadGroupStopsLocally(tripGroupId) { stops ->
                        currentGroupStops = stops
                        updateCardForCurrentStop()
                        drawGroupRoutePreviewOnly(stops)
                    }
                }
                override fun onCancelled(error: DatabaseError) {
                    Toast.makeText(context, "Failed to accept trip group: ${error.message}", Toast.LENGTH_SHORT).show()
                }
            })
    }

    private fun loadGroupStopsLocally(
        tripGroupId: String,
        onReady: (List<RideshareManager.RideshareStop>) -> Unit
    ) {
        database.child("bookings").orderByChild("tripGroupId").equalTo(tripGroupId)
            .addListenerForSingleValueEvent(object : ValueEventListener {
                override fun onDataChange(snapshot: DataSnapshot) {
                    val group = snapshot.children.mapNotNull { it.getValue(Booking::class.java) }
                        .filter { it.status != "COMPLETED" && it.status != "CANCELLED" }

                    if (group.isEmpty()) {
                        finishSharedTrip(tripGroupId)
                        return
                    }
                    val candidates = group.map {
                        RideshareManager.RideshareCandidate(
                            it.bookingId, it.passengerId,
                            LatLng(it.pickupLat, it.pickupLng), LatLng(it.dropoffLat, it.dropoffLng)
                        )
                    }
                    val anchor = currentLatLng ?: candidates.first().pickup
                    val sequencedStops = RideshareManager.Sequencer.sequenceStops(anchor, candidates)

                    val bookingsMap = group.associateBy { it.bookingId }
                    syncSequenceToFirebase(tripGroupId, sequencedStops, bookingsMap)

                    onReady(sequencedStops)
                }
                override fun onCancelled(error: DatabaseError) {}
            })
    }

    private fun syncSequenceToFirebase(
        tripGroupId: String,
        sortedStops: List<RideshareManager.RideshareStop>,
        bookingsMap: Map<String, Booking> = emptyMap()
    ) {
        var pCount = 1
        var dCount = 1

        val sequenceData = sortedStops.mapIndexed { index, stop ->
            val isPickup = stop.type == RideshareManager.StopType.PICKUP
            val label = if (isPickup) "P${pCount++}" else "D${dCount++}"
            val passengerId = bookingsMap[stop.bookingId]?.passengerId ?: ""

            mapOf(
                "bookingId" to stop.bookingId,
                "passengerId" to passengerId,
                "label" to label,
                "sequenceOrder" to index + 1,
                "isPickup" to isPickup
            )
        }

        database.child("tripGroups").child(tripGroupId).child("stops").setValue(sequenceData)
    }

    private fun drawGroupRoutePreviewOnly(stops: List<RideshareManager.RideshareStop>) {
        val driverLoc = currentLatLng ?: return
        val apiKey = getString(R.string.google_maps_key)

        executor.execute {
            val remainingStops = if (currentStopIndex in stops.indices) stops.subList(currentStopIndex, stops.size) else stops
            val finalRoute = RideshareRouteOptimizer.buildFinalDriverRoute(driverLoc, remainingStops, apiKey)
            activity?.runOnUiThread {
                if (::mMap.isInitialized) {
                    mMap.clear()
                    driverMarker = null
                }

                if (finalRoute != null) {
                    rawRoutePoints = finalRoute.polyline
                    activePolyline?.remove()
                    activePolyline = mMap.addPolyline(
                        PolylineOptions()
                            .addAll(finalRoute.polyline)
                            .width(12f)
                            .color(ContextCompat.getColor(requireContext(), R.color.ruta_primary_dark))
                    )
                }

                var pickupCount = 1
                var dropoffCount = 1

                stops.forEach { stop ->
                    val isPickup = stop.type == RideshareManager.StopType.PICKUP
                    val tag = if (isPickup) "P${pickupCount++}" else "D${dropoffCount++}"
                    val color = if (isPickup) Color.parseColor("#4CAF50") else Color.parseColor("#E53935")

                    val marker = mMap.addMarker(
                        MarkerOptions()
                            .position(stop.location)
                            .title("$tag: ${if (isPickup) "Pickup" else "Dropoff"}")
                            .icon(MapMarkerUtils.createNumberedMarker(requireContext(), tag, color))
                    )
                    marker?.showInfoWindow()
                }

                driverMarker = mMap.addMarker(
                    MarkerOptions()
                        .position(driverLoc)
                        .title("Your Location")
                        .icon(BitmapDescriptorFactory.defaultMarker(BitmapDescriptorFactory.HUE_AZURE))
                )
            }
        }
    }

    private fun drawDriverToPickupRoute(booking: Booking) {
        val driverLoc = currentLatLng ?: return
        val pickupLatLng = LatLng(booking.pickupLat, booking.pickupLng)

        pickupMarker = mMap.addMarker(
            MarkerOptions()
                .position(pickupLatLng)
                .title("Pickup Point (P1)")
                .icon(MapMarkerUtils.createNumberedMarker(requireContext(), "P1", Color.parseColor("#4CAF50")))
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
            MarkerOptions()
                .position(dropoffLatLng)
                .title("Dropoff Point (D1)")
                .icon(MapMarkerUtils.createNumberedMarker(requireContext(), "D1", Color.parseColor("#E53935")))
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

    private fun confirmSharedStop(isAuto: Boolean = false) {
        val stops = currentGroupStops
        if (stops.isEmpty()) {
            val tripGroupId = currentBooking?.tripGroupId
            if (!tripGroupId.isNullOrEmpty()) {
                loadGroupStopsLocally(tripGroupId) { rebuilt ->
                    currentGroupStops = rebuilt
                    currentStopIndex = 0
                    updateCardForCurrentStop()
                }
            } else {
                Toast.makeText(context, "No shared route loaded — try re-accepting the ride.", Toast.LENGTH_LONG).show()
            }
            return
        }
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

        val newStatus = if (stop.type == RideshareManager.StopType.PICKUP) "IN_PROGRESS" else "COMPLETED"
        database.child("bookings").child(stop.bookingId).child("status").setValue(newStatus)
            .addOnSuccessListener {
                advanceToNextStop()
            }
            .addOnFailureListener { err ->
                Toast.makeText(context, "Failed to update status: ${err.message}", Toast.LENGTH_SHORT).show()
            }
    }

    private fun advanceToNextStop() {
        autoConfirmedThisStop = false
        currentStopIndex++

        val tripGroupId = currentBooking?.tripGroupId ?: return

        database.child("tripGroups").child(tripGroupId).child("currentStopIndex").setValue(currentStopIndex)
            .addOnCompleteListener {
                if (currentStopIndex >= currentGroupStops.size) {
                    database.child("bookings").orderByChild("tripGroupId").equalTo(tripGroupId)
                        .addListenerForSingleValueEvent(object : ValueEventListener {
                            override fun onDataChange(snapshot: DataSnapshot) {
                                val activeBookingsExist = snapshot.children.any { child ->
                                    val status = child.child("status").getValue(String::class.java)
                                    status != "COMPLETED" && status != "CANCELLED"
                                }

                                if (!activeBookingsExist) {
                                    finishSharedTrip(tripGroupId)
                                } else {
                                    loadGroupStopsLocally(tripGroupId) { refreshedStops ->
                                        currentGroupStops = refreshedStops
                                        updateCardForCurrentStop()
                                        if (currentGroupStops.isNotEmpty()) {
                                            drawGroupRoutePreviewOnly(currentGroupStops)
                                        }
                                    }
                                }
                            }

                            override fun onCancelled(error: DatabaseError) {
                                finishSharedTrip(tripGroupId)
                            }
                        })
                } else {
                    updateCardForCurrentStop()
                    if (currentGroupStops.isNotEmpty()) {
                        drawGroupRoutePreviewOnly(currentGroupStops)
                    }
                }
            }
    }

    private fun updateCardForCurrentStop() {
        val stops = currentGroupStops
        if (currentStopIndex !in stops.indices) return
        val stop = stops[currentStopIndex]
        val isPickup = stop.type == RideshareManager.StopType.PICKUP
        val label = if (isPickup) "Pickup" else "Dropoff"

        database.child("bookings").child(stop.bookingId).get().addOnSuccessListener { snap ->
            val status = snap.child("status").getValue(String::class.java) ?: ""
            if (status == "COMPLETED" || status == "CANCELLED") {
                advanceToNextStop()
                return@addOnSuccessListener
            }

            val name = snap.child("passengerName").getValue(String::class.java)?.ifEmpty { null } ?: "Passenger"
            val passengerId = snap.child("passengerId").getValue(String::class.java) ?: ""
            val address = if (isPickup)
                snap.child("pickupAddress").getValue(String::class.java) ?: "N/A"
            else
                snap.child("dropoffAddress").getValue(String::class.java) ?: "N/A"

            if (passengerId.isNotEmpty()) {
                database.child("users").child(passengerId).get().addOnSuccessListener { userSnap ->
                    val phone = userSnap.child("phone").value?.toString()
                        ?: userSnap.child("phoneNumber").value?.toString()
                        ?: ""
                    val phoneText = if (phone.isNotEmpty()) "\nContact: $phone" else ""
                    binding.txtPassengerName.text = "$name (Stop ${currentStopIndex + 1} of ${stops.size})$phoneText"
                }
            } else {
                binding.txtPassengerName.text = "$name — Stop ${currentStopIndex + 1} of ${stops.size}"
            }

            binding.txtPickupLocation.text = if (isPickup) "Pickup: $address" else ""
            binding.txtDropoffLocation.text = if (!isPickup) "Dropoff: $address" else ""
        }

        binding.btnAcceptRide.visibility = View.GONE
        binding.btnDeclineRide.visibility = View.GONE
        binding.btnCompleteRide.visibility = View.GONE
        binding.btnConfirmPickup.visibility = View.VISIBLE
        binding.btnConfirmPickup.text = "Confirm $label"
        binding.btnChatWithPassenger.visibility = View.VISIBLE
    }

    private fun finishSharedTrip(tripGroupId: String) {
        calculateAndSaveSharedFares(tripGroupId)
        Toast.makeText(context, "Ride Completed!", Toast.LENGTH_SHORT).show()

        stopListeningForRequests()
        clearRouteAndMarkers()
        activeBookingId = null
        currentBooking = null
        currentGroupStops = emptyList()
        currentStopIndex = 0
        updateUIForState(DriverRideState.IDLE)
        listenForPendingRideRequests()
    }

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
        val booking = currentBooking
        val driverId = auth.currentUser?.uid

        database.child("bookings").child(bookingId).child("status").setValue("COMPLETED")
            .addOnSuccessListener {
                if (booking != null && driverId != null) {
                    WalletManager.deductCommission(database, driverId, booking.fare, booking.hasDiscount)
                }
                Toast.makeText(context, "Ride Completed!", Toast.LENGTH_SHORT).show()
                stopListeningForRequests()
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
                    if (group.size != 2) return

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

                        val driverId = auth.currentUser?.uid

                        listOf(a to breakdown.passengerA, b to breakdown.passengerB).forEach { (bookingObj, f) ->
                            database.child("bookings").child(f.bookingId).updateChildren(
                                mapOf(
                                    "finalFare" to f.total,
                                    "fareSoloCharge" to f.soloCharge,
                                    "fareSharedCharge" to f.sharedCharge,
                                    "fareDetourSurcharge" to f.detourSurcharge,
                                    "fareInconvenienceCredit" to f.inconvenienceCredit
                                )
                            )
                            if (driverId != null) {
                                WalletManager.deductCommission(database, driverId, f.total, bookingObj.hasDiscount)
                            }
                        }
                    }
                }

                override fun onCancelled(error: DatabaseError) {}
            })
    }

    private fun declineCurrentRide() {
        stopListeningForRequests()
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

        if (::mMap.isInitialized) {
            mMap.clear()

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

                val driverLoc = currentLatLng ?: return

                val eligible = mutableListOf<Booking>()
                for (child in snapshot.children) {
                    val status = child.child("status").getValue(String::class.java)
                    if (status == "REQUESTED" || status == "MATCHED") {
                        child.getValue(Booking::class.java)?.let { eligible.add(it) }
                    }
                }

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
                            bindPassengerInfoToView(nearestBooking)
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
            MarkerOptions().position(pickupLatLng).title("Pickup Point (P1)")
                .icon(MapMarkerUtils.createNumberedMarker(requireContext(), "P1", Color.parseColor("#4CAF50")))
        )
        dropoffMarker = mMap.addMarker(
            MarkerOptions().position(dropoffLatLng).title("Dropoff Point (D1)")
                .icon(MapMarkerUtils.createNumberedMarker(requireContext(), "D1", Color.parseColor("#E53935")))
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
            pendingRequestsListener = null
        }
        activeBookingListener?.let {
            activeBookingId?.let { id -> database.child("bookings").child(id).removeEventListener(it) }
            activeBookingListener = null
        }
    }

    private fun monitorSingleBookingForCancellation(bookingId: String) {
        activeBookingListener?.let {
            database.child("bookings").child(bookingId).removeEventListener(it)
        }

        val singleBookingRef = database.child("bookings").child(bookingId)

        activeBookingListener = object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                val status = snapshot.child("status").getValue(String::class.java) ?: return

                if (status == "CANCELLED" || status == "COMPLETED") {
                    if (status == "CANCELLED") {
                        context?.let {
                            Toast.makeText(it, "Passenger cancelled the ride request.", Toast.LENGTH_LONG).show()
                        }
                    }
                    stopListeningForRequests()
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