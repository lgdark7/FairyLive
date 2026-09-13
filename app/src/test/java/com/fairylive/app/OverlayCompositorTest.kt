package com.fairylive.app

import com.fairylive.app.core.compositor.OverlayLayer
import com.fairylive.app.core.compositor.OverlayScene
import com.fairylive.app.core.compositor.model.OverlayElement
import com.fairylive.app.core.compositor.model.OverlayTransform
import com.fairylive.app.core.compositor.model.OverlayType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for Milestone 7 (Broadcast Overlay Compositor).
 *
 * These test the pure, JVM-side model/scene/transform math and layer ordering —
 * the parts of the compositor that do not require a GL context. The GLES render
 * path is covered by the on-device instrumentation tests + A/B telemetry.
 */
class OverlayCompositorTest {

    private fun sampleElement(
        id: String = "logo",
        x: Float = 0.1f,
        y: Float = 0.2f,
        w: Float = 0.3f,
        opacity: Float = 1f,
        z: Int = 0
    ) = OverlayElement(
        id = id,
        type = OverlayType.PNG,
        enabled = true,
        normalizedX = x,
        normalizedY = y,
        normalizedWidth = w,
        scale = 1f,
        opacity = opacity,
        zIndex = z,
        visible = true,
        resourcePath = "assets://overlay_logo.png"
    )

    // --- 1. MODEL ---

    @Test
    fun testElementDefaults() {
        val e = sampleElement()
        assertEquals(OverlayType.PNG, e.type)
        assertTrue(e.enabled)
        assertTrue(e.visible)
        assertEquals(1f, e.scale, 0f)
    }

    @Test
    fun testTransformComputedFromNormalizedRect() {
        val e = sampleElement(x = 0.1f, y = 0.2f, w = 0.4f)
        val t = OverlayTransform.fromElement(e)
        assertEquals(0.1f, t.normalizedX, 1e-6f)
        assertEquals(0.2f, t.normalizedY, 1e-6f)
        assertEquals(0.4f, t.normalizedWidth, 1e-6f)
        assertTrue(t.opacity > 0f)
        assertTrue(t.zIndex >= 0)
        assertNotNull(t)
    }

    @Test
    fun testTransformClampsOpacityToUnitRange() {
        // sanitized() clamps out-of-range model values before they reach the transform.
        val e = sampleElement(opacity = 2f).sanitized()
        assertTrue(e.opacity <= 1f)
        val t = OverlayTransform.fromElement(e)
        assertTrue(t.opacity <= 1f)
        assertTrue(t.opacity >= 0f)
    }

    // --- 2. SCENE / ORDERING ---

    @Test
    fun testSceneAddsAndRemovesLayers() {
        val scene = OverlayScene()
        scene.add(sampleElement(id = "a"))
        scene.add(sampleElement(id = "b"))
        assertEquals(2, scene.size)
        scene.remove("a")
        assertEquals(1, scene.size)
        scene.clear()
        assertEquals(0, scene.size)
    }

    @Test
    fun testSceneDeduplicatesById() {
        val scene = OverlayScene()
        scene.add(sampleElement(id = "logo"))
        scene.add(sampleElement(id = "logo", w = 0.5f))
        assertEquals(1, scene.size)
        val layer = scene.find("logo")
        assertNotNull(layer)
        assertEquals(0.5f, layer!!.element.normalizedWidth, 1e-6f)
    }

    @Test
    fun testSceneReturnsLayersInZOrder() {
        val scene = OverlayScene()
        scene.add(sampleElement(id = "low", z = 0))
        scene.add(sampleElement(id = "high", z = 10))
        scene.add(sampleElement(id = "mid", z = 5))
        val ordered = scene.sortedLayers
        assertEquals(3, ordered.size)
        // z=0 first, z=10 last
        assertEquals("low", ordered[0].element.id)
        assertEquals("mid", ordered[1].element.id)
        assertEquals("high", ordered[2].element.id)
    }

    @Test
    fun testSceneToggleVisibility() {
        val scene = OverlayScene()
        scene.add(sampleElement(id = "logo"))
        scene.setVisible("logo", false)
        assertFalse(scene.find("logo")!!.element.visible)
        // Hidden layers stay in the scene but should be skipped at draw time
        assertTrue(scene.size == 1)
        assertFalse(scene.find("logo")!!.element.visible)
    }

    // --- 3. NORMALIZED COORDINATES / RESIZE STABILITY ---

    @Test
    fun testNormalizedCoordsSurviveResolutionChange() {
        val e = sampleElement(x = 0.25f, y = 0.5f, w = 0.2f)
        val t = OverlayTransform.fromElement(e)

        // Resize 480x1056 -> 640x1280: normalized coords are resolution-independent.
        val (oldW, oldH) = 480 to 1056
        val (newW, newH) = 640 to 1280
        val oldPx = listOf(t.normalizedX * oldW, t.normalizedY * oldH, t.normalizedWidth * oldW)
        val newPx = listOf(t.normalizedX * newW, t.normalizedY * newH, t.normalizedWidth * newW)

        // The aspect-ratio scaled width changes proportionally — position stays identical in normalized space.
        assertEquals(e.normalizedX, t.normalizedX, 1e-6f)
        assertEquals(e.normalizedY, t.normalizedY, 1e-6f)
        assertEquals(e.normalizedWidth, t.normalizedWidth, 1e-6f)
        assertTrue(newPx[0] > oldPx[0])  // wider screen -> further right in px, same normalized
        assertTrue(newPx[1] > oldPx[1])  // taller screen -> further down in px
    }

    @Test
    fun testOverlayLayerHoldsDrawReadyState() {
        val e = sampleElement()
        val layer = OverlayLayer(e)
        assertEquals(e.id, layer.element.id)
        assertEquals(0, layer.textureHandle)  // not uploaded yet (GL-side)
        assertTrue(layer.dirty)
    }

    @Test
    fun testCompositorMetricsCountersAndAccounting() {
        val metrics = com.fairylive.app.core.compositor.CompositorMetrics(
            captureFrames = 120L,
            surfaceTextureUpdates = 100L,
            renderIterations = 100L,
            duplicateRendersSkipped = 20L,
            framesComposited = 100L,
            outputFps = 20.0,
            sourceFps = 20.0,
            overlayCount = 2,
            state = com.fairylive.app.core.compositor.CompositorState.RUNNING
        )
        assertEquals(120L, metrics.captureFrames)
        assertEquals(100L, metrics.surfaceTextureUpdates)
        assertEquals(100L, metrics.renderIterations)
        assertEquals(20L, metrics.duplicateRendersSkipped)
        assertEquals(100L, metrics.framesComposited)
        assertEquals(20.0, metrics.outputFps, 0.001)
        assertEquals(20.0, metrics.sourceFps, 0.001)
        assertEquals(2, metrics.overlayCount)
        assertEquals(com.fairylive.app.core.compositor.CompositorState.RUNNING, metrics.state)
    }

    @Test
    fun testTextOverlayConfigAndFactory() {
        val config = com.fairylive.app.core.compositor.model.TextOverlayConfig(
            text = "Welcome to FAIRY LIVE",
            fontSizeSp = 22f
        )
        val element = OverlayElement.createText("txt1", config, x = 0.05f, y = 0.1f)
        assertEquals(OverlayType.TEXT, element.type)
        assertEquals("Welcome to FAIRY LIVE", element.textConfig?.text)
        assertEquals(0.05f, element.normalizedX, 1e-6f)
        assertEquals(0.1f, element.normalizedY, 1e-6f)
        assertTrue(element.isVisible)
    }

    @Test
    fun testGoalOverlayProgressFractionCalculation() {
        val goalConfig = com.fairylive.app.core.compositor.model.GoalOverlayConfig(
            title = "Subscribers",
            currentVal = 300,
            targetVal = 600
        )
        assertEquals(0.5f, goalConfig.progressFraction, 0.001f)

        val completedGoal = goalConfig.copy(currentVal = 700)
        assertEquals(1.0f, completedGoal.progressFraction, 0.001f) // Clamped to 1.0

        val zeroGoal = goalConfig.copy(targetVal = 0)
        assertEquals(0.0f, zeroGoal.progressFraction, 0.001f)
    }

    @Test
    fun testLowerThirdOverlayConfig() {
        val l3 = com.fairylive.app.core.compositor.model.LowerThirdOverlayConfig(
            title = "John Doe",
            subtitle = "Lead Mobile Architect"
        )
        val element = OverlayElement.createLowerThird("l3_banner", l3)
        assertEquals(OverlayType.LOWER_THIRD, element.type)
        assertEquals("John Doe", element.lowerThirdConfig?.title)
        assertEquals("Lead Mobile Architect", element.lowerThirdConfig?.subtitle)
        assertEquals(20, element.zIndex)
    }

    @Test
    fun testQrMatrixGenerationHasValidFinders() {
        val url = "https://fairylive.app/stream"
        val matrix = com.fairylive.app.core.compositor.rasterizer.QrOverlayRasterizer.generateQrMatrix(url)
        assertEquals(21, matrix.size)
        assertEquals(21, matrix[0].size)

        // Top-left finder center (row 3, col 3) must be dark
        assertTrue(matrix[3][3])
        // Top-left finder border (row 0, col 0) must be dark
        assertTrue(matrix[0][0])
        // Top-right finder center (row 3, col 21-4 = 17) must be dark
        assertTrue(matrix[3][17])
        // Bottom-left finder center (row 17, col 3) must be dark
        assertTrue(matrix[17][3])
    }

    @Test
    fun testCompositorLimitsEnforcedOnScene() {
        val scene = OverlayScene()
        // Add more than MAX_ACTIVE_OVERLAYS (10)
        for (i in 1..15) {
            val el = sampleElement(id = "layer_$i", z = i)
            scene.add(el)
        }
        assertEquals(com.fairylive.app.core.compositor.limits.CompositorLimits.MAX_ACTIVE_OVERLAYS, scene.size)
        // Ensure the highest z-index layers survived
        assertNotNull(scene.find("layer_15"))
        assertNotNull(scene.find("layer_14"))
    }

    @Test
    fun testMultiOverlaySceneOrdering() {
        val scene = OverlayScene()
        val logo = OverlayElement.createImage("logo", "asset://logo.png", 0.7f, 0.8f, 0.2f, zIndex = 5)
        val text = OverlayElement.createText("title", com.fairylive.app.core.compositor.model.TextOverlayConfig("Title"), 0.1f, 0.1f, zIndex = 15)
        val alert = OverlayElement.createAlert("alert", com.fairylive.app.core.compositor.model.AlertOverlayConfig("Alert", "Sub"), zIndex = 50)

        scene.add(alert)
        scene.add(logo)
        scene.add(text)

        val sorted = scene.orderedLayers
        assertEquals(3, sorted.size)
        assertEquals("logo", sorted[0].element.id) // zIndex = 5
        assertEquals("title", sorted[1].element.id) // zIndex = 15
        assertEquals("alert", sorted[2].element.id) // zIndex = 50
    }
}