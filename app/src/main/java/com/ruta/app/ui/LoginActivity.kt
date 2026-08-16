package com.ruta.app.ui

import android.content.Intent
import android.os.Bundle
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.database.FirebaseDatabase
import com.ruta.app.data.AuthRepository
import com.ruta.app.databinding.ActivityLoginBinding
import com.ruta.app.model.UserRole
import com.ruta.app.ui.driver.DriverMainActivity

class LoginActivity : AppCompatActivity() {

    private lateinit var binding: ActivityLoginBinding
    private val authRepository = AuthRepository()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityLoginBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // 🚀 Auto-check if session exists before asking for credentials
        checkAutoLogin()

        binding.btnLogin.setOnClickListener { attemptLogin() }

        binding.txtGoSignup.setOnClickListener {
            startActivity(Intent(this, SignupActivity::class.java))
        }
    }

    private fun checkAutoLogin() {
        val currentUser = FirebaseAuth.getInstance().currentUser

        if (currentUser != null) {
            setLoading(true)

            // User is already authenticated! Fetch their role from "users/$uid/role"
            FirebaseDatabase.getInstance().reference
                .child("users")
                .child(currentUser.uid)
                .child("role")
                .get()
                .addOnSuccessListener { snapshot ->
                    setLoading(false)
                    val roleStr = snapshot.getValue(String::class.java) ?: "PASSENGER"
                    val role = try {
                        UserRole.valueOf(roleStr.uppercase())
                    } catch (e: Exception) {
                        UserRole.PASSENGER
                    }
                    goToHome(role)
                }
                .addOnFailureListener {
                    setLoading(false)
                    // If network fails or role load fails, let them attempt manual login
                }
        }
    }

    private fun attemptLogin() {
        val email = binding.edtEmail.text.toString()
        val password = binding.edtPassword.text.toString()
        val role = selectedRole()

        if (email.isBlank() || password.isBlank()) {
            showError("Please fill in both email and password.")
            return
        }

        setLoading(true)
        authRepository.logIn(email, password, role) { result ->
            setLoading(false)
            when (result) {
                is AuthRepository.Result.Success -> {
                    hideError()
                    goToHome(result.role)
                }
                is AuthRepository.Result.Failure -> showError(result.message)
            }
        }
    }

    private fun selectedRole(): UserRole = when (binding.roleGroup.checkedRadioButtonId) {
        binding.roleDriver.id -> UserRole.DRIVER
        else -> UserRole.PASSENGER
    }

    private fun goToHome(role: UserRole) {
        val target = when (role) {
            UserRole.PASSENGER -> PassengerHomeActivity::class.java
            UserRole.DRIVER -> DriverMainActivity::class.java //
            UserRole.ADMIN -> AdminHomeActivity::class.java
        }
        startActivity(Intent(this, target))
        finish()
    }

    private fun setLoading(loading: Boolean) {
        binding.btnLogin.isEnabled = !loading
        binding.btnLogin.text = if (loading) "Logging in..." else getString(com.ruta.app.R.string.login_button)
    }

    private fun showError(message: String) {
        binding.txtError.text = message
        binding.txtError.visibility = View.VISIBLE
    }

    private fun hideError() {
        binding.txtError.visibility = View.GONE
    }
}