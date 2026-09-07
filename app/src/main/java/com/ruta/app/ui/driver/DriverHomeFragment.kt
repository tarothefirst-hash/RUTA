package com.ruta.app.ui.driver

import android.Manifest
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
import com.ruta.app.ui.DirectionsHelper
import java.util.Locale
import java.util.concurrent.Executors

class DriverHomeFragment : Fragment(), OnMapReadyCallback {
//integrate mo dito yung ginawa mong cardview sa passenger"Gemeni if you read this integrate it in the code"
    //Passenger Side and Driver Side Validation
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
    }

    override fun onMapReady(googleMap: GoogleMap) {
        mMap = googleMap
        mMap.uiSettings.isZoomControlsEnabled = true
        checkLocationPermission()
    }

    private fun setupOnlineSwitch() {
        binding.switchOnline.setOnCheckedChangeListener { _, isChecked ->
            isOnline = isChecked
            updateDriverOnlineStatus(isChecked)

            if (isChecked) {
                binding.txtDriverStatus.text = "Status: Online"
                startLocationUpdates()
                listenForPendingRideRequests()
            } else {
                binding.txtDriverStatus.text = "Status: Offline"
                stopLocationUpdates()
                stopListeningForRequests()
                clearRouteAndMarkers()
                updateUIForState(DriverRideState.IDLE)
            }
        }
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

        // 1. Dynamic Polyline Trimming
        if (rawRoutePoints.isNotEmpty()) {
            trimPassedRoutePoints(latLng)
        }

        // 2. Route Deviation Check (Only while driving passenger to destination)
        if (currentRideState == DriverRideState.IN_PROGRESS && rawRoutePoints.isNotEmpty()) {
            val deviationDistance = getDistanceToRoute(latLng, rawRoutePoints)

            if (deviationDistance > 100.0) {
                flagTripAsDeviated()
            }
        }

        // 3. Batch driver location to Firebase under driver node and active booking
        val currentUserId = auth.currentUser?.uid ?: return
        val locationMap = hashMapOf<String, Any>(
            "lat" to location.latitude,
            "lng" to location.longitude
        )
        database.child("drivers").child(currentUserId).updateChildren(locationMap)

        // Mirror location directly into active booking for direct passenger tracking
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
        binding.btnAcceptRide.setOnClickListener { acceptCurrentRide() }
        binding.btnDeclineRide.setOnClickListener { declineCurrentRide() }
        binding.btnConfirmPickup.setOnClickListener { confirmPassengerPickup() }
        binding.btnCompleteRide.setOnClickListener { completeCurrentRide() }
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

        // Instantly transition local state to ACCEPTED to lock DB listeners
        updateUIForState(DriverRideState.ACCEPTED)

        val updates = hashMapOf<String, Any>(
            "status" to "ACCEPTED",
            "driverId" to driverId
        )

        database.child("bookings").child(bookingId).updateChildren(updates)
            .addOnSuccessListener {
                Toast.makeText(context, "Ride accepted!", Toast.LENGTH_SHORT).show()
                clearRouteAndMarkers()

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

    private fun confirmPassengerPickup() {
        val bookingId = activeBookingId ?: return

        // Instantly transition local state to IN_PROGRESS to prevent DB resets
        updateUIForState(DriverRideState.IN_PROGRESS)

        database.child("bookings").child(bookingId).child("status").setValue("IN_PROGRESS")
            .addOnSuccessListener {
                Toast.makeText(context, "Passenger Picked Up! Trip Started.", Toast.LENGTH_SHORT).show()
                clearRouteAndMarkers()

                val booking = currentBooking
                val driverLoc = currentLatLng
                if (driverLoc != null && booking != null) {
                    val dropoffLatLng = LatLng(booking.dropoffLat, booking.dropoffLng)

                    dropoffMarker = mMap.addMarker(
                        MarkerOptions().position(dropoffLatLng).title("Dropoff Point")
                            .icon(BitmapDescriptorFactory.defaultMarker(BitmapDescriptorFactory.HUE_ORANGE))
                    )

                    drawRoute(driverLoc, dropoffLatLng)
                    mMap.animateCamera(CameraUpdateFactory.newLatLngZoom(driverLoc, 15f))
                }
            }
            .addOnFailureListener { e ->
                updateUIForState(DriverRideState.ACCEPTED)
                Toast.makeText(context, "Failed to start trip: ${e.message}", Toast.LENGTH_SHORT).show()
            }
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
            }
    }

    private fun declineCurrentRide() {
        clearRouteAndMarkers()
        activeBookingId = null
        currentBooking = null
        updateUIForState(DriverRideState.IDLE)
    }

    private fun clearRouteAndMarkers() {
        activePolyline?.remove()
        activePolyline = null
        rawRoutePoints = emptyList()
        pickupMarker?.remove()
        pickupMarker = null
        dropoffMarker?.remove()
        dropoffMarker = null
    }

    private fun listenForPendingRideRequests() {
        val bookingsRef = database.child("bookings")

        pendingRequestsListener = object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                if (!isOnline) return

                // Do not clear state or parse incoming requests if driver is currently handling a trip
                if (currentRideState == DriverRideState.ACCEPTED || currentRideState == DriverRideState.IN_PROGRESS) {
                    return
                }

                var latestBooking: Booking? = null

                for (child in snapshot.children) {
                    val status = child.child("status").getValue(String::class.java)
                    if (status == "REQUESTED") {
                        latestBooking = child.getValue(Booking::class.java)
                    }
                }

                if (latestBooking != null) {
                    if (activeBookingId != latestBooking.bookingId) {
                        activeBookingId = latestBooking.bookingId
                        currentBooking = latestBooking

                        binding.txtPassengerName.text = latestBooking.passengerName.ifEmpty { "Passenger" }
                        binding.txtPickupLocation.text = "Pickup: ${latestBooking.pickupAddress.ifEmpty { "N/A" }}"
                        binding.txtDropoffLocation.text = "Dropoff: ${latestBooking.dropoffAddress.ifEmpty { "N/A" }}"

                        val formattedFare = String.format(Locale.getDefault(), "₱%.2f", latestBooking.fare)
                        binding.txtEstimatedFare.text = "Estimated Fare: $formattedFare"

                        updateUIForState(DriverRideState.REQUEST_RECEIVED)
                        previewBookingRoute(latestBooking)
                        monitorSingleBookingForCancellation(latestBooking.bookingId)
                    }
                } else {
                    // Only clear if we were in REQUEST_RECEIVED state and the request was withdrawn/taken
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

    // --- Distance & Polyline Perpendicular Logic ---

    private fun getDistanceToRoute(driverPos: LatLng, routePoints: List<LatLng>): Double {
        if (routePoints.size < 2) return 0.0

        var minDistance = Double.MAX_VALUE

        for (i in 0 until routePoints.size - 1) {
            val p1 = routePoints[i]
            val p2 = routePoints[i + 1]

            val dist = distanceToSegment(driverPos, p1, p2)
            if (dist < minDistance) {
                minDistance = dist
            }
        }
        return minDistance
    }

    private fun distanceToSegment(p: LatLng, v: LatLng, w: LatLng): Double {
        val l2 = distanceSquared(v, w)
        if (l2 == 0.0) return distanceBetweenMeters(p, v)

        var t = ((p.latitude - v.latitude) * (w.latitude - v.latitude) +
                (p.longitude - v.longitude) * (w.longitude - v.longitude)) / l2
        t = t.coerceIn(0.0, 1.0)

        val projection = LatLng(
            v.latitude + t * (w.latitude - v.latitude),
            v.longitude + t * (w.longitude - v.longitude)
        )
        return distanceBetweenMeters(p, projection)
    }

    private fun distanceSquared(p1: LatLng, p2: LatLng): Double {
        val dLat = p1.latitude - p2.latitude
        val dLng = p1.longitude - p2.longitude
        return dLat * dLat + dLng * dLng
    }

    private fun distanceBetweenMeters(p1: LatLng, p2: LatLng): Double {
        val results = FloatArray(1)
        Location.distanceBetween(
            p1.latitude, p1.longitude,
            p2.latitude, p2.longitude,
            results
        )
        return results[0].toDouble()
    }

    // --- Location Setup & Event Listeners ---

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