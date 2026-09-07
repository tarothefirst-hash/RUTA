package com.ruta.app.ui

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.database.FirebaseDatabase
import com.ruta.app.R
import com.ruta.app.databinding.ActivityLoginBinding
import com.ruta.app.model.UserRole
import com.ruta.app.ui.driver.DriverMainActivity

class LoginActivity : AppCompatActivity() {

    private lateinit var binding: ActivityLoginBinding
    private val auth: FirebaseAuth by lazy { FirebaseAuth.getInstance() }
    private val database: FirebaseDatabase by lazy { FirebaseDatabase.getInstance() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityLoginBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // Auto-check session immediately
        checkAutoLogin()

        binding.btnLogin.setOnClickListener { attemptLogin() }

        binding.txtGoSignup.setOnClickListener {
            startActivity(Intent(this, SignupActivity::class.java))
        }
    }

    private fun checkAutoLogin() {
        val currentUser = auth.currentUser ?: return

        // 🚀 FAST-PATH: Use locally cached role first for instantaneous navigation
        val prefs = getSharedPreferences("USER_SESSION", Context.MODE_PRIVATE)
        val cachedRole = prefs.getString("USER_ROLE", null)

        if (!cachedRole.isNullOrEmpty()) {
            goToHome(parseRole(cachedRole))
            return
        }

        setLoading(true)
        fetchRoleAndNavigate(currentUser.uid)
    }

    private fun attemptLogin() {
        val email = binding.edtEmail.text.toString().trim()
        val password = binding.edtPassword.text.toString().trim()

        if (email.isBlank() || password.isBlank()) {
            showError("Please fill in both email and password.")
            return
        }

        setLoading(true)
        hideError()

        auth.signInWithEmailAndPassword(email, password)
            .addOnSuccessListener { authResult ->
                val uid = authResult.user?.uid
                if (uid != null) {
                    fetchRoleAndNavigate(uid)
                } else {
                    setLoading(false)
                    showError("User ID not found.")
                }
            }
            .addOnFailureListener { exception ->
                setLoading(false)
                showError(exception.localizedMessage ?: "Login failed. Please check your credentials.")
            }
    }

    private fun fetchRoleAndNavigate(uid: String) {
        database.reference
            .child("users")
            .child(uid)
            .get()
            .addOnSuccessListener { snapshot ->
                setLoading(false)
                val roleStr = snapshot.child("role").getValue(String::class.java) ?: "PASSENGER"
                val userName = snapshot.child("name").getValue(String::class.java) ?: "Passenger"

                // Cache user details locally to skip network calls on subsequent launches
                val prefs = getSharedPreferences("USER_SESSION", Context.MODE_PRIVATE)
                prefs.edit()
                    .putString("USER_ROLE", roleStr)
                    .putString("USER_NAME", userName)
                    .apply()

                val role = parseRole(roleStr)
                goToHome(role)
            }
            .addOnFailureListener { exception ->
                setLoading(false)
                showError("Failed to verify user profile: ${exception.localizedMessage}")
            }
    }

    private fun parseRole(roleStr: String): UserRole {
        return try {
            UserRole.valueOf(roleStr.uppercase())
        } catch (e: Exception) {
            UserRole.PASSENGER
        }
    }

    private fun goToHome(role: UserRole) {
        val target = when (role) {
            UserRole.PASSENGER -> PassengerHomeActivity::class.java
            UserRole.DRIVER -> DriverMainActivity::class.java
            UserRole.ADMIN -> AdminHomeActivity::class.java
        }

        val intent = Intent(this, target).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
        }
        startActivity(intent)
        finish()
    }

    private fun setLoading(loading: Boolean) {
        binding.btnLogin.isEnabled = !loading
        binding.btnLogin.text = if (loading) "Logging in..." else getString(R.string.login_button)
    }

    private fun showError(message: String) {
        binding.txtError.text = message
        binding.txtError.visibility = View.VISIBLE
    }

    private fun hideError() {
        binding.txtError.visibility = View.GONE
    }
}