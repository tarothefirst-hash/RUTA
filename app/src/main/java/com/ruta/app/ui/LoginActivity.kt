package com.ruta.app.ui

import android.content.Intent
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import com.ruta.app.data.AuthRepository
import com.ruta.app.databinding.ActivityLoginBinding
import com.ruta.app.model.UserRole

class LoginActivity : AppCompatActivity() {

    private lateinit var binding: ActivityLoginBinding
    private val authRepository = AuthRepository()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityLoginBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.btnLogin.setOnClickListener { attemptLogin() }

        binding.txtGoSignup.setOnClickListener {
            startActivity(Intent(this, SignupActivity::class.java))
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
            UserRole.DRIVER -> DriverHomeActivity::class.java
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
        binding.txtError.visibility = android.view.View.VISIBLE
    }

    private fun hideError() {
        binding.txtError.visibility = android.view.View.GONE
    }
}