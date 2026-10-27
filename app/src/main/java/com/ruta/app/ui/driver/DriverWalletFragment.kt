package com.ruta.app.ui.driver

import com.ruta.app.util.StoragePhotos
import com.ruta.app.util.PhotoPaths
import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.fragment.app.Fragment
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.database.DataSnapshot
import com.google.firebase.database.DatabaseError
import com.google.firebase.database.FirebaseDatabase
import com.google.firebase.database.ServerValue
import com.google.firebase.database.ValueEventListener
import com.google.firebase.storage.FirebaseStorage
import com.ruta.app.databinding.FragmentDriverWalletBinding
import java.util.Locale
import com.ruta.app.util.CashInRules
import com.ruta.app.util.ImageUtils

class DriverWalletFragment : Fragment() {

    private var _binding: FragmentDriverWalletBinding? = null
    private val binding get() = _binding!!

    private val auth by lazy { FirebaseAuth.getInstance() }
    private val database by lazy { FirebaseDatabase.getInstance().reference }
    private val storage by lazy { FirebaseStorage.getInstance().reference }

    private var selectedAmount: Double? = null
    private var selectedReceiptUri: Uri? = null
    private var walletListener: ValueEventListener? = null
    private var pendingRequestListener: ValueEventListener? = null

    // Photo picker launcher for payment receipts
    private val selectReceiptLauncher = registerForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        if (uri != null) {
            selectedReceiptUri = uri
            binding.imgReceiptPreview.setImageURI(uri)
            binding.imgReceiptPreview.visibility = View.VISIBLE
            binding.txtReceiptPlaceholder?.visibility = View.GONE
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View {
        _binding = FragmentDriverWalletBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        setupAmountButtons()

        binding.btnSelectReceipt.setOnClickListener {
            selectReceiptLauncher.launch("image/*")
        }

        binding.btnSubmitTopUp.setOnClickListener { submitTopUp() }
        listenForWalletBalance()
        listenForPendingCashIn()
    }

    private fun setupAmountButtons() {
        val amountButtons = listOf(
            binding.btnAmount20 to 20.0,
            binding.btnAmount50 to 50.0,
            binding.btnAmount100 to 100.0,
            binding.btnAmount200 to 200.0,
            binding.btnAmount500 to 500.0
        )

        // Custom amount: typing replaces any quick-pick and is validated live (20 to 10,000).
        binding.edtCustomAmount.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: android.text.Editable?) {
                when (val r = CashInRules.parse(s?.toString())) {
                    is CashInRules.Result.Valid -> {
                        selectedAmount = r.amount
                        binding.layoutCustomAmount.error = null
                        binding.txtSelectedAmount.text = "Selected amount: ₱${formatPeso(r.amount)}"
                        amountButtons.forEach { it.first.alpha = 0.4f }
                    }
                    is CashInRules.Result.Invalid -> {
                        selectedAmount = null
                        binding.layoutCustomAmount.error = r.message
                    }
                    CashInRules.Result.Empty -> {
                        binding.layoutCustomAmount.error = null
                        if (amountButtons.none { it.first.alpha == 1.0f }) selectedAmount = null
                    }
                }
            }
        })

        amountButtons.forEach { (button, amount) ->
            button.setOnClickListener {
                binding.edtCustomAmount.setText("")
                selectedAmount = amount
                binding.txtSelectedAmount.text = "Selected amount: ₱${formatPeso(amount)}"
                highlightSelected(button, amountButtons.map { it.first })
            }
        }
    }

    private fun formatPeso(amount: Double): String =
        if (amount % 1.0 == 0.0) String.format(Locale.US, "%,d", amount.toLong()) else String.format(Locale.US, "%,.2f", amount)

    private fun highlightSelected(selected: Button, all: List<Button>) {
        all.forEach { btn -> btn.alpha = if (btn == selected) 1.0f else 0.4f }
    }

    private fun listenForWalletBalance() {
        val driverId = auth.currentUser?.uid ?: return

        walletListener = database.child("users").child(driverId).child("walletBalance")
            .addValueEventListener(object : ValueEventListener {
                override fun onDataChange(snapshot: DataSnapshot) {
                    val balance = snapshot.getValue(Double::class.java) ?: 0.0
                    if (_binding != null) {
                        binding.txtWalletBalance.text = String.format(Locale.getDefault(), "₱%.2f", balance)
                    }
                }
                override fun onCancelled(error: DatabaseError) {}
            })
    }

    private fun listenForPendingCashIn() {
        val driverId = auth.currentUser?.uid ?: return

        pendingRequestListener = database.child("wallet_requests")
            .orderByChild("driverId").equalTo(driverId)
            .addValueEventListener(object : ValueEventListener {
                override fun onDataChange(snapshot: DataSnapshot) {
                    if (_binding == null) return

                    var hasPending = false
                    for (child in snapshot.children) {
                        val status = child.child("status").getValue(String::class.java) ?: "PENDING"
                        val amount = child.child("amount").getValue(Double::class.java) ?: 0.0

                        if (status == "PENDING") {
                            hasPending = true
                            binding.txtLastTopUpStatus.text = "Status: Pending Admin Confirmation (₱${formatPeso(amount)})"
                            binding.btnSubmitTopUp.isEnabled = false
                            binding.btnSubmitTopUp.text = "Pending Confirmation..."
                            binding.btnSelectReceipt.isEnabled = false
                            break
                        }
                    }

                    if (!hasPending) {
                        binding.btnSubmitTopUp.isEnabled = true
                        binding.btnSubmitTopUp.text = "I've Sent Payment"
                        binding.btnSelectReceipt.isEnabled = true
                    }
                }

                override fun onCancelled(error: DatabaseError) {}
            })
    }

    private fun submitTopUp() {
        val amount = selectedAmount
        if (amount == null) {
            Toast.makeText(context, "Pick an amount or type a valid one (₱20 to ₱10,000).", Toast.LENGTH_SHORT).show()
            return
        }

        val receiptUri = selectedReceiptUri
        if (receiptUri == null) {
            Toast.makeText(context, "Please attach proof of payment photo.", Toast.LENGTH_SHORT).show()
            return
        }

        val driverId = auth.currentUser?.uid ?: return

        binding.btnSubmitTopUp.isEnabled = false
        binding.btnSelectReceipt.isEnabled = false
        binding.btnSubmitTopUp.text = "Processing Receipt..."

        val requestId = database.child("wallet_requests").push().key ?: run {
            resetSubmitButton()
            return
        }

        // Convert selected image using ImageUtils
        val context = context ?: return

        // Calling your actual ImageUtils method name
        val base64Image = ImageUtils.uriToCompressedBase64(context, receiptUri)

        if (base64Image.isNullOrEmpty()) {
            handleFailure("Failed to process receipt image. Please try another photo.")
            return
        }

        // The proof of payment goes to Firebase Storage (owner + admin only); the request keeps its path.
        binding.btnSubmitTopUp.text = "Uploading Receipt..."
        StoragePhotos.uploadBase64(PhotoPaths.receipt(driverId, requestId), base64Image) { ref, error ->
            if (_binding == null) return@uploadBase64
            if (ref == null) {
                binding.btnSubmitTopUp.isEnabled = true
                binding.btnSelectReceipt.isEnabled = true
                binding.btnSubmitTopUp.text = "I've Sent Payment"
                Toast.makeText(context, "Receipt upload failed: ${error ?: "check your connection"}", Toast.LENGTH_LONG).show()
                return@uploadBase64
            }
            saveTopUpRequestToDatabase(requestId, driverId, amount, ref)
        }
    }

    private fun saveTopUpRequestToDatabase(
        requestId: String,
        driverId: String,
        amount: Double,
        receiptBase64: String
    ) {
        if (_binding == null) return
        binding.btnSubmitTopUp.text = "Submitting Request..."

        database.child("users").child(driverId).get().addOnSuccessListener { snap ->
            val firstName = snap.child("firstName").getValue(String::class.java) ?: ""
            val lastName = snap.child("lastName").getValue(String::class.java) ?: ""
            val fullName = if (firstName.isNotEmpty() || lastName.isNotEmpty()) {
                "$firstName $lastName".trim()
            } else {
                snap.child("fullName").getValue(String::class.java) ?: "Driver"
            }
            val email = snap.child("email").getValue(String::class.java) ?: ""

            val requestMap = mapOf(
                "requestId" to requestId,
                "driverId" to driverId,
                "driverUid" to driverId,
                "driverName" to fullName,
                "driverEmail" to email,
                "amount" to amount,
                "receiptUrl" to receiptBase64, // a "storage:" reference (older requests hold inline Base64)
                "status" to "PENDING",
                "timestamp" to ServerValue.TIMESTAMP
            )

            database.child("wallet_requests").child(requestId).setValue(requestMap)
                .addOnSuccessListener {
                    if (_binding == null) return@addOnSuccessListener

                    selectedReceiptUri = null
                    binding.imgReceiptPreview.visibility = View.GONE
                    binding.txtLastTopUpStatus.text =
                        "Last request: ₱${formatPeso(amount)} — awaiting admin confirmation"

                    Toast.makeText(
                        context,
                        "Top-up request sent! Your wallet updates once admin confirms.",
                        Toast.LENGTH_LONG
                    ).show()
                }
                .addOnFailureListener {
                    handleFailure("Failed to submit request to database.")
                }
        }.addOnFailureListener {
            handleFailure("Failed to fetch user profile.")
        }
    }

    private fun handleFailure(message: String) {
        if (_binding == null) return
        resetSubmitButton()
        Toast.makeText(context, message, Toast.LENGTH_LONG).show()
    }

    private fun resetSubmitButton() {
        binding.btnSubmitTopUp.isEnabled = true
        binding.btnSelectReceipt.isEnabled = true
        binding.btnSubmitTopUp.text = "I've Sent Payment"
    }

    override fun onDestroyView() {
        super.onDestroyView()
        val uid = auth.currentUser?.uid
        if (uid != null) {
            walletListener?.let { database.child("users").child(uid).child("walletBalance").removeEventListener(it) }
            pendingRequestListener?.let {
                database.child("wallet_requests").orderByChild("driverId").equalTo(uid).removeEventListener(it)
            }
        }
        _binding = null
    }
}