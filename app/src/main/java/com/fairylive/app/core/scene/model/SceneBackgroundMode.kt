package com.fairylive.app.core.scene.model

/**
 * Defines how the base layer behind overlay graphics is filled for a scene.
 */
enum class SceneBackgroundMode {
    /** Pass-through live screen capture from VirtualDisplay. */
    SCREEN_CAPTURE,

    /** Solid color background (e.g. for BRB / intermission scenes). */
    SOLID_COLOR,

    /** Transparent background (renders overlays over black/null if capture absent). */
    TRANSPARENT
}
