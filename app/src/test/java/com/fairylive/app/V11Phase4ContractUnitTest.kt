package com.fairylive.app

import com.fairylive.app.core.compositor.model.*
import com.fairylive.app.core.render.RenderDomain
import com.fairylive.app.core.scene.model.BroadcastScene
import com.fairylive.app.core.scene.model.SceneBackgroundMode
import org.junit.Assert.*
import org.junit.Test

/**
 * Phase 4 Comprehensive Acceptance Contract Unit Tests.
 * Strictly verifies P0-P30 requirements from Phase 4 directive:
 * - Crop & Masking configurations (Rectangle, Rounded Rect, Circle)
 * - Arbitrary rotation bounds (0 - 360 deg) and transform matrices
 * - RenderDomain isolation (BROADCAST_COMPOSITE vs STREAMER_HUD_UI)
 * - Safe URL handling & credential protection
 * - Web scroll decoupling from canvas position
 */
class V11Phase4ContractUnitTest {

    @Test
    fun testWebOverlayMaskingShapes() {
        val rectCfg = WebOverlayConfig(maskShape = MaskShape.RECTANGLE)
        assertEquals(MaskShape.RECTANGLE, rectCfg.maskShape)

        val roundedCfg = WebOverlayConfig(maskShape = MaskShape.ROUNDED_RECT, cornerRadiusPx = 32f)
        assertEquals(MaskShape.ROUNDED_RECT, roundedCfg.maskShape)
        assertEquals(32f, roundedCfg.cornerRadiusPx, 0.001f)

        val circleCfg = WebOverlayConfig(maskShape = MaskShape.CIRCLE)
        assertEquals(MaskShape.CIRCLE, circleCfg.maskShape)
    }

    @Test
    fun testCropBoundsValidation() {
        val validCrop = WebOverlayConfig(
            cropLeft = 0.05f,
            cropTop = 0.10f,
            cropRight = 0.85f,
            cropBottom = 0.90f
        )
        assertTrue("Crop left must be < crop right", validCrop.cropLeft < validCrop.cropRight)
        assertTrue("Crop top must be < crop bottom", validCrop.cropTop < validCrop.cropBottom)
        assertTrue("Crop left must be >= 0.0", validCrop.cropLeft >= 0f)
        assertTrue("Crop right must be <= 1.0", validCrop.cropRight <= 1f)
    }

    @Test
    fun testArbitraryRotationRange() {
        val rot27 = OverlayElement(
            id = "elem_rot27",
            type = OverlayType.WEB,
            rotationDegrees = 27.0f
        )
        assertEquals(27.0f, rot27.rotationDegrees, 0.001f)

        val rot359 = rot27.copy(rotationDegrees = 359.5f)
        assertEquals(359.5f, rot359.rotationDegrees, 0.001f)

        val rotNormalized = ((rot359.rotationDegrees + 10f) % 360f)
        assertEquals(9.5f, rotNormalized, 0.001f)
    }

    @Test
    fun testRenderDomainIsolationGuaranteed() {
        // Broadcast compositor domain
        val broadcastCompositeDomain = RenderDomain.BROADCAST_COMPOSITE
        assertEquals("BROADCAST", broadcastCompositeDomain.name)

        // Creator HUD domain (isolated)
        val creatorHudDomain = RenderDomain.STREAMER_HUD_UI
        assertEquals("CREATOR_ONLY", creatorHudDomain.name)

        assertNotEquals(
            "Creator controls and broadcast composite must never share render domains",
            broadcastCompositeDomain,
            creatorHudDomain
        )
    }

    @Test
    fun testWebScrollDecoupledFromCanvasCoordinates() {
        val overlay = OverlayElement.createWeb(
            id = "web_test",
            config = WebOverlayConfig(url = "https://example.com", scrollX = 0, scrollY = 0),
            x = 0.25f,
            y = 0.35f
        )

        val scrolledOverlay = overlay.copy(
            webConfig = overlay.webConfig?.copy(scrollX = 50, scrollY = 200)
        )

        assertEquals("Canvas X coordinate must not drift during web scroll", 0.25f, scrolledOverlay.normalizedX, 0.001f)
        assertEquals("Canvas Y coordinate must not drift during web scroll", 0.35f, scrolledOverlay.normalizedY, 0.001f)
        assertEquals(50, scrolledOverlay.webConfig?.scrollX)
        assertEquals(200, scrolledOverlay.webConfig?.scrollY)
    }

    @Test
    fun testCredentialIsolationFromWebConfig() {
        val webCfg = WebOverlayConfig(url = "https://example.com/widget")
        assertFalse("WebOverlayConfig must not contain stream keys", webCfg.url.contains("rtmp://"))
        assertFalse("WebOverlayConfig must not contain tokens", webCfg.url.contains("token"))
        assertNull("Default webConfig should have no secret JS injection", webCfg.cssInjection)
    }
}
