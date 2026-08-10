package com.ruta.app.model

enum class UserRole {
    PASSENGER,
    DRIVER,
    ADMIN
}
data class SavedPlace(
    val name: String = "",
    val address: String = "",
    val latitude: Double = 0.0,
    val longitude: Double = 0.0
)

data class User(
    val uid: String = "",
    val firstName: String = "",
    val lastName: String = "",
    val email: String = "",
    val role: String = "PASSENGER",
    val homeLocation: SavedPlace? = null,
    val workLocation: SavedPlace? = null,
    val favoriteLocation: SavedPlace? = null
)
