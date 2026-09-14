package com.fairylive.app.core.compositor.rasterizer

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import com.fairylive.app.core.compositor.model.QrOverlayConfig

/**
 * Rasterizes QR overlay bitmaps.
 *
 * The matrix is produced by [QrMatrixEncoder] — a dependency-free, standards-compliant
 * ISO/IEC 18004 Version 1-M byte-mode encoder (Reed-Solomon EC, mask 0, format info).
 * This class only renders the matrix to ARGB_8888 via android.graphics.
 */
object QrOverlayRasterizer {

    fun rasterize(config: QrOverlayConfig, sizePx: Int = 256): Bitmap {
        val safePayload = if (config.payloadUrl.toByteArray(Charsets.UTF_8).size > 16) {
            config.payloadUrl.take(16)
        } else {
            config.payloadUrl
        }
        val matrix = QrMatrixEncoder.encode(safePayload)
        val moduleCount = matrix.size

        val bitmap = Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)

        // Background
        val bgPaint = Paint().apply {
            color = config.backgroundColorRgba
            style = Paint.Style.FILL
        }
        canvas.drawRect(0f, 0f, sizePx.toFloat(), sizePx.toFloat(), bgPaint)

        // Dark modules
        val fgPaint = Paint().apply {
            color = config.foregroundColorRgba
            style = Paint.Style.FILL
        }

        val padding = config.borderPaddingModules
        val totalModules = moduleCount + padding * 2
        val moduleSize = sizePx.toFloat() / totalModules

        for (row in 0 until moduleCount) {
            for (col in 0 until moduleCount) {
                if (matrix[row][col]) {
                    val left = (col + padding) * moduleSize
                    val top = (row + padding) * moduleSize
                    canvas.drawRect(left, top, left + moduleSize, top + moduleSize, fgPaint)
                }
            }
        }

        return bitmap
    }
}
