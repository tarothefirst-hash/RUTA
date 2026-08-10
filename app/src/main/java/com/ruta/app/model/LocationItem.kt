package com.ruta.app.model

import com.google.android.gms.maps.model.LatLng

data class LocationItem(
    val name: String,
    val address: String,
    val latLng: LatLng? = null,
    val placeId: String? = null,
    val isUseMapOption: Boolean = false
)