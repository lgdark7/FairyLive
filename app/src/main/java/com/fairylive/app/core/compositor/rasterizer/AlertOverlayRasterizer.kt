package com.fairylive.app.core.compositor.rasterizer

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import com.fairylive.app.core.compositor.model.AlertOverlayConfig

/**
 * Renders animated stream alerts (Followers, Subscribers, Donations).
 */
object AlertOverlayRasterizer {

    fun rasterize(config: AlertOverlayConfig): Bitmap {
        val width = 560
        val height = 130

        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)

        // Glow / background card
        val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.argb(230, 15, 20, 32)
            style = Paint.Style.FILL
        }
        val cardRect = RectF(0f, 0f, width.toFloat(), height.toFloat())
        canvas.drawRoundRect(cardRect, 20f, 20f, bgPaint)

        // Accent border
        val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = config.badgeColorRgba
            style = Paint.Style.STROKE
            strokeWidth = 4f
        }
        canvas.drawRoundRect(cardRect, 20f, 20f, borderPaint)

        // Star badge / icon placeholder
        val iconPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = config.badgeColorRgba
            style = Paint.Style.FILL
        }
        canvas.drawCircle(60f, 65f, 32f, iconPaint)

        // Star text inside circle
        val starPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            textSize = 36f
            color = Color.BLACK
            textAlign = Paint.Align.CENTER
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        }
        canvas.drawText("★", 60f, 78f, starPaint)

        // Headline (e.g. "NEW FOLLOWER!")
        val headPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            textSize = 24f
            color = config.badgeColorRgba
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        }
        canvas.drawText(config.headline.uppercase(), 116f, 52f, headPaint)

        // Subtext (e.g. "Alex Live joined the stream!")
        val subPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            textSize = 28f
            color = Color.WHITE
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        }
        canvas.drawText(config.subtext, 116f, 96f, subPaint)

        return bitmap
    }
}
