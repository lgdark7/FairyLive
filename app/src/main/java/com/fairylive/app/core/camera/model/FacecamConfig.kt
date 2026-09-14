package com.fairylive.app.core.camera.model

/**
 * Normalized layout and appearance configuration for the broadcast Facecam layer.
 *
 * Uses the M7/M8 normalized coordinate system (0.0..1.0 relative to stream resolution)
 * to maintain consistent positioning regardless of physical screen orientation or resolution.
 */
data class FacecamConfig(
    val enabled: Boolean = false,
    val facing: CameraFacing = CameraFacing.FRONT,
    val normalizedX: Float = 0.70f,
    val normalizedY: Float = 0.05f,
    val normalizedWidth: Float = 0.26f,
    val normalizedHeight: Float = 0.26f,
    val scale: Float = 1.0f,
    val rotationDegrees: Float = 0f,
    val opacity: Float = 1.0f,
    val mirrorHorizontal: Boolean = true,
    val cropToAspect: Boolean = true,
    val aspectRatio: Float = 1.0f, // 1.0 = 1:1 square, 4f/3f = 4:3, 16f/9f = 16:9
    val zIndex: Int = 5
) {
    /** Clamped and normalized copy preventing out-of-bounds rendering. */
    fun sanitized(): FacecamConfig = copy(
        normalizedX = normalizedX.coerceIn(0f, 1f),
        normalizedY = normalizedY.coerceIn(0f, 1f),
        normalizedWidth = normalizedWidth.coerceIn(0.05f, 1f),
        normalizedHeight = normalizedHeight.coerceIn(0.05f, 1f),
        opacity = opacity.coerceIn(0f, 1f),
        scale = scale.coerceIn(0.1f, 5.0f),
        aspectRatio = if (aspectRatio > 0f) aspectRatio else 1.0f
    )

    /** Computes physical pixel width for a given stream resolution. */
    fun resolvedWidth(streamWidth: Int): Float = (streamWidth * normalizedWidth * scale).coerceAtLeast(1f)

    /** Computes physical pixel height preserving aspect ratio or normalized height. */
    fun resolvedHeight(streamWidth: Int, streamHeight: Int): Float {
        return if (cropToAspect && aspectRatio > 0f) {
            val w = resolvedWidth(streamWidth)
            (w / aspectRatio).coerceAtLeast(1f)
        } else {
            (streamHeight * normalizedHeight * scale).coerceAtLeast(1f)
        }
    }

    /** Resolved physical left coordinate. */
    fun resolvedLeft(streamWidth: Int): Float = streamWidth * normalizedX

    /** Resolved physical top coordinate. */
    fun resolvedTop(streamHeight: Int): Float = streamHeight * normalizedY
}
