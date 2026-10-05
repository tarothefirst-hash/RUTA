package com.ruta.app.ui.driver

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.fragment.app.Fragment
import com.google.android.material.bottomnavigation.BottomNavigationView
import com.ruta.app.R

class DriverMainActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_driver_main)

        val bottomNav = findViewById<BottomNavigationView>(R.id.driverBottomNav)


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
                R.id.navigation_driver_wallet -> {
                    loadFragment(DriverWalletFragment())
                    true
                }
                R.id.navigation_driver_profile -> {
                    loadFragment(DriverProfileFragment())
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
}