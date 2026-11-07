package com.ruta.app.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.os.Bundle
import android.view.View
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import com.google.android.material.button.MaterialButton
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.database.FirebaseDatabase
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.face.FaceDetection
import com.google.mlkit.vision.face.FaceDetectorOptions
import com.ruta.app.R
import com.ruta.app.util.LivenessLogic
import com.ruta.app.util.StoragePhotos
import java.io.ByteArrayOutputStream
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * Live face check at registration: blink, turn head, smile (order shuffled), then one selfie.
 * The detection runs on the phone with ML Kit; the selfie goes to private Storage and the
 * user record gets faceVerified = true so an admin can compare it with the submitted ID.
 *
 * Extras: NEXT_ACTIVITY (class name to open afterwards; otherwise just finish).
 */
class FaceVerificationActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_NEXT = "NEXT_ACTIVITY"
        private const val SELFIE_MAX = 640
    }

    private val auth = FirebaseAuth.getInstance()
    private val database = FirebaseDatabase.getInstance().reference

    private lateinit var preview: PreviewView
    private lateinit var txtInstruction: TextView
    private lateinit var progress: ProgressBar
    private lateinit var consent: View
    private lateinit var busy: View

    private val cameraExecutor: ExecutorService = Executors.newSingleThreadExecutor()
    private val detector by lazy {
        FaceDetection.getClient(
            FaceDetectorOptions.Builder()
                .setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_FAST)
                .setClassificationMode(FaceDetectorOptions.CLASSIFICATION_MODE_ALL)
                .build()
        )
    }
    private var imageCapture: ImageCapture? = null
    private val machine = LivenessLogic.Machine(
        listOf(LivenessLogic.Step.BLINK, LivenessLogic.Step.TURN, LivenessLogic.Step.SMILE).shuffled()
    )
    private var capturing = false
    private var lastShownStep: LivenessLogic.Step? = null

    private val cameraPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) startCamera()
            else Toast.makeText(this, "Camera permission is needed to verify your face.", Toast.LENGTH_LONG).show()
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_face_verification)

        preview = findViewById(R.id.facePreview)
        txtInstruction = findViewById(R.id.txtFaceInstruction)
        progress = findViewById(R.id.faceProgress)
        consent = findViewById(R.id.layoutFaceConsent)
        busy = findViewById(R.id.txtFaceBusy)

        val hasFrontCamera = packageManager.hasSystemFeature(PackageManager.FEATURE_CAMERA_FRONT)
        findViewById<MaterialButton>(R.id.btnFaceSkip).apply {
            // Only for phones that physically cannot do it; everyone else must verify.
            visibility = if (hasFrontCamera) View.GONE else View.VISIBLE
            setOnClickListener {
                auth.currentUser?.uid?.let { database.child("users").child(it).child("faceSkipped").setValue(true) }
                finishFlow()
            }
        }
        findViewById<MaterialButton>(R.id.btnFaceStart).setOnClickListener {
            consent.visibility = View.GONE
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
                startCamera()
            } else {
                cameraPermission.launch(Manifest.permission.CAMERA)
            }
        }
        findViewById<MaterialButton>(R.id.btnFaceSignOut).setOnClickListener {
            auth.signOut()
            startActivity(Intent(this, LoginActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
            })
            finish()
        }

        // Back leaves the app instead of skipping the check.
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() { finishAffinity() }
        })
    }

    private fun startCamera() {
        val providerFuture = ProcessCameraProvider.getInstance(this)
        providerFuture.addListener({
            val provider = providerFuture.get()
            val previewUseCase = Preview.Builder().build().also { it.setSurfaceProvider(preview.surfaceProvider) }
            val capture = ImageCapture.Builder().setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY).build()
            imageCapture = capture
            val analysis = ImageAnalysis.Builder()
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .build()
                .also { it.setAnalyzer(cameraExecutor) { proxy -> analyse(proxy) } }
            try {
                provider.unbindAll()
                provider.bindToLifecycle(this, CameraSelector.DEFAULT_FRONT_CAMERA, previewUseCase, capture, analysis)
            } catch (e: Exception) {
                Toast.makeText(this, "Couldn't open the camera: ${e.message}", Toast.LENGTH_LONG).show()
                consent.visibility = View.VISIBLE
            }
        }, ContextCompat.getMainExecutor(this))
        showStep(machine.step)
    }

    @androidx.annotation.OptIn(androidx.camera.core.ExperimentalGetImage::class)
    private fun analyse(proxy: ImageProxy) {
        val media = proxy.image
        if (media == null || capturing) { proxy.close(); return }
        val rotation = proxy.imageInfo.rotationDegrees
        val image = InputImage.fromMediaImage(media, rotation)
        val upright = rotation == 90 || rotation == 270
        val w = if (upright) proxy.height else proxy.width
        val h = if (upright) proxy.width else proxy.height

        detector.process(image)
            .addOnSuccessListener { faces ->
                val face = faces.firstOrNull()
                val box = face?.boundingBox
                val centered = box != null &&
                    kotlin.math.abs(box.centerX() - w / 2f) < w * 0.2f &&
                    kotlin.math.abs(box.centerY() - h / 2f) < h * 0.2f
                val big = box != null && box.height() >= h * 0.30f && box.height() <= h * 0.90f
                val step = machine.onFrame(
                    LivenessLogic.Frame(
                        faceCount = faces.size,
                        centered = centered,
                        bigEnough = big,
                        leftEyeOpen = face?.leftEyeOpenProbability,
                        rightEyeOpen = face?.rightEyeOpenProbability,
                        smile = face?.smilingProbability,
                        yaw = face?.headEulerAngleY ?: 0f
                    )
                )
                runOnUiThread {
                    progress.progress = (machine.progress * 100).toInt()
                    if (step != lastShownStep) showStep(step)
                }
                if (step == LivenessLogic.Step.DONE && !capturing) {
                    capturing = true
                    runOnUiThread { takeSelfie() }
                }
            }
            .addOnCompleteListener { proxy.close() }
    }

    private fun showStep(step: LivenessLogic.Step) {
        lastShownStep = step
        txtInstruction.text = LivenessLogic.instruction(step)
    }

    private fun takeSelfie() {
        val capture = imageCapture ?: return
        busy.visibility = View.VISIBLE
        capture.takePicture(cameraExecutor, object : ImageCapture.OnImageCapturedCallback() {
            override fun onCaptureSuccess(image: ImageProxy) {
                val jpeg = try {
                    val buffer = image.planes[0].buffer
                    val bytes = ByteArray(buffer.remaining()).also { buffer.get(it) }
                    val bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                    val rotated = Bitmap.createBitmap(
                        bmp, 0, 0, bmp.width, bmp.height,
                        Matrix().apply { postRotate(image.imageInfo.rotationDegrees.toFloat()) }, true
                    )
                    val ratio = minOf(1f, SELFIE_MAX.toFloat() / maxOf(rotated.width, rotated.height))
                    val small = Bitmap.createScaledBitmap(
                        rotated, (rotated.width * ratio).toInt(), (rotated.height * ratio).toInt(), true
                    )
                    ByteArrayOutputStream().also { small.compress(Bitmap.CompressFormat.JPEG, 80, it) }.toByteArray()
                } catch (e: Exception) { null } finally { image.close() }
                runOnUiThread { if (jpeg == null) retry("Couldn't read the photo. Try again.") else save(jpeg) }
            }
            override fun onError(exception: ImageCaptureException) {
                runOnUiThread { retry("Couldn't take the photo. Try again.") }
            }
        })
    }

    private fun save(jpeg: ByteArray) {
        val uid = auth.currentUser?.uid ?: return retry("You are signed out. Please sign in again.")
        StoragePhotos.upload("faces/$uid/selfie.jpg", jpeg) { value, error ->
            runOnUiThread {
                if (value == null) return@runOnUiThread retry(error ?: "Upload failed. Check your connection.")
                database.child("users").child(uid).updateChildren(
                    mapOf(
                        "faceSelfie" to value,
                        "faceVerified" to true,
                        "faceVerifiedAt" to System.currentTimeMillis(),
                        "faceSkipped" to null
                    )
                ).addOnSuccessListener { finishFlow() }
                    .addOnFailureListener { retry("Couldn't save. Check your connection and try again.") }
            }
        }
    }

    private fun retry(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_LONG).show()
        busy.visibility = View.GONE
        capturing = false
        consent.visibility = View.VISIBLE
    }

    private fun finishFlow() {
        val next = intent.getStringExtra(EXTRA_NEXT)
        if (next != null) {
            try {
                startActivity(Intent(this, Class.forName(next)).apply {
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
                })
            } catch (_: ClassNotFoundException) {}
        }
        finish()
    }

    override fun onDestroy() {
        super.onDestroy()
        cameraExecutor.shutdown()
        detector.close()
    }
}
