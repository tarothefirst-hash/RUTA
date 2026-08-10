package com.ruta.app.data

import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.database.FirebaseDatabase
import com.ruta.app.model.UserRole

/**
 * Firebase-backed authentication.
 *
 * - FirebaseAuth (Email/Password provider) handles credentials -- we never
 *   see or store raw passwords ourselves.
 * - Realtime Database stores each user's profile (first name, last name, email, role)
 *   under users/<uid>, keyed by the Firebase Auth UID.
 *
 * All calls are async (Firebase Tasks), so results come back via a
 * callback instead of a return value. The callbacks fire on the main
 * thread, so it's safe to touch views directly inside them.
 */
class AuthRepository {

    private val auth = FirebaseAuth.getInstance()
    private val usersRef = FirebaseDatabase.getInstance().getReference("users")

    sealed class Result {
        data class Success(val role: UserRole) : Result()
        data class Failure(val message: String) : Result()
    }

    fun signUp(
        firstName: String,
        lastName: String,
        email: String,
        password: String,
        role: UserRole,
        onResult: (Result) -> Unit
    ) {
        val normalizedEmail = email.trim().lowercase()

        if (firstName.isBlank() || lastName.isBlank()) {
            onResult(Result.Failure("Please enter both your first and last name.")); return
        }
        if (!isValidEmail(normalizedEmail)) {
            onResult(Result.Failure("Please enter a valid email address.")); return
        }
        if (password.length < 6) {
            onResult(Result.Failure("Password must be at least 6 characters.")); return
        }

        auth.createUserWithEmailAndPassword(normalizedEmail, password)
            .addOnSuccessListener { authResult ->
                val uid = authResult.user?.uid
                if (uid == null) {
                    onResult(Result.Failure("Something went wrong creating your account."))
                    return@addOnSuccessListener
                }

                val profile = mapOf(
                    "firstName" to firstName.trim(),
                    "lastName" to lastName.trim(),
                    "email" to normalizedEmail,
                    "role" to role.name
                )

                usersRef.child(uid).setValue(profile)
                    .addOnSuccessListener {
                        onResult(Result.Success(role))
                    }
                    .addOnFailureListener { e ->
                        onResult(Result.Failure("Account created, but saving your profile failed: ${e.message}"))
                    }
            }
            .addOnFailureListener { e ->
                onResult(Result.Failure(e.message ?: "Sign up failed."))
            }
    }

    fun logIn(
        email: String,
        password: String,
        expectedRole: UserRole,
        onResult: (Result) -> Unit
    ) {
        val normalizedEmail = email.trim().lowercase()

        auth.signInWithEmailAndPassword(normalizedEmail, password)
            .addOnSuccessListener { authResult ->
                val uid = authResult.user?.uid
                if (uid == null) {
                    onResult(Result.Failure("Something went wrong logging in."))
                    return@addOnSuccessListener
                }

                usersRef.child(uid).get()
                    .addOnSuccessListener { snapshot ->
                        val roleString = snapshot.child("role").getValue(String::class.java)
                        val storedRole = roleString?.let { runCatching { UserRole.valueOf(it) }.getOrNull() }

                        when {
                            storedRole == null ->
                                onResult(Result.Failure("No profile found for this account."))
                            storedRole != expectedRole ->
                                onResult(Result.Failure("This account is not registered as ${expectedRole.name.lowercase()}."))
                            else ->
                                onResult(Result.Success(storedRole))
                        }
                    }
                    .addOnFailureListener { e ->
                        onResult(Result.Failure("Could not load your profile: ${e.message}"))
                    }
            }
            .addOnFailureListener { e ->
                onResult(Result.Failure(e.message ?: "Login failed."))
            }
    }
    fun saveUserLocation(
        locationType: String, // "home", "work", or "favorite"
        name: String,
        address: String,
        lat: Double,
        lng: Double,
        onComplete: (Boolean) -> Unit
    ) {
        val uid = auth.currentUser?.uid ?: return onComplete(false)
        val locationMap = mapOf(
            "name" to name,
            "address" to address,
            "latitude" to lat,
            "longitude" to lng
        )

        usersRef.child(uid).child("savedLocations").child(locationType)
            .setValue(locationMap)
            .addOnSuccessListener { onComplete(true) }
            .addOnFailureListener { onComplete(false) }
    }
    private fun isValidEmail(email: String): Boolean =
        android.util.Patterns.EMAIL_ADDRESS.matcher(email).matches()
}