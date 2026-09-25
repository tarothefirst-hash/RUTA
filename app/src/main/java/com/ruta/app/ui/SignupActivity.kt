package com.ruta.app.ui

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import com.google.android.gms.auth.api.signin.GoogleSignIn
import com.google.android.gms.auth.api.signin.GoogleSignInAccount
import com.google.android.gms.auth.api.signin.GoogleSignInClient
import com.google.android.gms.auth.api.signin.GoogleSignInOptions
import com.google.android.gms.common.api.ApiException
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.GoogleAuthProvider
import com.google.firebase.database.FirebaseDatabase
import com.ruta.app.R
import com.ruta.app.databinding.ActivitySignupBinding
import com.ruta.app.model.UserRole
import com.ruta.app.ui.driver.DriverMainActivity

class SignupActivity : AppCompatActivity() {

    private lateinit var binding: ActivitySignupBinding
    private val auth: FirebaseAuth by lazy { FirebaseAuth.getInstance() }
    private val database: FirebaseDatabase by lazy { FirebaseDatabase.getInstance() }
    private lateinit var googleSignInClient: GoogleSignInClient

    private val googleSignInLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val task = GoogleSignIn.getSignedInAccountFromIntent(result.data)
        try {
            val account = task.getResult(ApiException::class.java)!!
            firebaseAuthWithGoogle(account.idToken!!, account)
        } catch (e: Exception) {
            setLoading(false)
            showError("Google sign-in failed: ${e.message}")
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySignupBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setupGoogleSignIn()

        binding.roleGroup.setOnCheckedChangeListener { _, checkedId ->
            if (checkedId == binding.roleDriver.id) {
                binding.layoutDriverFields.visibility = View.VISIBLE
            } else {
                binding.layoutDriverFields.visibility = View.GONE
            }
        }

        binding.btnSignup.setOnClickListener { attemptSignup() }

        binding.btnGoogle.setOnClickListener {
            val selectedRole = if (binding.roleDriver.isChecked) UserRole.DRIVER else UserRole.PASSENGER

            // Ensure drivers enter vehicle details before proceeding with Google Sign-In
            if (selectedRole == UserRole.DRIVER) {
                val vehicleModel = binding.edtVehicleModel.text.toString().trim()
                val plateNumber = binding.edtPlateNumber.text.toString().trim()
                val vehicleColor = binding.edtVehicleColor.text.toString().trim()

                if (vehicleModel.isEmpty() || plateNumber.isEmpty() || vehicleColor.isEmpty()) {
                    showError("Please enter your vehicle information before signing up with Google.")
                    return@setOnClickListener
                }
            }

            hideError()
            setLoading(true)
            val intent = googleSignInClient.signInIntent
            googleSignInLauncher.launch(intent)
        }

        binding.txtGoLogin.setOnClickListener { finish() }
    }

    private fun setupGoogleSignIn() {
        val gso = GoogleSignInOptions.Builder(GoogleSignInOptions.DEFAULT_SIGN_IN)
            .requestIdToken(getString(R.string.default_web_client_id))
            .requestEmail()
            .build()
        googleSignInClient = GoogleSignIn.getClient(this, gso)
    }

    private fun attemptSignup() {
        val firstName = binding.edtFirstName.text.toString().trim()
        val lastName = binding.edtLastName.text.toString().trim()
        val email = binding.edtEmail.text.toString().trim()
        val birthDate = binding.edtBirthDate.text.toString().trim()
        val password = binding.edtPassword.text.toString().trim()
        val confirmPassword = binding.edtConfirmPassword.text.toString().trim()
        val selectedRole = if (binding.roleDriver.isChecked) UserRole.DRIVER else UserRole.PASSENGER

        if (firstName.isEmpty() || lastName.isEmpty() || email.isEmpty() || birthDate.isEmpty() || password.isEmpty()) {
            showError("Please fill out all mandatory fields.")
            return
        }

        if (password != confirmPassword) {
            showError("Passwords do not match.")
            return
        }

        val passwordPattern = Regex("^(?=.*[0-9])(?=.*[a-z])(?=.*[A-Z])(?=.*[@#$%^&+=!._-]).{8,}$")
        if (!passwordPattern.matches(password)) {
            showError("Password requires 8+ chars with uppercase, lowercase, number, and special symbol.")
            return
        }

        if (selectedRole == UserRole.DRIVER) {
            val vehicleModel = binding.edtVehicleModel.text.toString().trim()
            val plateNumber = binding.edtPlateNumber.text.toString().trim()
            val vehicleColor = binding.edtVehicleColor.text.toString().trim()

            if (vehicleModel.isEmpty() || plateNumber.isEmpty() || vehicleColor.isEmpty()) {
                showError("Please complete all vehicle information fields.")
                return
            }
        }

        setLoading(true)
        hideError()

        auth.createUserWithEmailAndPassword(email, password)
            .addOnSuccessListener { authResult ->
                val uid = authResult.user?.uid
                if (uid != null) {
                    saveUserProfileToDatabase(uid, firstName, lastName, email, birthDate, selectedRole)
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

    private fun firebaseAuthWithGoogle(idToken: String, account: GoogleSignInAccount) {
        val credential = GoogleAuthProvider.getCredential(idToken, null)
        auth.signInWithCredential(credential)
            .addOnSuccessListener { authResult ->
                val uid = authResult.user?.uid ?: return@addOnSuccessListener
                val selectedRole = if (binding.roleDriver.isChecked) UserRole.DRIVER else UserRole.PASSENGER

                // Extract profile info from Google Account
                val firstName = account.givenName ?: "User"
                val lastName = account.familyName ?: ""
                val email = account.email ?: ""
                val birthDate = binding.edtBirthDate.text.toString().trim().ifEmpty { "Not Provided" }

                saveUserProfileToDatabase(uid, firstName, lastName, email, birthDate, selectedRole)
            }
            .addOnFailureListener { e ->
                setLoading(false)
                showError("Auth failed: ${e.message}")
            }
    }

    private fun saveUserProfileToDatabase(
        uid: String,
        firstName: String,
        lastName: String,
        email: String,
        birthDate: String,
        role: UserRole
    ) {
        val userMap = hashMapOf<String, Any>(
            "uid" to uid,
            "firstName" to firstName,
            "lastName" to lastName,
            "name" to "$firstName $lastName".trim(),
            "email" to email,
            "birthDate" to birthDate,
            "role" to role.name,
            "isActive" to (role == UserRole.PASSENGER), // Passengers active by default, Drivers need admin approval
            "createdAt" to System.currentTimeMillis()
        )

        if (role == UserRole.DRIVER) {
            userMap["vehicleModel"] = binding.edtVehicleModel.text.toString().trim()
            userMap["plateNumber"] = binding.edtPlateNumber.text.toString().trim()
            userMap["vehicleColor"] = binding.edtVehicleColor.text.toString().trim()
            userMap["walletBalance"] = 100.0
            userMap["isOnline"] = false
        }

        database.reference.child("users").child(uid)
            .setValue(userMap)
            .addOnSuccessListener {
                setLoading(false)
                Toast.makeText(this, "Account setup completed!", Toast.LENGTH_SHORT).show()
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
        binding.btnSignup.isEnabled = !loading
        binding.btnGoogle.isEnabled = !loading
        binding.btnSignup.text = if (loading) "Processing..." else "Sign Up"
    }

    private fun showError(message: String) {
        binding.txtError.text = message
        binding.txtError.visibility = View.VISIBLE
    }

    private fun hideError() {
        binding.txtError.visibility = View.GONE
    }
}