package com.ruta.app.ui

import android.os.Bundle
import android.os.CountDownTimer
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

    // OTP Security Controls
    private var countDownTimer: CountDownTimer? = null
    private var otpSentTimestamp: Long = 0L
    private var resendAttempts: Int = 0
    private val MAX_RESEND_ATTEMPTS = 3
    private val OTP_EXPIRY_MS = 5 * 60_000L // 5 minutes validity

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

        // Initial email send & timer start
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

            // Expiration Guard Check (5 min)
            if (System.currentTimeMillis() - otpSentTimestamp > OTP_EXPIRY_MS) {
                showError("This OTP code has expired. Please request a new code.")
                return@setOnClickListener
            }

            if (inputCode == generatedOtp) {
                countDownTimer?.cancel()
                setResult(RESULT_OK)
                finish()
            } else {
                showError("Invalid OTP code. Please try again.")
            }
        }

        binding.btnResendOtp.setOnClickListener {
            hideError()

            if (resendAttempts >= MAX_RESEND_ATTEMPTS) {
                showError("Maximum resend attempts reached. Please try again later.")
                return@setOnClickListener
            }

            resendAttempts++
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
                    startCountdownTimer()
                } else {
                    showError(errorMsg ?: "Failed to send email.")
                }
            }
        }
    }

    private fun startCountdownTimer() {
        otpSentTimestamp = System.currentTimeMillis()
        binding.btnResendOtp.isEnabled = false
        binding.btnResendOtp.alpha = 0.5f

        countDownTimer?.cancel()
        countDownTimer = object : CountDownTimer(60_000, 1000) {
            override fun onTick(millisUntilFinished: Long) {
                val secondsLeft = millisUntilFinished / 1000
                binding.txtOtpTimer.text = "Resend code in ${secondsLeft}s"
            }

            override fun onFinish() {
                binding.txtOtpTimer.text = if (resendAttempts < MAX_RESEND_ATTEMPTS) {
                    "Didn't receive code?"
                } else {
                    "Resend limit reached (${MAX_RESEND_ATTEMPTS}/${MAX_RESEND_ATTEMPTS})"
                }

                if (resendAttempts < MAX_RESEND_ATTEMPTS) {
                    binding.btnResendOtp.isEnabled = true
                    binding.btnResendOtp.alpha = 1.0f
                }
            }
        }.start()
    }

    private fun setLoading(loading: Boolean) {
        binding.btnVerifyOtp.isEnabled = !loading
        binding.btnResendOtp.isEnabled = !loading && (resendAttempts < MAX_RESEND_ATTEMPTS)
        binding.btnVerifyOtp.text = if (loading) "Sending Code..." else "VERIFY & CONTINUE"
    }

    private fun showError(msg: String) {
        binding.txtOtpError.text = msg
        binding.txtOtpError.visibility = View.VISIBLE
    }

    private fun hideError() {
        binding.txtOtpError.visibility = View.GONE
    }

    override fun onDestroy() {
        super.onDestroy()
        countDownTimer?.cancel()
    }
}