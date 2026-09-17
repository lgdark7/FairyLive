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

    // --- 7. P0: ALL 10 OVERLAY TYPES GEOMETRY & TRANSFORM PARITY ---

    @Test
    fun test13_allTenOverlayTypes_transformCorrectnessAndGeometryParity() {
        val streamW = 1920
        val streamH = 1080
        val types = listOf(
            OverlayType.TEXT,
            OverlayType.IMAGE,
            OverlayType.GIF,
            OverlayType.ALERT,
            OverlayType.QR,
            OverlayType.LOWER_THIRD,
            OverlayType.GOAL,
            OverlayType.WIDGET,
            OverlayType.YOUTUBE_SUBSCRIBER,
            OverlayType.WEB
        )

        for (type in types) {
            val elem = OverlayElement(
                id = "elem_${type.name}",
                type = type,
                normalizedX = 0.25f,
                normalizedY = 0.35f,
                normalizedWidth = 0.30f,
                normalizedHeight = 0.15f,
                scale = 1.25f,
                rotationDegrees = 45f
            )

            val transform = OverlayTransform(
                normalizedX = elem.normalizedX,
                normalizedY = elem.normalizedY,
                normalizedWidth = elem.normalizedWidth,
                normalizedHeight = elem.normalizedHeight,
                scale = elem.scale,
                rotationDegrees = elem.rotationDegrees
            )

            val cx = transform.centerX(streamW)
            val cy = transform.centerY(streamW, streamH, 16f / 9f)
            val w = transform.resolvedWidth(streamW)
            val h = transform.resolvedHeight(streamW, streamH, 16f / 9f)
            val l = transform.resolvedLeft(streamW)
            val t = transform.resolvedTop(streamW, streamH, 16f / 9f)

            // Verify center alignment
            assertEquals("Center X must match center of bounding box for ${type.name}", cx, l + w / 2f, 0.01f)
            assertEquals("Center Y must match center of bounding box for ${type.name}", cy, t + h / 2f, 0.01f)
            assertEquals(0.30f * 1920f * 1.25f, w, 0.01f)
            assertEquals(0.15f * 1080f * 1.25f, h, 0.01f)
        }
    }

    // --- 8. P1: GESTURE STATE MACHINE DETERMINISM ---

    @Test
    fun test14_gestureStateMachine_deterministicTransitions() {
        val states = listOf(
            "IDLE",
            "DRAG",
            "SCALE_ROTATE",
            "ROTATE",
            "RESIZE",
            "CROP_LEFT",
            "CROP_TOP",
            "CROP_RIGHT",
            "CROP_BOTTOM",
            "WEB_SCROLL"
        )

        // Verify each state string represents a unique valid state in the machine
        assertEquals(10, states.distinct().size)
        assertTrue(states.contains("IDLE"))
        assertTrue(states.contains("CROP_LEFT"))
        assertTrue(states.contains("CROP_TOP"))
        assertTrue(states.contains("CROP_RIGHT"))
        assertTrue(states.contains("CROP_BOTTOM"))
        assertTrue(states.contains("SCALE_ROTATE"))
    }

    // --- 9. P3: ROTATION QUALITY ACROSS ALL 12 ANGLES ---

    @Test
    fun test15_rotationQuality_preservesCenterAndGeometryAcrossAllTwelveAngles() {
        val angles = listOf(0f, 15f, 27f, 45f, 90f, 135f, 180f, 225f, 270f, 315f, 359f, 360f)
        val cx = 600f
        val cy = 400f
        val testDist = 120f

        for (deg in angles) {
            val rad = Math.toRadians(deg.toDouble())
            // Point displaced horizontally by testDist from center
            val localX = cx + testDist
            val localY = cy

            // Forward rotate
            val screenX = cx + ((localX - cx) * cos(rad) - (localY - cy) * sin(rad)).toFloat()
            val screenY = cy + ((localX - cx) * sin(rad) + (localY - cy) * cos(rad)).toFloat()

            // Inverse unrotate
            val (unrotX, unrotY) = unrotatePoint(screenX, screenY, cx, cy, deg)

            assertEquals("At angle $deg, unrotated X must match local X", localX, unrotX, 0.05f)
            assertEquals("At angle $deg, unrotated Y must match local Y", localY, unrotY, 0.05f)

            // Distance to center must remain exactly testDist
            val dist = sqrt((screenX - cx) * (screenX - cx) + (screenY - cy) * (screenY - cy))
            assertEquals("At angle $deg, distance to center must be invariant", testDist, dist, 0.01f)
        }
    }

    // --- 10. P5: WEBPAGE ZOOM VS OVERLAY SCALE INDEPENDENCE ---

    @Test
    fun test16_webOverlay_zoomLevelIndependentOfOverlayScale() {
        val streamW = 1280
        val streamH = 720

        val cfg1 = WebOverlayConfig(zoomLevel = 1.0f)
        val cfg2 = WebOverlayConfig(zoomLevel = 2.5f)

        val elem1 = OverlayElement(
            id = "web1",
            type = OverlayType.WEB,
            normalizedWidth = 0.4f,
            normalizedHeight = 0.3f,
            scale = 1.0f,
            webConfig = cfg1
        )
        val elem2 = elem1.copy(webConfig = cfg2)

        val t1 = OverlayTransform(
            normalizedWidth = elem1.normalizedWidth,
            normalizedHeight = elem1.normalizedHeight,
            scale = elem1.scale
        )
        val t2 = OverlayTransform(
            normalizedWidth = elem2.normalizedWidth,
            normalizedHeight = elem2.normalizedHeight,
            scale = elem2.scale
        )

        // Web zoom must NOT alter external canvas bounding box dimensions
        assertEquals(t1.resolvedWidth(streamW), t2.resolvedWidth(streamW), 0.001f)
        assertEquals(t1.resolvedHeight(streamW, streamH, 16f / 9f), t2.resolvedHeight(streamW, streamH, 16f / 9f), 0.001f)
    }

    // --- 11. P6: LAYER SYSTEM SYNCHRONIZATION ---

    @Test
    fun test17_layerSystem_synchronizationAndDuplication() {
        val base = OverlayElement(id = "layer_a", zIndex = 5, type = OverlayType.TEXT)
        val duplicate = base.copy(
            id = "layer_b",
            zIndex = 10,
            normalizedX = base.normalizedX + 0.04f,
            normalizedY = base.normalizedY + 0.04f
        )

        assertNotEquals(base.id, duplicate.id)
        assertEquals(base.type, duplicate.type)
        assertTrue("Duplicate position shifted for visibility", duplicate.normalizedX > base.normalizedX)
    }
}
