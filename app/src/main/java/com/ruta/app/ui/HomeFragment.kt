package com.ruta.app.ui

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import androidx.fragment.app.Fragment
import com.google.android.gms.maps.CameraUpdateFactory
import com.google.android.gms.maps.GoogleMap
import com.google.android.gms.maps.OnMapReadyCallback
import com.google.android.gms.maps.SupportMapFragment
import com.google.android.gms.maps.model.LatLng
import com.google.android.gms.maps.model.MarkerOptions
import com.google.firebase.auth.FirebaseAuth
import com.ruta.app.R
import com.ruta.app.ui.LoginActivity

class HomeFragment : Fragment(), OnMapReadyCallback {

    private lateinit var mMap: GoogleMap

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View? {
        return inflater.inflate(R.layout.fragment_home, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        // 1. Initialize Google Map
        val mapFragment = childFragmentManager.findFragmentById(R.id.map) as SupportMapFragment?
        mapFragment?.getMapAsync(this)

        // 2. View References
        val txtGreeting = view.findViewById<TextView>(R.id.txtGreeting)
        val layoutSearchBar = view.findViewById<LinearLayout>(R.id.layoutSearchBar)

        // 3. Dynamic Name from Firebase Auth or SharedPreferences
        val firebaseUser = FirebaseAuth.getInstance().currentUser
        val sharedPref = requireContext().getSharedPreferences("USER_SESSION", Context.MODE_PRIVATE)

        val userName = firebaseUser?.displayName
            ?: sharedPref.getString("USER_NAME", null)
            ?: "Passenger"

        txtGreeting.text = "Hi $userName!"

        // 4. CLICK SEARCH BAR ➔ Open Location Selection Activity
        layoutSearchBar.setOnClickListener {
            val intent = Intent(requireContext(), LocationSelectionActivity::class.java)
            startActivity(intent)
        }
    }

    override fun onMapReady(googleMap: GoogleMap) {
        mMap = googleMap
        val tarlacCity = LatLng(15.4802, 120.5979)
        mMap.addMarker(MarkerOptions().position(tarlacCity).title("Tarlac City"))
        mMap.moveCamera(CameraUpdateFactory.newLatLngZoom(tarlacCity, 14f))
    }
}