package com.ruta.app.ui

import android.os.Bundle
import android.widget.ImageView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.viewpager2.widget.ViewPager2
import com.ruta.app.R

class PassengerHomeActivity : AppCompatActivity() {

    private lateinit var viewPager: ViewPager2
    private lateinit var navHome: ImageView
    private lateinit var navPromos: ImageView
    private lateinit var navSettings: ImageView
    private lateinit var btnProfile: ImageView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_passenger_home)

        initViews()
        setupViewPager()
        setupClickListeners()
    }

    private fun initViews() {
        viewPager = findViewById(R.id.viewPager)
        navHome = findViewById(R.id.navHome)
        navPromos = findViewById(R.id.navPromos)
        navSettings = findViewById(R.id.navSettings)
        btnProfile = findViewById(R.id.btnProfile)
    }

    private fun setupViewPager() {
        val adapter = PassengerPagerAdapter(this)
        viewPager.adapter = adapter

        // Swiping syncs the icon highlighting
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
        // Tapping icons smooth-scrolls the ViewPager2 to that fragment
        navHome.setOnClickListener { viewPager.setCurrentItem(0, true) }
        navPromos.setOnClickListener { viewPager.setCurrentItem(1, true) }
        navSettings.setOnClickListener { viewPager.setCurrentItem(2, true) }
        btnProfile.setOnClickListener { viewPager.setCurrentItem(3, true) }
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
}