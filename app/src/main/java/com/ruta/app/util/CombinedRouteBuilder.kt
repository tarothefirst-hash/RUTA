package com.ruta.app.util

import com.google.android.gms.maps.model.LatLng

object CombinedRouteBuilder {
    fun combineLegs(legs: List<List<LatLng>>): List<LatLng> {
        val combined = mutableListOf<LatLng>()
        for (leg in legs) {
            combined.addAll(leg)
        }
        return combined
    }
}