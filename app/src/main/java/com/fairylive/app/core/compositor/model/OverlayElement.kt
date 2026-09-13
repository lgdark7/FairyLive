package com.fairylive.app.core.compositor.model

/**
 * Overlay element model — the declarative, serializable description of a single
 * broadcast overlay. Pure data; no rendering logic. Coordinates are normalized
 * (0.0..1.0 relative to the *broadcast/stream* resolution, NOT the device UI
 * resolution) so overlays survive resolution/profile changes.
 */
data class OverlayElement(
    val id: String,
    val type: OverlayType,
    val enabled: Boolean = true,
    val normalizedX: Float = 0f,      // 0.0 = left, 1.0 = right
    val normalizedY: Float = 0f,      // 0.0 = top, 1.0 = bottom
    val normalizedWidth: Float = 0.2f, // fraction of stream width
    val normalizedHeight: Float = 0f,  // 0 = auto (derived from aspect + width)
    val scale: Float = 1f,
    val rotationDegrees: Float = 0f,
    val opacity: Float = 1f,          // 0..1
    val zIndex: Int = 0,              // higher = on top
    val visible: Boolean = true,
    val resourcePath: String? = null    // PNG/asset resource reference
) {
    val isVisible: Boolean get() = enabled && visible && opacity > 0f

    /** Clamped copy — never lets callers violate the invariants. */
    fun sanitized(): OverlayElement = copy(
        opacity = opacity.coerceIn(0f, 1f),
        normalizedX = normalizedX.coerceIn(0f, 1f),
        normalizedY = normalizedY.coerceIn(0f, 1f),
        normalizedWidth = normalizedWidth.coerceIn(0f, 1f),
        scale = scale.coerceAtLeast(0.01f)
    )
}

enum class OverlayType {
    PNG,
    TEXT,
    // Future: ANIMATED_GIF, SHAPE, ... (M7 architecture only)
}