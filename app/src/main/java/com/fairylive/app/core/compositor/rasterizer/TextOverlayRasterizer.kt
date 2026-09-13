package com.fairylive.app.core.compositor.rasterizer

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import com.fairylive.app.core.compositor.limits.CompositorLimits
import com.fairylive.app.core.compositor.model.TextOverlayConfig

/**
 * Renders styled text into a GPU-ready Bitmap.
 *
 * Designed to be called once upon text modification and cached;
 * never called in the per-frame loop for unchanged text.
 */
object TextOverlayRasterizer {

    fun rasterize(config: TextOverlayConfig): Bitmap {
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            textSize = config.fontSizeSp * 2f // Scale up for crisp broadcast resolution
            color = config.textColorRgba
            typeface = Typeface.create(Typeface.DEFAULT, config.fontStyle)
        }

        val textWidth = paint.measureText(config.text)
        val fontMetrics = paint.fontMetrics
        val textHeight = fontMetrics.bottom - fontMetrics.top

        val pad = config.paddingPx * 2
        val width = (textWidth + pad * 2).toInt().coerceIn(64, CompositorLimits.MAX_TEXTURE_DIMENSION)
        val height = (textHeight + pad * 2).toInt().coerceIn(32, CompositorLimits.MAX_TEXTURE_DIMENSION)

        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)

        // Background card
        val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = config.backgroundColorRgba
            style = Paint.Style.FILL
        }
        val cardRect = RectF(0f, 0f, width.toFloat(), height.toFloat())
        canvas.drawRoundRect(cardRect, config.cornerRadiusPx * 2f, config.cornerRadiusPx * 2f, bgPaint)

        // Text baseline
        val baseline = pad - fontMetrics.top
        canvas.drawText(config.text, pad.toFloat(), baseline, paint)

        return bitmap
    }
}
