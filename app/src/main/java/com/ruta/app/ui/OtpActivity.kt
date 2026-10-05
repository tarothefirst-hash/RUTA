package com.ruta.app.ui

import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import com.ruta.app.databinding.ActivityOtpBinding
import com.ruta.app.network.BrevoEmailService
import kotlin.random.Random

class OtpActivity : AppCompatActivity() {

    private lateinit var binding: ActivityOtpBinding
    private var generatedOtp: String = ""
    private var targetEmail: String = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        WindowCompat.setDecorFitsSystemWindows(window, false)

        binding = ActivityOtpBinding.inflate(layoutInflater)
        setContentView(binding.root)

        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { view, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            view.setPadding(
                systemBars.left,
                systemBars.top,
                systemBars.right,
                systemBars.bottom
            )
            insets
        }

        targetEmail = intent.getStringExtra("EXTRA_EMAIL") ?: ""
        generatedOtp = intent.getStringExtra("EXTRA_OTP") ?: ""

        binding.txtOtpSubtitle.text = "Enter the 6-digit verification code sent to\n$targetEmail"

        // Send initial email via Brevo
        if (targetEmail.isNotEmpty() && generatedOtp.isNotEmpty()) {
            sendOtpEmail(targetEmail, generatedOtp)
        }

        binding.btnVerifyOtp.setOnClickListener {
            hideError()
            val inputCode = binding.edtOtpCode.text.toString().trim()

            if (inputCode.length != 6) {
                showError("Please enter all 6 digits.")
                return@setOnClickListener
            }

            if (inputCode == generatedOtp) {
                setResult(RESULT_OK)
                finish()
            } else {
                showError("Invalid OTP code. Please try again.")
            }
        }

        binding.btnResendOtp.setOnClickListener {
            hideError()
            generatedOtp = String.format("%06d", Random.nextInt(0, 1000000))
            binding.edtOtpCode.setText("")
            sendOtpEmail(targetEmail, generatedOtp)
        }
    }

    private fun sendOtpEmail(email: String, code: String) {
        setLoading(true)
        BrevoEmailService.sendOtpEmail(email, code) { success, errorMsg ->
            runOnUiThread {
                setLoading(false)
                if (success) {
                    Toast.makeText(this, "Verification code sent to $email", Toast.LENGTH_SHORT).show()
                } else {
                    showError(errorMsg ?: "Failed to send email.")
                }
            }
        }
    }

    private fun setLoading(loading: Boolean) {
        binding.btnVerifyOtp.isEnabled = !loading
        binding.btnResendOtp.isEnabled = !loading
        binding.btnVerifyOtp.text = if (loading) "Sending Code..." else "VERIFY & CONTINUE"
    }

    private fun showError(msg: String) {
        binding.txtOtpError.text = msg
        binding.txtOtpError.visibility = View.VISIBLE
    }

    private fun hideError() {
        binding.txtOtpError.visibility = View.GONE
    }
}