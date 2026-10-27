package com.ruta.app.ui.driver

import android.net.Uri
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.ImageButton
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowCompat
import com.google.android.material.textfield.TextInputEditText
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.database.FirebaseDatabase
import com.ruta.app.R
import com.ruta.app.util.ImageUtils
import com.ruta.app.util.keepClearOfKeyboard

class DriverDocumentUploadActivity : AppCompatActivity() {

    private val auth = FirebaseAuth.getInstance()
    private val database = FirebaseDatabase.getInstance().reference

    private var licenseUri: Uri? = null
    private var registrationUri: Uri? = null
    private var nbiUri: Uri? = null

    private lateinit var txtLicenseStatus: TextView
    private lateinit var txtRegistrationStatus: TextView
    private lateinit var txtNbiStatus: TextView
    private lateinit var progressBar: ProgressBar

    // Documents the admin already marked good: kept as they are, no re-upload needed.
    private val approvedDocs = mutableSetOf<String>()

    // Photo selection contract pickers
    private val pickLicense = registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) {
            licenseUri = uri
            txtLicenseStatus.text = "License Attached ✓"
        }
    }

    private val pickRegistration = registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) {
            registrationUri = uri
            txtRegistrationStatus.text = "OR/CR Attached ✓"
        }
    }

    private val pickNbi = registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) {
            nbiUri = uri
            txtNbiStatus.text = "NBI Clearance Attached ✓"
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_driver_document_upload)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        keepClearOfKeyboard(findViewById(android.R.id.content))

        val btnBack = findViewById<ImageButton>(R.id.btnBack)
        val edtModel = findViewById<TextInputEditText>(R.id.edtVehicleModel)
        val edtPlate = findViewById<TextInputEditText>(R.id.edtPlateNumber)
        val edtColor = findViewById<TextInputEditText>(R.id.edtVehicleColor)

        txtLicenseStatus = findViewById(R.id.txtLicenseStatus)
        txtRegistrationStatus = findViewById(R.id.txtRegistrationStatus)
        txtNbiStatus = findViewById(R.id.txtNbiStatus)
        progressBar = findViewById(R.id.progressBar)

        val btnLicense = findViewById<Button>(R.id.btnSelectLicense)
        val btnRegistration = findViewById<Button>(R.id.btnSelectRegistration)
        val btnNbi = findViewById<Button>(R.id.btnSelectNbi)
        val btnSubmit = findViewById<Button>(R.id.btnSubmitDocuments)

        // Show what the admin said about each file, and prefill the vehicle info.
        auth.currentUser?.uid?.let { uid ->
            database.child("users").child(uid).get().addOnSuccessListener { snap ->
                edtModel.setText(snap.child("vehicleModel").getValue(String::class.java) ?: "")
                edtPlate.setText(snap.child("plateNumber").getValue(String::class.java) ?: "")
                edtColor.setText(snap.child("vehicleColor").getValue(String::class.java) ?: "")

                val review = snap.child("docReview")
                if (snap.child("docReviewStatus").getValue(String::class.java) == "REJECTED") {
                    showReview(review.child("license"), "license", txtLicenseStatus, "Driver's License")
                    showReview(review.child("registration"), "registration", txtRegistrationStatus, "OR/CR")
                    showReview(review.child("nbi"), "nbi", txtNbiStatus, "NBI Clearance")
                    Toast.makeText(this, "Some documents need changes. See the notes in red.", Toast.LENGTH_LONG).show()
                }
            }
        }

        // Return / Back action
        btnBack.setOnClickListener { finish() }

        btnLicense.setOnClickListener { pickLicense.launch("image/*") }
        btnRegistration.setOnClickListener { pickRegistration.launch("image/*") }
        btnNbi.setOnClickListener { pickNbi.launch("image/*") }

        btnSubmit.setOnClickListener {
            val model = edtModel.text.toString().trim()
            val plate = edtPlate.text.toString().trim()
            val color = edtColor.text.toString().trim()

            if (model.isEmpty() || plate.isEmpty() || color.isEmpty()) {
                Toast.makeText(this, "Please fill out all vehicle information fields.", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            val missing = (licenseUri == null && "license" !in approvedDocs) ||
                (registrationUri == null && "registration" !in approvedDocs) ||
                (nbiUri == null && "nbi" !in approvedDocs)
            if (missing) {
                Toast.makeText(this, "Please attach every document that is not already approved.", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            uploadCredentials(model, plate, color)
        }
    }

    private fun showReview(node: com.google.firebase.database.DataSnapshot, key: String, label: TextView, name: String) {
        val ok = node.child("ok").getValue(Boolean::class.java)
        if (ok == true) {
            approvedDocs.add(key)
            label.text = "$name approved ✓ (no need to upload again)"
            label.setTextColor(android.graphics.Color.parseColor("#059669"))
        } else if (ok == false) {
            val why = node.child("comment").getValue(String::class.java).orEmpty()
            label.text = "✗ $name rejected: $why. Please upload a new photo."
            label.setTextColor(android.graphics.Color.parseColor("#DC2626"))
        }
    }

    private fun uploadCredentials(model: String, plate: String, color: String) {
        val uid = auth.currentUser?.uid ?: return

        progressBar.visibility = View.VISIBLE

        // Process images on a background thread to prevent UI locking
        Thread {
            try {
                // Only the documents the driver picked this time; approved ones are kept untouched.
                val chosen = linkedMapOf(
                    "license" to (licenseUri to "licenseUrl"),
                    "registration" to (registrationUri to "registrationUrl"),
                    "nbi" to (nbiUri to "nbiClearanceUrl")
                )
                val encoded = HashMap<String, String?>()
                for ((key, pair) in chosen) {
                    val uri = pair.first ?: continue
                    encoded[key] = ImageUtils.uriToCompressedBase64(this, uri)
                }

                runOnUiThread {
                    if (encoded.values.any { it == null }) {
                        progressBar.visibility = View.GONE
                        Toast.makeText(this, "Failed to process attached document photos.", Toast.LENGTH_SHORT).show()
                        return@runOnUiThread
                    }

                    // Format as Data URIs for Realtime DB storage
                    val updates = hashMapOf<String, Any?>(
                        "vehicleModel" to model,
                        "plateNumber" to plate,
                        "vehicleColor" to color,
                        "documentsSubmitted" to true,
                        "docReviewStatus" to "PENDING"
                    )
                    for ((key, b64) in encoded) {
                        updates[chosen.getValue(key).second] = "data:image/jpeg;base64,$b64"
                        // A new file clears the old verdict for that document.
                        updates["docReview/$key"] = null
                    }

                    database.child("users").child(uid).updateChildren(updates)
                        .addOnSuccessListener {
                            progressBar.visibility = View.GONE
                            Toast.makeText(this, "Credentials submitted! Awaiting Admin approval.", Toast.LENGTH_LONG).show()
                            finish()
                        }
                        .addOnFailureListener { e ->
                            progressBar.visibility = View.GONE
                            Toast.makeText(this, "Failed to update profile: ${e.message}", Toast.LENGTH_SHORT).show()
                        }
                }
            } catch (e: Exception) {
                runOnUiThread {
                    progressBar.visibility = View.GONE
                    Toast.makeText(this, "Error processing images: ${e.message}", Toast.LENGTH_SHORT).show()
                }
            }
        }.start()
    }
}
