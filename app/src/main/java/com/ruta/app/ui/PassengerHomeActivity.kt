package com.ruta.app.ui

import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.google.android.gms.maps.CameraUpdateFactory
import com.google.android.gms.maps.GoogleMap
import com.google.android.gms.maps.OnMapReadyCallback
import com.google.android.gms.maps.SupportMapFragment
import com.google.android.gms.maps.model.BitmapDescriptorFactory
import com.google.android.gms.maps.model.LatLng
import com.google.android.gms.maps.model.LatLngBounds
import com.google.android.gms.maps.model.MarkerOptions
import com.google.android.gms.maps.model.PolylineOptions
import com.google.android.libraries.places.api.Places
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.database.FirebaseDatabase
import com.ruta.app.R
import com.ruta.app.data.AuthRepository
import org.json.JSONObject

class PassengerHomeActivity : AppCompatActivity(), OnMapReadyCallback {

    private lateinit var mMap: GoogleMap

    // Default Starting Location (Macabulos, Tarlac City)
    private var currentPickupLoc = LatLng(15.4855, 120.5920)
    private var currentPickupAddress = "Macabulos, Tarlac City"

    // Default Selected Destination
    private var currentDropoffLoc = LatLng(15.4820, 120.5975)
    private var currentDropoffAddress = "SM Tarlac, Tarlac City"

    private var userHomeLoc: LatLng? = null
    private var userWorkLoc: LatLng? = null
    private var userFavoriteLoc: LatLng? = null

    private val placesApiKey = "AIzaSyA_aZwg2zvItoG12d_kmMtPZGB0f8PxChk"

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_passenger_home)

        // Initialize Places SDK
        if (!Places.isInitialized()) {
            Places.initialize(applicationContext, placesApiKey)
        }

        // Setup UI Listeners
        loadUserProfile()
        loadSavedLocationsFromFirebase()
        setupSavedLocationButtons()
        setupSearchBar()
        setupBookNowButton()

        // Initialize Map
        val mapFragment = supportFragmentManager
            .findFragmentById(R.id.map) as SupportMapFragment
        mapFragment.getMapAsync(this)
    }

    private fun setupSearchBar() {
        val layoutSearchBar = findViewById<LinearLayout>(R.id.layoutSearchBar)
        layoutSearchBar?.setOnClickListener {
            // Launches the custom screen with the inline suggestions list and map card
            val intent = Intent(this, LocationSelectionActivity::class.java)
            startActivity(intent)
        }
    }

    private fun loadUserProfile() {
        val currentUser = FirebaseAuth.getInstance().currentUser ?: return
        val userRef = FirebaseDatabase.getInstance().getReference("users").child(currentUser.uid)

        userRef.get().addOnSuccessListener { snapshot ->
            val firstName = snapshot.child("firstName").getValue(String::class.java)
            if (!firstName.isNullOrEmpty()) {
                val txtGreeting = findViewById<TextView>(R.id.txtGreeting)
                txtGreeting?.text = "Hi $firstName!"
            }
        }
    }

    private fun setupSavedLocationButtons() {
        val btnHome = findViewById<android.view.View>(R.id.btnHome)
        val btnWork = findViewById<android.view.View>(R.id.btnWork)
        val btnFavorite = findViewById<android.view.View>(R.id.btnFavorite)

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

            val distanceInMeters = parseDistanceMeters(jsonData)
            val calculatedFare = calculateFare(distanceInMeters)

            runOnUiThread {
                if (routePoints.isNotEmpty()) {
                    val lineOptions = PolylineOptions()
                        .addAll(routePoints)
                        .width(12f)
                        .color(Color.RED)
                        .geodesic(true)

                    mMap.addPolyline(lineOptions)
                }

                Toast.makeText(this, "Estimated Fare: ₱${calculatedFare.toInt()}", Toast.LENGTH_SHORT).show()
            }
        }.start()
    }

    private fun parseDistanceMeters(jsonData: String): Int {
        return try {
            val jsonObject = JSONObject(jsonData)
            val routes = jsonObject.getJSONArray("routes")
            if (routes.length() > 0) {
                val legs = routes.getJSONObject(0).getJSONArray("legs")
                if (legs.length() > 0) {
                    val distanceObj = legs.getJSONObject(0).getJSONObject("distance")
                    distanceObj.getInt("value")
                } else 0
            } else 0
        } catch (e: Exception) {
            3000
        }
    }

    private fun calculateFare(distanceMeters: Int): Double {
        val distanceKm = distanceMeters / 1000.0
        val baseFare = 50.0
        val perKmRate = 15.0

        return if (distanceKm <= 2.0) {
            baseFare
        } else {
            baseFare + ((distanceKm - 2.0) * perKmRate)
        }
    }

    private fun setupBookNowButton() {
        val btnBookNow = findViewById<TextView>(R.id.btnBookNow)
        btnBookNow?.setOnClickListener {
            Toast.makeText(this, "Searching for nearby RUTA drivers...", Toast.LENGTH_LONG).show()
        }
    }
}