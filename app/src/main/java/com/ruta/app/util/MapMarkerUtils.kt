package com.ruta.app.util

import android.content.Context
import android.graphics.*
import com.google.android.gms.maps.model.BitmapDescriptor
import com.google.android.gms.maps.model.BitmapDescriptorFactory

object MapMarkerUtils {

    fun createNumberedMarker(context: Context, label: String, colorInt: Int): BitmapDescriptor {
        val size = 110
        val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)

        val paint = Paint().apply {
            color = colorInt
            isAntiAlias = true
        }
        canvas.drawCircle(size / 2f, size / 2f, size / 2.2f, paint)

        val textPaint = Paint().apply {
            color = Color.WHITE
            textSize = 38f
            typeface = Typeface.DEFAULT_BOLD
            textAlign = Paint.Align.CENTER
            isAntiAlias = true
        }

        val yPos = (canvas.height / 2f) - ((textPaint.descent() + textPaint.ascent()) / 2f)
        canvas.drawText(label, size / 2f, yPos, textPaint)

        return BitmapDescriptorFactory.fromBitmap(bitmap)
    }

    /** A marker image plus where on it the map point sits (0 = top edge, 1 = bottom edge). */
    data class Pin(val icon: BitmapDescriptor, val anchorY: Float)

    /**
     * A rideshare stop pin that says whose stop it is.
     *
     *  - the shape tells pickup from drop-off: a circle for a pickup, a rounded
     *    square for a drop-off
     *  - the colour is the passenger's colour (lavender for "you", blue for the
     *    co-passenger)
     *  - [label] is the short text inside the shape ("P", "D", "P1", "D2"...)
     *  - [caption] is a small name tag under the shape ("You", "Maria"...)
     *
     * Use `.anchor(0.5f, pin.anchorY)` so the shape's centre sits on the stop.
     */
    fun createPassengerPin(
        context: Context,
        label: String,
        caption: String,
        colorInt: Int,
        isDropoff: Boolean
    ): Pin {
        val d = context.resources.displayMetrics.density
        val shape = 40f * d
        val pillH = 18f * d
        val gap = 3f * d
        val pad = 2f * d

        val captionPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = colorInt
            textSize = 11f * d
            typeface = Typeface.DEFAULT_BOLD
            textAlign = Paint.Align.CENTER
        }
        val pillW = maxOf(shape, captionPaint.measureText(caption) + 16f * d)

        val w = Math.ceil((pillW + pad * 2).toDouble()).toInt()
        val h = Math.ceil((pad * 2 + shape + gap + pillH).toDouble()).toInt()
        val bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)

        val cx = w / 2f
        val cy = pad + shape / 2f

        val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = colorInt; style = Paint.Style.FILL }
        val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE; style = Paint.Style.STROKE; strokeWidth = 3f * d
        }
        val half = shape / 2f - 1.5f * d
        if (isDropoff) {
            val rect = RectF(cx - half, cy - half, cx + half, cy + half)
            canvas.drawRoundRect(rect, 8f * d, 8f * d, fill)
            canvas.drawRoundRect(rect, 8f * d, 8f * d, ring)
        } else {
            canvas.drawCircle(cx, cy, half, fill)
            canvas.drawCircle(cx, cy, half, ring)
        }

        val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            textSize = (if (label.length > 1) 14f else 18f) * d
            typeface = Typeface.DEFAULT_BOLD
            textAlign = Paint.Align.CENTER
        }
        canvas.drawText(label, cx, cy - (labelPaint.descent() + labelPaint.ascent()) / 2f, labelPaint)

        val pillTop = pad + shape + gap
        val pill = RectF(cx - pillW / 2f, pillTop, cx + pillW / 2f, pillTop + pillH)
        canvas.drawRoundRect(pill, pillH / 2f, pillH / 2f, Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE; style = Paint.Style.FILL
        })
        canvas.drawRoundRect(pill, pillH / 2f, pillH / 2f, Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = colorInt; style = Paint.Style.STROKE; strokeWidth = 1.5f * d
        })
        canvas.drawText(caption, cx, pillTop + pillH / 2f - (captionPaint.descent() + captionPaint.ascent()) / 2f, captionPaint)

        return Pin(BitmapDescriptorFactory.fromBitmap(bitmap), cy / h)
    }
}
