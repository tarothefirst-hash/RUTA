package com.ruta.app.util

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Base64
import android.util.LruCache
import android.widget.ImageView
import com.google.firebase.storage.FirebaseStorage

/**
 * Upload / show photos kept in Firebase Storage, with the old inline-Base64 format as a
 * fallback. Sensitive files (IDs, documents, receipts) are fetched through the Storage
 * SDK, so the Storage security rules (owner + admin only) are always enforced; there is
 * never a public download link to leak.
 */
object StoragePhotos {

    private const val MAX_DOWNLOAD_BYTES = 6L * 1024 * 1024
    private val cache = object : LruCache<String, Bitmap>(24) {}

    /** Uploads [jpegBytes] to [path]; on success returns the value to store in the database. */
    fun upload(path: String, jpegBytes: ByteArray, onDone: (value: String?, error: String?) -> Unit) {
        val ref = FirebaseStorage.getInstance().reference.child(path)
        val meta = com.google.firebase.storage.StorageMetadata.Builder().setContentType("image/jpeg").build()
        ref.putBytes(jpegBytes, meta)
            .addOnSuccessListener {
                cache.remove(path)
                onDone(PhotoRef.toValue(path), null)
            }
            .addOnFailureListener { onDone(null, it.message ?: "Upload failed") }
    }

    fun uploadBase64(path: String, base64: String, onDone: (value: String?, error: String?) -> Unit) {
        val bytes = try { Base64.decode(base64, Base64.DEFAULT) } catch (e: Exception) {
            onDone(null, "Could not read the image"); return
        }
        upload(path, bytes, onDone)
    }

    fun delete(path: String) {
        FirebaseStorage.getInstance().reference.child(path).delete()
        cache.remove(path)
    }

    /** Loads a stored value (Storage ref or legacy Base64) into a bitmap; null if there is none. */
    fun loadBitmap(value: String?, onResult: (Bitmap?) -> Unit) {
        when (val ref = PhotoRef.parse(value)) {
            PhotoRef.None -> onResult(null)
            is PhotoRef.Inline -> onResult(ImageUtils.base64ToBitmap(ref.base64))
            is PhotoRef.Storage -> {
                cache.get(ref.path)?.let { onResult(it); return }
                FirebaseStorage.getInstance().reference.child(ref.path).getBytes(MAX_DOWNLOAD_BYTES)
                    .addOnSuccessListener { bytes ->
                        val bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                        if (bmp != null) cache.put(ref.path, bmp)
                        onResult(bmp)
                    }
                    .addOnFailureListener { onResult(null) }
            }
        }
    }

    /**
     * Shows [value] in [view]. [placeholderRes] stays when there is no photo or it fails.
     * A tag on the view stops a slow load from overwriting a newer one in recycled rows.
     */
    fun show(view: ImageView, value: String?, placeholderRes: Int) {
        view.tag = value
        val ref = PhotoRef.parse(value)
        if (ref == PhotoRef.None) { view.setImageResource(placeholderRes); return }
        if (ref is PhotoRef.Storage && cache.get(ref.path) == null) view.setImageResource(placeholderRes)
        loadBitmap(value) { bmp ->
            if (view.tag != value) return@loadBitmap
            if (bmp != null) view.setImageBitmap(bmp) else view.setImageResource(placeholderRes)
        }
    }

    /** Letters shown when someone has no photo: "Maria Santos" -> "MS", "Juan" -> "J". */
    fun initials(name: String?): String {
        val parts = name.orEmpty().trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
        return when {
            parts.isEmpty() -> "?"
            parts.size == 1 -> parts[0].take(1).uppercase()
            else -> (parts.first().take(1) + parts.last().take(1)).uppercase()
        }
    }

    private val AVATAR_COLORS = intArrayOf(
        0xFF2563EB.toInt(), 0xFF059669.toInt(), 0xFFD97706.toInt(),
        0xFF7C3AED.toInt(), 0xFFDB2777.toInt(), 0xFF0891B2.toInt()
    )

    /** Same name always gets the same colour. */
    fun colorFor(name: String?): Int =
        AVATAR_COLORS[Math.floorMod(name.orEmpty().trim().lowercase().hashCode(), AVATAR_COLORS.size)]

    private fun initialsBitmap(name: String?, sizePx: Int): Bitmap {
        val bmp = Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.ARGB_8888)
        val c = android.graphics.Canvas(bmp)
        val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG)
        paint.color = colorFor(name)
        c.drawRect(0f, 0f, sizePx.toFloat(), sizePx.toFloat(), paint)
        paint.color = android.graphics.Color.WHITE
        paint.textAlign = android.graphics.Paint.Align.CENTER
        paint.typeface = android.graphics.Typeface.DEFAULT_BOLD
        paint.textSize = sizePx * 0.42f
        val y = sizePx / 2f - (paint.descent() + paint.ascent()) / 2f
        c.drawText(initials(name), sizePx / 2f, y, paint)
        return bmp
    }

    /**
     * Round avatar: the photo when there is one, otherwise coloured initials.
     * [viewSizePx] is only used to draw the initials sharply.
     */
    fun showAvatar(view: ImageView, value: String?, name: String?, viewSizePx: Int = 160) {
        view.scaleType = ImageView.ScaleType.CENTER_CROP
        view.clipToOutline = true
        view.outlineProvider = object : android.view.ViewOutlineProvider() {
            override fun getOutline(v: android.view.View, outline: android.graphics.Outline) {
                outline.setOval(0, 0, v.width, v.height)
            }
        }
        view.tag = value
        view.setImageBitmap(initialsBitmap(name, viewSizePx))
        if (PhotoRef.parse(value) == PhotoRef.None) return
        loadBitmap(value) { bmp ->
            if (view.tag == value && bmp != null) view.setImageBitmap(bmp)
        }
    }
}
