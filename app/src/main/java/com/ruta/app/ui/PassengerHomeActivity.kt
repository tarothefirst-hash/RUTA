package com.ruta.app.ui

import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.viewpager2.widget.ViewPager2
import com.google.android.material.button.MaterialButton
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.database.DataSnapshot
import com.google.firebase.database.DatabaseError
import com.google.firebase.database.FirebaseDatabase
import com.google.firebase.database.ValueEventListener
import com.ruta.app.R
import com.ruta.app.util.RouteDeviationManager

class PassengerHomeActivity : AppCompatActivity() {

    private lateinit var viewPager: ViewPager2
    private lateinit var navHome: ImageView
    private lateinit var navPromos: ImageView
    private lateinit var navSettings: ImageView
    private lateinit var btnProfile: ImageView

    private lateinit var cardRouteDeviationWarning: View
    private lateinit var btnDismissWarning: MaterialButton
    private lateinit var btnSosEmergency: MaterialButton

    private var activeBookingListener: ValueEventListener? = null
    private var activeBookingId: String? = null
    private var hasFiredNotification = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_passenger_home)

        requestNotificationPermission()
        initViews()
        setupViewPager()
        setupClickListeners()
        listenForActivePassengerTrip()

        // Handle activity launch via notification click
        intent?.getStringExtra("BOOKING_ID")?.let { bookingId ->
            cardRouteDeviationWarning.visibility = View.VISIBLE
        }
    }

    private fun requestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, android.Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED) {

                ActivityCompat.requestPermissions(
                    this,
                    arrayOf(android.Manifest.permission.POST_NOTIFICATIONS),
                    101
                )
            }
        }
    }

    private fun initViews() {
        viewPager = findViewById(R.id.viewPager)
        navHome = findViewById(R.id.navHome)
        navPromos = findViewById(R.id.navPromos)
        navSettings = findViewById(R.id.navSettings)
        btnProfile = findViewById(R.id.btnProfile)

        // Binds directly to the FrameLayout overlay container in the XML layout
        cardRouteDeviationWarning = findViewById(R.id.cardRouteDeviationWarning)
        btnDismissWarning = findViewById(R.id.btnDismissWarning)
        btnSosEmergency = findViewById(R.id.btnSosEmergency)
    }

    private fun setupViewPager() {
        val adapter = PassengerPagerAdapter(this)
        viewPager.adapter = adapter

        viewPager.registerOnPageChangeCallback(object : ViewPager2.OnPageChangeCallback() {
            override fun onPageSelected(position: Int) {
                super.onPageSelected(position)
                when (position) {
                    0 -> updateActiveTab(navHome)
                    1 -> updateActiveTab(navPromos)
                    2 -> updateActiveTab(navSettings)
                    3 -> updateActiveTab(btnProfile)
                }
            }
        })
    }

    private fun setupClickListeners() {
        navHome.setOnClickListener { viewPager.setCurrentItem(0, true) }
        navPromos.setOnClickListener { viewPager.setCurrentItem(1, true) }
        navSettings.setOnClickListener { viewPager.setCurrentItem(2, true) }
        btnProfile.setOnClickListener { viewPager.setCurrentItem(3, true) }

        // "I'm Safe" Dismiss Button
        btnDismissWarning.setOnClickListener {
            activeBookingId?.let { id ->
                FirebaseDatabase.getInstance().reference
                    .child("bookings")
                    .child(id)
                    .child("routeStatus")
                    .setValue("NORMAL")
            }
            cardRouteDeviationWarning.visibility = View.GONE
            hasFiredNotification = false
        }

        // Emergency SOS Button
        btnSosEmergency.setOnClickListener {
            val intent = Intent(Intent.ACTION_DIAL).apply {
                data = Uri.parse("tel:911")
            }
            startActivity(intent)
        }
    }

    private fun updateActiveTab(selectedTab: ImageView) {
        val navTabs = listOf(navHome, navPromos, navSettings, btnProfile)
        for (tab in navTabs) {
            if (tab == selectedTab) {
                tab.setColorFilter(ContextCompat.getColor(this, R.color.ruta_primary))
            } else {
                tab.setColorFilter(ContextCompat.getColor(this, R.color.ruta_text_muted))
            }
        }
    }

    private fun listenForActivePassengerTrip() {
        val currentUserId = FirebaseAuth.getInstance().currentUser?.uid ?: return
        val bookingsRef = FirebaseDatabase.getInstance().reference.child("bookings")

        bookingsRef.addValueEventListener(object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                for (child in snapshot.children) {
                    val passengerId = child.child("passengerId").getValue(String::class.java)
                    val status = child.child("status").getValue(String::class.java)

                    if (passengerId == currentUserId && (status == "ACCEPTED" || status == "IN_PROGRESS")) {
                        val bookingId = child.key ?: return
                        if (activeBookingId != bookingId) {
                            activeBookingId = bookingId
                            monitorRouteDeviationForBooking(bookingId)
                        }
                        return
                    }
                }
            }

            override fun onCancelled(error: DatabaseError) {}
        })
    }

    private fun monitorRouteDeviationForBooking(bookingId: String) {
        val bookingRef = FirebaseDatabase.getInstance().reference.child("bookings").child(bookingId)

        activeBookingListener = object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                val routeStatus = snapshot.child("routeStatus").getValue(String::class.java)

                if (routeStatus == "DEVIATED") {
                    cardRouteDeviationWarning.visibility = View.VISIBLE

                    if (!hasFiredNotification) {
                        hasFiredNotification = true
                        RouteDeviationManager.sendDeviationNotification(this@PassengerHomeActivity, bookingId)
                    }
                } else {
                    cardRouteDeviationWarning.visibility = View.GONE
                    hasFiredNotification = false
                }
            }

            override fun onCancelled(error: DatabaseError) {}
        }

        bookingRef.addValueEventListener(activeBookingListener!!)
    }

    override fun onDestroy() {
        super.onDestroy()
        activeBookingId?.let { id ->
            activeBookingListener?.let { listener ->
                FirebaseDatabase.getInstance().reference.child("bookings").child(id).removeEventListener(listener)
            }
        }
    }
}