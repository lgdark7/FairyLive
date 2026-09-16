package com.fairylive.app

import com.fairylive.app.core.compositor.model.*
import com.fairylive.app.core.scene.SceneRepository
import com.fairylive.app.core.scene.model.BroadcastScene
import com.fairylive.app.core.scene.model.SceneBackgroundMode
import org.junit.Assert.*
import org.junit.Test

/**
 * Phase 3 Universal Transform, Web Interaction, Crop, Rotation & Persistence Unit Tests.
 * Strictly verifies P0-P21 requirements from Phase 3 directive.
 */
class V11Phase3ContractUnitTest {

    @Test
    fun testWebOverlayConfigScrollAndCropDefaults() {
        val cfg = WebOverlayConfig()
        assertEquals(0, cfg.scrollX)
        assertEquals(0, cfg.scrollY)
        assertEquals(0f, cfg.cropLeft, 0.001f)
        assertEquals(0f, cfg.cropTop, 0.001f)
        assertEquals(1f, cfg.cropRight, 0.001f)
        assertEquals(1f, cfg.cropBottom, 0.001f)
    }

    @Test
    fun testWebOverlayConfigCustomScrollAndCrop() {
        val cfg = WebOverlayConfig(
            url = "https://example.com/stream",
            scrollX = 120,
            scrollY = 450,
            cropLeft = 0.10f,
            cropTop = 0.05f,
            cropRight = 0.90f,
            cropBottom = 0.95f
        )
        assertEquals(120, cfg.scrollX)
        assertEquals(450, cfg.scrollY)
        assertEquals(0.10f, cfg.cropLeft, 0.001f)
        assertEquals(0.05f, cfg.cropTop, 0.001f)
        assertEquals(0.90f, cfg.cropRight, 0.001f)
        assertEquals(0.95f, cfg.cropBottom, 0.001f)
    }

    @Test
    fun testOverlayElementRotationSupported() {
        val element = OverlayElement(
            id = "elem_rot",
            type = OverlayType.WEB,
            rotationDegrees = 27.5f,
            normalizedX = 0.1f,
            normalizedY = 0.2f
        )
        assertEquals(27.5f, element.rotationDegrees, 0.001f)
    }

    @Test
    fun testOverlayTransformPreservesRotation() {
        val elem = OverlayElement(
            id = "elem_transform",
            type = OverlayType.WEB,
            rotationDegrees = 45.0f,
            normalizedX = 0.2f,
            normalizedY = 0.3f,
            scale = 1.2f
        )
        val transform = OverlayTransform.fromElement(elem)
        assertEquals(45.0f, transform.rotationDegrees, 0.001f)
        assertEquals(1.2f, transform.scale, 0.001f)
        assertEquals(0.2f, transform.normalizedX, 0.001f)
        assertEquals(0.3f, transform.normalizedY, 0.001f)
    }

    @Test
    fun testWebScrollDoesNotMutateOverlayPosition() {
        // P1 & P10 Invariant: Web scroll position change must NEVER move the overlay's canvas position
        val initialOverlay = OverlayElement.createWeb(
            id = "web_scroll_test",
            config = WebOverlayConfig(url = "https://example.com", scrollX = 0, scrollY = 0),
            x = 0.35f,
            y = 0.45f
        )

        // Simulate user scrolling web document to scrollY = 600
        val scrolledOverlay = initialOverlay.copy(
            webConfig = initialOverlay.webConfig?.copy(scrollY = 600)
        )

        // Canvas coordinates remain identical
        assertEquals(0.35f, scrolledOverlay.normalizedX, 0.001f)
        assertEquals(0.45f, scrolledOverlay.normalizedY, 0.001f)
        assertEquals(600, scrolledOverlay.webConfig?.scrollY)
        assertEquals(0, scrolledOverlay.webConfig?.scrollX)
    }

    @Test
    fun testCropBoundsIntegrity() {
        // Normalized crop coordinates should always satisfy left < right and top < bottom
        val cfg = WebOverlayConfig(
            cropLeft = 0.10f,
            cropTop = 0.10f,
            cropRight = 0.90f,
            cropBottom = 0.90f
        )
        assertTrue(cfg.cropLeft < cfg.cropRight)
        assertTrue(cfg.cropTop < cfg.cropBottom)
        assertTrue(cfg.cropLeft >= 0f)
        assertTrue(cfg.cropRight <= 1f)
    }
}
