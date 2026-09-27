package com.ruta.app.ui.driver

import android.net.Uri
import android.os.Bundle
import android.util.Log
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

            if (licenseUri == null || registrationUri == null || nbiUri == null) {
                Toast.makeText(this, "Please attach License, OR/CR, and NBI Clearance.", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            uploadCredentials(model, plate, color)
        }
    }

    private fun uploadCredentials(model: String, plate: String, color: String) {
        val uid = auth.currentUser?.uid ?: return
        val licUri = licenseUri ?: return
        val regUri = registrationUri ?: return
        val nbiClearanceUri = nbiUri ?: return

        progressBar.visibility = View.VISIBLE

        // Process images on a background thread to prevent UI locking
        Thread {
            try {
                val licenseBase64 = ImageUtils.uriToCompressedBase64(this, licUri)
                val registrationBase64 = ImageUtils.uriToCompressedBase64(this, regUri)
                val nbiBase64 = ImageUtils.uriToCompressedBase64(this, nbiClearanceUri)

                runOnUiThread {
                    if (licenseBase64 == null || registrationBase64 == null || nbiBase64 == null) {
                        progressBar.visibility = View.GONE
                        Toast.makeText(this, "Failed to process attached document photos.", Toast.LENGTH_SHORT).show()
                        return@runOnUiThread
                    }

                    // Format as Data URIs for Realtime DB storage
                    val updates = mapOf<String, Any>(
                        "vehicleModel" to model,
                        "plateNumber" to plate,
                        "vehicleColor" to color,
                        "licenseUrl" to "data:image/jpeg;base64,$licenseBase64",
                        "registrationUrl" to "data:image/jpeg;base64,$registrationBase64",
                        "nbiClearanceUrl" to "data:image/jpeg;base64,$nbiBase64",
                        "documentsSubmitted" to true
                    )

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
                    Log.e("DriverDocUpload", "Image encoding failed", e)
                    Toast.makeText(this, "Error processing images: ${e.message}", Toast.LENGTH_SHORT).show()
                }
            }
        }.start()
    }
}