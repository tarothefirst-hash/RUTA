package com.ruta.app.ui

import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.GravityCompat
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.database.DataSnapshot
import com.google.firebase.database.DatabaseError
import com.google.firebase.database.FirebaseDatabase
import com.google.firebase.database.ValueEventListener
import com.ruta.app.R
import com.ruta.app.databinding.ActivityAdminHomeBinding
import com.ruta.app.model.BookingModel
import com.ruta.app.model.User
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale

class AdminHomeActivity : AppCompatActivity() {

    private lateinit var binding: ActivityAdminHomeBinding
    private val database = FirebaseDatabase.getInstance().reference
    private val auth = FirebaseAuth.getInstance()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityAdminHomeBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setupHeader()
        setupNavigationDrawer()
        startDataListeners()

        binding.btnViewAllApprovals.setOnClickListener { showSection(R.id.nav_driver_approvals) }
        binding.btnAdminLogout.setOnClickListener { performLogout() }
    }

    private fun setupHeader() {
        val sdf = SimpleDateFormat("EEEE, d MMMM yyyy", Locale.US)
        binding.txtCurrentDate.text = sdf.format(Calendar.getInstance().time).uppercase()
        binding.txtGreeting.text = "Good morning, Admin."
    }

    private fun setupNavigationDrawer() {
        binding.btnMenu.setOnClickListener {
            binding.drawerLayout.openDrawer(GravityCompat.START)
        }

        val headerView = binding.navView.getHeaderView(0)
        val txtAdminName = headerView.findViewById<TextView>(R.id.txtAdminName)

        val currentUid = auth.currentUser?.uid
        if (currentUid != null) {
            database.child("users").child(currentUid).child("name").get().addOnSuccessListener {
                val name = it.getValue(String::class.java)
                if (!name.isNullOrEmpty()) {
                    txtAdminName.text = name
                }
            }
        }

        binding.navView.setNavigationItemSelectedListener { item ->
            showSection(item.itemId)
            binding.drawerLayout.closeDrawer(GravityCompat.START)
            true
        }
    }

    private fun showSection(id: Int) {
        // Hide all
        binding.layoutDashboard.visibility = View.GONE
        binding.layoutRiders.visibility = View.GONE
        binding.layoutApprovals.visibility = View.GONE
        binding.layoutTrips.visibility = View.GONE
        binding.layoutSettings.visibility = View.GONE

        when (id) {
            R.id.nav_dashboard -> {
                binding.layoutDashboard.visibility = View.VISIBLE
                binding.txtToolbarTitle.text = "Dashboard"
            }
            R.id.nav_riders -> {
                binding.layoutRiders.visibility = View.VISIBLE
                binding.txtToolbarTitle.text = "Rider Accounts"
            }
            R.id.nav_driver_approvals -> {
                binding.layoutApprovals.visibility = View.VISIBLE
                binding.txtToolbarTitle.text = "Driver Approvals"
            }
            R.id.nav_trips -> {
                binding.layoutTrips.visibility = View.VISIBLE
                binding.txtToolbarTitle.text = "Trips & Activity"
            }
            R.id.nav_settings -> {
                binding.layoutSettings.visibility = View.VISIBLE
                binding.txtToolbarTitle.text = "Settings"
            }
        }
    }

    private fun startDataListeners() {
        // 1. Fetch Users (Riders & Drivers & Approvals)
        database.child("users").addValueEventListener(object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                var totalRiders = 0
                var activeDrivers = 0
                var pendingApprovals = 0

                val allRiders = mutableListOf<User>()
                val pendingDrivers = mutableListOf<User>()

                for (userSnap in snapshot.children) {
                    val role = userSnap.child("role").getValue(String::class.java) ?: "PASSENGER"
                    val isActive = userSnap.child("isActive").getValue(Boolean::class.java) ?: false

                    if (role == "PASSENGER") {
                        totalRiders++
                        userSnap.getValue(User::class.java)?.let { allRiders.add(it) }
                    } else if (role == "DRIVER") {
                        if (isActive) {
                            val isOnline = userSnap.child("isOnline").getValue(Boolean::class.java) ?: false
                            if (isOnline) activeDrivers++
                        } else {
                            pendingApprovals++
                            userSnap.getValue(User::class.java)?.let { pendingDrivers.add(it) }
                        }
                    }
                }

                binding.txtTotalRiders.text = totalRiders.toString()
                binding.txtActiveDrivers.text = activeDrivers.toString()
                binding.txtPendingReview.text = pendingApprovals.toString()

                updateRiderList(allRiders)
                updateApprovalList(pendingDrivers)

                binding.txtQueueEmpty.visibility = if (pendingDrivers.isEmpty()) View.VISIBLE else View.GONE
            }

            override fun onCancelled(error: DatabaseError) {}
        })

        // 2. Fetch Trips Today & Activity Feed
        database.child("bookings").addValueEventListener(object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                var tripsToday = 0
                val bookings = mutableListOf<BookingModel>()
                val todayStart = Calendar.getInstance().apply {
                    set(Calendar.HOUR_OF_DAY, 0)
                    set(Calendar.MINUTE, 0)
                    set(Calendar.SECOND, 0)
                }.timeInMillis

                for (bookingSnap in snapshot.children) {
                    val booking = bookingSnap.getValue(BookingModel::class.java)
                    if (booking != null) {
                        bookings.add(booking)
                        if (booking.createdAt >= todayStart) tripsToday++
                    }
                }
                binding.txtTripsToday.text = tripsToday.toString()
                updateTripFeed(bookings.sortedByDescending { it.createdAt }.take(15))
            }

            override fun onCancelled(error: DatabaseError) {}
        })
    }

    private fun updateRiderList(riders: List<User>) {
        binding.layoutFullRiderList.removeAllViews()
        val inflater = LayoutInflater.from(this)

        for (rider in riders) {
            val itemView = inflater.inflate(android.R.layout.simple_list_item_2, binding.layoutFullRiderList, false)
            val text1 = itemView.findViewById<TextView>(android.R.id.text1)
            val text2 = itemView.findViewById<TextView>(android.R.id.text2)

            text1.text = "${rider.firstName} ${rider.lastName}"
            text1.setTypeface(null, android.graphics.Typeface.BOLD)
            text2.text = "${rider.email} • Joined: ${formatDate(rider.createdAt)}"

            binding.layoutFullRiderList.addView(itemView)
            addDivider(binding.layoutFullRiderList)
        }
    }

    private fun updateApprovalList(drivers: List<User>) {
        binding.layoutApprovalList.removeAllViews()
        val inflater = LayoutInflater.from(this)

        for (driver in drivers) {
            val view = inflater.inflate(R.layout.item_booking_history, binding.layoutApprovalList, false)

            view.findViewById<TextView>(R.id.txtHistoryPickup).text = "Applicant: ${driver.firstName} ${driver.lastName}"
            view.findViewById<TextView>(R.id.txtHistoryDropoff).text = "Email: ${driver.email}"
            view.findViewById<TextView>(R.id.txtHistoryFare).visibility = View.GONE
            view.findViewById<TextView>(R.id.txtHistoryServiceType).visibility = View.GONE
            view.findViewById<TextView>(R.id.txtHistoryDate).text = "Pending Review"

            val btnApprove = Button(this).apply {
                text = "APPROVE"
                setBackgroundColor(Color.parseColor("#10B981"))
                setTextColor(Color.WHITE)
                textSize = 12f
                val params = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 120)
                params.setMargins(0, 32, 0, 0)
                layoutParams = params
            }

            btnApprove.setOnClickListener { approveDriver(driver.uid) }

            // Correct way to find the inner linear layout in item_booking_history
            val innerLayout = view.findViewById<TextView>(R.id.txtHistoryPickup).parent as LinearLayout
            innerLayout.addView(btnApprove)

            binding.layoutApprovalList.addView(view)
        }
    }

    private fun updateTripFeed(bookings: List<BookingModel>) {
        binding.layoutTripFeed.removeAllViews()
        val inflater = LayoutInflater.from(this)

        for (booking in bookings) {
            val view = inflater.inflate(R.layout.item_booking_history, binding.layoutTripFeed, false)
            val txtStatus = view.findViewById<TextView>(R.id.txtHistoryStatus)
            txtStatus.text = booking.status

            view.findViewById<TextView>(R.id.txtHistoryPickup).text = "From: ${booking.pickupAddress}"
            view.findViewById<TextView>(R.id.txtHistoryDropoff).text = "To: ${booking.dropoffAddress}"
            view.findViewById<TextView>(R.id.txtHistoryFare).text = "₱${booking.fare}"

            binding.layoutTripFeed.addView(view)
            addDivider(binding.layoutTripFeed)
        }
    }

    private fun approveDriver(uid: String) {
        database.child("users").child(uid).child("isActive").setValue(true)
            .addOnSuccessListener {
                Toast.makeText(this, "Driver approved successfully!", Toast.LENGTH_SHORT).show()
            }
            .addOnFailureListener {
                Toast.makeText(this, "Approval failed: ${it.message}", Toast.LENGTH_SHORT).show()
            }
    }

    private fun performLogout() {
        auth.signOut()
        startActivity(Intent(this, LoginActivity::class.java))
        finish()
    }

    private fun addDivider(parent: LinearLayout) {
        val divider = View(this).apply {
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 2)
            setBackgroundColor(Color.parseColor("#F3F4F6"))
        }
        parent.addView(divider)
    }

    private fun formatDate(time: Long): String {
        return SimpleDateFormat("MMM dd, yyyy", Locale.US).format(java.util.Date(time))
    }
}