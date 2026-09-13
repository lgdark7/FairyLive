package com.fairylive.app.core.compositor.rasterizer

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import com.fairylive.app.core.compositor.model.LowerThirdOverlayConfig

/**
 * Renders television / streaming lower-third banner graphics.
 */
object LowerThirdOverlayRasterizer {

    fun rasterize(config: LowerThirdOverlayConfig): Bitmap {
        val width = 640
        val height = 120

        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)

        // Semi-transparent background card
        val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = config.backgroundColorRgba
            style = Paint.Style.FILL
        }
        val cardRect = RectF(16f, 0f, width.toFloat(), height.toFloat())
        canvas.drawRoundRect(cardRect, 12f, 12f, bgPaint)

        // Left accent stripe
        val accentPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = config.accentColorRgba
            style = Paint.Style.FILL
        }
        val stripeRect = RectF(0f, 0f, 16f, height.toFloat())
        canvas.drawRoundRect(stripeRect, 6f, 6f, accentPaint)

        // Title text
        val titlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            textSize = 34f
            color = Color.WHITE
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        }
        canvas.drawText(config.title, 40f, 52f, titlePaint)

        // Subtitle text
        val subPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            textSize = 24f
            color = Color.rgb(180, 190, 210)
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.NORMAL)
        }
        canvas.drawText(config.subtitle, 40f, 94f, subPaint)

        return bitmap
    }
}
