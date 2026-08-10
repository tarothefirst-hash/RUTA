package com.ruta.app.ui

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import com.ruta.app.databinding.ActivityDriverHomeBinding

/**
 * Placeholder driver dashboard (matches the "Driver" prototype:
 * earnings today, trips travelled, recent trips, online/offline toggle).
 * Build out the real UI here.
 */
class DriverHomeActivity : AppCompatActivity() {

    private lateinit var binding: ActivityDriverHomeBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityDriverHomeBinding.inflate(layoutInflater)
        setContentView(binding.root)
    }
}
