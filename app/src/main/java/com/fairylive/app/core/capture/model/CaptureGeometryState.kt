package com.fairylive.app.core.capture.model

/**
 * Scaling policy for adapting device screen aspect ratio to stream canvas.
 */
enum class ScaleMode {
    /**
     * Preserves 100% of the game screen and HUD with zero cropping.
     * Fits the entire source into the broadcast canvas, dynamically letterboxing or pillarboxing
     * to ensure no buttons, minimaps, or controls are sliced off.
     */
    FIT,

    /**
     * Symmetrically crops edges so the video fills 100% of the canvas without bars.
     */
    FILL
}

/**
 * Immutable atomic snapshot of screen capture geometry and transformation parameters.
 *
 * Tracks the complete spatial relationship between:
 * - The raw Android capture source (e.g. Free Fire MAX at 2400x1080, 20:9)
 * - The VirtualDisplay buffer dimensions
 * - The broadcast canvas / encoder output (e.g. 1920x1080 or 1280x720, 16:9)
 * - Dynamic scaling (scaleX, scaleY) and crop offsets
 * - SurfaceTexture transform matrix and visibility lifecycle
 */
data class CaptureGeometryState(
    val sourceWidth: Int = 0,
    val sourceHeight: Int = 0,
    val outputWidth: Int = 0,
    val outputHeight: Int = 0,
    val rotation: Int = 0,
    val scale: Float = 1.0f,
    val scaleX: Float = 1.0f,
    val scaleY: Float = 1.0f,
    val cropLeft: Float = 0.0f,
    val cropRight: Float = 0.0f,
    val cropTop: Float = 0.0f,
    val cropBottom: Float = 0.0f,
    val visibility: Boolean = true,
    val scaleMode: ScaleMode = ScaleMode.FILL,
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
         * Calculates deterministic scaling and crop parameters to map [sourceWidth] x [sourceHeight]
         * to [outputWidth] x [outputHeight].
         *
         * When [scaleMode] is [ScaleMode.FIT]:
         * - Zero pixels are cropped.
         * - If source is wider than output (e.g. 20:9 -> 16:9), it scales height to letterbox centered,
         *   guaranteeing 100% of game buttons (Start, Vault, HUD, chat) are visible.
         * - If source is narrower (portrait or 4:3), it pillarboxes width centered.
         *
         * When [scaleMode] is [ScaleMode.FILL] (legacy default):
         * - Excess edges are symmetrically cropped to fill 100% of the canvas without bars.
         */
        fun calculate(
            sourceWidth: Int,
            sourceHeight: Int,
            outputWidth: Int,
            outputHeight: Int,
            rotation: Int = 0,
            visibility: Boolean = true,
            scaleMode: ScaleMode = ScaleMode.FILL
        ): CaptureGeometryState {
            if (sourceWidth <= 0 || sourceHeight <= 0 || outputWidth <= 0 || outputHeight <= 0) {
                return CaptureGeometryState(
                    sourceWidth = sourceWidth,
                    sourceHeight = sourceHeight,
                    outputWidth = outputWidth,
                    outputHeight = outputHeight,
                    rotation = rotation,
                    visibility = visibility,
                    scaleMode = scaleMode
                )
            }

            val sourceAspect = sourceWidth.toFloat() / sourceHeight.toFloat()
            val outputAspect = outputWidth.toFloat() / outputHeight.toFloat()

            var cropL = 0.0f
            var cropR = 0.0f
            var cropT = 0.0f
            var cropB = 0.0f
            var sX = 1.0f
            var sY = 1.0f

            if (scaleMode == ScaleMode.FIT) {
                // FIT MODE: 100% of screen is displayed, zero cropping
                if (sourceAspect >= outputAspect) {
                    // Source is wider than canvas (e.g. 20:9 / 2400x1080 -> 16:9 / 1280x720)
                    // Full width fits edge-to-edge; scale down height with symmetrical letterbox bars
                    sX = 1.0f
                    sY = (outputAspect / sourceAspect).coerceIn(0.05f, 1.0f)
                } else {
                    // Source is narrower than canvas (e.g. 9:20 portrait or 4:3 tablet -> 16:9)
                    // Full height fits top-to-bottom; scale down width with symmetrical pillarbox bars
                    sX = (sourceAspect / outputAspect).coerceIn(0.05f, 1.0f)
                    sY = 1.0f
                }
            } else {
                // FILL MODE: Symmetrical crop to fill 100% of canvas
                if (sourceWidth >= sourceHeight) {
                    if (sourceAspect >= outputAspect) {
                        val visibleWidthFraction = outputAspect / sourceAspect
                        val cropX = ((1.0f - visibleWidthFraction) / 2.0f).coerceIn(0.0f, 0.49f)
                        cropL = cropX
                        cropR = cropX
                    } else {
                        val visibleHeightFraction = sourceAspect / outputAspect
                        val cropY = ((1.0f - visibleHeightFraction) / 2.0f).coerceIn(0.0f, 0.49f)
                        cropT = cropY
                        cropB = cropY
                    }
                } else {
                    val scalePortrait = (sourceAspect / outputAspect).coerceIn(0.05f, 1.0f)
                    sX = scalePortrait
                }
            }

            return CaptureGeometryState(
                sourceWidth = sourceWidth,
                sourceHeight = sourceHeight,
                outputWidth = outputWidth,
                outputHeight = outputHeight,
                rotation = rotation,
                scale = sX,
                scaleX = sX,
                scaleY = sY,
                cropLeft = cropL,
                cropRight = cropR,
                cropTop = cropT,
                cropBottom = cropB,
                visibility = visibility,
                scaleMode = scaleMode
            )
        }
    }
}
