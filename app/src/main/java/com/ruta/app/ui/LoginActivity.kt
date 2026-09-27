package com.ruta.app.ui

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.View
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import com.google.android.gms.auth.api.signin.GoogleSignIn
import com.google.android.gms.auth.api.signin.GoogleSignInClient
import com.google.android.gms.auth.api.signin.GoogleSignInOptions
import com.google.android.gms.common.api.ApiException
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.FirebaseAuthInvalidCredentialsException
import com.google.firebase.auth.FirebaseAuthInvalidUserException
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
            val idToken = account.idToken

            if (!idToken.isNullOrEmpty()) {
                val credential = GoogleAuthProvider.getCredential(idToken, null)
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
                        showError("Google login failed: ${e.localizedMessage}")
                    }
            } else {
                setLoading(false)
                showError("Could not retrieve a valid Google ID token. Please try again.")
            }
        } catch (e: ApiException) {
            setLoading(false)
            showError("Google Sign-In canceled or failed (Code: ${e.statusCode})")
        } catch (e: Exception) {
            setLoading(false)
            showError("Google Sign-In failed: ${e.localizedMessage}")
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // 1. Inflate binding FIRST
        binding = ActivityLoginBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // 2. Enable Edge-to-Edge
        WindowCompat.setDecorFitsSystemWindows(window, false)

        // 3. Dynamic Keyboard (IME) and System Bar Inset Handling
        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { view, insets ->
            val imeInsets = insets.getInsets(WindowInsetsCompat.Type.ime())
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())

            // Sets bottom padding based on whichever is larger (keyboard or nav bar)
            view.setPadding(
                systemBars.left,
                systemBars.top,
                systemBars.right,
                maxOf(imeInsets.bottom, systemBars.bottom)
            )
            insets
        }

        setupGoogleSignIn()
        checkAutoLogin()

        // 4. Standard Email/Password Login
        binding.btnLogin.setOnClickListener { attemptLogin() }

        // 5. Google Sign-In with forced client sign-out to clear stale tokens
        binding.btnGoogle.setOnClickListener {
            hideError()
            setLoading(true)
            googleSignInClient.signOut().addOnCompleteListener {
                val signInIntent = googleSignInClient.signInIntent
                googleLoginLauncher.launch(signInIntent)
            }
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

        if (cachedUid == currentUser.uid && !cachedRole.isNullOrEmpty()) {
            goToHome(parseRole(cachedRole))
            return
        }

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
                val friendlyMessage = when (exception) {
                    is FirebaseAuthInvalidCredentialsException -> "Incorrect email or password."
                    is FirebaseAuthInvalidUserException -> "No account found with this email."
                    else -> exception.localizedMessage ?: "Login failed. Please check your credentials."
                }
                showError(friendlyMessage)
            }
    }

    private fun fetchRoleAndNavigate(uid: String) {
        database.reference
            .child("users")
            .child(uid)
            .get()
            .addOnSuccessListener { snapshot ->
                val roleStr = snapshot.child("role").getValue(String::class.java) ?: "PASSENGER"
                val userName = snapshot.child("name").getValue(String::class.java) ?: "User"
                val isApproved = snapshot.child("isApproved").getValue(Boolean::class.java) ?: false

                val role = parseRole(roleStr)

                setLoading(false)

                val prefs = getSharedPreferences("USER_SESSION", Context.MODE_PRIVATE)
                prefs.edit()
                    .putString("USER_UID", uid)
                    .putString("USER_ROLE", roleStr)
                    .putString("USER_NAME", userName)
                    .putBoolean("IS_APPROVED", isApproved)
                    .apply()

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