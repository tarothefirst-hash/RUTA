package com.ruta.app.ui

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
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import com.google.android.material.switchmaterial.SwitchMaterial
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.database.DatabaseReference
import com.google.firebase.database.FirebaseDatabase
import com.google.firebase.database.ValueEventListener
import com.google.zxing.BarcodeFormat
import com.google.zxing.qrcode.QRCodeWriter
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions
import com.ruta.app.R
import com.ruta.app.util.EmergencyContactManager
import com.ruta.app.util.ImageUtils
import com.ruta.app.util.PhotoPaths
import com.ruta.app.util.StoragePhotos
import com.ruta.app.util.ProfileFields
import java.util.UUID
import com.ruta.app.ui.driver.DriverProfileFragment
import com.ruta.app.ui.PortraitCaptureActivity
class ProfileFragment : Fragment() {

    // Views
    private lateinit var imgProfile: ImageView
    private lateinit var btnChangePhoto: ImageView
    private lateinit var imgMyQrCode: ImageView
    private lateinit var txtLinkedContactStatus: TextView
    private lateinit var btnScanQr: Button
    private lateinit var etFullName: EditText
    private lateinit var etPhone: EditText
    private lateinit var etEmail: EditText
    private lateinit var btnSaveProfile: Button
    private lateinit var btnLogout: Button
    private lateinit var btnViewTrustedList: Button

    private val currentUid = FirebaseAuth.getInstance().currentUser?.uid
    private val database: DatabaseReference = FirebaseDatabase.getInstance().reference

    /** A newly picked picture, already shrunk, waiting for Save. */
    private var pendingPhotoBase64: String? = null
    private var activeQrToken: String? = null
    private var trustedContactsListener: ValueEventListener? = null

    // Photo Picker Contract
    private val profilePhotoLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            val uri = result.data?.data
            if (uri != null) {
                val base64 = ImageUtils.uriToAvatarBase64(requireContext(), uri)
                if (base64 != null && ImageUtils.showAvatar(imgProfile, base64)) {
                    pendingPhotoBase64 = base64
                    Toast.makeText(requireContext(), "Photo selected. Tap Save to keep it.", Toast.LENGTH_SHORT).show()
                } else {
                    Toast.makeText(requireContext(), "Couldn't read that image. Try another one.", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    // Storage Permission Launcher (For Android 12 & below)
    private val requestStoragePermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        if (isGranted) {
            launchImagePickerIntent()
        } else {
            Toast.makeText(requireContext(), "Storage permission is required to select a photo.", Toast.LENGTH_SHORT).show()
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
    ): View? {
        return inflater.inflate(R.layout.fragment_profile, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        imgProfile = view.findViewById(R.id.imgProfile)
        btnChangePhoto = view.findViewById(R.id.btnChangePhoto)
        imgMyQrCode = view.findViewById(R.id.imgMyQrCode)
        txtLinkedContactStatus = view.findViewById(R.id.txtLinkedContactStatus)
        btnScanQr = view.findViewById(R.id.btnScanQr)
        etFullName = view.findViewById(R.id.etFullName)
        etPhone = view.findViewById(R.id.etPhone)
        etEmail = view.findViewById(R.id.etEmail)
        btnSaveProfile = view.findViewById(R.id.btnSaveProfile)
        btnLogout = view.findViewById(R.id.btnLogout)
        btnViewTrustedList = view.findViewById(R.id.btnViewTrustedList)

        // The email is the account's identity: shown, never editable.
        etEmail.isEnabled = false

        if (currentUid != null) {
            generateAndDisplayDynamicQr()
            loadUserProfile()
            listenForTrustedContactCount()
        }

        btnChangePhoto.setOnClickListener {
            val uid = FirebaseAuth.getInstance().currentUser?.uid
            if (uid == null) { openImagePickerWithPermissionCheck(); return@setOnClickListener }
            androidx.appcompat.app.AlertDialog.Builder(requireContext())
                .setTitle("Profile photo")
                .setItems(arrayOf("Choose a new photo", "Remove my photo")) { _, which ->
                    if (which == 0) openImagePickerWithPermissionCheck() else removeProfilePhoto(uid)
                }
                .setNegativeButton("Cancel", null)
                .show()
        }

        btnScanQr.setOnClickListener {
            val options = ScanOptions().apply {
                setPrompt("Scan a contact's RUTA Safety QR Code")
                setBeepEnabled(true)
                setOrientationLocked(true)
                setCaptureActivity(PortraitCaptureActivity::class.java)
            }
            qrScannerLauncher.launch(options)
        }
        btnViewTrustedList.setOnClickListener { showTrustedListSheet() }

        btnSaveProfile.setOnClickListener { saveUserProfile() }
        btnLogout.setOnClickListener { performLogout() }
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

    private fun generateAndDisplayDynamicQr() {
        val uid = currentUid ?: return
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
                imgMyQrCode.setImageBitmap(generateQRCodeBitmap(qrPayload))
            }
    }

    private fun processScannedQrCode(payload: String) {
        val parts = payload.split(":")
        if (parts.size != 3 || parts[0] != "RUTA_PAIR") {
            Toast.makeText(requireContext(), "Invalid RUTA QR Code!", Toast.LENGTH_SHORT).show()
            return
        }

        val targetUid = parts[1]
        val token = parts[2]
        val myUid = currentUid ?: return

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

                val myName = etFullName.text.toString().trim().ifEmpty { "A RUTA user" }

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

    private fun loadUserProfile() {
        val uid = currentUid ?: return
        database.child("users").child(uid).get().addOnSuccessListener { snapshot ->
            if (_isGone() || !snapshot.exists()) return@addOnSuccessListener

            // Sign-up stores firstName/lastName and phoneNumber; "name"/"phone" are the older spellings.
            val first = snapshot.child("firstName").getValue(String::class.java).orEmpty()
            val last = snapshot.child("lastName").getValue(String::class.java).orEmpty()
            val fullName = "$first $last".trim()
                .ifEmpty { snapshot.child("name").getValue(String::class.java).orEmpty() }
            val phone = snapshot.child("phoneNumber").getValue(String::class.java)
                ?: snapshot.child("phone").getValue(String::class.java).orEmpty()

            etFullName.setText(fullName)
            etPhone.setText(phone)
            etEmail.setText(
                snapshot.child("email").getValue(String::class.java)
                    ?: FirebaseAuth.getInstance().currentUser?.email.orEmpty()
            )
            StoragePhotos.show(imgProfile, snapshot.child("profilePhoto").getValue(String::class.java), R.drawable.ic_person)
        }
    }

    private fun _isGone() = !isAdded || view == null

    private fun saveUserProfile() {
        val uid = currentUid ?: return
        val name = etFullName.text.toString().trim()
        val phone = etPhone.text.toString().trim()

        if (!ProfileFields.isValidName(name)) {
            etFullName.error = "Enter your full name"
            return
        }
        if (!ProfileFields.isValidPhone(phone)) {
            etPhone.error = "Enter a valid mobile number"
            return
        }

        val updates = HashMap<String, Any>(ProfileFields.updatesFor(name, phone))

        btnSaveProfile.isEnabled = false
        val pending = pendingPhotoBase64
        if (pending == null) {
            commitProfile(uid, updates, phone)
        } else {
            // The picture goes to Firebase Storage; the user record only keeps its reference.
            StoragePhotos.uploadBase64(PhotoPaths.avatar(uid), pending) { value, error ->
                if (_isGone()) return@uploadBase64
                if (value == null) {
                    btnSaveProfile.isEnabled = true
                    Toast.makeText(requireContext(), "Photo upload failed: $error", Toast.LENGTH_LONG).show()
                    return@uploadBase64
                }
                updates["profilePhoto"] = value
                updates["profilePhotoUpdatedAt"] = System.currentTimeMillis()
                commitProfile(uid, updates, phone)
            }
        }
    }

    private fun commitProfile(uid: String, updates: HashMap<String, Any>, phone: String) {
        database.child("users").child(uid).updateChildren(updates).addOnSuccessListener {
            if (_isGone()) return@addOnSuccessListener
            btnSaveProfile.isEnabled = true
            pendingPhotoBase64 = null
            etPhone.setText(ProfileFields.normalizePhone(phone))
            Toast.makeText(requireContext(), "Profile updated successfully!", Toast.LENGTH_SHORT).show()
        }.addOnFailureListener {
            if (_isGone()) return@addOnFailureListener
            btnSaveProfile.isEnabled = true
            Toast.makeText(requireContext(), "Failed to update profile. Check your connection.", Toast.LENGTH_SHORT).show()
        }
    }

    private fun listenForTrustedContactCount() {
        val myUid = currentUid ?: return
        trustedContactsListener = EmergencyContactManager.listenForTrustedContacts(database, myUid) { contactUids ->
            txtLinkedContactStatus.text = "Trusted Contacts: ${contactUids.size}/${EmergencyContactManager.MAX_TRUSTED_CONTACTS}"
            txtLinkedContactStatus.setTextColor(
                if (contactUids.isNotEmpty()) Color.parseColor("#388E3C") else Color.parseColor("#D32F2F")
            )
        }
    }

    private fun showTrustedListSheet() {
        val myUid = currentUid ?: return
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

    private fun performLogout() {
        NotificationWatchService.stop(requireContext().applicationContext)
        com.ruta.app.util.PushTokens.onSignOut()
        FirebaseAuth.getInstance().signOut()
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
        trustedContactsListener?.let {
            currentUid?.let { uid -> database.child("users").child(uid).child("trustedContacts").removeEventListener(it) }
        }
    }

    /** Deletes the stored photo and goes back to the initials look. */
    private fun removeProfilePhoto(uid: String) {
        com.ruta.app.util.StoragePhotos.delete(com.ruta.app.util.PhotoPaths.avatar(uid))
        pendingPhotoBase64 = null
        FirebaseDatabase.getInstance().reference.child("users").child(uid).updateChildren(
            mapOf<String, Any?>("profilePhoto" to null, "profilePhotoUpdatedAt" to null)
        )
        imgProfile.setImageResource(R.drawable.ic_person)
        Toast.makeText(context, "Photo removed.", Toast.LENGTH_SHORT).show()
    }
}
