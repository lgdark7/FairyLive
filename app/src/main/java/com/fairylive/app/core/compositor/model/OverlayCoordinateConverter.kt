package com.fairylive.app.core.compositor.model

import com.fairylive.app.core.capture.model.CaptureGeometryState

/**
 * Coordinate space converter between:
 * - Source / Device screen space (e.g. Free Fire MAX at 2400x1080)
 * - Broadcast output canvas space (e.g. 1920x1080 16:9)
 *
 * Ensures that overlays placed relative to device or source coordinates are
 * mathematically mapped to the correct position on the cropped broadcast frame,
 * and vice versa.
 */
object OverlayCoordinateConverter {

    /**
     * Converts a source coordinate (e.g. x = 1600 out of 2400) to normalized
     * output broadcast coordinates (0.0..1.0 relative to 1920x1080).
     */
    fun sourceToNormalizedOutput(
        sourceX: Float,
        sourceY: Float,
        geometry: CaptureGeometryState
    ): Pair<Float, Float> {
        if (geometry.sourceWidth <= 0 || geometry.sourceHeight <= 0) {
            return Pair(0f, 0f)
        }

        // Normalized coordinate in the raw source buffer [0..1]
        val normSourceX = sourceX / geometry.sourceWidth.toFloat()
        val normSourceY = sourceY / geometry.sourceHeight.toFloat()

        if (geometry.isLandscape && geometry.cropLeft + geometry.cropRight > 0f) {
            // Symmetrical horizontal crop applied to fill 16:9
            val visibleSpanX = 1.0f - geometry.cropLeft - geometry.cropRight
            val outNormX = ((normSourceX - geometry.cropLeft) / visibleSpanX).coerceIn(0f, 1f)
            val outNormY = normSourceY.coerceIn(0f, 1f)
            return Pair(outNormX, outNormY)
        } else if (geometry.isLandscape && geometry.cropTop + geometry.cropBottom > 0f) {
            // Symmetrical vertical crop
            val visibleSpanY = 1.0f - geometry.cropTop - geometry.cropBottom
            val outNormX = normSourceX.coerceIn(0f, 1f)
            val outNormY = ((normSourceY - geometry.cropTop) / visibleSpanY).coerceIn(0f, 1f)
            return Pair(outNormX, outNormY)
        } else if (!geometry.isLandscape) {
            // Pillarbox: source is centered horizontally within output canvas
            val outNormX = (0.5f + (normSourceX - 0.5f) * geometry.scale).coerceIn(0f, 1f)
            val outNormY = normSourceY.coerceIn(0f, 1f)
            return Pair(outNormX, outNormY)
        }

        return Pair(normSourceX.coerceIn(0f, 1f), normSourceY.coerceIn(0f, 1f))
    }

    /**
     * Converts normalized output broadcast coordinates [0..1] to source pixel coordinates.
     */
    fun normalizedOutputToSource(
        normOutX: Float,
        normOutY: Float,
        geometry: CaptureGeometryState
    ): Pair<Float, Float> {
        if (geometry.sourceWidth <= 0 || geometry.sourceHeight <= 0) {
            return Pair(0f, 0f)
        }

        if (geometry.isLandscape && geometry.cropLeft + geometry.cropRight > 0f) {
            val visibleSpanX = 1.0f - geometry.cropLeft - geometry.cropRight
            val normSourceX = geometry.cropLeft + (normOutX * visibleSpanX)
            val sourceX = normSourceX * geometry.sourceWidth
            val sourceY = normOutY * geometry.sourceHeight
            return Pair(sourceX, sourceY)
        } else if (geometry.isLandscape && geometry.cropTop + geometry.cropBottom > 0f) {
            val visibleSpanY = 1.0f - geometry.cropTop - geometry.cropBottom
            val normSourceY = geometry.cropTop + (normOutY * visibleSpanY)
            val sourceX = normOutX * geometry.sourceWidth
            val sourceY = normSourceY * geometry.sourceHeight
            return Pair(sourceX, sourceY)
        }

        return Pair(normOutX * geometry.sourceWidth, normOutY * geometry.sourceHeight)
    }
}
