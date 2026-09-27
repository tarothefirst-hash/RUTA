package com.ruta.app.util

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import com.google.android.gms.maps.model.BitmapDescriptor
import com.google.android.gms.maps.model.BitmapDescriptorFactory

object MarkerHelper {

    fun createNumberedMarker(
        context: Context,
        number: String,
        isPickup: Boolean
    ): BitmapDescriptor {
        val size = 110
        val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)

        val pinPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = if (isPickup) Color.parseColor("#4CAF50") else Color.parseColor("#F44336") // Green or Red
            style = Paint.Style.FILL
        }

        val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            style = Paint.Style.STROKE
            strokeWidth = 6f
        }

        val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            textSize = 42f
            isFakeBoldText = true
            textAlign = Paint.Align.CENTER
        }

        val radius = size / 2f - 6f
        canvas.drawCircle(size / 2f, size / 2f, radius, pinPaint)
        canvas.drawCircle(size / 2f, size / 2f, radius, borderPaint)

        val yPos = (canvas.height / 2f) - ((textPaint.descent() + textPaint.ascent()) / 2f)
        canvas.drawText(number, size / 2f, yPos, textPaint)

        return BitmapDescriptorFactory.fromBitmap(bitmap)
    }
}