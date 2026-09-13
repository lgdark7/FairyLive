package com.fairylive.app.core.compositor.rasterizer

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import com.fairylive.app.core.compositor.model.WidgetOverlayConfig

/**
 * Renders on-screen broadcast widgets and live statistics pills.
 */
object WidgetOverlayRasterizer {

    fun rasterize(config: WidgetOverlayConfig): Bitmap {
        val width = 360
        val height = 70

        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)

        // Pill background
        val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.argb(200, 16, 22, 34)
            style = Paint.Style.FILL
        }
        val pillRect = RectF(0f, 0f, width.toFloat(), height.toFloat())
        canvas.drawRoundRect(pillRect, 35f, 35f, bgPaint)

        // Indicator dot
        val dotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = config.accentColorRgba
            style = Paint.Style.FILL
        }
        canvas.drawCircle(26f, 35f, 8f, dotPaint)

        // Label (e.g. "STREAM STATS")
        val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            textSize = 20f
            color = Color.rgb(160, 175, 200)
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        }
        canvas.drawText(config.label, 44f, 42f, labelPaint)

        // Value (e.g. "10.4 FPS | 330k")
        val valPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            textSize = 22f
            color = Color.WHITE
            typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
            textAlign = Paint.Align.RIGHT
        }
        canvas.drawText(config.value, (width - 24).toFloat(), 43f, valPaint)

        return bitmap
    }
}
