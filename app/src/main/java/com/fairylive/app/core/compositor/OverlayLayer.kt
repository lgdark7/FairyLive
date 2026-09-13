package com.fairylive.app.core.compositor

import com.fairylive.app.core.compositor.model.OverlayElement
import com.fairylive.app.core.compositor.model.OverlayTransform

/**
 * A single runtime overlay layer: the element model plus its GPU texture handle.
 * Each layer owns (or references) one cached texture. Layers are rendered in
 * z-order (ascending zIndex) by the compositor renderer.
 */
class OverlayLayer(
    val element: OverlayElement
) {
    /** GLES texture handle (0 = none/not yet uploaded). Set by the renderer. */
    @Volatile
    var textureHandle: Int = 0
        internal set

    /** Pixel dimensions of the source bitmap/texture (for aspect + scaling). */
    @Volatile
    var sourceWidth: Int = 0
        internal set

    @Volatile
    var sourceHeight: Int = 0
        internal set

    val sourceAspect: Float
        get() = if (sourceHeight > 0) sourceWidth.toFloat() / sourceHeight.toFloat() else 1f

    val transform: OverlayTransform
        get() = OverlayTransform(
            normalizedX = element.normalizedX,
            normalizedY = element.normalizedY,
            normalizedWidth = element.normalizedWidth,
            normalizedHeight = element.normalizedHeight,
            scale = element.scale,
            rotationDegrees = element.rotationDegrees,
            opacity = element.opacity,
            zIndex = element.zIndex
        )

    /** Mutable live state (independent of the immutable element model). */
    @Volatile
    var liveOpacity: Float = element.opacity

    @Volatile
    var liveVisible: Boolean = element.visible

    @Volatile
    var liveScale: Float = element.scale

    /** True if the texture needs (re)upload on next draw (model changed). */
    @Volatile
    var dirty: Boolean = true
}