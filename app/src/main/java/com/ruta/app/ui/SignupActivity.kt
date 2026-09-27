package com.ruta.app.ui

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.RadioButton
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
import android.app.DatePickerDialog
import java.util.Calendar
import java.util.Locale
import androidx.core.view.WindowCompat
import com.ruta.app.util.keepClearOfKeyboard
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat

class SignupActivity : AppCompatActivity() {

    private lateinit var binding: ActivitySignupBinding
    private val auth: FirebaseAuth by lazy { FirebaseAuth.getInstance() }
    private val database: FirebaseDatabase by lazy { FirebaseDatabase.getInstance() }
    private lateinit var googleSignInClient: GoogleSignInClient

    private val googleSignInLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val task = GoogleSignIn.getSignedInAccountFromIntent(result.data)
        try {
            val account = task.getResult(ApiException::class.java)!!
            val idToken = account.idToken

            if (idToken != null) {
                val phoneNumber = binding.edtPhoneNumber.text.toString().trim()
                val role = getSelectedRole()
                firebaseAuthWithGoogle(idToken, account, phoneNumber, role)
            } else {
                setLoading(false)
                showError("Google sign-in failed: Null ID Token.")
            }
        } catch (e: Exception) {
            setLoading(false)
            showError("Google sign-in failed: ${e.message}")
        }
    }

    private fun firebaseAuthWithGoogle(
        idToken: String,
        account: GoogleSignInAccount,
        phoneNumber: String,
        role: UserRole
    ) {
        val credential = GoogleAuthProvider.getCredential(idToken, null)
        auth.signInWithCredential(credential)
            .addOnSuccessListener { authResult ->
                val uid = authResult.user?.uid ?: return@addOnSuccessListener
                val firstName = account.givenName ?: "User"
                val lastName = account.familyName ?: ""
                val email = account.email ?: ""
                val birthDate = binding.edtBirthDate.text.toString().trim().ifEmpty { "Not Provided" }

                saveUserProfileToDatabase(uid, firstName, lastName, email, phoneNumber, birthDate, role)
            }
            .addOnFailureListener { e ->
                setLoading(false)
                showError("Auth failed: ${e.message}")
            }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // 1. Enable Edge-to-Edge window
        WindowCompat.setDecorFitsSystemWindows(window, false)

        // 2. Inflate views
        binding = ActivitySignupBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // 3. Handle Status Bar top inset padding
        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { view, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            view.setPadding(
                systemBars.left,
                systemBars.top,
                systemBars.right,
                view.paddingBottom
            )
            insets
        }

        // 4. Keyboard resize listener
        keepClearOfKeyboard(binding.root)

        // 5. Business logic & listeners
        setupGoogleSignIn()
        binding.edtBirthDate.setOnClickListener { showBirthDatePicker() }
        binding.btnSignup.setOnClickListener { attemptSignup() }

        binding.btnGoogle.setOnClickListener {
            hideError()
            setLoading(true)
            googleSignInLauncher.launch(googleSignInClient.signInIntent)
        }

        binding.txtGoLogin.setOnClickListener { finish() }
    }
    private fun showBirthDatePicker() {
        val today = Calendar.getInstance()

        val picker = DatePickerDialog(
            this,
            { _, year, month, day ->
                binding.edtBirthDate.setText(String.format(Locale.US, "%04d-%02d-%02d", year, month + 1, day))
            },
            today.get(Calendar.YEAR),
            today.get(Calendar.MONTH),
            today.get(Calendar.DAY_OF_MONTH)
        )

        picker.datePicker.maxDate = System.currentTimeMillis() // can't pick a future birthday
        picker.show()
    }
    private fun setupGoogleSignIn() {
        val gso = GoogleSignInOptions.Builder(GoogleSignInOptions.DEFAULT_SIGN_IN)
            .requestIdToken(getString(R.string.default_web_client_id))
            .requestEmail()
            .build()
        googleSignInClient = GoogleSignIn.getClient(this, gso)
    }

    private fun getSelectedRole(): UserRole {
        val rbDriver = findViewById<RadioButton>(R.id.rbDriver)
        return if (rbDriver?.isChecked == true) UserRole.DRIVER else UserRole.PASSENGER
    }

    private fun attemptSignup() {
        val firstName = binding.edtFirstName.text.toString().trim()
        val lastName = binding.edtLastName.text.toString().trim()
        val email = binding.edtEmail.text.toString().trim()
        val phoneNumber = binding.edtPhoneNumber.text.toString().trim()
        val birthDate = binding.edtBirthDate.text.toString().trim()
        val password = binding.edtPassword.text.toString().trim()
        val confirmPassword = binding.edtConfirmPassword.text.toString().trim()

        if (firstName.isEmpty() || lastName.isEmpty() || email.isEmpty() ||
            phoneNumber.isEmpty() || birthDate.isEmpty() || password.isEmpty()) {
            showError("Please fill out all mandatory fields.")
            return
        }

        if (phoneNumber.length < 10) {
            showError("Please enter a valid phone number.")
            return
        }

        if (password != confirmPassword) {
            showError("Passwords do not match.")
            return
        }

        setLoading(true)
        hideError()

        val role = getSelectedRole()

        auth.createUserWithEmailAndPassword(email, password)
            .addOnSuccessListener { authResult ->
                val uid = authResult.user?.uid
                if (uid != null) {
                    saveUserProfileToDatabase(uid, firstName, lastName, email, phoneNumber, birthDate, role)
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
        phoneNumber: String,
        birthDate: String,
        role: UserRole
    ) {
        val isDriver = role == UserRole.DRIVER

        val userMap = hashMapOf<String, Any>(
            "uid" to uid,
            "firstName" to firstName,
            "lastName" to lastName,
            "name" to "$firstName $lastName".trim(),
            "email" to email,
            "phoneNumber" to phoneNumber,
            "birthDate" to birthDate,
            "role" to role.name,
            "isActive" to true,
            "isApproved" to !isDriver,
            "createdAt" to System.currentTimeMillis()
        )

        database.reference.child("users").child(uid)
            .setValue(userMap)
            .addOnSuccessListener {
                setLoading(false)
                if (isDriver) {
                    auth.signOut()
                    Toast.makeText(this, "Driver account created! Please await admin approval.", Toast.LENGTH_LONG).show()
                    finish()
                } else {
                    Toast.makeText(this, "Account setup completed!", Toast.LENGTH_SHORT).show()
                    goToHome()
                }
            }
            .addOnFailureListener { e ->
                setLoading(false)
                showError("Database error: ${e.message}")
            }
    }

    private fun goToHome() {
        val intent = Intent(this, PassengerHomeActivity::class.java).apply {
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