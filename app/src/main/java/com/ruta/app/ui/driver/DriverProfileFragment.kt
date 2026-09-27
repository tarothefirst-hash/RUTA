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
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import com.bumptech.glide.Glide
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.database.DataSnapshot
import com.google.firebase.database.DatabaseError
import com.google.firebase.database.DatabaseReference
import com.google.firebase.database.FirebaseDatabase
import com.google.firebase.database.ValueEventListener
import com.google.firebase.storage.FirebaseStorage
import com.ruta.app.R
import com.ruta.app.databinding.FragmentDriverProfileBinding
import com.ruta.app.ui.LoginActivity

class DriverProfileFragment : Fragment() {

    private var _binding: FragmentDriverProfileBinding? = null
    private val binding get() = _binding!!

    private val auth by lazy { FirebaseAuth.getInstance() }
    private val database: DatabaseReference by lazy { FirebaseDatabase.getInstance().reference }
    private val storage by lazy { FirebaseStorage.getInstance().reference }

    private var selectedImageUri: Uri? = null

    // Photo picker launcher
    private val profilePhotoLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK && result.data != null) {
            val uri = result.data?.data
            if (uri != null) {
                selectedImageUri = uri
                _binding?.imgDriverProfile?.setImageURI(uri)
            }
        }
    }

    // Permission launcher
    private val requestStoragePermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        if (isGranted) {
            launchImagePickerIntent()
        } else {
            context?.let {
                Toast.makeText(it, "Storage permission required to select photo", Toast.LENGTH_SHORT).show()
            }
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentDriverProfileBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        // 1. Fetch Existing Driver Data
        loadDriverProfile()

        // 2. Photo Pickers
        binding.imgDriverProfile.setOnClickListener {
            openImagePickerWithPermissionCheck()
        }

        binding.btnChangeDriverPhoto.setOnClickListener {
            openImagePickerWithPermissionCheck()
        }

        // 3. Save Button
        binding.btnSaveDriverProfile.setOnClickListener {
            saveDriverProfile()
        }

        // 4. Logout Button
        binding.btnDriverLogoutProfile.setOnClickListener {
            checkTripStatusBeforeLogout()
        }
    }

    private fun openImagePickerWithPermissionCheck() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            launchImagePickerIntent()
        } else {
            val ctx = context ?: return
            val permission = Manifest.permission.READ_EXTERNAL_STORAGE
            if (ContextCompat.checkSelfPermission(ctx, permission) == PackageManager.PERMISSION_GRANTED) {
                launchImagePickerIntent()
            } else {
                requestStoragePermissionLauncher.launch(permission)
            }
        }
    }

    private fun launchImagePickerIntent() {
        try {
            val intent = Intent(Intent.ACTION_PICK, MediaStore.Images.Media.EXTERNAL_CONTENT_URI)
            profilePhotoLauncher.launch(intent)
        } catch (e: Exception) {
            Log.e("DriverProfile", "Error launching gallery", e)
        }
    }

    private fun loadDriverProfile() {
        val uid = auth.currentUser?.uid ?: return

        // First attempt: Check 'users' node
        database.child("users").child(uid).get().addOnSuccessListener { snapshot ->
            if (_binding == null || !isAdded) return@addOnSuccessListener

            if (snapshot.exists() && snapshot.childrenCount > 0) {
                populateUIFromSnapshot(snapshot)
            } else {
                // Second attempt: Fallback check 'drivers' node
                database.child("drivers").child(uid).get().addOnSuccessListener { driverSnap ->
                    if (_binding == null || !isAdded) return@addOnSuccessListener
                    if (driverSnap.exists()) {
                        populateUIFromSnapshot(driverSnap)
                    }
                }
            }
        }.addOnFailureListener { e ->
            Log.e("DriverProfile", "Failed fetching profile: ${e.message}")
        }
    }

    private fun populateUIFromSnapshot(snapshot: DataSnapshot) {
        if (_binding == null || !isAdded) return

        // 1. FULL NAME
        val firstName = snapshot.child("firstName").getValue(String::class.java) ?: ""
        val lastName = snapshot.child("lastName").getValue(String::class.java) ?: ""
        var fullName = "$firstName $lastName".trim()
        if (fullName.isEmpty()) {
            fullName = snapshot.child("name").getValue(String::class.java)
                ?: snapshot.child("fullName").getValue(String::class.java) ?: ""
        }

        // 2. VEHICLE DETAILS (Model, Plate Number, Color)
        val vehicleModel = snapshot.child("vehicleModel").getValue(String::class.java)
            ?: snapshot.child("carModel").getValue(String::class.java) ?: ""
        val plateNumber = snapshot.child("plateNumber").getValue(String::class.java)
            ?: snapshot.child("licensePlate").getValue(String::class.java) ?: ""
        val vehicleColor = snapshot.child("vehicleColor").getValue(String::class.java)
            ?: snapshot.child("carColor").getValue(String::class.java) ?: ""

        var vehicleDisplay = snapshot.child("vehicleDetails").getValue(String::class.java) ?: ""
        if (vehicleDisplay.isEmpty()) {
            vehicleDisplay = buildString {
                if (vehicleColor.isNotEmpty()) append("$vehicleColor ")
                if (vehicleModel.isNotEmpty()) append(vehicleModel)
                if (plateNumber.isNotEmpty()) append(" ($plateNumber)")
            }.trim()
        }

        // 3. PHONE & EMAIL
        val phone = snapshot.child("phoneNumber").getValue(String::class.java)
            ?: snapshot.child("phone").getValue(String::class.java)
            ?: snapshot.child("contactNumber").getValue(String::class.java) ?: ""

        val email = snapshot.child("email").getValue(String::class.java)
            ?: auth.currentUser?.email ?: ""

        // 4. METRICS (Rating & Rides)
        val rating = snapshot.child("rating").getValue(Double::class.java)
            ?: snapshot.child("rating").getValue(Long::class.java)?.toDouble() ?: 5.0
        val rides = snapshot.child("completedRides").getValue(Int::class.java)
            ?: snapshot.child("ridesCompleted").getValue(Int::class.java)
            ?: snapshot.child("totalRides").getValue(Int::class.java) ?: 0

        // 5. PROFILE PICTURE URL
        val photoUrl = snapshot.child("profilePictureUrl").getValue(String::class.java)
            ?: snapshot.child("profileImageUrl").getValue(String::class.java)

        // Set inputs without risk of NullPointer crashes
        binding.etDriverName.setText(fullName)
        binding.etVehicleDetails.setText(vehicleDisplay)
        binding.etDriverPhone.setText(phone)
        binding.etDriverEmail.setText(email)
        binding.txtDriverRating.text = String.format("%.1f ★", rating)
        binding.txtCompletedRides.text = rides.toString()

        // Load profile picture with Glide
        if (!photoUrl.isNullOrEmpty() && isAdded) {
            try {
                Glide.with(this)
                    .load(photoUrl)
                    .placeholder(R.drawable.carpin)
                    .error(R.drawable.carpin)
                    .circleCrop()
                    .into(binding.imgDriverProfile)
            } catch (e: Exception) {
                Log.e("DriverProfile", "Glide image load error", e)
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
                _binding?.btnSaveDriverProfile?.isEnabled = true
                context?.let { Toast.makeText(it, "Photo upload failed: ${err.message}", Toast.LENGTH_SHORT).show() }
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
            "phone" to phone,
            "phoneNumber" to phone
        )
        if (photoUrl != null) {
            updates["profilePictureUrl"] = photoUrl
            updates["profileImageUrl"] = photoUrl
        }

        database.child("users").child(uid).updateChildren(updates)
        database.child("drivers").child(uid).updateChildren(updates).addOnSuccessListener {
            _binding?.btnSaveDriverProfile?.isEnabled = true
            context?.let { Toast.makeText(it, "Profile updated successfully!", Toast.LENGTH_SHORT).show() }
        }.addOnFailureListener {
            _binding?.btnSaveDriverProfile?.isEnabled = true
            context?.let { Toast.makeText(it, "Failed to update profile.", Toast.LENGTH_SHORT).show() }
        }
    }

    private fun checkTripStatusBeforeLogout() {
        val uid = auth.currentUser?.uid ?: run {
            performLogout()
            return
        }

        binding.btnDriverLogoutProfile.isEnabled = false

        database.child("bookings")
            .orderByChild("driverId")
            .equalTo(uid)
            .addListenerForSingleValueEvent(object : ValueEventListener {
                override fun onDataChange(snapshot: DataSnapshot) {
                    if (_binding == null || !isAdded) return
                    binding.btnDriverLogoutProfile.isEnabled = true

                    var hasActiveTrip = false
                    for (child in snapshot.children) {
                        val status = child.child("status").getValue(String::class.java) ?: ""
                        if (status == "ACCEPTED" || status == "ARRIVED" || status == "IN_PROGRESS") {
                            hasActiveTrip = true
                            break
                        }
                    }

                    if (hasActiveTrip) {
                        Toast.makeText(requireContext(), "Cannot log out while a trip is active!", Toast.LENGTH_LONG).show()
                    } else {
                        performLogout()
                    }
                }

                override fun onCancelled(error: DatabaseError) {
                    if (_binding == null || !isAdded) return
                    binding.btnDriverLogoutProfile.isEnabled = true
                    context?.let { Toast.makeText(it, "Network error.", Toast.LENGTH_SHORT).show() }
                }
            })
    }

    private fun performLogout() {
        val ctx = context ?: return
        auth.signOut()
        val sharedPref = ctx.getSharedPreferences("USER_SESSION", Context.MODE_PRIVATE)
        sharedPref.edit().clear().apply()

        Toast.makeText(ctx, "Logged out", Toast.LENGTH_SHORT).show()

        val intent = Intent(ctx, LoginActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
        }
        startActivity(intent)
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}