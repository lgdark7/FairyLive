package com.fairylive.app

import com.fairylive.app.core.compositor.rasterizer.QrMatrixEncoder
import com.google.zxing.BinaryBitmap
import com.google.zxing.DecodeHintType
import com.google.zxing.RGBLuminanceSource
import com.google.zxing.common.GlobalHistogramBinarizer
import com.google.zxing.qrcode.QRCodeReader
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Cross-validates our pure-Kotlin QrMatrixEncoder against the independent,
 * industry-standard ZXing decoder. ZXing is test-scope only (never in the APK).
 */
class QrZxingCrossValidationTest {

    private fun renderToPixels(matrix: Array<BooleanArray>, scale: Int = 4): IntArray {
        val size = matrix.size
        val out = IntArray(size * scale * size * scale)
        for (r in 0 until size) {
            for (c in 0 until size) {
                val dark = matrix[r][c]
                val color = if (dark) 0xFF000000.toInt() else 0xFFFFFFFF.toInt()
                for (dr in 0 until scale) {
                    for (dc in 0 until scale) {
                        out[(r * scale + dr) * (size * scale) + (c * scale + dc)] = color
                    }
                }
            }
        }
        return out
    }

    private fun zxingDecode(matrix: Array<BooleanArray>): String? {
        val scale = 4
        val size = matrix.size
        val pixels = renderToPixels(matrix, scale)
        val source = RGBLuminanceSource(size * scale, size * scale, pixels)
        val bitmap = BinaryBitmap(GlobalHistogramBinarizer(source))
        val hints = mapOf(DecodeHintType.POSSIBLE_FORMATS to listOf(com.google.zxing.BarcodeFormat.QR_CODE))
        return try {
            QRCodeReader().decode(bitmap, hints).text
        } catch (e: Exception) {
            null
        }
    }

    @Test
    fun zxingCanDecodeOurMatrix() {
        val payloads = listOf(
            "https://fl.app",
            "fairy.live",
            "FAIRYLIVE",
            "A1",
            "HELLO-WORLD"
        )
        for (payload in payloads) {
            val matrix = QrMatrixEncoder.encode(payload)
            val decoded = zxingDecode(matrix)
            assertEquals("ZXing cross-validation for '$payload'", payload, decoded)
        }
    }

    @Test
    fun zxingConfirmsErrorCorrectionLevelM() {
        val matrix = QrMatrixEncoder.encode("https://fl.app")
        val scale = 4
        val size = matrix.size
        val pixels = renderToPixels(matrix, scale)
        val source = RGBLuminanceSource(size * scale, size * scale, pixels)
        val bitmap = BinaryBitmap(GlobalHistogramBinarizer(source))
        val result = QRCodeReader().decode(bitmap, mapOf(DecodeHintType.POSSIBLE_FORMATS to listOf(com.google.zxing.BarcodeFormat.QR_CODE)))
        val ec = result.resultMetadata?.get(com.google.zxing.ResultMetadataType.ERROR_CORRECTION_LEVEL)
        assertEquals("M", ec) // zxing returns EC level as String "M" confirming v1-M
    }
}