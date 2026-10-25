package com.ruta.app.ui

import android.app.DatePickerDialog
import android.content.ActivityNotFoundException
import android.content.Intent
import android.os.Bundle
import android.util.Patterns
import android.view.View
import android.widget.RadioButton
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
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
import com.ruta.app.ui.driver.DriverDocumentUploadActivity
import com.ruta.app.util.keepClearOfKeyboard
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale

class SignupActivity : AppCompatActivity() {

    private lateinit var binding: ActivitySignupBinding
    private val auth: FirebaseAuth by lazy { FirebaseAuth.getInstance() }
    private val database: FirebaseDatabase by lazy { FirebaseDatabase.getInstance() }
    private lateinit var googleSignInClient: GoogleSignInClient

    // Temporary storage for credentials while waiting for OTP verification
    private var pendingEmail = ""
    private var pendingPassword = ""
    private var pendingFirstName = ""
    private var pendingLastName = ""
    private var pendingPhoneNumber = ""
    private var pendingBirthDate = ""
    private var pendingRole: UserRole = UserRole.PASSENGER

    // Launcher for OTP Screen
    private val otpLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == RESULT_OK) {
            executeFirebaseSignup()
        } else {
            setLoading(false)
            showError("Email verification was canceled or incomplete.")
        }
    }

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

        WindowCompat.setDecorFitsSystemWindows(window, false)

        binding = ActivitySignupBinding.inflate(layoutInflater)
        setContentView(binding.root)

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

        keepClearOfKeyboard(binding.root)

        setupGoogleSignIn()
        binding.edtBirthDate.setOnClickListener { showBirthDatePicker() }
        binding.btnSignup.setOnClickListener { attemptSignup() }

        binding.txtTermsNotice.setOnClickListener {
            val intent = Intent(this, TermsActivity::class.java)
            startActivity(intent)
        }

        binding.btnGoogle.setOnClickListener {
            hideError()
            if (!binding.chkTerms.isChecked) {
                showError("Please agree to the Terms & Conditions to proceed.")
                return@setOnClickListener
            }
            setLoading(true)
            googleSignInLauncher.launch(googleSignInClient.signInIntent)
        }

        binding.txtGoLogin.setOnClickListener { finish() }
    }

    private fun showBirthDatePicker() {
        val maxAllowedDate = Calendar.getInstance().apply {
            add(Calendar.YEAR, -18)
        }

        val picker = DatePickerDialog(
            this,
            { _, year, month, day ->
                binding.edtBirthDate.setText(String.format(Locale.US, "%04d-%02d-%02d", year, month + 1, day))
            },
            maxAllowedDate.get(Calendar.YEAR),
            maxAllowedDate.get(Calendar.MONTH),
            maxAllowedDate.get(Calendar.DAY_OF_MONTH)
        )

        picker.datePicker.maxDate = maxAllowedDate.timeInMillis
        picker.show()
    }

    private fun isAtLeast18(birthDateStr: String): Boolean {
        return try {
            val sdf = SimpleDateFormat("yyyy-MM-dd", Locale.US)
            val birthDate = sdf.parse(birthDateStr) ?: return false

            val dob = Calendar.getInstance().apply { time = birthDate }
            val cutoff = Calendar.getInstance().apply { add(Calendar.YEAR, -18) }

            !dob.after(cutoff)
        } catch (e: Exception) {
            false
        }
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
        val email = binding.edtEmail.text.toString().trim().lowercase(Locale.ROOT)
        val phoneNumber = binding.edtPhoneNumber.text.toString().trim()
        val birthDate = binding.edtBirthDate.text.toString().trim()
        val password = binding.edtPassword.text.toString().trim()
        val confirmPassword = binding.edtConfirmPassword.text.toString().trim()

        if (!binding.chkTerms.isChecked) {
            showError("Please agree to the Terms & Conditions to proceed.")
            return
        }

        if (firstName.isEmpty() || lastName.isEmpty() || email.isEmpty() ||
            phoneNumber.isEmpty() || birthDate.isEmpty() || password.isEmpty() || confirmPassword.isEmpty()) {
            showError("Please fill out all mandatory fields.")
            return
        }

        if (!isAtLeast18(birthDate)) {
            showError("You must be at least 18 years old to register.")
            return
        }

        val nameRegex = Regex("^[a-zA-Z\\s'-]{2,}$")
        if (!nameRegex.matches(firstName)) {
            showError("First name must be at least 2 letters long and contain valid characters.")
            return
        }
        if (!nameRegex.matches(lastName)) {
            showError("Last name must be at least 2 letters long and contain valid characters.")
            return
        }

        val emailPrefix = email.substringBefore("@", "")
        if (emailPrefix.length < 2 || !Patterns.EMAIL_ADDRESS.matcher(email).matches()) {
            showError("Please enter a valid email address (e.g., user@domain.com).")
            return
        }

        val phoneRegex = Regex("^(09|\\+639)\\d{9}$")
        if (!phoneRegex.matches(phoneNumber)) {
            showError("Please enter a valid Philippine mobile number (e.g., 09123456789 or +639123456789).")
            return
        }

        if (password.length < 8) {
            showError("Password must be at least 8 characters long.")
            return
        }

        val passwordComplexityRegex = Regex("^(?=.*[a-z])(?=.*[A-Z])(?=.*\\d)(?=.*[@\$!%*?&#^()_+\\-=\\[\\]{};':\"\\\\|,.<>/?]).+$")
        if (!passwordComplexityRegex.matches(password)) {
            showError("Password must include uppercase, lowercase, number, and a special character.")
            return
        }

        val weakPasswords = setOf("password", "password123", "password123!", "12345678", "qwerty1234")
        if (weakPasswords.contains(password.lowercase(Locale.ROOT))) {
            showError("This password is too common. Please choose a stronger password.")
            return
        }

        if (password != confirmPassword) {
            showError("Passwords do not match.")
            return
        }

        hideError()

        pendingFirstName = firstName
        pendingLastName = lastName
        pendingEmail = email
        pendingPhoneNumber = phoneNumber
        pendingBirthDate = birthDate
        pendingPassword = password
        pendingRole = getSelectedRole()

        triggerEmailOtpFlow(email)
    }

    private fun triggerEmailOtpFlow(email: String) {
        setLoading(true)
        val generatedOtp = String.format("%06d", (0..999999).random())

        try {
            val intent = Intent(this, OtpActivity::class.java).apply {
                putExtra("EXTRA_EMAIL", email)
                putExtra("EXTRA_OTP", generatedOtp)
            }
            otpLauncher.launch(intent)
        } catch (e: ActivityNotFoundException) {
            setLoading(false)
            showError("OtpActivity is not registered in AndroidManifest.xml.")
        } catch (e: Exception) {
            setLoading(false)
            showError("Failed to launch OTP screen: ${e.message}")
        }
    }

    private fun executeFirebaseSignup() {
        setLoading(true)
        auth.createUserWithEmailAndPassword(pendingEmail, pendingPassword)
            .addOnSuccessListener { authResult ->
                val uid = authResult.user?.uid
                if (uid != null) {
                    saveUserProfileToDatabase(
                        uid,
                        pendingFirstName,
                        pendingLastName,
                        pendingEmail,
                        pendingPhoneNumber,
                        pendingBirthDate,
                        pendingRole
                    )
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
                    Toast.makeText(this, "Driver account registered! Please complete document submission.", Toast.LENGTH_LONG).show()
                    goToDriverDocumentUpload()
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

    private fun goToDriverDocumentUpload() {
        val intent = Intent(this, DriverDocumentUploadActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
        }
        startActivity(intent)
        finish()
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