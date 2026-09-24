package com.ruta.app.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.Path
import android.util.AttributeSet
import android.view.View

class RoadGraphicView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    // 1. Road surface paint (Filled with subtle transparent white)
    private val roadPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#1BFFFFFF") // ~10% transparent white
        style = Paint.Style.FILL
    }

    // 2. Dashed center line paint
    private val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#40FFFFFF") // ~25% transparent white
        style = Paint.Style.STROKE
        strokeWidth = 6f
        // Creates a dashed pattern: 20px dash, 20px gap
        pathEffect = DashPathEffect(floatArrayOf(20f, 20f), 0f)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        val w = width.toFloat()
        val h = height.toFloat()
        if (w == 0f || h == 0f) return

        // Set perspective bounds
        val startY = h * 0.22f            // Start road below the logo
        val topWidth = w * 0.12f           // Narrow top vanishing point
        val bottomWidth = w * 0.95f        // Wide base at screen bottom
        val centerX = w / 2f

        // Draw Filled Road Surface
        val roadPath = Path().apply {
            moveTo(centerX - (topWidth / 2), startY)
            lineTo(centerX - (bottomWidth / 2), h)
            lineTo(centerX + (bottomWidth / 2), h)
            lineTo(centerX + (topWidth / 2), startY)
            close()
        }
        canvas.drawPath(roadPath, roadPaint)

        // Draw Dashed Center Line
        val linePath = Path().apply {
            moveTo(centerX, startY)
            lineTo(centerX, h)
        }
        canvas.drawPath(linePath, linePaint)
    }
}