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
import com.ruta.app.util.PhotoPaths
import com.ruta.app.util.StoragePhotos
import com.ruta.app.util.keepClearOfKeyboard

class DriverDocumentUploadActivity : AppCompatActivity() {

    private val auth = FirebaseAuth.getInstance()
    private val database = FirebaseDatabase.getInstance().reference

    private var licenseUri: Uri? = null
    private var registrationUri: Uri? = null
    private var nbiUri: Uri? = null
    private var photoUri: Uri? = null
    private var alreadyHasPhoto = false

    private lateinit var txtLicenseStatus: TextView
    private lateinit var txtRegistrationStatus: TextView
    private lateinit var txtNbiStatus: TextView
    private lateinit var txtPhotoStatus: TextView
    private lateinit var imgPhotoPreview: android.widget.ImageView
    private lateinit var progressBar: ProgressBar

    // Documents the admin already marked good: kept as they are, no re-upload needed.
    private val approvedDocs = mutableSetOf<String>()
    private val rejectedDocs = mutableSetOf<String>()

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

    private val pickPhoto = registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) {
            photoUri = uri
            imgPhotoPreview.setImageURI(uri)
            txtPhotoStatus.text = "1x1 photo attached ✓"
            txtPhotoStatus.setTextColor(android.graphics.Color.parseColor("#6B7280"))
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
        txtPhotoStatus = findViewById(R.id.txtPhotoStatus)
        imgPhotoPreview = findViewById(R.id.imgPhotoPreview)
        progressBar = findViewById(R.id.progressBar)

        val btnLicense = findViewById<Button>(R.id.btnSelectLicense)
        val btnRegistration = findViewById<Button>(R.id.btnSelectRegistration)
        val btnNbi = findViewById<Button>(R.id.btnSelectNbi)
        val btnSubmit = findViewById<Button>(R.id.btnSubmitDocuments)
        findViewById<Button>(R.id.btnSelectPhoto).setOnClickListener { pickPhoto.launch("image/*") }

        // Show what the admin said about each file, and prefill the vehicle info.
        auth.currentUser?.uid?.let { uid ->
            database.child("users").child(uid).get().addOnSuccessListener { snap ->
                edtModel.setText(snap.child("vehicleModel").getValue(String::class.java) ?: "")
                edtPlate.setText(snap.child("plateNumber").getValue(String::class.java) ?: "")
                edtColor.setText(snap.child("vehicleColor").getValue(String::class.java) ?: "")
                val existingPhoto = snap.child("profilePhoto").getValue(String::class.java)
                alreadyHasPhoto = !existingPhoto.isNullOrBlank()
                if (alreadyHasPhoto) StoragePhotos.show(imgPhotoPreview, existingPhoto, R.drawable.ic_person)

                val review = snap.child("docReview")
                if (snap.child("docReviewStatus").getValue(String::class.java) == "REJECTED") {
                    showReview(review.child("license"), "license", txtLicenseStatus, "Driver's License")
                    showReview(review.child("registration"), "registration", txtRegistrationStatus, "OR/CR")
                    showReview(review.child("nbi"), "nbi", txtNbiStatus, "NBI Clearance")
                    showReview(review.child("photo"), "photo", txtPhotoStatus, "1x1 photo")
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
                (nbiUri == null && "nbi" !in approvedDocs) ||
                (photoUri == null && "photo" !in approvedDocs && (!alreadyHasPhoto || "photo" in rejectedDocs))
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
            rejectedDocs.add(key)
            val why = node.child("comment").getValue(String::class.java).orEmpty()
            label.text = "✗ $name rejected: $why. Please upload a new photo."
            label.setTextColor(android.graphics.Color.parseColor("#DC2626"))
        }
    }

    private fun uploadCredentials(model: String, plate: String, color: String) {
        val uid = auth.currentUser?.uid ?: return

        progressBar.visibility = View.VISIBLE
        findViewById<Button>(R.id.btnSubmitDocuments).isEnabled = false

        // Decode/compress off the main thread, then upload each chosen file to Firebase Storage.
        // Only files picked this time are sent; documents the admin already approved stay as they are.
        Thread {
            val jobs = linkedMapOf<String, Pair<String, String>>()   // db field -> (storage path, base64)
            var failed = false
            fun add(uri: Uri?, field: String, path: String, square: Boolean = false) {
                if (uri == null) return
                val b64 = if (square) ImageUtils.uriToAvatarBase64(this, uri, 512)
                else ImageUtils.uriToCompressedBase64(this, uri)
                if (b64 == null) failed = true else jobs[field] = path to b64
            }
            try {
                add(licenseUri, "licenseUrl", PhotoPaths.driverDoc(uid, "license"))
                add(registrationUri, "registrationUrl", PhotoPaths.driverDoc(uid, "registration"))
                add(nbiUri, "nbiClearanceUrl", PhotoPaths.driverDoc(uid, "nbi"))
                add(photoUri, "profilePhoto", PhotoPaths.avatar(uid), square = true)
            } catch (e: Exception) { failed = true }

            runOnUiThread {
                if (failed) { finishFail("Failed to process attached photos."); return@runOnUiThread }

                val updates = hashMapOf<String, Any?>(
                    "vehicleModel" to model,
                    "plateNumber" to plate,
                    "vehicleColor" to color,
                    "documentsSubmitted" to true,
                    "docReviewStatus" to "PENDING"
                )
                val queue = jobs.entries.toMutableList()
                fun next() {
                    val job = queue.removeFirstOrNull()
                    if (job == null) {
                        database.child("users").child(uid).updateChildren(updates)
                            .addOnSuccessListener {
                                progressBar.visibility = View.GONE
                                Toast.makeText(this, "Credentials submitted! Awaiting Admin approval.", Toast.LENGTH_LONG).show()
                                finish()
                            }
                            .addOnFailureListener { e -> finishFail("Failed to update profile: ${e.message}") }
                        return
                    }
                    val (path, b64) = job.value
                    StoragePhotos.uploadBase64(path, b64) { ref, error ->
                        if (ref == null) { finishFail("Upload failed: $error"); return@uploadBase64 }
                        updates[job.key] = ref
                        // A new file clears the old verdict for that document.
                        val reviewKey = when (job.key) {
                            "licenseUrl" -> "license"; "registrationUrl" -> "registration"
                            "nbiClearanceUrl" -> "nbi"; else -> "photo"
                        }
                        updates["docReview/$reviewKey"] = null
                        if (job.key == "profilePhoto") {
                            updates["photoLocked"] = true
                            updates["profilePhotoUpdatedAt"] = System.currentTimeMillis()
                        }
                        next()
                    }
                }
                next()
            }
        }.start()
    }

    private fun finishFail(message: String) {
        progressBar.visibility = View.GONE
        findViewById<Button>(R.id.btnSubmitDocuments).isEnabled = true
        Toast.makeText(this, message, Toast.LENGTH_LONG).show()
    }
}
