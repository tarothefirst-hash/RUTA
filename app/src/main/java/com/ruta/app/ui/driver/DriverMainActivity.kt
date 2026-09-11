package com.ruta.app.ui.driver

import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.fragment.app.Fragment
import com.google.android.material.bottomnavigation.BottomNavigationView
import com.google.android.material.textfield.TextInputEditText
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.database.FirebaseDatabase
import com.ruta.app.R
import com.ruta.app.ui.LoginActivity

class DriverMainActivity : AppCompatActivity() {

    private val auth: FirebaseAuth by lazy { FirebaseAuth.getInstance() }
    private val database: FirebaseDatabase by lazy { FirebaseDatabase.getInstance() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_driver_main)

        val bottomNav = findViewById<BottomNavigationView>(R.id.driverBottomNav)

        // Load Home Fragment by default
        if (savedInstanceState == null) {
            loadFragment(DriverHomeFragment())
        }

        bottomNav.setOnItemSelectedListener { item ->
            when (item.itemId) {
                R.id.navigation_driver_home -> {
                    loadFragment(DriverHomeFragment())
                    true
                }
                R.id.navigation_driver_rides -> {
                    // Load Driver Rides / History Fragment
                    loadFragment(DriverRidesFragment())
                    true
                }
                R.id.navigation_driver_profile -> {
                    // Perform driver logout and return to LoginActivity
                    performLogout()
                    true
                }
                else -> false
            }
        }
    }

    private fun loadFragment(fragment: Fragment) {
        supportFragmentManager.beginTransaction()
            .replace(R.id.driverFragmentContainer, fragment)
            .commit()
    }

    private fun performLogout() {
        // 1. Set driver offline status before signing out
        val currentUserId = auth.currentUser?.uid
        if (currentUserId != null) {
            database.reference.child("drivers").child(currentUserId).child("isOnline").setValue(false)
        }

        // 2. Sign out from Firebase
        auth.signOut()

        // 3. Navigate back to LoginActivity and clear backstack
        val intent = Intent(this, LoginActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
        }
        startActivity(intent)
        finish()
    }

    // Mock GCash / Maya Wallet Top-Up Modal for Presentation
    private fun showDriverWalletDialog() {
        val currentUserId = auth.currentUser?.uid ?: return

        database.reference.child("users").child(currentUserId).child("walletBalance")
            .get()
            .addOnSuccessListener { snapshot ->
                val currentBalance = snapshot.getValue(Double::class.java) ?: 0.0

                val input = TextInputEditText(this).apply {
                    hint = "Enter Top-Up Amount (₱)"
                    inputType = android.text.InputType.TYPE_CLASS_NUMBER or android.text.InputType.TYPE_NUMBER_FLAG_DECIMAL
                }

                AlertDialog.Builder(this)
                    .setTitle("Driver System Wallet")
                    .setMessage("Current Balance: ₱${String.format("%.2f", currentBalance)}\n\nTop-Up credits to pay platform fees:")
                    .setView(input)
                    .setPositiveButton("Top-Up (GCash)") { dialog, _ ->
                        val amount = input.text.toString().toDoubleOrNull()
                        if (amount != null && amount > 0) {
                            val newBalance = currentBalance + amount
                            database.reference.child("users").child(currentUserId)
                                .child("walletBalance").setValue(newBalance)
                                .addOnSuccessListener {
                                    Toast.makeText(this, "Successfully added ₱$amount! New Balance: ₱${String.format("%.2f", newBalance)}", Toast.LENGTH_LONG).show()
                                }
                        } else {
                            Toast.makeText(this, "Enter a valid amount", Toast.LENGTH_SHORT).show()
                        }
                        dialog.dismiss()
                    }
                    .setNegativeButton("Cancel") { dialog, _ -> dialog.dismiss() }
                    .show()
            }
    }
}