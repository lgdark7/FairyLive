package com.fairylive.app.core.capture.model

/**
 * Immutable atomic snapshot of screen capture geometry and transformation parameters.
 *
 * Tracks the complete spatial relationship between:
 * - The raw Android capture source (e.g. Free Fire MAX at 2400x1080, 20:9)
 * - The VirtualDisplay buffer dimensions
 * - The broadcast canvas / encoder output (e.g. 1920x1080, 16:9)
 * - Symmetrical crop offsets to eliminate letterboxing/pillarboxing for gaming
 * - SurfaceTexture transform matrix and visibility lifecycle
 */
data class CaptureGeometryState(
    val sourceWidth: Int = 0,
    val sourceHeight: Int = 0,
    val outputWidth: Int = 0,
    val outputHeight: Int = 0,
    val rotation: Int = 0,
    val scale: Float = 1.0f,
    val cropLeft: Float = 0.0f,
    val cropRight: Float = 0.0f,
    val cropTop: Float = 0.0f,
    val cropBottom: Float = 0.0f,
    val visibility: Boolean = true,
    val timestampMs: Long = System.currentTimeMillis()
) {
    val sourceAspectRatio: Float
        get() = if (sourceHeight > 0) sourceWidth.toFloat() / sourceHeight.toFloat() else 0f

    val outputAspectRatio: Float
        get() = if (outputHeight > 0) outputWidth.toFloat() / outputHeight.toFloat() else 0f

    val isLandscape: Boolean
        get() = sourceWidth >= sourceHeight

    /** Visible source width after horizontal cropping in source pixels */
    val visibleSourceWidth: Float
        get() = sourceWidth * (1.0f - cropLeft - cropRight)

    /** Visible source height after vertical cropping in source pixels */
    val visibleSourceHeight: Float
        get() = sourceHeight * (1.0f - cropTop - cropBottom)

    companion object {
        /**
         * Calculates deterministic symmetrical crop parameters to map [sourceWidth] x [sourceHeight]
         * to [outputWidth] x [outputHeight].
         *
         * Production Gaming Policy:
         * - For landscape sources wider than 16:9 (e.g. 2400x1080 20:9 -> 1920x1080 16:9):
         *   Symmetrically crops excess horizontal pixels (e.g. 240px from left, 240px from right)
         *   so the game fills 100% of the 16:9 canvas with ZERO stretching, ZERO squashing,
         *   and ZERO black letterbox bars.
         * - For landscape sources narrower than 16:9 (e.g. 16:10 or 4:3):
         *   Symmetrically crops excess vertical pixels to fill 16:9 canvas.
         * - For portrait sources (sourceWidth < sourceHeight):
         *   Pillarboxes to preserve full portrait content without cutting off controls.
         */
        fun calculate(
            sourceWidth: Int,
            sourceHeight: Int,
            outputWidth: Int,
            outputHeight: Int,
            rotation: Int = 0,
            visibility: Boolean = true
        ): CaptureGeometryState {
            if (sourceWidth <= 0 || sourceHeight <= 0 || outputWidth <= 0 || outputHeight <= 0) {
                return CaptureGeometryState(
                    sourceWidth = sourceWidth,
                    sourceHeight = sourceHeight,
                    outputWidth = outputWidth,
                    outputHeight = outputHeight,
                    rotation = rotation,
                    visibility = visibility
                )
            }

            val sourceAspect = sourceWidth.toFloat() / sourceHeight.toFloat()
            val outputAspect = outputWidth.toFloat() / outputHeight.toFloat()

            var cropL = 0.0f
            var cropR = 0.0f
            var cropT = 0.0f
            var cropB = 0.0f
            var scale = 1.0f

            if (sourceWidth >= sourceHeight) {
                // Landscape gaming mode
                if (sourceAspect >= outputAspect) {
                    // Source is wider than output (e.g. 20:9 -> 16:9)
                    // Visible fraction of width = outputAspect / sourceAspect
                    // e.g. (16/9) / (20/9) = 16/20 = 0.80 -> crop 10% on each side
                    val visibleWidthFraction = outputAspect / sourceAspect
                    val cropX = ((1.0f - visibleWidthFraction) / 2.0f).coerceIn(0.0f, 0.49f)
                    cropL = cropX
                    cropR = cropX
                    cropT = 0.0f
                    cropB = 0.0f
                    scale = 1.0f
                } else {
                    // Source is narrower than output (e.g. 4:3 -> 16:9)
                    val visibleHeightFraction = sourceAspect / outputAspect
                    val cropY = ((1.0f - visibleHeightFraction) / 2.0f).coerceIn(0.0f, 0.49f)
                    cropL = 0.0f
                    cropR = 0.0f
                    cropT = cropY
                    cropB = cropY
                    scale = 1.0f
                }
            } else {
                // Portrait mode: pillarbox to preserve full screen height
                val scaleX = sourceAspect / outputAspect
                scale = scaleX
                cropL = 0.0f
                cropR = 0.0f
                cropT = 0.0f
                cropB = 0.0f
            }

            return CaptureGeometryState(
                sourceWidth = sourceWidth,
                sourceHeight = sourceHeight,
                outputWidth = outputWidth,
                outputHeight = outputHeight,
                rotation = rotation,
                scale = scale,
                cropLeft = cropL,
                cropRight = cropR,
                cropTop = cropT,
                cropBottom = cropB,
                visibility = visibility
            )
        }
    }
}
