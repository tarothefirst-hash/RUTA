package com.ruta.app.ui

import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.provider.MediaStore
import android.provider.OpenableColumns
import android.util.Base64
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.ImageView
import android.widget.ProgressBar
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.Fragment
import com.bumptech.glide.Glide
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.database.DataSnapshot
import com.google.firebase.database.DatabaseError
import com.google.firebase.database.DatabaseReference
import com.google.firebase.database.ValueEventListener
import com.google.firebase.database.FirebaseDatabase
import com.ruta.app.R
import com.ruta.app.util.ImageUtils
import com.ruta.app.util.PhotoPaths
import com.ruta.app.util.StoragePhotos

class PromosFragment : Fragment() {

    private lateinit var txtVerificationStatus: TextView
    private lateinit var radioGroupDiscount: RadioGroup
    private lateinit var radioStudent: RadioButton
    private lateinit var radioSenior: RadioButton
    private lateinit var txtLimitNotice: TextView
    private lateinit var imgFrontPreview: ImageView
    private lateinit var btnUploadFront: Button
    private lateinit var imgBackPreview: ImageView
    private lateinit var btnUploadBack: Button
    private lateinit var progressBar: ProgressBar
    private lateinit var btnSubmitDiscount: Button
    private lateinit var layoutApproved: View
    private lateinit var scrollForm: View

    private var frontUri: Uri? = null
    private var backUri: Uri? = null

    private var frontBase64: String? = null
    private var backBase64: String? = null
    // true = freshly picked (still needs uploading); false = already stored (Storage ref or legacy Base64)
    private var frontIsNew = false
    private var backIsNew = false

    private val currentUid = FirebaseAuth.getInstance().currentUser?.uid
    private val database: DatabaseReference = FirebaseDatabase.getInstance().reference

    private val MAX_FILE_SIZE_BYTES = 5 * 1024 * 1024 // 5 MB

    private val frontIdLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK && result.data?.data != null) {
            val uri = result.data?.data!!

            if (isFileSizeExceeded(uri)) {
                Toast.makeText(requireContext(), "Photo exceeds the 5MB limit. Please choose a smaller image.", Toast.LENGTH_LONG).show()
                return@registerForActivityResult
            }

            val convertedBase64 = ImageUtils.uriToCompressedBase64(requireContext(), uri)
            if (convertedBase64 != null && convertedBase64 == backBase64) {
                Toast.makeText(requireContext(), "You cannot upload the exact same photo for both Front and Back ID!", Toast.LENGTH_LONG).show()
                return@registerForActivityResult
            }

            showUploadConfirmationDialog("Front ID") {
                frontUri = uri
                frontBase64 = convertedBase64
                frontIsNew = true
                if (frontBase64 != null) {
                    imgFrontPreview.setImageURI(uri)
                    btnUploadFront.text = "Replace / Remove Front ID"
                } else {
                    Toast.makeText(requireContext(), "Failed to process Front ID image", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    private val backIdLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK && result.data?.data != null) {
            val uri = result.data?.data!!

            if (isFileSizeExceeded(uri)) {
                Toast.makeText(requireContext(), "Photo exceeds the 5MB limit. Please choose a smaller image.", Toast.LENGTH_LONG).show()
                return@registerForActivityResult
            }

            val convertedBase64 = ImageUtils.uriToCompressedBase64(requireContext(), uri)
            if (convertedBase64 != null && convertedBase64 == frontBase64) {
                Toast.makeText(requireContext(), "You cannot upload the exact same photo for both Front and Back ID!", Toast.LENGTH_LONG).show()
                return@registerForActivityResult
            }

            showUploadConfirmationDialog("Back ID") {
                backUri = uri
                backBase64 = convertedBase64
                backIsNew = true
                if (backBase64 != null) {
                    imgBackPreview.setImageURI(uri)
                    btnUploadBack.text = "Replace / Remove Back ID"
                } else {
                    Toast.makeText(requireContext(), "Failed to process Back ID image", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View? {
        return inflater.inflate(R.layout.fragment_promos, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        txtVerificationStatus = view.findViewById(R.id.txtVerificationStatus)
        radioGroupDiscount = view.findViewById(R.id.radioGroupDiscount)
        radioStudent = view.findViewById(R.id.radioStudent)
        radioSenior = view.findViewById(R.id.radioSenior)
        imgFrontPreview = view.findViewById(R.id.imgFrontPreview)
        btnUploadFront = view.findViewById(R.id.btnUploadFront)
        imgBackPreview = view.findViewById(R.id.imgBackPreview)
        btnUploadBack = view.findViewById(R.id.btnUploadBack)
        progressBar = view.findViewById(R.id.progressBar)
        btnSubmitDiscount = view.findViewById(R.id.btnSubmitDiscount)
        layoutApproved = view.findViewById(R.id.layoutDiscountApproved)
        scrollForm = view.findViewById(R.id.scrollDiscountForm)

        txtLimitNotice = view.findViewById(R.id.txtLimitNotice) ?: TextView(requireContext())
        txtLimitNotice.text = "Note: Upload valid ID photos up to 5MB max."

        loadExistingDiscountStatus()

        btnUploadFront.setOnClickListener {
            if (frontBase64 != null) {
                showReplaceOrRemoveMenu("Front ID", onReplace = { openImagePicker(frontIdLauncher) }, onRemove = {
                    frontUri = null
                    frontBase64 = null
                    imgFrontPreview.setImageResource(R.drawable.ic_business)
                    btnUploadFront.text = getString(R.string.h_upload_id_front_button)
                })
            } else {
                openImagePicker(frontIdLauncher)
            }
        }

        btnUploadBack.setOnClickListener {
            if (backBase64 != null) {
                showReplaceOrRemoveMenu("Back ID", onReplace = { openImagePicker(backIdLauncher) }, onRemove = {
                    backUri = null
                    backBase64 = null
                    imgBackPreview.setImageResource(R.drawable.ic_business)
                    btnUploadBack.text = getString(R.string.h_upload_id_back_button)
                })
            } else {
                openImagePicker(backIdLauncher)
            }
        }

        btnSubmitDiscount.setOnClickListener { submitDiscountApplication() }
    }

    private fun isFileSizeExceeded(uri: Uri): Boolean {
        return try {
            val cursor = requireContext().contentResolver.query(uri, null, null, null, null)
            val sizeIndex = cursor?.getColumnIndex(OpenableColumns.SIZE)
            cursor?.moveToFirst()
            val size = if (sizeIndex != null && sizeIndex != -1) cursor.getLong(sizeIndex) else 0L
            cursor?.close()
            size > MAX_FILE_SIZE_BYTES
        } catch (e: Exception) {
            false
        }
    }

    private fun openImagePicker(launcher: androidx.activity.result.ActivityResultLauncher<Intent>) {
        val intent = Intent(Intent.ACTION_PICK, MediaStore.Images.Media.EXTERNAL_CONTENT_URI)
        launcher.launch(intent)
    }

    private fun showUploadConfirmationDialog(idType: String, onConfirm: () -> Unit) {
        AlertDialog.Builder(requireContext())
            .setTitle("Confirm Selection")
            .setMessage("Do you want to set this image as your $idType?")
            .setPositiveButton("Confirm") { _, _ -> onConfirm() }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun showReplaceOrRemoveMenu(idType: String, onReplace: () -> Unit, onRemove: () -> Unit) {
        val options = arrayOf("Replace Photo", "Remove Photo")
        AlertDialog.Builder(requireContext())
            .setTitle(idType)
            .setItems(options) { _, which ->
                if (which == 0) onReplace() else onRemove()
            }
            .show()
    }

    private fun submitDiscountApplication() {
        if (frontBase64.isNullOrEmpty() || backBase64.isNullOrEmpty()) {
            Toast.makeText(requireContext(), "Please upload both Front and Back ID photos.", Toast.LENGTH_SHORT).show()
            return
        }

        if (frontIsNew && backIsNew && frontBase64 == backBase64) {
            Toast.makeText(requireContext(), "Front ID and Back ID cannot be the same image!", Toast.LENGTH_LONG).show()
            return
        }

        val uid = currentUid ?: return
        val discountType = if (radioStudent.isChecked) "STUDENT" else "SENIOR_PWD"

        progressBar.visibility = View.VISIBLE
        btnSubmitDiscount.isEnabled = false

        // 1) photos to Firebase Storage (only the ones picked this time), 2) the request itself.
        uploadSide("front", frontBase64!!, frontIsNew, uid) { frontRef, e1 ->
            if (!isAdded) return@uploadSide
            if (frontRef == null) { submitFailed(e1); return@uploadSide }
            uploadSide("back", backBase64!!, backIsNew, uid) { backRef, e2 ->
                if (!isAdded) return@uploadSide
                if (backRef == null) { submitFailed(e2); return@uploadSide }
                saveRequest(uid, discountType, frontRef, backRef)
            }
        }
    }

    private fun uploadSide(
        side: String, value: String, isNew: Boolean, uid: String,
        done: (ref: String?, error: String?) -> Unit
    ) {
        if (!isNew) { done(value, null); return }
        StoragePhotos.uploadBase64(PhotoPaths.discountId(uid, side), value, done)
    }

    private fun submitFailed(error: String?) {
        progressBar.visibility = View.GONE
        btnSubmitDiscount.isEnabled = true
        Toast.makeText(requireContext(), "Upload failed: ${error ?: "check your connection"}", Toast.LENGTH_LONG).show()
    }

    private fun saveRequest(uid: String, discountType: String, frontRef: String, backRef: String) {
        database.child("users").child(uid).get().addOnCompleteListener { task ->
            if (!isAdded) return@addOnCompleteListener
            val snap = task.result
            val name = snap?.child("name")?.getValue(String::class.java)
                ?: "${snap?.child("firstName")?.getValue(String::class.java) ?: ""} ${snap?.child("lastName")?.getValue(String::class.java) ?: ""}".trim()

            // Status PENDING is what the admin's ID Confirmation queue listens for.
            val request = hashMapOf<String, Any>(
                "uid" to uid,
                "userId" to uid,
                "userName" to name.ifBlank { "Passenger" },
                "discountType" to discountType,
                "frontRef" to frontRef,
                "backRef" to backRef,
                "status" to "PENDING",
                "timestamp" to System.currentTimeMillis()
            )

            database.child("discount_requests").child(uid).setValue(request)
                .addOnSuccessListener {
                    if (!isAdded) return@addOnSuccessListener
                    progressBar.visibility = View.GONE
                    frontIsNew = false
                    backIsNew = false
                    frontBase64 = frontRef
                    backBase64 = backRef
                    updateStatusUi("PENDING", null)
                    Toast.makeText(requireContext(), "Application submitted for review!", Toast.LENGTH_LONG).show()
                }
                .addOnFailureListener { error ->
                    if (!isAdded) return@addOnFailureListener
                    progressBar.visibility = View.GONE
                    btnSubmitDiscount.isEnabled = true
                    Toast.makeText(requireContext(), "Submission Failed: ${error.message}", Toast.LENGTH_SHORT).show()
                }
        }
    }

    private var statusListener: ValueEventListener? = null

    // Live: when the admin approves or rejects, this screen changes by itself.
    private fun loadExistingDiscountStatus() {
        val uid = currentUid ?: return
        val l = object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                if (!isAdded || view == null) return
                applyDiscountSnapshot(snapshot)
            }
            override fun onCancelled(error: DatabaseError) {}
        }
        statusListener = l
        database.child("discount_requests").child(uid).addValueEventListener(l)
    }

    override fun onDestroyView() {
        val uid = currentUid
        statusListener?.let { l -> if (uid != null) database.child("discount_requests").child(uid).removeEventListener(l) }
        statusListener = null
        super.onDestroyView()
    }

    private fun applyDiscountSnapshot(snapshot: DataSnapshot) {
        if (!snapshot.exists()) return
                val status = snapshot.child("status").getValue(String::class.java) ?: "PENDING_REVIEW"
                // New requests keep a Storage reference; older ones kept the Base64 inline.
                val loadedFrontBase64 = snapshot.child("frontRef").getValue(String::class.java)
                    ?: snapshot.child("frontBase64").getValue(String::class.java)
                val loadedBackBase64 = snapshot.child("backRef").getValue(String::class.java)
                    ?: snapshot.child("backBase64").getValue(String::class.java)

                // Items the admin marked with an X must be re-uploaded; the good ones are kept.
                val review = snapshot.child("review")
                val frontBad = status == "REJECTED" && review.child("front").child("ok").getValue(Boolean::class.java) == false
                val backBad = status == "REJECTED" && review.child("back").child("ok").getValue(Boolean::class.java) == false

                if (!frontBad && !loadedFrontBase64.isNullOrEmpty()) {
                    frontBase64 = loadedFrontBase64
                    frontIsNew = false
                    StoragePhotos.show(imgFrontPreview, loadedFrontBase64, R.drawable.ic_business)
                    btnUploadFront.text = "Replace / Remove Front ID"
                }
                if (!backBad && !loadedBackBase64.isNullOrEmpty()) {
                    backBase64 = loadedBackBase64
                    backIsNew = false
                    StoragePhotos.show(imgBackPreview, loadedBackBase64, R.drawable.ic_business)
                    btnUploadBack.text = "Replace / Remove Back ID"
                }

                if (frontBad) {
                    frontBase64 = null
                    imgFrontPreview.setImageResource(R.drawable.ic_business)
                    btnUploadFront.text = getString(R.string.h_upload_id_front_button)
                }
                if (backBad) {
                    backBase64 = null
                    imgBackPreview.setImageResource(R.drawable.ic_business)
                    btnUploadBack.text = getString(R.string.h_upload_id_back_button)
                }
                val reasons = buildString {
                    if (frontBad) append("Front ID: ${review.child("front").child("comment").getValue(String::class.java) ?: "Not accepted"}")
                    if (frontBad && backBad) append("\n")
                    if (backBad) append("Back ID: ${review.child("back").child("comment").getValue(String::class.java) ?: "Not accepted"}")
                }

                updateStatusUi(status, reasons.ifBlank { null })
    }

    private fun loadBase64IntoImageView(base64Str: String, imageView: ImageView) {
        try {
            val imageBytes = Base64.decode(base64Str, Base64.DEFAULT)
            Glide.with(this)
                .asBitmap()
                .load(imageBytes)
                .into(imageView)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun updateStatusUi(status: String, rejectionReasons: String?) {
        txtVerificationStatus.visibility = View.VISIBLE
        // Approved: the whole screen is replaced by the "Registration Complete" panel.
        layoutApproved.visibility = if (status == "APPROVED") View.VISIBLE else View.GONE
        scrollForm.visibility = if (status == "APPROVED") View.GONE else View.VISIBLE
        when (status) {
            "APPROVED" -> {
                txtVerificationStatus.text = "✓ Verification Approved! Your discount is active."
                txtVerificationStatus.setBackgroundColor(Color.parseColor("#E8F5E9"))
                txtVerificationStatus.setTextColor(Color.parseColor("#2E7D32"))
                btnSubmitDiscount.text = "Discount Active ✓"
                btnSubmitDiscount.isEnabled = false
            }
            "REJECTED" -> {
                txtVerificationStatus.text = "✕ Verification Rejected. Please re-upload the marked ID photo(s)." +
                    (rejectionReasons?.let { "\n\n$it" } ?: "")
                txtVerificationStatus.setBackgroundColor(Color.parseColor("#FFEBEE"))
                txtVerificationStatus.setTextColor(Color.parseColor("#C62828"))
                btnSubmitDiscount.text = "Re-submit Application"
                btnSubmitDiscount.isEnabled = true
            }
            else -> {
                // PENDING (and the legacy PENDING_REVIEW)
                txtVerificationStatus.text = "⏳ Pending Review. We are verifying your uploaded IDs."
                txtVerificationStatus.setBackgroundColor(Color.parseColor("#FFF8E1"))
                txtVerificationStatus.setTextColor(Color.parseColor("#F57F17"))
                btnSubmitDiscount.text = "Application Pending Review"
                btnSubmitDiscount.isEnabled = false
            }
        }
    }
}
