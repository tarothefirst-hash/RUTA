package com.ruta.app.ui

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import com.ruta.app.databinding.ActivityAdminHomeBinding

/**
 * Placeholder admin dashboard. No prototype exists yet for this role --
 * build out the real UI here (user/driver management, fare oversight,
 * route deviation monitoring, etc.).
 */
class AdminHomeActivity : AppCompatActivity() {

    private lateinit var binding: ActivityAdminHomeBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityAdminHomeBinding.inflate(layoutInflater)
        setContentView(binding.root)
    }
}
