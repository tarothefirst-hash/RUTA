package com.ruta.app.ui.driver

import android.Manifest
import android.app.Activity
import android.content.Context
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
import com.bumptech.glide.Glide
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.database.DatabaseReference
import com.google.firebase.database.FirebaseDatabase
import com.google.firebase.storage.FirebaseStorage
import com.ruta.app.R
import com.ruta.app.databinding.FragmentDriverProfileBinding
import com.ruta.app.ui.LoginActivity

class DriverProfileFragment : Fragment() {

    private var _binding: FragmentDriverProfileBinding? = null
    private val binding get() = _binding!!

    private val auth = FirebaseAuth.getInstance()
    private val database: DatabaseReference = FirebaseDatabase.getInstance().reference
    private val storage = FirebaseStorage.getInstance().reference

    private var selectedImageUri: Uri? = null

    // Image Picker Launcher
    private val profilePhotoLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            val uri = result.data?.data
            if (uri != null) {
                selectedImageUri = uri
                binding.imgDriverProfile.setImageURI(uri)
            }
        }
    }

    // Storage Permission Launcher (Android 12 & lower)
    private val requestStoragePermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        if (isGranted) {
            launchImagePickerIntent()
        } else {
            Toast.makeText(requireContext(), "Storage permission is required to change photo.", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentDriverProfileBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        loadDriverProfile()

        binding.btnChangeDriverPhoto.setOnClickListener {
            openImagePickerWithPermissionCheck()
        }

        binding.btnSaveDriverProfile.setOnClickListener {
            saveDriverProfile()
        }

        binding.btnDriverLogoutProfile.setOnClickListener {
            performLogout()
        }
    }

    private fun openImagePickerWithPermissionCheck() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            launchImagePickerIntent()
        } else {
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
        profilePhotoLauncher.launch(intent)
    }

    private fun loadDriverProfile() {
        val uid = auth.currentUser?.uid ?: return

        database.child("drivers").child(uid).get().addOnSuccessListener { snapshot ->
            if (snapshot.exists()) {
                val name = snapshot.child("name").getValue(String::class.java) ?: ""
                val vehicle = snapshot.child("vehicleDetails").getValue(String::class.java) ?: ""
                val phone = snapshot.child("phone").getValue(String::class.java) ?: ""
                val email = snapshot.child("email").getValue(String::class.java) ?: auth.currentUser?.email ?: ""
                val rating = snapshot.child("rating").getValue(Double::class.java) ?: 5.0
                val rides = snapshot.child("completedRides").getValue(Int::class.java) ?: 0
                val photoUrl = snapshot.child("profilePictureUrl").getValue(String::class.java)

                binding.etDriverName.setText(name)
                binding.etVehicleDetails.setText(vehicle)
                binding.etDriverPhone.setText(phone)
                binding.etDriverEmail.setText(email)
                binding.txtDriverRating.text = String.format("%.1f ★", rating)
                binding.txtCompletedRides.text = rides.toString()

                if (!photoUrl.isNullOrEmpty() && isAdded) {
                    Glide.with(this)
                        .load(photoUrl)
                        .placeholder(R.drawable.carpin)
                        .into(binding.imgDriverProfile)
                }
            }
        }
    }

    private fun saveDriverProfile() {
        val uid = auth.currentUser?.uid ?: return
        val name = binding.etDriverName.text.toString().trim()
        val vehicle = binding.etVehicleDetails.text.toString().trim()
        val phone = binding.etDriverPhone.text.toString().trim()

        binding.btnSaveDriverProfile.isEnabled = false

        if (selectedImageUri != null) {
            val photoRef = storage.child("driver_profiles/${uid}.jpg")
            photoRef.putFile(selectedImageUri!!).addOnSuccessListener {
                photoRef.downloadUrl.addOnSuccessListener { photoUrl ->
                    updateDatabaseProfile(uid, name, vehicle, phone, photoUrl.toString())
                }
            }.addOnFailureListener { err ->
                binding.btnSaveDriverProfile.isEnabled = true
                Toast.makeText(requireContext(), "Failed to upload photo: ${err.message}", Toast.LENGTH_SHORT).show()
            }
        } else {
            updateDatabaseProfile(uid, name, vehicle, phone, null)
        }
    }

    private fun updateDatabaseProfile(
        uid: String, name: String, vehicle: String, phone: String, photoUrl: String?
    ) {
        val updates = hashMapOf<String, Any>(
            "name" to name,
            "vehicleDetails" to vehicle,
            "phone" to phone
        )
        if (photoUrl != null) updates["profilePictureUrl"] = photoUrl

        database.child("drivers").child(uid).updateChildren(updates).addOnSuccessListener {
            binding.btnSaveDriverProfile.isEnabled = true
            Toast.makeText(requireContext(), "Profile updated successfully!", Toast.LENGTH_SHORT).show()
        }.addOnFailureListener {
            binding.btnSaveDriverProfile.isEnabled = true
            Toast.makeText(requireContext(), "Failed to update profile.", Toast.LENGTH_SHORT).show()
        }
    }

    private fun performLogout() {
        auth.signOut()
        val sharedPref = requireContext().getSharedPreferences("USER_SESSION", Context.MODE_PRIVATE)
        sharedPref.edit().clear().apply()

        Toast.makeText(requireContext(), "Logged out successfully", Toast.LENGTH_SHORT).show()

        val intent = Intent(requireContext(), LoginActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
        }
        startActivity(intent)
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}