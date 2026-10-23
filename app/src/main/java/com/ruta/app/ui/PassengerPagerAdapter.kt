package com.ruta.app.ui

import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentActivity
import androidx.viewpager2.adapter.FragmentStateAdapter

class PassengerPagerAdapter(activity: FragmentActivity) : FragmentStateAdapter(activity) {

    override fun getItemCount(): Int = 5 // 5 Panels: Home, Live Track, Promos, Settings, Profile

    override fun createFragment(position: Int): Fragment {
        return when (position) {
            0 -> HomeFragment()
            1 -> LiveTrackingFragment()
            2 -> PromosFragment()
            3 -> SettingsFragment()
            4 -> ProfileFragment()
            else -> HomeFragment()
        }
    }
}