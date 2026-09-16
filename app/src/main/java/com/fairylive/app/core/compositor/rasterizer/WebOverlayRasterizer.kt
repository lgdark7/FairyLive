package com.fairylive.app.core.compositor.rasterizer

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import com.fairylive.app.core.compositor.model.WebOverlayConfig

/**
 * Offscreen fallback and placeholder rasterizer for Web/Browser overlays.
 * Generates an ARGB_8888 bitmap formatted for GLES texture uploading when web content
 * is loading, offline, or rendering placeholder tiles.
 */
object WebOverlayRasterizer {

    private const val BITMAP_WIDTH = 640
    private const val BITMAP_HEIGHT = 360

    fun rasterizePlaceholder(config: WebOverlayConfig): Bitmap {
        val bitmap = Bitmap.createBitmap(BITMAP_WIDTH, BITMAP_HEIGHT, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)

        if (!config.transparentBackground) {
            val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = 0xEE1E2430.toInt()
                style = Paint.Style.FILL
            }
            val rect = RectF(8f, 8f, BITMAP_WIDTH - 8f, BITMAP_HEIGHT - 8f)
            canvas.drawRoundRect(rect, 16f, 16f, bgPaint)
        }

        val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0x4400E5FF.toInt()
            style = Paint.Style.STROKE
            strokeWidth = 2f
        }
        canvas.drawRoundRect(RectF(8f, 8f, BITMAP_WIDTH - 8f, BITMAP_HEIGHT - 8f), 16f, 16f, borderPaint)

        val headerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0xFF00E5FF.toInt()
            textSize = 24f
            typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
        }
        canvas.drawText("🌐 WEB OVERLAY", 24f, 48f, headerPaint)

        val urlPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0xFFCCCCCC.toInt()
            textSize = 20f
            typeface = Typeface.MONOSPACE
        }
        val displayUrl = if (config.url.length > 40) config.url.take(37) + "..." else config.url
        canvas.drawText(displayUrl, 24f, 82f, urlPaint)

        val fallbackPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0xFF888888.toInt()
            textSize = 18f
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.ITALIC)
        }
        canvas.drawText(config.fallbackText, 24f, 120f, fallbackPaint)

        return bitmap
    }
}
