package com.ruta.app.ui

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import com.google.android.gms.auth.api.signin.GoogleSignIn
import com.google.android.gms.auth.api.signin.GoogleSignInClient
import com.google.android.gms.auth.api.signin.GoogleSignInOptions
import com.google.android.gms.common.api.ApiException
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.GoogleAuthProvider
import com.google.firebase.database.FirebaseDatabase
import com.ruta.app.R
import com.ruta.app.databinding.ActivityLoginBinding
import com.ruta.app.model.UserRole
import com.ruta.app.ui.driver.DriverMainActivity

class LoginActivity : AppCompatActivity() {

    private lateinit var binding: ActivityLoginBinding
    private val auth: FirebaseAuth by lazy { FirebaseAuth.getInstance() }
    private val database: FirebaseDatabase by lazy { FirebaseDatabase.getInstance() }
    private lateinit var googleSignInClient: GoogleSignInClient

    private val googleLoginLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val task = GoogleSignIn.getSignedInAccountFromIntent(result.data)
        try {
            val account = task.getResult(ApiException::class.java)!!
            val credential = GoogleAuthProvider.getCredential(account.idToken, null)

            setLoading(true)
            auth.signInWithCredential(credential)
                .addOnSuccessListener { authResult ->
                    val uid = authResult.user?.uid
                    if (uid != null) {
                        fetchRoleAndNavigate(uid)
                    } else {
                        setLoading(false)
                        showError("User ID not found.")
                    }
                }
                .addOnFailureListener { e ->
                    setLoading(false)
                    showError("Google login failed: ${e.message}")
                }
        } catch (e: Exception) {
            setLoading(false)
            Toast.makeText(this, "Google Sign-In canceled or failed.", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityLoginBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // Setup Google Auth Client
        setupGoogleSignIn()

        // Auto-check session immediately
        checkAutoLogin()

        binding.btnLogin.setOnClickListener { attemptLogin() }

        // Trigger Google Sign-In on button click
        binding.btnGoogle.setOnClickListener {
            setLoading(true)
            val signInIntent = googleSignInClient.signInIntent
            googleLoginLauncher.launch(signInIntent)
        }

        binding.txtGoSignup.setOnClickListener {
            startActivity(Intent(this, SignupActivity::class.java))
        }
    }

    private fun setupGoogleSignIn() {
        val gso = GoogleSignInOptions.Builder(GoogleSignInOptions.DEFAULT_SIGN_IN)
            .requestIdToken(getString(R.string.default_web_client_id))
            .requestEmail()
            .build()
        googleSignInClient = GoogleSignIn.getClient(this, gso)
    }

    private fun checkAutoLogin() {
        val currentUser = auth.currentUser ?: return

        val prefs = getSharedPreferences("USER_SESSION", Context.MODE_PRIVATE)
        val cachedUid = prefs.getString("USER_UID", null)
        val cachedRole = prefs.getString("USER_ROLE", null)

        // FAST-PATH: Only bypass network if the cached user ID strictly matches the logged-in Firebase UID
        if (cachedUid == currentUser.uid && !cachedRole.isNullOrEmpty()) {
            goToHome(parseRole(cachedRole))
            return
        }

        // If UIDs don't match or cache is missing, clear old cache and fetch fresh role from Firebase
        prefs.edit().clear().apply()
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
        if (email.equals("admin@ruta.app", ignoreCase = true)) {
            if (password != "LigmaRUTA2.31") {
                showError("Invalid admin credentials.")
                return
            }
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
                val userName = snapshot.child("name").getValue(String::class.java) ?: "User"

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
        binding.btnGoogle.isEnabled = !loading
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