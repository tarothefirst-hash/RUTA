package com.ruta.app.ui

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.database.FirebaseDatabase
import com.google.firebase.storage.FirebaseStorage
import com.ruta.app.databinding.FragmentPromosBinding
import com.ruta.app.model.DiscountRequest
import java.util.UUID

class PromosFragment : Fragment() {

    private var _binding: FragmentPromosBinding? = null
    private val binding get() = _binding!!

    private var frontImageUri: Uri? = null
    private var backImageUri: Uri? = null
    private var isSelectingFront = true

    private val auth = FirebaseAuth.getInstance()
    private val database = FirebaseDatabase.getInstance().reference
    private val storage = FirebaseStorage.getInstance().reference

    // Image Picker Launcher
    private val imagePickerLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            val uri = result.data?.data
            if (uri != null) {
                if (isSelectingFront) {
                    frontImageUri = uri
                    binding.imgFrontPreview.setImageURI(uri)
                } else {
                    backImageUri = uri
                    binding.imgBackPreview.setImageURI(uri)
                }
            }
        }
    }

    // Permission Launcher for Android 12 and lower
    private val requestStoragePermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        if (isGranted) {
            launchImagePickerIntent()
        } else {
            Toast.makeText(requireContext(), "Storage permission is required to upload ID photos.", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentPromosBinding.inflate(inflater, container, false)

        binding.btnUploadFront.setOnClickListener {
            isSelectingFront = true
            openImagePickerWithPermissionCheck()
        }

        binding.btnUploadBack.setOnClickListener {
            isSelectingFront = false
            openImagePickerWithPermissionCheck()
        }

        binding.btnSubmitDiscount.setOnClickListener {
            submitDiscountApplication()
        }

        return binding.root
    }

    private fun openImagePickerWithPermissionCheck() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            // Android 13+ (API 33+) does not need explicit READ_EXTERNAL_STORAGE for photo picking
            launchImagePickerIntent()
        } else {
            // Android 12 and below require runtime READ_EXTERNAL_STORAGE check
            val permission = Manifest.permission.READ_EXTERNAL_STORAGE
            if (ContextCompat.checkSelfPermission(requireContext(), permission) == PackageManager.PERMISSION_GRANTED) {
                launchImagePickerIntent()
            } else {
                requestStoragePermissionLauncher.launch(permission)
            }
        }
    }

    private fun launchImagePickerIntent() {
        val intent = Intent(Intent.ACTION_PICK, MediaStore.Images.Media.EXTERNAL_CONTENT_URI)
        imagePickerLauncher.launch(intent)
    }

    private fun submitDiscountApplication() {
        val user = auth.currentUser ?: return
        if (frontImageUri == null || backImageUri == null) {
            Toast.makeText(context, "Please upload both Front and Back of your ID.", Toast.LENGTH_SHORT).show()
            return
        }

        val discountType = if (binding.radioStudent.isChecked) "STUDENT" else "SENIOR"
        binding.progressBar.visibility = View.VISIBLE
        binding.btnSubmitDiscount.isEnabled = false

        // 1. Upload Front Image
        val frontRef = storage.child("discount_ids/${user.uid}_front_${UUID.randomUUID()}.jpg")
        frontRef.putFile(frontImageUri!!).addOnSuccessListener {
            frontRef.downloadUrl.addOnSuccessListener { frontUrl: Uri ->

                // 2. Upload Back Image
                val backRef = storage.child("discount_ids/${user.uid}_back_${UUID.randomUUID()}.jpg")
                backRef.putFile(backImageUri!!).addOnSuccessListener {
                    backRef.downloadUrl.addOnSuccessListener { backUrl: Uri ->

                        // 3. Save Request to Firebase Realtime Database
                        saveRequestToDatabase(discountType, frontUrl.toString(), backUrl.toString())
                    }
                }
            }
        }.addOnFailureListener { err ->
            binding.progressBar.visibility = View.GONE
            binding.btnSubmitDiscount.isEnabled = true
            Toast.makeText(context, "Failed to upload images: ${err.message}", Toast.LENGTH_SHORT).show()
        }
    }

    private fun saveRequestToDatabase(type: String, frontUrl: String, backUrl: String) {
        val uid = auth.currentUser?.uid ?: return
        val requestId = database.child("discount_requests").push().key ?: return

        database.child("users").child(uid).get().addOnSuccessListener { snapshot ->
            val firstName = snapshot.child("firstName").getValue(String::class.java) ?: ""
            val lastName = snapshot.child("lastName").getValue(String::class.java) ?: ""

            val request = DiscountRequest(
                requestId = requestId,
                userId = uid,
                userName = "$firstName $lastName".trim(),
                discountType = type,
                idFrontUrl = frontUrl,
                idBackUrl = backUrl,
                status = "PENDING"
            )

            database.child("discount_requests").child(requestId).setValue(request)
                .addOnSuccessListener {
                    binding.progressBar.visibility = View.GONE
                    Toast.makeText(context, "Application submitted! Pending admin review.", Toast.LENGTH_LONG).show()
                }
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}