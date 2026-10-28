package com.ruta.app.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.os.Looper
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
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
import com.google.android.gms.maps.model.RoundCap
import com.google.android.material.button.MaterialButton
import com.google.android.material.floatingactionbutton.FloatingActionButton
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.database.DataSnapshot
import com.google.firebase.database.DatabaseError
import com.google.firebase.database.DatabaseReference
import com.google.firebase.database.FirebaseDatabase
import com.google.firebase.database.ValueEventListener
import com.ruta.app.R
import com.ruta.app.model.Booking
import com.ruta.app.ui.DirectionsHelper
import com.ruta.app.util.MapsApi
import com.ruta.app.util.RouteDeviationManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import android.util.Log
data class TrackableTarget(val uid: String, val name: String, val isSelf: Boolean = false)

class LiveTrackingFragment : Fragment(), OnMapReadyCallback {

    private lateinit var mMap: GoogleMap
    private lateinit var fusedLocationClient: FusedLocationProviderClient
    private var myLocationPublisherCallback: LocationCallback? = null

    private lateinit var txtTrackedContactName: TextView
    private lateinit var txtTrackedContactStatus: TextView
    private lateinit var btnRefreshMap: MaterialButton

    private lateinit var btnMyLocation: FloatingActionButton
    private lateinit var btnPrevContact: ImageButton
    private lateinit var btnNextContact: ImageButton
    private lateinit var txtCurrentTargetIndicator: TextView

    private lateinit var cardDriverDetails: View
    private lateinit var txtDriverName: TextView
    private lateinit var txtVehicleInfo: TextView
    private lateinit var txtPlateNumber: TextView
    private lateinit var txtTripRouteInfo: TextView
    private lateinit var btnCallDriver: MaterialButton
    private lateinit var btnCloseDetails: ImageView

    private val database: DatabaseReference = FirebaseDatabase.getInstance().reference
    private val myUid: String? get() = FirebaseAuth.getInstance().currentUser?.uid
    private val placesApiKey: String by lazy { MapsApi.key(requireContext()) }

    private var targetsList = mutableListOf<TrackableTarget>()
    private var currentTargetIndex = 0

    private var targetBookingId: String? = null

    private var contactMarker: Marker? = null
    private var driverMarker: Marker? = null
    private var activeRoutePolyline: Polyline? = null
    private var currentRoutePoints = listOf<LatLng>()

    private var contactLocationRef: DatabaseReference? = null
    private var contactLocationListener: ValueEventListener? = null
    private var bookingRef: DatabaseReference? = null
    private var bookingListener: ValueEventListener? = null
    private var driverPhone: String? = null

    private var pickupLatLng: LatLng? = null
    private var dropoffLatLng: LatLng? = null

    companion object {
        fun newInstance(contactUid: String?, bookingId: String?): LiveTrackingFragment {
            val fragment = LiveTrackingFragment()
            val args = Bundle().apply {
                putString("EXTRA_CONTACT_UID", contactUid)
                putString("EXTRA_BOOKING_ID", bookingId)
            }
            fragment.arguments = args
            return fragment
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val initialContactUid = arguments?.getString("EXTRA_CONTACT_UID")
        targetBookingId = arguments?.getString("EXTRA_BOOKING_ID")

        myUid?.let { uid ->
            targetsList.add(TrackableTarget(uid, "Me", isSelf = true))
            if (!initialContactUid.isNullOrEmpty() && initialContactUid != uid) {
                targetsList.add(TrackableTarget(initialContactUid, "Trusted Contact"))
                currentTargetIndex = 1
            }
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        return inflater.inflate(R.layout.fragment_live_tracking, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        fusedLocationClient = LocationServices.getFusedLocationProviderClient(requireActivity())

        txtTrackedContactName = view.findViewById(R.id.txtTrackedContactName)
        txtTrackedContactStatus = view.findViewById(R.id.txtTrackedContactStatus)
        btnRefreshMap = view.findViewById(R.id.btnRefreshMap)

        btnMyLocation = view.findViewById(R.id.btnMyLocation)
        btnPrevContact = view.findViewById(R.id.btnPrevContact)
        btnNextContact = view.findViewById(R.id.btnNextContact)
        txtCurrentTargetIndicator = view.findViewById(R.id.txtCurrentTargetIndicator)

        cardDriverDetails = view.findViewById(R.id.cardDriverDetails)
        txtDriverName = view.findViewById(R.id.txtDriverName)
        txtVehicleInfo = view.findViewById(R.id.txtVehicleInfo)
        txtPlateNumber = view.findViewById(R.id.txtPlateNumber)
        txtTripRouteInfo = view.findViewById(R.id.txtTripRouteInfo)
        btnCallDriver = view.findViewById(R.id.btnCallDriver)
        btnCloseDetails = view.findViewById(R.id.btnCloseDetails)

        val mapFragment = childFragmentManager.findFragmentById(R.id.mapLiveTracking) as SupportMapFragment?
        mapFragment?.getMapAsync(this)

        btnCloseDetails.setOnClickListener { cardDriverDetails.visibility = View.GONE }

        btnCallDriver.setOnClickListener {
            val phone = driverPhone
            if (!phone.isNullOrEmpty()) {
                val intent = Intent(Intent.ACTION_DIAL, Uri.parse("tel:$phone"))
                startActivity(intent)
            } else {
                Toast.makeText(requireContext(), "Phone number not available.", Toast.LENGTH_SHORT).show()
            }
        }

        btnRefreshMap.setOnClickListener { centerMapOnActivePin() }

        btnMyLocation.setOnClickListener {
            val selfIndex = targetsList.indexOfFirst { it.isSelf }
            if (selfIndex != -1) {
                switchTarget(selfIndex)
            }
            centerMapOnSelfLocation()
        }

        btnPrevContact.setOnClickListener {
            if (targetsList.isNotEmpty()) {
                val prev = if (currentTargetIndex - 1 < 0) targetsList.size - 1 else currentTargetIndex - 1
                switchTarget(prev)
            }
        }

        btnNextContact.setOnClickListener {
            if (targetsList.isNotEmpty()) {
                val next = (currentTargetIndex + 1) % targetsList.size
                switchTarget(next)
            }
        }

        loadTrustedContactsList()
        startPublishingMyLiveLocation()
    }

    override fun onMapReady(googleMap: GoogleMap) {
        mMap = googleMap
        mMap.uiSettings.isZoomControlsEnabled = true

        enableMyLocationOnMap()

        mMap.setOnMarkerClickListener { marker ->
            if (marker == driverMarker || marker == contactMarker) {
                cardDriverDetails.visibility = View.VISIBLE
            }
            true
        }

        if (targetsList.isNotEmpty()) {
            switchTarget(currentTargetIndex)
        }
        startTrackingBookingAndDriver()
    }

    private fun enableMyLocationOnMap() {
        val permission = Manifest.permission.ACCESS_FINE_LOCATION
        if (ContextCompat.checkSelfPermission(requireContext(), permission) == PackageManager.PERMISSION_GRANTED) {
            mMap.isMyLocationEnabled = true
            mMap.uiSettings.isMyLocationButtonEnabled = false
            centerMapOnSelfLocation()
        }
    }

    private fun startPublishingMyLiveLocation() {
        val uid = myUid ?: return
        val permission = Manifest.permission.ACCESS_FINE_LOCATION
        if (ContextCompat.checkSelfPermission(requireContext(), permission) != PackageManager.PERMISSION_GRANTED) return

        val locationRequest = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, 3000)
            .setMinUpdateIntervalMillis(2000)
            .build()

        myLocationPublisherCallback = object : LocationCallback() {
            override fun onLocationResult(result: LocationResult) {
                val loc = result.lastLocation ?: return
                val updates = hashMapOf<String, Any>(
                    "lat" to loc.latitude,
                    "lng" to loc.longitude,
                    "timestamp" to System.currentTimeMillis()
                )
                database.child("users").child(uid).child("liveLocation").setValue(updates)
            }
        }

        fusedLocationClient.requestLocationUpdates(
            locationRequest,
            myLocationPublisherCallback!!,
            Looper.getMainLooper()
        )
    }

    private fun centerMapOnSelfLocation() {
        if (ContextCompat.checkSelfPermission(requireContext(), Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED) {
            fusedLocationClient.lastLocation.addOnSuccessListener { loc ->
                if (loc != null) {
                    val myPos = LatLng(loc.latitude, loc.longitude)
                    mMap.animateCamera(CameraUpdateFactory.newLatLngZoom(myPos, 16f))
                }
            }
        }
    }

    private var trustedContactsListener: ValueEventListener? = null

    /** Live list: a contact added (or removed) while this screen is open shows up straight away. */
    private fun loadTrustedContactsList() {
        val uid = myUid ?: return
        if (trustedContactsListener != null) return
        val initialUid = arguments?.getString("EXTRA_CONTACT_UID")
        val listener = object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                if (!isAdded) return
                val contactUids = snapshot.children.mapNotNull { it.key }.toSet()

                // Contacts that were removed disappear from the Next/Prev rotation.
                val current = targetsList.getOrNull(currentTargetIndex)
                targetsList.removeAll { !it.isSelf && it.uid !in contactUids && it.uid != initialUid }
                currentTargetIndex = targetsList.indexOfFirst { it.uid == current?.uid }.let { if (it < 0) 0 else it }

                contactUids.forEach { contactUid ->
                    if (targetsList.none { it.uid == contactUid }) {
                        database.child("users").child(contactUid).child("name").get()
                            .addOnSuccessListener { nameSnap ->
                                if (!isAdded || targetsList.any { it.uid == contactUid }) return@addOnSuccessListener
                                val name = nameSnap.getValue(String::class.java)?.ifEmpty { null } ?: "Trusted Contact"
                                targetsList.add(TrackableTarget(contactUid, name))
                            }
                    }
                }
            }
            override fun onCancelled(error: DatabaseError) {}
        }
        trustedContactsListener = listener
        database.child("users").child(uid).child("trustedContacts").addValueEventListener(listener)
    }

    private fun switchTarget(index: Int) {
        if (index !in targetsList.indices) return
        currentTargetIndex = index
        val target = targetsList[index]

        txtCurrentTargetIndicator.text = "Pin: ${target.name}"
        detachContactListener()

        if (target.isSelf) {
            txtTrackedContactName.text = "My Location"
            txtTrackedContactStatus.text = "Showing your live position"
            centerMapOnSelfLocation()
        } else {
            startTrackingTarget(target)
        }
    }

    private fun centerMapOnActivePin() {
        val pos = contactMarker?.position ?: driverMarker?.position
        if (pos != null) {
            mMap.animateCamera(CameraUpdateFactory.newLatLngZoom(pos, 16f))
        } else {
            centerMapOnSelfLocation()
        }
    }

    private fun startTrackingTarget(target: TrackableTarget) {
        txtTrackedContactName.text = target.name
        txtTrackedContactStatus.text = "Listening for live location..."

        database.child("users").child(target.uid).get().addOnSuccessListener { snapshot ->
            val name = snapshot.child("name").getValue(String::class.java) ?: target.name
            val isSharing = snapshot.child("isLiveSharingEnabled").getValue(Boolean::class.java) ?: true

            txtTrackedContactName.text = name

            if (!isSharing) {
                txtTrackedContactStatus.text = "Location sharing disabled by user."
                return@addOnSuccessListener
            }

            txtTrackedContactStatus.text = "Live location active"

            contactLocationRef = database.child("users").child(target.uid).child("liveLocation")
            contactLocationListener = object : ValueEventListener {
                override fun onDataChange(locSnap: DataSnapshot) {
                    val lat = locSnap.child("lat").getValue(Double::class.java)
                        ?: locSnap.child("latitude").getValue(Double::class.java)
                    val lng = locSnap.child("lng").getValue(Double::class.java)
                        ?: locSnap.child("longitude").getValue(Double::class.java)

                    if (lat == null || lng == null || (lat == 0.0 && lng == 0.0)) {
                        database.child("users").child(target.uid).get().addOnSuccessListener { userSnap ->
                            val uLat = userSnap.child("lat").getValue(Double::class.java)
                            val uLng = userSnap.child("lng").getValue(Double::class.java)
                            if (uLat != null && uLng != null && uLat != 0.0 && uLng != 0.0) {
                                updateContactMarker(LatLng(uLat, uLng), name)
                            } else {
                                database.child("drivers").child(target.uid).get().addOnSuccessListener { dSnap ->
                                    val dLat = dSnap.child("lat").getValue(Double::class.java)
                                    val dLng = dSnap.child("lng").getValue(Double::class.java)
                                    if (dLat != null && dLng != null && dLat != 0.0 && dLng != 0.0) {
                                        updateContactMarker(LatLng(dLat, dLng), name)
                                    } else {
                                        txtTrackedContactStatus.text = "Location not reported yet"
                                    }
                                }
                            }
                        }
                    } else {
                        updateContactMarker(LatLng(lat, lng), name)
                    }
                }

                override fun onCancelled(error: DatabaseError) {}
            }

            contactLocationRef?.addValueEventListener(contactLocationListener!!)
        }
    }

    private fun updateContactMarker(pos: LatLng, name: String) {
        if (!::mMap.isInitialized) return

        if (contactMarker == null) {
            contactMarker = mMap.addMarker(
                MarkerOptions()
                    .position(pos)
                    .title(name)
                    .icon(BitmapDescriptorFactory.defaultMarker(BitmapDescriptorFactory.HUE_VIOLET))
            )
            mMap.animateCamera(CameraUpdateFactory.newLatLngZoom(pos, 15f))
        } else {
            contactMarker?.position = pos
        }
    }

    private fun startTrackingBookingAndDriver() {
        var bookingId = targetBookingId

        if (bookingId.isNullOrEmpty()) {
            val uid = myUid ?: return

            database.child("bookings")
                .orderByChild("passengerId")
                .equalTo(uid)
                .get().addOnSuccessListener { snapshot ->
                    for (child in snapshot.children) {
                        val status = child.child("status").getValue(String::class.java)
                        if (status == "ACCEPTED" || status == "IN_PROGRESS" || status == "ARRIVED") {
                            bookingId = child.key
                            attachBookingListener(bookingId!!)
                            break
                        }
                    }
                }
        } else {
            attachBookingListener(bookingId!!)
        }
    }

    private var lastRerouteAt = 0L
    private var shownDriverPhotoFor: String? = null

    /** The driver's locked 1x1 photo, so the passenger can check who is coming. */
    private fun showDriverPhoto(driverId: String?, name: String) {
        val v = view ?: return
        val img = v.findViewById<android.widget.ImageView>(R.id.imgDriverPhoto) ?: return
        if (driverId.isNullOrEmpty() || shownDriverPhotoFor == driverId) return
        shownDriverPhotoFor = driverId
        val px = (72 * resources.displayMetrics.density).toInt()
        com.ruta.app.util.StoragePhotos.showAvatar(img, null, name.ifEmpty { "Driver" }, px)
        database.child("users").child(driverId).child("profilePhoto").get().addOnSuccessListener { s ->
            if (!isAdded) return@addOnSuccessListener
            com.ruta.app.util.StoragePhotos.showAvatar(img, s.getValue(String::class.java), name.ifEmpty { "Driver" }, px)
        }
    }

    private fun attachBookingListener(bId: String) {
        bookingRef = database.child("bookings").child(bId)
        bookingListener = object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                val booking = snapshot.getValue(Booking::class.java) ?: return

                txtDriverName.text = "Driver: ${booking.driverName.ifEmpty { "Assigned Driver" }}"
                showDriverPhoto(booking.driverId, booking.driverName)
                txtVehicleInfo.text = "Vehicle: ${booking.vehicleModel.ifEmpty { "N/A" }} (${booking.vehicleColor.ifEmpty { "Color N/A" }})"
                txtPlateNumber.text = "Plate Number: ${booking.plateNumber.ifEmpty { "N/A" }}"
                txtTripRouteInfo.text = "Route: ${booking.pickupAddress} ➔ ${booking.dropoffAddress}"
                driverPhone = booking.driverPhone

                val dLat = snapshot.child("driverLat").getValue(Double::class.java)
                val dLng = snapshot.child("driverLng").getValue(Double::class.java)
                val status = snapshot.child("status").getValue(String::class.java) ?: "ACCEPTED"
                val routeStatus = snapshot.child("routeStatus").getValue(String::class.java) ?: "NORMAL"

                if (booking.pickupLat != 0.0 && booking.pickupLng != 0.0) {
                    pickupLatLng = LatLng(booking.pickupLat, booking.pickupLng)
                }
                if (booking.dropoffLat != 0.0 && booking.dropoffLng != 0.0) {
                    dropoffLatLng = LatLng(booking.dropoffLat, booking.dropoffLng)
                }

                if (dLat != null && dLng != null && dLat != 0.0 && dLng != 0.0) {
                    val driverPos = LatLng(dLat, dLng)
                    if (driverMarker == null) {
                        driverMarker = mMap.addMarker(
                            MarkerOptions()
                                .position(driverPos)
                                .title("Driver: ${booking.driverName}")
                                .icon(BitmapDescriptorFactory.defaultMarker(BitmapDescriptorFactory.HUE_AZURE))
                        )
                    } else {
                        driverMarker?.position = driverPos
                    }

                    val destinationPos = if (status == "IN_PROGRESS") dropoffLatLng else pickupLatLng

                    // The line only changes when the driver's reroute was APPROVED (the driver
                    // stamps `reroutedAt`). While a deviation is pending or denied the
                    // original line stays, so the rider can see where the driver should be.
                    val reroutedAt = snapshot.child("reroutedAt").getValue(Long::class.java) ?: 0L
                    val rerouteApproved = reroutedAt > lastRerouteAt
                    if (destinationPos != null && (currentRoutePoints.isEmpty() || rerouteApproved)) {
                        lastRerouteAt = reroutedAt
                        recalculateAndRedrawRoute(driverPos, destinationPos)
                    }
                    activeRoutePolyline?.color = Color.parseColor(
                        if (routeStatus == "DEVIATED") "#E53935" else "#6D5EFC"
                    )
                }
            }

            override fun onCancelled(error: DatabaseError) {}
        }

        bookingRef?.addValueEventListener(bookingListener!!)
    }

    private fun recalculateAndRedrawRoute(origin: LatLng, destination: LatLng) {
        val url = DirectionsHelper.getDirectionsUrl(origin, destination, placesApiKey)

        lifecycleScope.launch(Dispatchers.IO) {
            val jsonData = DirectionsHelper.downloadUrl(url)
            val routePoints = DirectionsHelper.parseDirections(jsonData)

            withContext(Dispatchers.Main) {
                if (!::mMap.isInitialized) return@withContext

                activeRoutePolyline?.remove()

                if (routePoints.isNotEmpty()) {
                    currentRoutePoints = routePoints
                    activeRoutePolyline = mMap.addPolyline(
                        PolylineOptions()
                            .addAll(routePoints)
                            .width(14f)
                            .color(Color.parseColor("#6D5EFC"))
                            .startCap(RoundCap())
                            .endCap(RoundCap())
                            .geodesic(true)
                    )

                    val bounds = LatLngBounds.Builder()
                        .include(origin)
                        .include(destination)
                        .build()
                    mMap.animateCamera(CameraUpdateFactory.newLatLngBounds(bounds, 150))
                }
            }
        }
    }

    private fun detachContactListener() {
        contactLocationListener?.let { contactLocationRef?.removeEventListener(it) }
        contactLocationListener = null
        contactLocationRef = null
        contactMarker?.remove()
        contactMarker = null
    }

    override fun onResume() {
        super.onResume()
        // Permission may have been granted after this screen was built (for example in Settings).
        if (myLocationPublisherCallback == null) startPublishingMyLiveLocation()
        if (::mMap.isInitialized) enableMyLocationOnMap()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        detachListeners()
    }

    private fun detachListeners() {
        detachContactListener()

        myLocationPublisherCallback?.let { fusedLocationClient.removeLocationUpdates(it) }
        myLocationPublisherCallback = null

        trustedContactsListener?.let { l ->
            myUid?.let { database.child("users").child(it).child("trustedContacts").removeEventListener(l) }
        }
        trustedContactsListener = null

        bookingListener?.let { bookingRef?.removeEventListener(it) }
        bookingListener = null
        bookingRef = null

        driverMarker?.remove()
        driverMarker = null
        activeRoutePolyline?.remove()
        activeRoutePolyline = null
    }
}