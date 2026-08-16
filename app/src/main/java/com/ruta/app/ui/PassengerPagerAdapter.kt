package com.ruta.app.ui

import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentActivity
import androidx.viewpager2.adapter.FragmentStateAdapter

class PassengerPagerAdapter(activity: FragmentActivity) : FragmentStateAdapter(activity) {

    override fun getItemCount(): Int = 4 // 4 Panels: Home, Promos, Settings, Profile

    override fun createFragment(position: Int): Fragment {
        return when (position) {
            0 -> HomeFragment()
            1 -> PromosFragment()
            2 -> SettingsFragment()
            3 -> ProfileFragment()
            else -> HomeFragment()
        }
    }
}