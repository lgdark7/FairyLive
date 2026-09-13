package com.fairylive.app.core.compositor.model

/**
 * Immutable transform applied to an overlay when drawing to the broadcast surface.
 * Normalized coordinates resolve against the actual encoder output dimensions,
 * so a given element keeps its relative position when the profile changes
 * (e.g. 480x1056 -> 720x1920).
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

    fun resolvedLeft(streamWidth: Int): Float = normalizedX * streamWidth
    fun resolvedTop(streamHeight: Int): Float = normalizedY * streamHeight

    /** Resolved width in pixels honoring auto-aspect (normalizedHeight==0 → keep aspect). */
    fun resolvedWidth(streamWidth: Int): Float = normalizedWidth * streamWidth * scale

    fun resolvedHeight(streamWidth: Int, streamHeight: Int, sourceAspect: Float): Float {
        val baseH = if (normalizedHeight > 0f) normalizedHeight * streamHeight else normalizedWidth * streamWidth / sourceAspect
        return baseH * scale
    }
}