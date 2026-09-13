package com.fairylive.app.core.compositor.rasterizer

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import com.fairylive.app.core.compositor.model.GoalOverlayConfig

/**
 * Renders broadcast goal progress bars (Followers, Subs, Donations).
 */
object GoalOverlayRasterizer {

    fun rasterize(config: GoalOverlayConfig): Bitmap {
        val width = 480
        val height = 110

        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)

        // Background container
        val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = config.backgroundColorRgba
            style = Paint.Style.FILL
        }
        val cardRect = RectF(0f, 0f, width.toFloat(), height.toFloat())
        canvas.drawRoundRect(cardRect, 16f, 16f, bgPaint)

        // Title text
        val titlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            textSize = 28f
            color = Color.WHITE
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        }
        canvas.drawText(config.title, 24f, 40f, titlePaint)

        // Progress text: "350 / 500"
        val progressText = "${config.unitPrefix}${config.currentVal} / ${config.unitPrefix}${config.targetVal}"
        val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            textSize = 24f
            color = config.barColorRgba
            typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
            textAlign = Paint.Align.RIGHT
        }
        canvas.drawText(progressText, (width - 24).toFloat(), 40f, textPaint)

        // Progress track (dark groove)
        val trackPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = config.trackColorRgba
            style = Paint.Style.FILL
        }
        val trackRect = RectF(24f, 58f, (width - 24).toFloat(), 86f)
        canvas.drawRoundRect(trackRect, 14f, 14f, trackPaint)

        // Progress bar fill
        val fillWidth = (trackRect.width() * config.progressFraction)
        if (fillWidth > 0f) {
            val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = config.barColorRgba
                style = Paint.Style.FILL
            }
            val fillRect = RectF(24f, 58f, 24f + fillWidth, 86f)
            canvas.drawRoundRect(fillRect, 14f, 14f, fillPaint)
        }

        return bitmap
    }
}
