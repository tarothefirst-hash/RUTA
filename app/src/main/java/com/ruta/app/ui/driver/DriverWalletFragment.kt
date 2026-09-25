package com.ruta.app.ui.driver

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.Toast
import androidx.fragment.app.Fragment
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.database.DataSnapshot
import com.google.firebase.database.DatabaseError
import com.google.firebase.database.FirebaseDatabase
import com.google.firebase.database.ValueEventListener
import com.ruta.app.R
import com.ruta.app.databinding.FragmentDriverWalletBinding
import com.ruta.app.util.WalletManager
import java.util.Locale

class DriverWalletFragment : Fragment() {

    private var _binding: FragmentDriverWalletBinding? = null
    private val binding get() = _binding!!

    private val auth by lazy { FirebaseAuth.getInstance() }
    private val database by lazy { FirebaseDatabase.getInstance().reference }

    private var selectedAmount: Double? = null
    private var walletListener: ValueEventListener? = null

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View {
        _binding = FragmentDriverWalletBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        setupAmountButtons()
        binding.btnSubmitTopUp.setOnClickListener { submitTopUp() }
        listenForWalletBalance()
    }

    private fun setupAmountButtons() {
        val amountButtons = listOf(
            binding.btnAmount20 to 20.0,
            binding.btnAmount50 to 50.0,
            binding.btnAmount100 to 100.0,
            binding.btnAmount200 to 200.0,
            binding.btnAmount500 to 500.0
        )

        amountButtons.forEach { (button, amount) ->
            button.setOnClickListener {
                selectedAmount = amount
                binding.txtSelectedAmount.text = "Selected amount: ₱${amount.toInt()}"
                highlightSelected(button, amountButtons.map { it.first })
            }
        }
    }

    private fun highlightSelected(selected: Button, all: List<Button>) {
        all.forEach { btn -> btn.alpha = if (btn == selected) 1.0f else 0.55f }
    }

    private fun listenForWalletBalance() {
        val driverId = auth.currentUser?.uid ?: return

        walletListener = database.child("users").child(driverId).child("walletBalance")
            .addValueEventListener(object : ValueEventListener {
                override fun onDataChange(snapshot: DataSnapshot) {
                    val balance = snapshot.getValue(Double::class.java) ?: 0.0
                    binding.txtWalletBalance.text = String.format(Locale.getDefault(), "₱%.2f", balance)
                }
                override fun onCancelled(error: DatabaseError) {}
            })
    }

    private fun submitTopUp() {
        val amount = selectedAmount
        if (amount == null) {
            Toast.makeText(context, "Pick an amount first.", Toast.LENGTH_SHORT).show()
            return
        }

        val driverId = auth.currentUser?.uid ?: return
        binding.btnSubmitTopUp.isEnabled = false

        database.child("users").child(driverId).child("firstName").get()
            .addOnSuccessListener { snap ->
                val name = snap.getValue(String::class.java) ?: "Driver"

                WalletManager.submitTopUpRequest(database, driverId, name, amount) { success ->
                    binding.btnSubmitTopUp.isEnabled = true
                    if (success) {
                        binding.txtLastTopUpStatus.text =
                            "Last request: ₱${amount.toInt()} — awaiting admin confirmation"
                        Toast.makeText(
                            context,
                            "Top-up request sent! Your wallet updates once admin confirms.",
                            Toast.LENGTH_LONG
                        ).show()
                    } else {
                        Toast.makeText(
                            context,
                            "Failed to submit — check your connection and try again.",
                            Toast.LENGTH_LONG
                        ).show()
                    }
                }
            }
            .addOnFailureListener {
                binding.btnSubmitTopUp.isEnabled = true
                Toast.makeText(context, "Failed to submit — check your connection and try again.", Toast.LENGTH_LONG).show()
            }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        walletListener?.let { listener ->
            auth.currentUser?.uid?.let { uid ->
                database.child("users").child(uid).child("walletBalance").removeEventListener(listener)
            }
        }
        _binding = null
    }
}