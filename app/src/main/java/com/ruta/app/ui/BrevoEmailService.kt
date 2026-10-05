package com.ruta.app.network

import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException

object BrevoEmailService {

    private const val BREVO_API_KEY = "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"
    private const val BREVO_URL = "https://api.brevo.com/v3/smtp/email"
    private const val SENDER_EMAIL = "rutaadmin01@gmail.com"
    private const val SENDER_NAME = "RUTA"

    fun sendOtpEmail(recipientEmail: String, otpCode: String, onResult: (Boolean, String?) -> Unit) {
        val client = OkHttpClient()

        val jsonBody = JSONObject().apply {
            put("sender", JSONObject().apply {
                put("name", SENDER_NAME)
                put("email", SENDER_EMAIL)
            })
            put("to", JSONArray().apply {
                put(JSONObject().apply {
                    put("email", recipientEmail)
                })
            })
            put("subject", "Your RUTA Verification Code")
            put("htmlContent", """
                <!DOCTYPE html>
                <html>
                <body style="font-family: Arial, sans-serif; background-color: #f8f9fa; padding: 20px;">
                    <div style="max-width: 500px; margin: 0 auto; background: #ffffff; padding: 24px; border-radius: 12px; box-shadow: 0 4px 6px rgba(0,0,0,0.05);">
                        <h2 style="color: #6200EE; text-align: center;">RUTA Verification Code</h2>
                        <p style="color: #495057; font-size: 15px;">Your 6-digit email verification code is:</p>
                        <div style="background-color: #f1f3f5; padding: 16px; text-align: center; border-radius: 8px; font-size: 28px; font-weight: bold; letter-spacing: 6px; color: #1a1d1e;">
                            $otpCode
                        </div>
                        <p style="color: #868e96; font-size: 13px; margin-top: 20px; text-align: center;">If you did not request this code, please ignore this email.</p>
                    </div>
                </body>
                </html>
            """.trimIndent())
        }

        val requestBody = jsonBody.toString().toRequestBody("application/json; charset=utf-8".toMediaType())

        val request = Request.Builder()
            .url(BREVO_URL)
            .addHeader("api-key", BREVO_API_KEY)
            .addHeader("Content-Type", "application/json")
            .post(requestBody)
            .build()

        client.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                onResult(false, e.message ?: "Network error occurred")
            }

            override fun onResponse(call: Call, response: Response) {
                val responseBody = response.body?.string()
                if (response.isSuccessful) {
                    onResult(true, null)
                } else {
                    onResult(false, "Brevo Error (${response.code}): $responseBody")
                }
            }
        })
    }
}