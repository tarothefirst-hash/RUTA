package com.ruta.app.util

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.ExifInterface
import android.widget.ImageView
import android.net.Uri
import android.util.Base64
import java.io.ByteArrayOutputStream


object ImageUtils {

    private const val MAX_DIMENSION = 800
    private const val JPEG_QUALITY = 55

    fun uriToCompressedBase64(context: Context, uri: Uri): String? {
        return try {
            val inputStream = context.contentResolver.openInputStream(uri) ?: return null
            val original = BitmapFactory.decodeStream(inputStream)
            inputStream.close()
            if (original == null) return null

            val scaled = scaleDown(original)
            val outputStream = ByteArrayOutputStream()
            scaled.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, outputStream)
            Base64.encodeToString(outputStream.toByteArray(), Base64.NO_WRAP)
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }

    private fun scaleDown(bitmap: Bitmap): Bitmap {
        val width = bitmap.width
        val height = bitmap.height
        if (width <= MAX_DIMENSION && height <= MAX_DIMENSION) return bitmap

        val ratio = minOf(MAX_DIMENSION.toFloat() / width, MAX_DIMENSION.toFloat() / height)
        return Bitmap.createScaledBitmap(bitmap, (width * ratio).toInt(), (height * ratio).toInt(), true)
    }

    // ------------------------------------------------------------------
    // Profile pictures
    //
    // There is no Firebase Storage in this project, so a profile picture is kept the
    // same way the ID and receipt photos are: a small JPEG, Base64 encoded, stored on
    // the user's own record (users/<uid>/profilePhoto). It is square-cropped and
    // shrunk to AVATAR_SIZE first so it stays around 10-25 KB.
    // ------------------------------------------------------------------

    private const val AVATAR_SIZE = 256
    private const val AVATAR_QUALITY = 80

    /** A square, upright, 256px JPEG of the picked image as Base64, or null if unreadable. */
    fun uriToAvatarBase64(context: Context, uri: Uri, size: Int = AVATAR_SIZE): String? {
        return try {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null

            // Decode no larger than needed, so a 12 MP photo never fills memory.
            var sample = 1
            while (bounds.outWidth / (sample * 2) >= size && bounds.outHeight / (sample * 2) >= size) {
                sample *= 2
            }
            val decoded = context.contentResolver.openInputStream(uri)?.use {
                BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = sample })
            } ?: return null

            val rotation = context.contentResolver.openInputStream(uri)?.use { exifRotationDegrees(it) } ?: 0
            val upright = if (rotation != 0) {
                Bitmap.createBitmap(
                    decoded, 0, 0, decoded.width, decoded.height,
                    Matrix().apply { postRotate(rotation.toFloat()) }, true
                )
            } else decoded

            val side = minOf(upright.width, upright.height)
            val square = Bitmap.createBitmap(
                upright, (upright.width - side) / 2, (upright.height - side) / 2, side, side
            )
            val avatar = Bitmap.createScaledBitmap(square, size, size, true)

            val out = ByteArrayOutputStream()
            avatar.compress(Bitmap.CompressFormat.JPEG, AVATAR_QUALITY, out)
            Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP)
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }

    private fun exifRotationDegrees(stream: java.io.InputStream): Int =
        try {
            when (ExifInterface(stream).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)) {
                ExifInterface.ORIENTATION_ROTATE_90 -> 90
                ExifInterface.ORIENTATION_ROTATE_180 -> 180
                ExifInterface.ORIENTATION_ROTATE_270 -> 270
                else -> 0
            }
        } catch (e: Exception) {
            0
        }

    fun base64ToBitmap(base64: String?): Bitmap? {
        if (base64.isNullOrBlank()) return null
        return try {
            val bytes = Base64.decode(base64, Base64.DEFAULT)
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
        } catch (e: Exception) {
            null
        }
    }

    /** Shows [base64] in [view]; returns false (and leaves the view alone) if there is nothing to show. */
    fun showAvatar(view: ImageView, base64: String?): Boolean {
        val bitmap = base64ToBitmap(base64) ?: return false
        view.setImageBitmap(bitmap)
        return true
    }
}
