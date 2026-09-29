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
}