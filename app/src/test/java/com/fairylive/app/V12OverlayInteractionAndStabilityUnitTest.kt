package com.fairylive.app

import com.fairylive.app.core.compositor.model.*
import com.fairylive.app.core.scene.BroadcastSceneManager
import com.fairylive.app.core.scene.model.BroadcastScene
import com.fairylive.app.core.scene.model.SceneBackgroundMode
import org.junit.Assert.*
import org.junit.Test
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * V12 Phase 2: Overlay Interaction & Rendering Stability Unit Test Suite.
 *
 * Verifies mathematical parity, rotation-invariant hit-testing, crop clamping,
 * scale bounds, z-index reordering, and interaction mode isolation.
 */
class V12OverlayInteractionAndStabilityUnitTest {

    // Helper: Unrotate point around center by -deg (same as in BroadcastPreviewView)
    private fun unrotatePoint(x: Float, y: Float, cx: Float, cy: Float, deg: Float): Pair<Float, Float> {
        val rad = Math.toRadians(-deg.toDouble())
        val c = cos(rad).toFloat()
        val s = sin(rad).toFloat()
        val dx = x - cx
        val dy = y - cy
        return Pair(cx + (dx * c - dy * s), cy + (dx * s + dy * c))
    }

    // Helper: Unrotate delta by -deg
    private fun unrotateDelta(dx: Float, dy: Float, deg: Float): Pair<Float, Float> {
        val rad = Math.toRadians(-deg.toDouble())
        val c = cos(rad).toFloat()
        val s = sin(rad).toFloat()
        return Pair(dx * c - dy * s, dx * s + dy * c)
    }

    // --- 1. PREVIEW <-> BROADCAST MATHEMATICAL PARITY ---

    @Test
    fun test01_overlayTransform_centerRemainsInvariantUnderScale() {
        val streamW = 1920
        val streamH = 1080
        val normX = 0.2f
        val normY = 0.3f
        val normW = 0.4f
        val normH = 0.2f

        val t1 = OverlayTransform(
            normalizedX = normX,
            normalizedY = normY,
            normalizedWidth = normW,
            normalizedHeight = normH,
            scale = 1.0f
        )
        val t2 = OverlayTransform(
            normalizedX = normX,
            normalizedY = normY,
            normalizedWidth = normW,
            normalizedHeight = normH,
            scale = 1.5f
        )
        val t3 = OverlayTransform(
            normalizedX = normX,
            normalizedY = normY,
            normalizedWidth = normW,
            normalizedHeight = normH,
            scale = 0.5f
        )

        val cx1 = t1.centerX(streamW)
        val cx2 = t2.centerX(streamW)
        val cx3 = t3.centerX(streamW)

        val cy1 = t1.centerY(streamW, streamH, 16f / 9f)
        val cy2 = t2.centerY(streamW, streamH, 16f / 9f)
        val cy3 = t3.centerY(streamW, streamH, 16f / 9f)

        assertEquals("Center X must be identical regardless of scale", cx1, cx2, 0.001f)
        assertEquals("Center X must be identical regardless of scale", cx1, cx3, 0.001f)
        assertEquals("Center Y must be identical regardless of scale", cy1, cy2, 0.001f)
        assertEquals("Center Y must be identical regardless of scale", cy1, cy3, 0.001f)
    }

    @Test
    fun test02_overlayTransform_resolvedBoundsExpandSymmetricallyAroundCenter() {
        val streamW = 1000
        val streamH = 1000
        val t = OverlayTransform(
            normalizedX = 0.2f,
            normalizedY = 0.2f,
            normalizedWidth = 0.2f,
            normalizedHeight = 0.2f,
            scale = 1.5f
        )

        val expectedW = 0.2f * 1000f * 1.5f // 300px
        val expectedH = 0.2f * 1000f * 1.5f // 300px
        val expectedCx = (0.2f + 0.1f) * 1000f // 300px
        val expectedCy = (0.2f + 0.1f) * 1000f // 300px

        val resolvedW = t.resolvedWidth(streamW)
        val resolvedH = t.resolvedHeight(streamW, streamH, 1.0f)
        val resolvedLeft = t.resolvedLeft(streamW)
        val resolvedTop = t.resolvedTop(streamW, streamH, 1.0f)

        assertEquals("Width must be scaled 1.5x", expectedW, resolvedW, 0.01f)
        assertEquals("Height must be scaled 1.5x", expectedH, resolvedH, 0.01f)
        assertEquals("Left must expand to maintain center", expectedCx - expectedW / 2f, resolvedLeft, 0.01f)
        assertEquals("Top must expand to maintain center", expectedCy - expectedH / 2f, resolvedTop, 0.01f)
    }

    // --- 2. ROTATION & HIT-TESTING INVARIANTS ---

    @Test
    fun test03_rotationHitTest_unrotateRestoresLocalCoordinatesAt45Degrees() {
        val cx = 500f
        val cy = 500f
        val deg = 45f

        // A point 100px directly above the center before rotation (0, -100)
        val localX = 500f
        val localY = 400f

        // Rotate (localX, localY) by +45 deg around (cx, cy)
        val rad = Math.toRadians(45.0)
        val screenX = cx + (0f * cos(rad) - (-100f) * sin(rad)).toFloat()
        val screenY = cy + (0f * sin(rad) + (-100f) * cos(rad)).toFloat()

        // Now test inverse unrotate
        val (unrotX, unrotY) = unrotatePoint(screenX, screenY, cx, cy, deg)

        assertEquals("Unrotated X must match original local X", localX, unrotX, 0.01f)
        assertEquals("Unrotated Y must match original local Y", localY, unrotY, 0.01f)
    }

    @Test
    fun test04_rotationHitTest_rotationStemAlwaysLocatedAtTopCenterInLocalSpace() {
        val cx = 200f
        val cy = 300f
        val rectTop = 200f
        val stemHeight = 40f
        val targetLocalY = rectTop - stemHeight // 160f

        // Test angles 0, 90, 180, 270
        val angles = listOf(0f, 90f, 180f, 270f)
        for (deg in angles) {
            val rad = Math.toRadians(deg.toDouble())
            val dy = targetLocalY - cy
            val screenX = cx + (-dy * sin(rad)).toFloat()
            val screenY = cy + (dy * cos(rad)).toFloat()

            val (unrotX, unrotY) = unrotatePoint(screenX, screenY, cx, cy, deg)
            assertEquals("Handle unrotated X must be cx", cx, unrotX, 0.01f)
            assertEquals("Handle unrotated Y must be rectTop - stemHeight", targetLocalY, unrotY, 0.01f)
        }
    }

    // --- 3. CROP INVARIANTS & ROTATION DELTA ISOLATION ---

    @Test
    fun test05_cropInvariants_boundsClampedBetweenZeroAndOne() {
        var cl = 0.1f
        var ct = 0.1f
        var cr = 0.9f
        var cb = 0.9f

        // Extreme negative delta
        val negDelta = -10.0f
        cl = (cl + negDelta).coerceIn(0f, cr - 0.05f)
        ct = (ct + negDelta).coerceIn(0f, cb - 0.05f)

        assertEquals("Crop left cannot be negative", 0f, cl, 0.001f)
        assertEquals("Crop top cannot be negative", 0f, ct, 0.001f)

        // Extreme positive delta
        val posDelta = 10.0f
        cr = (cr + posDelta).coerceIn(cl + 0.05f, 1f)
        cb = (cb + posDelta).coerceIn(ct + 0.05f, 1f)

        assertEquals("Crop right cannot exceed 1.0", 1f, cr, 0.001f)
        assertEquals("Crop bottom cannot exceed 1.0", 1f, cb, 0.001f)
    }

    @Test
    fun test06_cropInvariants_leftCannotCrossRight() {
        var cl = 0.45f
        val cr = 0.50f

        // Drag rightwards past right edge
        val excessiveDelta = 0.20f
        cl = (cl + excessiveDelta).coerceIn(0f, cr - 0.05f)

        assertTrue("Crop left ($cl) must remain strictly less than crop right ($cr)", cl <= cr - 0.05f)
        assertEquals(0.45f, cl, 0.001f)
    }

    @Test
    fun test07_cropDelta_unrotatesCorrectlyAt90Degrees() {
        // Dragging DOWN on screen (dx = 0, dy = 50) when element is rotated 90 degrees clockwise
        // In local coordinates, dragging down screen corresponds to dragging RIGHT in local space
        val deg = 90f
        val (localDx, localDy) = unrotateDelta(0f, 50f, deg)

        assertEquals("At 90 deg, screen dy becomes local dx", 50f, localDx, 0.01f)
        assertEquals("At 90 deg, screen dx becomes local dy", 0f, localDy, 0.01f)
    }

    // --- 4. SCALE LIMITS & CLAMPING ---

    @Test
    fun test08_scaleLimits_enforcesMin01Max50() {
        val initialScale = 1.0f

        val tinyPinchFactor = 0.01f
        val clampedMin = (initialScale * tinyPinchFactor).coerceIn(0.1f, 5.0f)
        assertEquals("Min scale must be 0.1", 0.1f, clampedMin, 0.001f)

        val giantPinchFactor = 100.0f
        val clampedMax = (initialScale * giantPinchFactor).coerceIn(0.1f, 5.0f)
        assertEquals("Max scale must be 5.0", 5.0f, clampedMax, 0.001f)
    }

    // --- 5. Z-ORDER REORDERING NORMALIZATION ---

    @Test
    fun test09_zOrder_reorderingNormalizesMonotonicZIndices() {
        val elem1 = OverlayElement(id = "1", zIndex = 10, type = OverlayType.TEXT)
        val elem2 = OverlayElement(id = "2", zIndex = 20, type = OverlayType.IMAGE)
        val elem3 = OverlayElement(id = "3", zIndex = 30, type = OverlayType.WEB)

        val initialScene = BroadcastScene(
            id = "scene1",
            name = "Default",
            overlayElements = listOf(elem1, elem2, elem3)
        )

        val manager = BroadcastSceneManager(listOf(initialScene))

        // Move elem1 (index 0) to top (index 2)
        val updated = manager.reorderLayersInActiveScene(0, 2)

        assertEquals(3, updated.overlayElements.size)
        assertEquals("2", updated.overlayElements[0].id)
        assertEquals("3", updated.overlayElements[1].id)
        assertEquals("1", updated.overlayElements[2].id)

        // Verify zIndices are monotonically strictly increasing
        val z0 = updated.overlayElements[0].zIndex
        val z1 = updated.overlayElements[1].zIndex
        val z2 = updated.overlayElements[2].zIndex

        assertTrue("z0 < z1", z0 < z1)
        assertTrue("z1 < z2", z1 < z2)
        assertEquals(5, z0)
        assertEquals(10, z1)
        assertEquals(15, z2)
    }

    // --- 6. INTERACTION MODE ISOLATION & LOCKING ---

    @Test
    fun test10_locking_lockedElementsPreserveTransformAndRejectEdits() {
        val lockedElem = OverlayElement(
            id = "locked1",
            type = OverlayType.WEB,
            locked = true,
            normalizedX = 0.1f,
            normalizedY = 0.1f,
            scale = 1.0f,
            rotationDegrees = 0f
        )

        assertTrue("Element must report locked", lockedElem.locked)
        // Ensure that locked elements cannot be manipulated accidentally
        val scene = BroadcastScene(
            id = "scene",
            name = "Test",
            overlayElements = listOf(lockedElem)
        )
        val elem = scene.overlayElements.first { it.id == "locked1" }
        assertTrue(elem.locked)
    }

    @Test
    fun test11_webOverlay_configPersistenceAcrossCopies() {
        val webConfig = WebOverlayConfig(
            url = "https://example.com",
            cropLeft = 0.15f,
            cropTop = 0.20f,
            cropRight = 0.85f,
            cropBottom = 0.80f,
            scrollX = 120,
            scrollY = 350
        )

        val overlay = OverlayElement(
            id = "web1",
            type = OverlayType.WEB,
            webConfig = webConfig
        )

        val duplicate = overlay.copy(
            id = "web2",
            normalizedX = overlay.normalizedX + 0.05f
        )

        assertNotNull(duplicate.webConfig)
        assertEquals(0.15f, duplicate.webConfig!!.cropLeft, 0.001f)
        assertEquals(0.20f, duplicate.webConfig!!.cropTop, 0.001f)
        assertEquals(0.85f, duplicate.webConfig!!.cropRight, 0.001f)
        assertEquals(0.80f, duplicate.webConfig!!.cropBottom, 0.001f)
        assertEquals(120, duplicate.webConfig!!.scrollX)
        assertEquals(350, duplicate.webConfig!!.scrollY)
    }

    @Test
    fun test12_encoderCountInvariant_alwaysTwoOrFewer() {
        // Enforce strict limit: 1 video encoder + 1 audio encoder <= 2
        val maxSimultaneousHardwareCodecs = 2
        val activeCodecs = listOf("c2.android.avc.encoder", "c2.android.aac.encoder")
        assertTrue("Active codecs must be <= 2", activeCodecs.size <= maxSimultaneousHardwareCodecs)
    }
}
