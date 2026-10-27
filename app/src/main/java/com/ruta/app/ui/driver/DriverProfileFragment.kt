package com.ruta.app.ui.driver

import com.ruta.app.util.NotificationWatchService
import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import com.bumptech.glide.Glide
import com.google.android.material.switchmaterial.SwitchMaterial
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.database.DataSnapshot
import com.google.firebase.database.DatabaseError
import com.google.firebase.database.DatabaseReference
import com.google.firebase.database.FirebaseDatabase
import com.google.firebase.database.ValueEventListener
import com.google.zxing.BarcodeFormat
import com.google.zxing.qrcode.QRCodeWriter
import com.journeyapps.barcodescanner.CaptureActivity
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions
import com.ruta.app.R
import com.ruta.app.databinding.FragmentDriverProfileBinding
import com.ruta.app.ui.LoginActivity
import com.ruta.app.util.EmergencyContactManager
import com.ruta.app.util.ImageUtils
import com.ruta.app.util.PhotoPaths
import com.ruta.app.util.StoragePhotos
import com.ruta.app.util.ProfileFields
import java.util.UUID
import com.ruta.app.ui.PortraitCaptureActivity
class DriverProfileFragment : Fragment() {

    private var _binding: FragmentDriverProfileBinding? = null
    private val binding get() = _binding!!

    private val auth by lazy { FirebaseAuth.getInstance() }
    private val database: DatabaseReference by lazy { FirebaseDatabase.getInstance().reference }

    /** A newly picked picture, already shrunk, waiting for Save. */
    private var pendingPhotoBase64: String? = null
    private var photoLocked = false
    private var activeQrToken: String? = null

    private var trustedContactsListener: ValueEventListener? = null
    private var pairRequestListener: ValueEventListener? = null
    private val shownPairRequestUids = mutableSetOf<String>()

    private val profilePhotoLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK && result.data != null) {
            val uri = result.data?.data
            val ctx = context
            if (uri != null && ctx != null) {
                val base64 = ImageUtils.uriToAvatarBase64(ctx, uri)
                val img = _binding?.imgDriverProfile
                if (base64 != null && img != null && ImageUtils.showAvatar(img, base64)) {
                    pendingPhotoBase64 = base64
                    Toast.makeText(ctx, "Photo selected. Tap Save to keep it.", Toast.LENGTH_SHORT).show()
                } else {
                    Toast.makeText(ctx, "Couldn't read that image. Try another one.", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

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

    private val qrScannerLauncher = registerForActivityResult(ScanContract()) { result ->
        if (result.contents != null) {
            processScannedQrCode(result.contents)
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

        val myUid = auth.currentUser?.uid ?: return

        generateAndDisplayDynamicQr(myUid)
        loadDriverProfile()
        setupTrustedContacts(myUid)

        binding.imgDriverProfile.setOnClickListener { openImagePickerWithPermissionCheck() }
        binding.btnChangeDriverPhoto.setOnClickListener { openImagePickerWithPermissionCheck() }
        binding.btnSaveDriverProfile.setOnClickListener { saveDriverProfile() }
        binding.btnDriverLogoutProfile.setOnClickListener { checkTripStatusBeforeLogout() }
    }

    private fun generateAndDisplayDynamicQr(uid: String) {
        val newToken = UUID.randomUUID().toString().take(8)
        activeQrToken = newToken

        val qrPayload = "RUTA_PAIR:$uid:$newToken"

        val tokenData = hashMapOf<String, Any>(
            "token" to newToken,
            "status" to "ACTIVE",
            "createdAt" to System.currentTimeMillis()
        )

        database.child("users").child(uid).child("activeQr").setValue(tokenData)
            .addOnSuccessListener {
                _binding?.imgDriverQrCode?.setImageBitmap(generateQRCodeBitmap(qrPayload))
            }
    }

    private fun generateQRCodeBitmap(text: String): Bitmap {
        val writer = QRCodeWriter()
        val bitMatrix = writer.encode(text, BarcodeFormat.QR_CODE, 512, 512)
        val bitmap = Bitmap.createBitmap(512, 512, Bitmap.Config.RGB_565)
        for (x in 0 until 512) {
            for (y in 0 until 512) {
                bitmap.setPixel(x, y, if (bitMatrix[x, y]) Color.BLACK else Color.WHITE)
            }
        }
        return bitmap
    }

    private fun processScannedQrCode(payload: String) {
        val parts = payload.split(":")
        if (parts.size != 3 || parts[0] != "RUTA_PAIR") {
            Toast.makeText(requireContext(), "Invalid RUTA QR Code!", Toast.LENGTH_SHORT).show()
            return
        }

        val targetUid = parts[1]
        val token = parts[2]
        val myUid = auth.currentUser?.uid ?: return

        if (targetUid == myUid) {
            Toast.makeText(requireContext(), "You cannot link with yourself!", Toast.LENGTH_SHORT).show()
            return
        }

        database.child("users").child(targetUid).child("activeQr").get()
            .addOnSuccessListener { snapshot ->
                val activeToken = snapshot.child("token").getValue(String::class.java)
                val status = snapshot.child("status").getValue(String::class.java)

                if (activeToken != token || status != "ACTIVE") {
                    Toast.makeText(requireContext(), "This QR Code has expired or already been used!", Toast.LENGTH_LONG).show()
                    return@addOnSuccessListener
                }

                val myName = _binding?.etDriverName?.text?.toString()?.trim()?.ifEmpty { "A RUTA driver" } ?: "A RUTA driver"

                EmergencyContactManager.sendPairRequest(database, myUid, myName, targetUid) { success, message ->
                    Toast.makeText(requireContext(), message, Toast.LENGTH_LONG).show()
                    if (success) {
                        database.child("users").child(targetUid).child("activeQr").child("status").setValue("EXPIRED")
                    }
                }
            }
            .addOnFailureListener {
                Toast.makeText(requireContext(), "Failed to verify QR code.", Toast.LENGTH_SHORT).show()
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
        } catch (_: Exception) {
            context?.let { Toast.makeText(it, "Unable to open gallery. Please try again.", Toast.LENGTH_SHORT).show() }
        }
    }

    private fun loadDriverProfile() {
        val uid = auth.currentUser?.uid ?: return

        database.child("users").child(uid).get().addOnSuccessListener { snapshot ->
            if (_binding == null || !isAdded) return@addOnSuccessListener

            if (snapshot.exists() && snapshot.childrenCount > 0) {
                populateUIFromSnapshot(snapshot)
            } else {
                database.child("drivers").child(uid).get().addOnSuccessListener { driverSnap ->
                    if (_binding == null || !isAdded) return@addOnSuccessListener
                    if (driverSnap.exists()) {
                        populateUIFromSnapshot(driverSnap)
                    }
                }
            }
        }.addOnFailureListener {
            context?.let { Toast.makeText(it, "Failed to load profile. Please try again.", Toast.LENGTH_SHORT).show() }
        }
    }

    private fun populateUIFromSnapshot(snapshot: DataSnapshot) {
        val bindingRef = _binding ?: return
        if (!isAdded) return

        val firstName = snapshot.child("firstName").getValue(String::class.java) ?: ""
        val lastName = snapshot.child("lastName").getValue(String::class.java) ?: ""
        var fullName = "$firstName $lastName".trim()
        if (fullName.isEmpty()) {
            fullName = snapshot.child("name").getValue(String::class.java)
                ?: snapshot.child("fullName").getValue(String::class.java) ?: ""
        }

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

        val phone = snapshot.child("phoneNumber").getValue(String::class.java)
            ?: snapshot.child("phone").getValue(String::class.java)
            ?: snapshot.child("contactNumber").getValue(String::class.java) ?: ""

        val email = snapshot.child("email").getValue(String::class.java)
            ?: auth.currentUser?.email ?: ""

        val rides = snapshot.child("completedRides").getValue(Int::class.java)
            ?: snapshot.child("ridesCompleted").getValue(Int::class.java)
            ?: snapshot.child("totalRides").getValue(Int::class.java) ?: 0

        val photoUrl = snapshot.child("profilePictureUrl").getValue(String::class.java)
            ?: snapshot.child("profileImageUrl").getValue(String::class.java)

        bindingRef.etDriverName.setText(fullName)
        bindingRef.etVehicleDetails.setText(vehicleDisplay)
        bindingRef.etDriverPhone.setText(phone)
        bindingRef.etDriverEmail.setText(email)
        bindingRef.txtCompletedRides.text = rides.toString()

        val storedPhoto = snapshot.child("profilePhoto").getValue(String::class.java)
        // The 1x1 photo checked by the admin at registration is locked in as the profile picture.
        photoLocked = snapshot.child("photoLocked").getValue(Boolean::class.java) == true && !storedPhoto.isNullOrBlank()
        bindingRef.btnChangeDriverPhoto.visibility = if (photoLocked) View.GONE else View.VISIBLE
        if (!storedPhoto.isNullOrBlank()) {
            StoragePhotos.show(bindingRef.imgDriverProfile, storedPhoto, R.drawable.carpin)
        } else if (!photoUrl.isNullOrEmpty() && isAdded) {
            try {
                Glide.with(this)
                    .load(photoUrl)
                    .placeholder(R.drawable.carpin)
                    .error(R.drawable.carpin)
                    .circleCrop()
                    .into(bindingRef.imgDriverProfile)
            } catch (_: Exception) {
                context?.let { Toast.makeText(it, "Failed to load profile photo.", Toast.LENGTH_SHORT).show() }
            }
        }
    }

    private fun setupTrustedContacts(myUid: String) {
        trustedContactsListener = EmergencyContactManager.listenForTrustedContacts(database, myUid) { contactUids ->
            val bindingRef = _binding ?: return@listenForTrustedContacts
            if (!isAdded) return@listenForTrustedContacts
            bindingRef.txtTrustedContactsSummary.text = "Trusted Contacts: ${contactUids.size}/${EmergencyContactManager.MAX_TRUSTED_CONTACTS}"
            bindingRef.txtTrustedContactsSummary.setTextColor(
                if (contactUids.isNotEmpty()) Color.parseColor("#388E3C") else Color.parseColor("#D32F2F")
            )
        }

        pairRequestListener = EmergencyContactManager.listenForIncomingPairRequests(database, myUid) { requesterUid, requesterName ->
            if (_binding == null || !isAdded || shownPairRequestUids.contains(requesterUid)) return@listenForIncomingPairRequests
            shownPairRequestUids.add(requesterUid)

            AlertDialog.Builder(requireContext())
                .setTitle("Trusted Contact Request")
                .setMessage("$requesterName wants to add you as a trusted emergency contact.")
                .setCancelable(false)
                .setPositiveButton("Accept") { dialog, _ ->
                    EmergencyContactManager.acceptPairRequest(database, myUid, requesterUid) { _, message ->
                        Toast.makeText(context, message, Toast.LENGTH_LONG).show()
                    }
                    shownPairRequestUids.remove(requesterUid)
                    dialog.dismiss()
                }
                .setNegativeButton("Decline") { dialog, _ ->
                    EmergencyContactManager.declinePairRequest(database, myUid, requesterUid)
                    shownPairRequestUids.remove(requesterUid)
                    dialog.dismiss()
                }
                .show()
        }

        binding.btnScanContactQr.setOnClickListener {
            val options = ScanOptions().apply {
                setPrompt("Scan a contact's RUTA Safety QR Code")
                setBeepEnabled(true)
                setOrientationLocked(true)
                setCaptureActivity(PortraitCaptureActivity::class.java)
            }
            qrScannerLauncher.launch(options)
        }

        binding.btnViewTrustedContacts.setOnClickListener {
            showTrustedListSheet(myUid)
        }
    }

    private fun showTrustedListSheet(myUid: String) {
        val dialogView = layoutInflater.inflate(R.layout.dialog_trusted_list, null)

        val alertDialog = AlertDialog.Builder(requireContext())
            .setView(dialogView)
            .create()

        alertDialog.window?.setBackgroundDrawableResource(android.R.color.transparent)

        val container = dialogView.findViewById<LinearLayout>(R.id.llTrustedContactsContainer)
        val txtEmpty = dialogView.findViewById<TextView>(R.id.txtNoTrustedContacts)
        val btnClose = dialogView.findViewById<Button>(R.id.btnCloseDialog)

        btnClose.setOnClickListener { alertDialog.dismiss() }

        database.child("users").child(myUid).child("trustedContacts").get()
            .addOnSuccessListener { snapshot ->
                container.removeAllViews()
                val contactUids = snapshot.children.mapNotNull { it.key }

                if (contactUids.isEmpty()) {
                    txtEmpty.visibility = View.VISIBLE
                    alertDialog.show()
                    return@addOnSuccessListener
                }
                txtEmpty.visibility = View.GONE

                contactUids.forEach { contactUid ->
                    val contactRef = database.child("users").child(myUid).child("trustedContacts").child(contactUid)

                    contactRef.get().addOnSuccessListener { contactSnap ->
                        val isDeviationEnabled = contactSnap.child("receiveDeviationAlerts").getValue(Boolean::class.java) ?: true

                        database.child("users").child(contactUid).child("name").get()
                            .addOnSuccessListener { nameSnap ->
                                val name = nameSnap.getValue(String::class.java)?.ifEmpty { null } ?: "RUTA User"
                                val row = layoutInflater.inflate(R.layout.item_trusted_contact, container, false)

                                row.findViewById<TextView>(R.id.txtContactName).text = name

                                val switchDeviation = row.findViewById<SwitchMaterial>(R.id.switchRouteDeviation)
                                switchDeviation.isChecked = isDeviationEnabled

                                switchDeviation.setOnCheckedChangeListener { _, isChecked ->
                                    contactRef.child("receiveDeviationAlerts").setValue(isChecked)
                                }

                                row.findViewById<Button>(R.id.btnRemoveContact).setOnClickListener {
                                    EmergencyContactManager.removeTrustedContact(database, myUid, contactUid)
                                    container.removeView(row)
                                    if (container.childCount == 0) txtEmpty.visibility = View.VISIBLE
                                }
                                container.addView(row)
                            }
                    }
                }
                alertDialog.show()
            }
            .addOnFailureListener {
                Toast.makeText(requireContext(), "Failed to load trusted contacts.", Toast.LENGTH_SHORT).show()
            }
    }

    private fun saveDriverProfile() {
        val uid = auth.currentUser?.uid ?: return
        val name = binding.etDriverName.text.toString().trim()
        val vehicle = binding.etVehicleDetails.text.toString().trim()
        val phone = binding.etDriverPhone.text.toString().trim()

        if (!ProfileFields.isValidName(name)) {
            binding.etDriverName.error = "Enter your full name"
            return
        }
        if (!ProfileFields.isValidPhone(phone)) {
            binding.etDriverPhone.error = "Enter a valid mobile number"
            return
        }

        // name + phone go to every field the app reads; the email is never written.
        val updates = HashMap<String, Any>(ProfileFields.updatesFor(name, phone))
        updates["vehicleDetails"] = vehicle

        binding.btnSaveDriverProfile.isEnabled = false

        val pending = pendingPhotoBase64
        if (pending == null || photoLocked) {
            commitDriverProfile(uid, updates, phone)
        } else {
            // Only reachable for drivers who registered before the 1x1 photo existed: one upload,
            // then it is locked like everyone else's.
            StoragePhotos.uploadBase64(PhotoPaths.avatar(uid), pending) { value, error ->
                val b = _binding ?: return@uploadBase64
                if (value == null) {
                    b.btnSaveDriverProfile.isEnabled = true
                    context?.let { Toast.makeText(it, "Photo upload failed: $error", Toast.LENGTH_LONG).show() }
                    return@uploadBase64
                }
                updates["profilePhoto"] = value
                updates["profilePhotoUpdatedAt"] = System.currentTimeMillis()
                updates["photoLocked"] = true
                commitDriverProfile(uid, updates, phone)
            }
        }
    }

    private fun commitDriverProfile(uid: String, updates: HashMap<String, Any>, phone: String) {
        database.child("users").child(uid).updateChildren(updates)
            .addOnSuccessListener {
                // Keep the driver record in step (it has no photo of its own: users/<uid> is the source).
                val driverUpdates = HashMap(updates).apply {
                    remove("profilePhoto"); remove("profilePhotoUpdatedAt"); remove("photoLocked")
                }
                database.child("drivers").child(uid).updateChildren(driverUpdates)

                _binding?.btnSaveDriverProfile?.isEnabled = true
                pendingPhotoBase64 = null
                _binding?.etDriverPhone?.setText(ProfileFields.normalizePhone(phone))
                context?.let { Toast.makeText(it, "Profile updated successfully!", Toast.LENGTH_SHORT).show() }
            }
            .addOnFailureListener {
                _binding?.btnSaveDriverProfile?.isEnabled = true
                context?.let { Toast.makeText(it, "Failed to update profile. Check your connection.", Toast.LENGTH_SHORT).show() }
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
                    _binding?.btnDriverLogoutProfile?.isEnabled = true

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
                    _binding?.btnDriverLogoutProfile?.isEnabled = true
                    context?.let { Toast.makeText(it, "Network error.", Toast.LENGTH_SHORT).show() }
                }
            })
    }

    private fun performLogout() {
        val ctx = context ?: return
        NotificationWatchService.stop(requireContext().applicationContext)
        com.ruta.app.util.PushTokens.onSignOut()
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
        val uid = auth.currentUser?.uid
        if (uid != null) {
            trustedContactsListener?.let { database.child("users").child(uid).child("trustedContacts").removeEventListener(it) }
            pairRequestListener?.let { database.child("pairRequests").child(uid).removeEventListener(it) }
        }
        _binding = null
    }
}

