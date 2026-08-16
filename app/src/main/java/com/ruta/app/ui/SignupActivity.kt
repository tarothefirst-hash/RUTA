package com.ruta.app.ui

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.database.FirebaseDatabase
import com.ruta.app.databinding.ActivitySignupBinding
import com.ruta.app.model.UserRole

class SignupActivity : AppCompatActivity() {

    private lateinit var binding: ActivitySignupBinding
    private val auth: FirebaseAuth by lazy { FirebaseAuth.getInstance() }
    private val database: FirebaseDatabase by lazy { FirebaseDatabase.getInstance() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySignupBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.btnSignup.setOnClickListener { attemptSignup() }

        binding.txtGoLogin.setOnClickListener {
            finish() // Return to Login
        }
    }

    private fun attemptSignup() {
        val firstName = binding.edtFirstName.text.toString().trim()
        val lastName = binding.edtLastName.text.toString().trim()
        val email = binding.edtEmail.text.toString().trim()
        val password = binding.edtPassword.text.toString().trim()
        val selectedRole = if (binding.roleDriver.isChecked) UserRole.DRIVER else UserRole.PASSENGER

        if (firstName.isEmpty() || lastName.isEmpty() || email.isEmpty() || password.isEmpty()) {
            showError("Please fill out all fields.")
            return
        }

        if (password.length < 6) {
            showError("Password must be at least 6 characters.")
            return
        }

        setLoading(true)

        // 1. Create User in Firebase Auth
        auth.createUserWithEmailAndPassword(email, password)
            .addOnSuccessListener { authResult ->
                val uid = authResult.user?.uid
                if (uid != null) {
                    // 2. Save User Profile in Firebase Realtime Database
                    saveUserProfileToDatabase(uid, firstName, lastName, email, selectedRole)
                } else {
                    setLoading(false)
                    showError("Failed to retrieve user ID.")
                }
            }
            .addOnFailureListener { e ->
                setLoading(false)
                showError(e.message ?: "Registration failed.")
            }
    }

    private fun saveUserProfileToDatabase(
        uid: String,
        firstName: String,
        lastName: String,
        email: String,
        role: UserRole
    ) {
        val userMap = hashMapOf(
            "uid" to uid,
            "firstName" to firstName,
            "lastName" to lastName,
            "email" to email,
            "role" to role.name, // PASSENGER or DRIVER
            "createdAt" to System.currentTimeMillis()
        )

        // Write directly to users/$uid node
        database.reference.child("users").child(uid)
            .setValue(userMap)
            .addOnSuccessListener {
                setLoading(false)
                Toast.makeText(this, "Account created successfully!", Toast.LENGTH_SHORT).show()

                // Route user based on role
                goToHome(role)
            }
            .addOnFailureListener { e ->
                setLoading(false)
                showError("Database error: ${e.message}")
            }
    }

    private fun goToHome(role: UserRole) {
        val target = when (role) {
            UserRole.PASSENGER -> PassengerHomeActivity::class.java
            UserRole.DRIVER -> DriverHomeActivity::class.java
            UserRole.ADMIN -> AdminHomeActivity::class.java
        }
        val intent = Intent(this, target)
        intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
        startActivity(intent)
        finish()
    }

    private fun setLoading(loading: Boolean) {
        binding.btnSignup.isEnabled = !loading
        binding.btnSignup.text = if (loading) "Creating account..." else "Sign Up"
    }

    private fun showError(message: String) {
        binding.txtError.text = message
        binding.txtError.visibility = View.VISIBLE
    }
}