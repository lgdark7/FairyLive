package com.fairylive.app.core.compositor.model

/**
 * Immutable transform applied to an overlay when drawing to the broadcast surface.
 * Normalized coordinates resolve against the actual encoder output dimensions,
 * so a given element keeps its relative position when the profile changes
 * (e.g. 480x1056 -> 720x1920).
 *
 * Geometric Invariant: Scale and rotation pivot around the element center,
 * guaranteeing 100% mathematical parity with Creator Studio preview views.
 */
data class OverlayTransform(
    val normalizedX: Float = 0f,
    val normalizedY: Float = 0f,
    val normalizedWidth: Float = 0.2f,
    val normalizedHeight: Float = 0f, // 0 = auto aspect
    val scale: Float = 1f,
    val rotationDegrees: Float = 0f,
    val opacity: Float = 1f,
    val zIndex: Int = 0
) {
    companion object {
        /** Build a transform from an [OverlayElement], clamping opacity to [0,1]. */
        fun fromElement(e: OverlayElement): OverlayTransform = OverlayTransform(
            normalizedX = e.normalizedX,
            normalizedY = e.normalizedY,
            normalizedWidth = e.normalizedWidth,
            normalizedHeight = e.normalizedHeight,
            scale = e.scale,
            rotationDegrees = e.rotationDegrees,
            opacity = e.opacity.coerceIn(0f, 1f),
            zIndex = e.zIndex
        )
    }

    /** Center X in stream pixels */
    fun centerX(streamWidth: Int): Float = (normalizedX + normalizedWidth / 2f) * streamWidth

    /** Base height in stream pixels prior to scaling */
    fun baseHeight(streamWidth: Int, streamHeight: Int, sourceAspect: Float = 1f): Float =
        if (normalizedHeight > 0f) {
            normalizedHeight * streamHeight
        } else {
            val aspect = if (sourceAspect > 0f) sourceAspect else 1f
            (normalizedWidth * streamWidth) / aspect
        }

    /** Center Y in stream pixels */
    fun centerY(streamWidth: Int, streamHeight: Int, sourceAspect: Float = 1f): Float =
        (normalizedY * streamHeight) + baseHeight(streamWidth, streamHeight, sourceAspect) / 2f

    /** Resolved width in pixels honoring auto-aspect and center-pivot scale. */
    fun resolvedWidth(streamWidth: Int): Float = (normalizedWidth * streamWidth) * scale

    /** Resolved height in pixels honoring auto-aspect and center-pivot scale. */
    fun resolvedHeight(streamWidth: Int, streamHeight: Int, sourceAspect: Float = 1f): Float =
        baseHeight(streamWidth, streamHeight, sourceAspect) * scale

    /** Resolved Left X anchored to center-scale */
    fun resolvedLeft(streamWidth: Int): Float = centerX(streamWidth) - resolvedWidth(streamWidth) / 2f

    /** Resolved Top Y anchored to center-scale */
    fun resolvedTop(streamWidth: Int, streamHeight: Int, sourceAspect: Float = 1f): Float =
        centerY(streamWidth, streamHeight, sourceAspect) - resolvedHeight(streamWidth, streamHeight, sourceAspect) / 2f

    /** Backward-compatible overload assuming default aspect */
    fun resolvedTop(streamHeight: Int): Float =
        (normalizedY * streamHeight) + (if (normalizedHeight > 0f) normalizedHeight * streamHeight else 0.4f * streamHeight) * (1f - scale) / 2f
}
