package com.fairylive.app.core.compositor.rasterizer

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import com.fairylive.app.core.compositor.model.YouTubeSubscriberWidgetConfig

/**
 * High-performance offscreen rasterizer for YouTube Live Subscriber Counter broadcast widget.
 * Renders into an ARGB_8888 bitmap formatted cleanly for GLES compositing.
 */
object YouTubeSubscriberRasterizer {

    private const val BITMAP_WIDTH = 480
    private const val BITMAP_HEIGHT = 160

    fun rasterize(config: YouTubeSubscriberWidgetConfig): Bitmap {
        val bitmap = Bitmap.createBitmap(BITMAP_WIDTH, BITMAP_HEIGHT, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)

        val cardPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = config.backgroundColorRgba
            style = Paint.Style.FILL
        }
        val cardRect = RectF(10f, 10f, BITMAP_WIDTH - 10f, BITMAP_HEIGHT - 10f)
        canvas.drawRoundRect(cardRect, 24f, 24f, cardPaint)

        val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0x33FFFFFF
            style = Paint.Style.STROKE
            strokeWidth = 3f
        }
        canvas.drawRoundRect(cardRect, 24f, 24f, borderPaint)

        var contentLeft = 32f

        if (config.showIcon) {
            val iconPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = config.accentColorRgba
                style = Paint.Style.FILL
            }
            val iconRect = RectF(contentLeft, 44f, contentLeft + 72f, 116f)
            canvas.drawRoundRect(iconRect, 16f, 16f, iconPaint)

            val playPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.WHITE
                style = Paint.Style.FILL
            }
            val path = android.graphics.Path().apply {
                moveTo(contentLeft + 28f, 62f)
                lineTo(contentLeft + 48f, 80f)
                lineTo(contentLeft + 28f, 98f)
                close()
            }
            canvas.drawPath(path, playPaint)

            contentLeft += 92f
        }

        val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0xFFAAAAAA.toInt()
            textSize = 22f
            typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
        }
        val label = "${config.channelTitle.uppercase()} • ${config.labelText}"
        canvas.drawText(label, contentLeft, 56f, labelPaint)

        val countPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            textSize = 56f
            typeface = Typeface.create(Typeface.DEFAULT_BOLD, Typeface.BOLD)
        }
        canvas.drawText(config.subscriberCountFormatted, contentLeft, 118f, countPaint)

        return bitmap
    }
}
