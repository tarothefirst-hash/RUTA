package com.ruta.app.ui

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Bundle
import android.util.Base64
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.fragment.app.Fragment
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.database.FirebaseDatabase
import com.ruta.app.databinding.FragmentPromosBinding
import com.ruta.app.model.DiscountRequest
import java.io.ByteArrayOutputStream
import java.io.InputStream
import com.ruta.app.util.ImageUtils

class PromosFragment : Fragment() {

    private var _binding: FragmentPromosBinding? = null
    private val binding get() = _binding!!

    private var frontImageUri: Uri? = null
    private var backImageUri: Uri? = null
    private var isSelectingFront = true

    private val auth = FirebaseAuth.getInstance()
    private val database = FirebaseDatabase.getInstance().reference

    // Standard activity result launcher for image selection
    private val imagePickerLauncher = registerForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        uri?.let {
            if (isSelectingFront) {
                frontImageUri = it
                binding.imgFrontPreview.setImageURI(it)
            } else {
                backImageUri = it
                binding.imgBackPreview.setImageURI(it)
            }
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentPromosBinding.inflate(inflater, container, false)

        binding.btnUploadFront.setOnClickListener {
            isSelectingFront = true
            imagePickerLauncher.launch("image/*")
        }

        binding.btnUploadBack.setOnClickListener {
            isSelectingFront = false
            imagePickerLauncher.launch("image/*")
        }

        binding.btnSubmitDiscount.setOnClickListener {
            submitDiscountApplication()
        }

        return binding.root
    }

    private fun submitDiscountApplication() {
        val user = auth.currentUser
        if (user == null) {
            Toast.makeText(context, "User not authenticated.", Toast.LENGTH_SHORT).show()
            return
        }

        val frontUri = frontImageUri
        val backUri = backImageUri

        if (frontUri == null || backUri == null) {
            Toast.makeText(context, "Please upload both Front and Back of your ID.", Toast.LENGTH_SHORT).show()
            return
        }

        binding.progressBar.visibility = View.VISIBLE
        binding.btnSubmitDiscount.isEnabled = false

        // Convert images on a background thread to prevent UI freeze
        Thread {
            try {
                val frontBase64 = ImageUtils.uriToCompressedBase64(requireContext(), frontUri)
                val backBase64 = ImageUtils.uriToCompressedBase64(requireContext(), backUri)

                requireActivity().runOnUiThread {
                    if (frontBase64 == null || backBase64 == null) {
                        resetLoadingState()
                        Toast.makeText(context, "Failed to process ID photos.", Toast.LENGTH_SHORT).show()
                        return@runOnUiThread
                    }

                    val discountType = if (binding.radioStudent.isChecked) "STUDENT" else "SENIOR"

                    // Format as Data URI before saving to Realtime Database
                    val frontDataUri = "data:image/jpeg;base64,$frontBase64"
                    val backDataUri = "data:image/jpeg;base64,$backBase64"

                    saveRequestToDatabase(discountType, frontDataUri, backDataUri)
                }
            } catch (e: Exception) {
                requireActivity().runOnUiThread {
                    resetLoadingState()
                    Log.e("PromosFragment", "Base64 conversion failed", e)
                    Toast.makeText(context, "Error processing images: ${e.message}", Toast.LENGTH_SHORT).show()
                }
            }
        }.start()
    }

    private fun saveRequestToDatabase(type: String, frontBase64: String, backBase64: String) {
        val uid = auth.currentUser?.uid ?: return
        val requestId = database.child("discount_requests").push().key ?: return

        database.child("users").child(uid).get().addOnSuccessListener { snapshot ->
            val firstName = snapshot.child("firstName").getValue(String::class.java) ?: ""
            val lastName = snapshot.child("lastName").getValue(String::class.java) ?: ""

            // Base64 strings formatted as Data URIs so image loaders like Glide/Picasso or web admins can render them directly
            val request = DiscountRequest(
                requestId = requestId,
                userId = uid,
                userName = "$firstName $lastName".trim(),
                discountType = type,
                idFrontUrl = "data:image/jpeg;base64,$frontBase64",
                idBackUrl = "data:image/jpeg;base64,$backBase64",
                status = "PENDING"
            )

            database.child("discount_requests").child(requestId).setValue(request)
                .addOnSuccessListener {
                    resetLoadingState()
                    Toast.makeText(context, "Application submitted! Pending admin review.", Toast.LENGTH_LONG).show()
                }
                .addOnFailureListener { err ->
                    resetLoadingState()
                    Toast.makeText(context, "Database error: ${err.message}", Toast.LENGTH_SHORT).show()
                }
        }.addOnFailureListener { err ->
            resetLoadingState()
            Toast.makeText(context, "Failed to fetch user data: ${err.message}", Toast.LENGTH_SHORT).show()
        }
    }

    // Helper method to scale and compress the image before encoding
    private fun uriToBase64(uri: Uri): String? {
        return try {
            val inputStream: InputStream? = requireContext().contentResolver.openInputStream(uri)
            val originalBitmap = BitmapFactory.decodeStream(inputStream)
            inputStream?.close() ?: return null

            // Resize image to max 800px width/height while maintaining aspect ratio
            val maxDimension = 800
            val width = originalBitmap.width
            val height = originalBitmap.height

            val scaledWidth: Int
            val scaledHeight: Int

            if (width > height) {
                scaledWidth = maxDimension
                scaledHeight = (maxDimension * (height.toFloat() / width)).toInt()
            } else {
                scaledHeight = maxDimension
                scaledWidth = (maxDimension * (width.toFloat() / height)).toInt()
            }

            val scaledBitmap = Bitmap.createScaledBitmap(originalBitmap, scaledWidth, scaledHeight, true)
            val byteArrayOutputStream = ByteArrayOutputStream()

            // Compress as JPEG with 70% quality (keeps payload under ~150KB per photo)
            scaledBitmap.compress(Bitmap.CompressFormat.JPEG, 70, byteArrayOutputStream)
            val byteArray = byteArrayOutputStream.toByteArray()

            Base64.encodeToString(byteArray, Base64.NO_WRAP)
        } catch (e: Exception) {
            Log.e("PromosFragment", "Error converting Uri to Base64", e)
            null
        }
    }

    private fun resetLoadingState() {
        binding.progressBar.visibility = View.GONE
        binding.btnSubmitDiscount.isEnabled = true
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}