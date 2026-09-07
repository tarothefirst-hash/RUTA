package com.ruta.app.ui

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.fragment.app.Fragment
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.database.FirebaseDatabase
import com.google.zxing.BarcodeFormat
import com.google.zxing.qrcode.QRCodeWriter
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions
import com.ruta.app.R
import java.util.UUID

class ProfileFragment : Fragment() {

    // Views
    private lateinit var imgMyQrCode: ImageView
    private lateinit var txtLinkedContactStatus: TextView
    private lateinit var btnScanQr: Button
    private lateinit var etFullName: EditText
    private lateinit var etPhone: EditText
    private lateinit var etEmail: EditText
    private lateinit var etEmergencyContact: EditText
    private lateinit var btnSaveProfile: Button
    private lateinit var btnLogout: Button

    private val currentUid = FirebaseAuth.getInstance().currentUser?.uid
    private val database = FirebaseDatabase.getInstance().reference

    private var activeQrToken: String? = null

    // Camera Scanner Launcher for QR Pairing
    private val qrScannerLauncher = registerForActivityResult(ScanContract()) { result ->
        if (result.contents != null) {
            val scannedPayload = result.contents
            processScannedQrCode(scannedPayload)
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

        // Bind Views
        imgMyQrCode = view.findViewById(R.id.imgMyQrCode)
        txtLinkedContactStatus = view.findViewById(R.id.txtLinkedContactStatus)
        btnScanQr = view.findViewById(R.id.btnScanQr)
        etFullName = view.findViewById(R.id.etFullName)
        etPhone = view.findViewById(R.id.etPhone)
        etEmail = view.findViewById(R.id.etEmail)
        etEmergencyContact = view.findViewById(R.id.etEmergencyContact)
        btnSaveProfile = view.findViewById(R.id.btnSaveProfile)
        btnLogout = view.findViewById(R.id.btnLogout)

        if (currentUid != null) {
            // 1. Generate Dynamic Single-Use QR
            generateAndDisplayDynamicQr()

            loadUserProfile()
            checkCurrentLinkedContact()
        }

        // 2. Scan QR Button Listener
        btnScanQr.setOnClickListener {
            val options = ScanOptions().apply {
                setPrompt("Scan a contact's RUTA Safety QR Code")
                setBeepEnabled(true)
                setOrientationLocked(true)
            }
            qrScannerLauncher.launch(options)
        }

        // 3. Save Profile Listener
        btnSaveProfile.setOnClickListener { saveUserProfile() }

        // 4. Logout Listener
        btnLogout.setOnClickListener { performLogout() }
    }

    private fun generateAndDisplayDynamicQr() {
        val uid = currentUid ?: return
        val newToken = UUID.randomUUID().toString().take(8) // Unique short token
        activeQrToken = newToken

        // Payload formatted as: RUTA_PAIR:<UID>:<TOKEN>
        val qrPayload = "RUTA_PAIR:$uid:$newToken"

        // Save active token to Firebase
        val tokenData = hashMapOf<String, Any>(
            "token" to newToken,
            "status" to "ACTIVE",
            "createdAt" to System.currentTimeMillis()
        )

        database.child("users").child(uid).child("activeQr").setValue(tokenData)
            .addOnSuccessListener {
                val qrBitmap = generateQRCodeBitmap(qrPayload)
                imgMyQrCode.setImageBitmap(qrBitmap)
            }
    }

    private fun processScannedQrCode(payload: String) {
        // Expected format: RUTA_PAIR:targetUid:token
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

        // Validate token status in target user's Firebase node
        database.child("users").child(targetUid).child("activeQr").get()
            .addOnSuccessListener { snapshot ->
                val activeToken = snapshot.child("token").getValue(String::class.java)
                val status = snapshot.child("status").getValue(String::class.java)

                if (activeToken == token && status == "ACTIVE") {
                    // Valid single-use QR! Perform pairing & invalidate token
                    linkEmergencyContactAndInvalidate(targetUid)
                } else {
                    Toast.makeText(requireContext(), "This QR Code has expired or already been used!", Toast.LENGTH_LONG).show()
                }
            }
            .addOnFailureListener {
                Toast.makeText(requireContext(), "Failed to verify QR code.", Toast.LENGTH_SHORT).show()
            }
    }

    private fun linkEmergencyContactAndInvalidate(contactUid: String) {
        val myUid = currentUid ?: return

        val updates = hashMapOf<String, Any>(
            "users/$myUid/emergencyContactId" to contactUid,
            "users/$contactUid/emergencyContactId" to myUid,
            "users/$contactUid/activeQr/status" to "EXPIRED" // 🔒 Invalidate single-use QR immediately
        )

        database.updateChildren(updates).addOnSuccessListener {
            Toast.makeText(requireContext(), "Emergency contact paired successfully!", Toast.LENGTH_SHORT).show()
            txtLinkedContactStatus.text = "Linked Contact: Active"
            txtLinkedContactStatus.setTextColor(Color.parseColor("#388E3C"))
        }.addOnFailureListener {
            Toast.makeText(requireContext(), "Failed to link contact.", Toast.LENGTH_SHORT).show()
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
            if (snapshot.exists()) {
                etFullName.setText(snapshot.child("name").getValue(String::class.java) ?: "")
                etPhone.setText(snapshot.child("phone").getValue(String::class.java) ?: "")
                etEmail.setText(snapshot.child("email").getValue(String::class.java) ?: FirebaseAuth.getInstance().currentUser?.email ?: "")
                etEmergencyContact.setText(snapshot.child("emergencyPhone").getValue(String::class.java) ?: "")
            }
        }
    }

    private fun saveUserProfile() {
        val uid = currentUid ?: return
        val name = etFullName.text.toString().trim()
        val phone = etPhone.text.toString().trim()
        val email = etEmail.text.toString().trim()
        val emergencyPhone = etEmergencyContact.text.toString().trim()

        val updates = hashMapOf<String, Any>(
            "name" to name,
            "phone" to phone,
            "email" to email,
            "emergencyPhone" to emergencyPhone
        )

        database.child("users").child(uid).updateChildren(updates).addOnSuccessListener {
            Toast.makeText(requireContext(), "Profile updated successfully!", Toast.LENGTH_SHORT).show()
        }.addOnFailureListener {
            Toast.makeText(requireContext(), "Failed to update profile.", Toast.LENGTH_SHORT).show()
        }
    }

    private fun checkCurrentLinkedContact() {
        val myUid = currentUid ?: return
        database.child("users").child(myUid).child("emergencyContactId")
            .get().addOnSuccessListener { snapshot ->
                val contactUid = snapshot.getValue(String::class.java)
                if (!contactUid.isNullOrEmpty()) {
                    txtLinkedContactStatus.text = "Linked Contact: Active"
                    txtLinkedContactStatus.setTextColor(Color.parseColor("#388E3C"))
                } else {
                    txtLinkedContactStatus.text = "Linked Contact: None"
                    txtLinkedContactStatus.setTextColor(Color.parseColor("#D32F2F"))
                }
            }
    }

    private fun performLogout() {
        FirebaseAuth.getInstance().signOut()
        val sharedPref = requireContext().getSharedPreferences("USER_SESSION", Context.MODE_PRIVATE)
        sharedPref.edit().clear().apply()

        Toast.makeText(requireContext(), "Logged out successfully", Toast.LENGTH_SHORT).show()

        val intent = Intent(requireContext(), LoginActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
        }
        startActivity(intent)
    }
}