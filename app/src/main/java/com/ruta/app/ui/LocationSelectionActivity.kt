package com.ruta.app.ui

import android.Manifest
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
import com.google.android.gms.maps.model.PolylineOptions
import com.google.android.libraries.places.api.Places
import com.google.android.libraries.places.api.model.AutocompletePrediction
import com.google.android.libraries.places.api.net.FindAutocompletePredictionsRequest
import com.google.android.libraries.places.api.net.PlacesClient
import com.ruta.app.R
import com.ruta.app.model.LocationItem
import org.json.JSONObject
import java.util.Locale

class LocationSelectionActivity : AppCompatActivity(), OnMapReadyCallback {

    private lateinit var mMap: GoogleMap
    private lateinit var fusedLocationClient: FusedLocationProviderClient
    private lateinit var placesClient: PlacesClient

    private lateinit var etSearchLocation: EditText
    private lateinit var rvSuggestions: RecyclerView
    private lateinit var txtPickupDisplay: TextView
    private lateinit var txtDropoffDisplay: TextView
    private lateinit var btnConfirmStage: Button

    private enum class SelectionStage { PICKUP, DROPOFF, ROUTE_READY }
    private var currentStage = SelectionStage.PICKUP

    private var pickupLatLng: LatLng? = null
    private var dropoffLatLng: LatLng? = null
    private var pickupAddress: String = "Detecting location..."
    private var dropoffAddress: String = "Add Location"

    private var activeMarker: Marker? = null
    private var pickupMarker: Marker? = null

    private var isProgrammaticTextUpdate = false

    private val placesApiKey = "AIzaSyA_aZwg2zvItoG12d_kmMtPZGB0f8PxChk"
    private val locationPermissionRequestCode = 1001

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_location_selection)

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

        findViewById<View>(R.id.btnReturn)?.setOnClickListener { finish() }

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
            if (currentStage != SelectionStage.ROUTE_READY) {
                activeMarker?.position = latLng
                reverseGeocodeLocation(latLng)
            }
        }

        checkLocationPermissionAndFetch()
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
        val defaultLoc = LatLng(15.4855, 120.5920) // Tarlac City
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
                    Toast.makeText(this, "Proceeding to driver match...", Toast.LENGTH_SHORT).show()
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

        val url = "https://maps.googleapis.com/maps/api/directions/json?" +
                "origin=${origin.latitude},${origin.longitude}" +
                "&destination=${destination.latitude},${destination.longitude}" +
                "&key=$placesApiKey"

        Thread {
            val jsonData = DirectionsHelper.downloadUrl(url)
            val routePoints = DirectionsHelper.parseDirections(jsonData)
            val distanceMeters = parseDistanceMeters(jsonData)
            val calculatedFare = calculateFare(distanceMeters)

            runOnUiThread {
                if (routePoints.isNotEmpty()) {
                    mMap.addPolyline(
                        PolylineOptions()
                            .addAll(routePoints)
                            .width(14f)
                            .color(Color.RED)
                            .geodesic(true)
                    )
                }

                btnConfirmStage.text = "Book Ride - ₱${calculatedFare.toInt()}"
            }
        }.start()
    }

    private fun parseDistanceMeters(jsonData: String): Int {
        return try {
            val jsonObject = JSONObject(jsonData)
            val routes = jsonObject.getJSONArray("routes")
            if (routes.length() > 0) {
                val legs = routes.getJSONObject(0).getJSONArray("legs")
                legs.getJSONObject(0).getJSONObject("distance").getInt("value")
            } else 3000
        } catch (e: Exception) {
            3000
        }
    }

    private fun calculateFare(distanceMeters: Int): Double {
        val distanceKm = distanceMeters / 1000.0
        val baseFare = 50.0
        val perKmRate = 15.0

        return if (distanceKm <= 2.0) baseFare else baseFare + ((distanceKm - 2.0) * perKmRate)
    }
}