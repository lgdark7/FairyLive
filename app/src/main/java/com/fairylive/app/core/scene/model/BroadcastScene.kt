package com.fairylive.app.core.scene.model

import com.fairylive.app.core.camera.model.FacecamConfig
import com.fairylive.app.core.compositor.model.OverlayElement

/**
 * An immutable broadcast scene containing an overlay layout, Facecam configuration,
 * and background rendering mode.
 */
data class BroadcastScene(
    val id: String,
    val name: String,
    val overlayElements: List<OverlayElement> = emptyList(),
    val facecamConfig: FacecamConfig = FacecamConfig(),
    val backgroundMode: SceneBackgroundMode = SceneBackgroundMode.SCREEN_CAPTURE,
    val backgroundColorArgb: Int = 0xFF000000.toInt(),
    val orderIndex: Int = 0
)
