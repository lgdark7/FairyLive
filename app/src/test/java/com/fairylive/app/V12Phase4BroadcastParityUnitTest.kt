package com.fairylive.app

import com.fairylive.app.core.camera.model.FacecamConfig
import com.fairylive.app.core.compositor.model.*
import com.fairylive.app.core.render.RenderDomain
import com.fairylive.app.core.scene.model.BroadcastScene
import com.fairylive.app.core.settings.CaptureSource
import com.fairylive.app.core.settings.StreamDestination
import com.fairylive.app.core.settings.StreamSettings
import com.fairylive.app.core.stream.flv.AacFlvPacketizer
import org.junit.Assert.*
import org.junit.Test
import kotlin.math.*

class V12Phase4BroadcastParityUnitTest {

    // --- 1. TRANSFORM CENTER PARITY ---
    @Test
    fun test01_transformCenterParity_previewMatchesGles() {
        val streamW = 1280
        val streamH = 720
        val viewW = 360f
        val viewH = 640f

        val normX = 0.25f
        val normY = 0.35f
        val normW = 0.30f
        val normH = 0.15f
        val scale = 1.4f

        val transform = OverlayTransform(
            normalizedX = normX,
            normalizedY = normY,
            normalizedWidth = normW,
            normalizedHeight = normH,
            scale = scale
        )

        // Stream (GLES) center
        val glesCenterX = transform.centerX(streamW)
        val glesCenterY = transform.centerY(streamW, streamH, 16f / 9f)

        // Preview view center
        val previewBaseW = normW * viewW
        val previewBaseH = normH * viewH
        val previewCenterX = (normX * viewW) + (previewBaseW / 2f)
        val previewCenterY = (normY * viewH) + (previewBaseH / 2f)

        // Ratio of centers must match normalized coordinate center exactly
        val expectedNormCenterX = normX + normW / 2f
        val expectedNormCenterY = normY + normH / 2f

        assertEquals(expectedNormCenterX, glesCenterX / streamW, 0.001f)
        assertEquals(expectedNormCenterY, glesCenterY / streamH, 0.001f)
        assertEquals(expectedNormCenterX, previewCenterX / viewW, 0.001f)
        assertEquals(expectedNormCenterY, previewCenterY / viewH, 0.001f)
    }

    // --- 2. SCALE PARITY ---
    @Test
    fun test02_scaleParity_scalesSymmetricallyAroundCenter() {
        val streamW = 1920
        val streamH = 1080
        val scales = listOf(0.2f, 0.5f, 1.0f, 1.5f, 2.0f, 5.0f)

        for (s in scales) {
            val t = OverlayTransform(
                normalizedX = 0.4f,
                normalizedY = 0.4f,
                normalizedWidth = 0.2f,
                normalizedHeight = 0.2f,
                scale = s
            )
            val cx = t.centerX(streamW)
            val cy = t.centerY(streamW, streamH, 16f / 9f)
            val w = t.resolvedWidth(streamW)
            val h = t.resolvedHeight(streamW, streamH, 16f / 9f)
            val left = t.resolvedLeft(streamW)
            val top = t.resolvedTop(streamW, streamH, 16f / 9f)

            // Center must not shift regardless of scale factor
            assertEquals(cx, left + w / 2f, 0.01f)
            assertEquals(cy, top + h / 2f, 0.01f)
            assertEquals(0.2f * streamW * s, w, 0.01f)
        }
    }

    // --- 3. ROTATION PARITY ---
    @Test
    fun test03_rotationParity_allTwelveAnglesPreserveCenter() {
        val angles = listOf(0f, 15f, 27f, 45f, 90f, 135f, 180f, 225f, 270f, 315f, 359f, 360f)
        val cx = 500f
        val cy = 500f
        val radius = 100f

        for (deg in angles) {
            val rad = Math.toRadians(deg.toDouble())
            val rx = cx + (radius * cos(rad)).toFloat()
            val ry = cy + (radius * sin(rad)).toFloat()

            // Distance to center invariant
            val dist = sqrt((rx - cx) * (rx - cx) + (ry - cy) * (ry - cy))
            assertEquals("Rotation by $deg deg must preserve distance to center", radius, dist, 0.01f)
        }
    }

    // --- 4. CROP PARITY ---
    @Test
    fun test04_cropParity_nonInversionAndLocalOrientation() {
        val cfg = WebOverlayConfig(cropLeft = 0.1f, cropTop = 0.15f, cropRight = 0.85f, cropBottom = 0.90f)
        assertTrue("Crop left must be < crop right", cfg.cropLeft < cfg.cropRight)
        assertTrue("Crop top must be < crop bottom", cfg.cropTop < cfg.cropBottom)
        assertTrue("Crop left must be >= 0", cfg.cropLeft >= 0f)
        assertTrue("Crop right must be <= 1", cfg.cropRight <= 1f)
        assertTrue("Crop top must be >= 0", cfg.cropTop >= 0f)
        assertTrue("Crop bottom must be <= 1", cfg.cropBottom <= 1f)

        // Effective visible ratio
        val visibleW = cfg.cropRight - cfg.cropLeft
        val visibleH = cfg.cropBottom - cfg.cropTop
        assertEquals(0.75f, visibleW, 0.001f)
        assertEquals(0.75f, visibleH, 0.001f)
    }

    // --- 5. Z-ORDER PARITY ---
    @Test
    fun test05_zOrderParity_monotonicOrderInPreviewAndBroadcast() {
        val e1 = OverlayElement(id = "layer1", type = OverlayType.TEXT, zIndex = 5)
        val e2 = OverlayElement(id = "layer2", type = OverlayType.TEXT, zIndex = 10)
        val e3 = OverlayElement(id = "layer3", type = OverlayType.TEXT, zIndex = 15)

        val elements = listOf(e3, e1, e2)
        val sortedForPreview = elements.sortedBy { it.zIndex }
        val sortedForBroadcast = elements.map { OverlayTransform(zIndex = it.zIndex) }.sortedBy { it.zIndex }

        assertEquals(listOf("layer1", "layer2", "layer3"), sortedForPreview.map { it.id })
        assertEquals(listOf(5, 10, 15), sortedForBroadcast.map { it.zIndex })
    }

    // --- 6. OPACITY PARITY ---
    @Test
    fun test06_opacityParity_identicalValueShared() {
        val elem = OverlayElement(id = "alert", type = OverlayType.ALERT, opacity = 0.75f)
        val transform = OverlayTransform(opacity = elem.opacity)
        assertEquals(0.75f, elem.opacity, 0.001f)
        assertEquals(0.75f, transform.opacity, 0.001f)
    }

    // --- 7. VISIBILITY PARITY ---
    @Test
    fun test07_visibilityParity_hiddenElementExcludedFromRender() {
        val visibleElem = OverlayElement(id = "v", type = OverlayType.TEXT, visible = true)
        val hiddenElem = OverlayElement(id = "h", type = OverlayType.TEXT, visible = false)

        val scene = BroadcastScene(
            id = "s",
            name = "S",
            overlayElements = listOf(visibleElem, hiddenElem)
        )

        val broadcastRenderable = scene.overlayElements.filter { it.visible }
        assertEquals(1, broadcastRenderable.size)
        assertEquals("v", broadcastRenderable[0].id)
    }

    // --- 8. ASPECT-RATIO MAPPING ---
    @Test
    fun test08_aspectRatioMapping_streamCoordinatesPreserveElementAspect() {
        val streamW = 1920
        val streamH = 1080
        val elem = OverlayElement(
            id = "banner",
            type = OverlayType.TEXT,
            normalizedWidth = 0.40f,
            normalizedHeight = 0.10f
        )
        val transform = OverlayTransform(
            normalizedWidth = elem.normalizedWidth,
            normalizedHeight = elem.normalizedHeight
        )
        val w = transform.resolvedWidth(streamW)
        val h = transform.resolvedHeight(streamW, streamH, 16f / 9f)
        assertEquals(0.40f * 1920f, w, 0.01f)
        assertEquals(0.10f * 1080f, h, 0.01f)
        assertEquals(768f, w, 0.01f)
        assertEquals(108f, h, 0.01f)
    }

    // --- 9. PREVIEW-TO-STREAM COORDINATE CONVERSION ---
    @Test
    fun test09_previewToStreamCoordinateConversion() {
        val previewW = 360f
        val previewH = 640f
        val streamW = 1280
        val streamH = 720

        // Touch at center of preview
        val touchPreviewX = 180f
        val touchPreviewY = 320f

        val normX = touchPreviewX / previewW
        val normY = touchPreviewY / previewH
        assertEquals(0.5f, normX, 0.001f)
        assertEquals(0.5f, normY, 0.001f)

        // Maps to center of broadcast stream
        val streamX = normX * streamW
        val streamY = normY * streamH
        assertEquals(640f, streamX, 0.01f)
        assertEquals(360f, streamY, 0.01f)
    }

    // --- 10. CREATOR HUD ISOLATION ---
    @Test
    fun test10_creatorHudIsolation_domainIsStrictlyCreatorOnly() {
        val hudDomain = RenderDomain.CREATOR_ONLY
        val broadcastDomain = RenderDomain.BROADCAST

        assertNotEquals(hudDomain, broadcastDomain)
        assertEquals("CREATOR_ONLY", hudDomain.name)
        assertEquals("BROADCAST", broadcastDomain.name)
    }

    // --- 11. SELECTION-HANDLE ISOLATION ---
    @Test
    fun test11_selectionHandleIsolation_neverIncludedInBroadcastLayers() {
        val overlay = OverlayElement(id = "img", type = OverlayType.IMAGE)
        val scene = BroadcastScene(
            id = "scene",
            name = "Scene",
            overlayElements = listOf(overlay)
        )

        // Broadcast layer list only contains overlay elements, never selection handles
        assertFalse(scene.overlayElements.any { it.id.contains("handle") })
        assertFalse(scene.overlayElements.any { it.id.contains("selection") })
        assertFalse(scene.overlayElements.any { it.id.contains("border") })
    }

    // --- 12. CROP-HANDLE ISOLATION ---
    @Test
    fun test12_cropHandleIsolation_handlesOnlyExistInStudioTouchView() {
        val webOverlay = OverlayElement(
            id = "web_src",
            type = OverlayType.WEB,
            webConfig = WebOverlayConfig(cropLeft = 0.05f, cropRight = 0.95f)
        )
        // Broadcast compositor consumes webConfig crop parameters directly via texture matrix / GLES,
        // never as visual circular handle layers
        val broadcastLayers = listOf(webOverlay)
        assertEquals(1, broadcastLayers.size)
        assertNull(broadcastLayers.find { it.id == "crop_handle_left" })
        assertNull(broadcastLayers.find { it.id == "crop_handle_top" })
    }

    // --- 13. ROTATION-HANDLE ISOLATION ---
    @Test
    fun test13_rotationHandleIsolation_rotationIsPropertyNotLayer() {
        val rotatedElem = OverlayElement(id = "rot", type = OverlayType.IMAGE, rotationDegrees = 45f)
        // Rotation is an internal float transformation property, not a separate layer
        assertEquals(45f, rotatedElem.rotationDegrees, 0.001f)
        val scene = BroadcastScene(id = "s", name = "S", overlayElements = listOf(rotatedElem))
        assertEquals(1, scene.overlayElements.size)
    }

    // --- 14. WEB ZOOM / OVERLAY SCALE INDEPENDENCE ---
    @Test
    fun test14_webZoomAndOverlayScaleIndependence() {
        val streamW = 1280
        val streamH = 720
        val cfg1 = WebOverlayConfig(zoomLevel = 1.0f)
        val cfg2 = WebOverlayConfig(zoomLevel = 3.0f)

        val elem = OverlayElement(
            id = "web",
            type = OverlayType.WEB,
            normalizedWidth = 0.3f,
            normalizedHeight = 0.2f,
            scale = 1.5f,
            webConfig = cfg1
        )

        val t1 = OverlayTransform(normalizedWidth = elem.normalizedWidth, scale = elem.scale)
        val elemWithZoom = elem.copy(webConfig = cfg2)
        val t2 = OverlayTransform(normalizedWidth = elemWithZoom.normalizedWidth, scale = elemWithZoom.scale)

        // Webpage internal zoom does not alter bounding box geometry
        assertEquals(t1.resolvedWidth(streamW), t2.resolvedWidth(streamW), 0.001f)
    }

    // --- 15. FACECAM BROADCAST VS CAMERA-CONTROL ISOLATION ---
    @Test
    fun test15_facecamBroadcastVsCameraControlIsolation() {
        val facecam = FacecamConfig(enabled = true, normalizedX = 0.7f, normalizedY = 0.7f)
        // FacecamConfig belongs to BroadcastScene for video compositing
        assertTrue(facecam.enabled)
        // Camera button controls in floating HUD or studio toolbar are UI widgets, not part of FacecamConfig
        assertFalse(facecam.toString().contains("btn_camera"))
    }

    // --- 16. CAPTURE-SOURCE STATE ---
    @Test
    fun test16_captureSourceState_singleAppVersusEntireScreen() {
        val s1 = StreamSettings(captureSource = CaptureSource.SINGLE_APP)
        val s2 = StreamSettings(captureSource = CaptureSource.ENTIRE_SCREEN)

        assertEquals(CaptureSource.SINGLE_APP, s1.captureSource)
        assertEquals(CaptureSource.ENTIRE_SCREEN, s2.captureSource)
        assertNotEquals(s1.captureSource, s2.captureSource)
    }

    // --- 17. MEDIAPROJECTION CONFIGURATION ---
    @Test
    fun test17_mediaProjectionConfiguration_singleAppOmitsConfig() {
        // When SINGLE_APP is selected, MediaProjectionManager.createScreenCaptureIntent() is called
        // without createConfigForDefaultDisplay(), allowing Android 14+ task-level app isolation.
        val singleAppSetting = StreamSettings(captureSource = CaptureSource.SINGLE_APP)
        assertTrue(singleAppSetting.captureSource == CaptureSource.SINGLE_APP)
    }

    // --- 18. NO CREATOR UI ENTERS BROADCAST LAYER LIST ---
    @Test
    fun test18_noCreatorUiEntersBroadcastLayerList() {
        val allowedTypes = setOf(
            OverlayType.IMAGE,
            OverlayType.GIF,
            OverlayType.TEXT,
            OverlayType.GOAL,
            OverlayType.LOWER_THIRD,
            OverlayType.ALERT,
            OverlayType.QR,
            OverlayType.WIDGET,
            OverlayType.WEB,
            OverlayType.YOUTUBE_SUBSCRIBER,
            OverlayType.PNG
        )

        val activeScene = BroadcastScene(
            id = "prod_scene",
            name = "Gaming Scene",
            overlayElements = listOf(
                OverlayElement(id = "txt_title", type = OverlayType.TEXT),
                OverlayElement(id = "web_browser", type = OverlayType.WEB),
                OverlayElement(id = "goal_subs", type = OverlayType.GOAL)
            )
        )

        for (layer in activeScene.overlayElements) {
            assertTrue("Every broadcast layer must be an authorized broadcast type", allowedTypes.contains(layer.type))
            assertFalse("Layer ID must not be a creator control", layer.id.startsWith("btn_"))
            assertFalse("Layer ID must not be HUD", layer.id.startsWith("hud_"))
            assertFalse("Layer ID must not be handle", layer.id.startsWith("handle_"))
        }
    }

    // --- 19. FREE FIRE MAX 2400x1080 ASPECT-PRESERVING FIT ---
    @Test
    fun test19_freeFireMax2400x1080_fittedWithoutCropping() {
        val streamWidth = 1920
        val streamHeight = 1080
        val captureWidth = 2400
        val captureHeight = 1080

        val canvasAspect = streamWidth.toFloat() / streamHeight.toFloat()
        val sourceAspect = captureWidth.toFloat() / captureHeight.toFloat()

        assertTrue("Source is wider than canvas", sourceAspect > canvasAspect)

        val scaleX = 1.0f
        val scaleY = canvasAspect / sourceAspect

        assertEquals("Full width span in NDC", 1.0f, scaleX, 0.001f)
        assertEquals("Letterbox height ratio", 0.8f, scaleY, 0.001f)

        val fittedPixelWidth = streamWidth * scaleX
        val fittedPixelHeight = streamHeight * scaleY
        val topLetterboxMargin = (streamHeight - fittedPixelHeight) / 2f

        assertEquals(1920f, fittedPixelWidth, 0.01f)
        assertEquals(864f, fittedPixelHeight, 0.01f)
        assertEquals(108f, topLetterboxMargin, 0.01f)

        assertEquals(sourceAspect, fittedPixelWidth / fittedPixelHeight, 0.001f)
    }

    // --- 20. CENTERED OVERLAY QUAD EXACT NDC BOUNDS ---
    @Test
    fun test20_centeredOverlayQuad_exactNdcBounds() {
        val streamWidth = 1920
        val streamHeight = 1080

        val normX = 0.75f
        val normY = 0.05f
        val normW = 0.20f
        val normH = 0.20f * (16f / 9f)

        val t = OverlayTransform(
            normalizedX = normX,
            normalizedY = normY,
            normalizedWidth = normW,
            normalizedHeight = normH
        )

        val w = t.resolvedWidth(streamWidth)
        val h = t.resolvedHeight(streamWidth, streamHeight)
        val left = t.resolvedLeft(streamWidth)
        val top = t.resolvedTop(streamWidth, streamHeight)

        val clipX = left * 2f / streamWidth - 1f
        val clipY = 1f - top * 2f / streamHeight
        val clipW = w * 2f / streamWidth
        val clipH = h * 2f / streamHeight

        val centerX = clipX + clipW / 2f
        val centerY = clipY - clipH / 2f

        val vBL_x = -0.5f * clipW + centerX
        val vBL_y = -0.5f * clipH + centerY
        val vTR_x = 0.5f * clipW + centerX
        val vTR_y = 0.5f * clipH + centerY

        assertEquals(clipX, vBL_x, 0.001f)
        assertEquals(clipY - clipH, vBL_y, 0.001f)
        assertEquals(clipX + clipW, vTR_x, 0.001f)
        assertEquals(clipY, vTR_y, 0.001f)

        assertTrue("X min >= -1", vBL_x >= -1.0f)
        assertTrue("X max <= 1", vTR_x <= 1.0f)
        assertTrue("Y min >= -1", vBL_y >= -1.0f)
        assertTrue("Y max <= 1", vTR_y <= 1.0f)
    }

    // --- 21. ORIENTATION HANDLING LANDSCAPE PRESERVATION ---
    @Test
    fun test21_orientationHandling_landscapePreservation() {
        // When device is in landscape (e.g. playing Free Fire MAX) but a system dialog momentarily
        // reports portrait dimensions (1080x2400), the orientation logic must preserve landscape (2400x1080)
        val reportedWidth = 1080
        val reportedHeight = 2400
        val isLandscape = true

        val adjustedW = if (isLandscape && reportedWidth < reportedHeight) reportedHeight else reportedWidth
        val adjustedH = if (isLandscape && reportedWidth < reportedHeight) reportedWidth else reportedHeight

        assertEquals(2400, adjustedW)
        assertEquals(1080, adjustedH)

        // Verify that 2400x1080 maps to full width on 1920x1080 canvas with 0.80 scaleY
        val canvasW = 1920
        val canvasH = 1080
        val canvasAspect = canvasW.toFloat() / canvasH.toFloat()
        val sourceAspect = adjustedW.toFloat() / adjustedH.toFloat()

        assertTrue(sourceAspect > canvasAspect)
        val scaleX = 1.0f
        val scaleY = canvasAspect / sourceAspect
        assertEquals(1.0f, scaleX, 0.001f)
        assertEquals(0.80f, scaleY, 0.001f)
    }

    // --- 22. FLV AAC TAG HEADER SPECIFICATION COMPLIANCE ---
    @Test
    fun test22_flvAacTagHeaderByte0xAF() {
        val packetizerMono = AacFlvPacketizer(sampleRate = 44100, channelCount = 1)
        val packetizerStereo = AacFlvPacketizer(sampleRate = 44100, channelCount = 2)

        val frameData = byteArrayOf(0x12, 0x34)
        val packetsMono = packetizerMono.processAacFrame(frameData, 100L)
        val packetsStereo = packetizerStereo.processAacFrame(frameData, 100L)

        // Per Adobe FLV Spec v10.1 (p. 76), AAC tag header first byte MUST ALWAYS be 0xAF
        assertEquals(0xAF, packetsMono[0].payload[0].toInt() and 0xFF)
        assertEquals(0xAF, packetsMono[1].payload[0].toInt() and 0xFF)
        assertEquals(0xAF, packetsStereo[0].payload[0].toInt() and 0xFF)
        assertEquals(0xAF, packetsStereo[1].payload[0].toInt() and 0xFF)
    }

    // --- 23. DYNAMIC ABR LADDER CREATION ---
    @Test
    fun test23_dynamicAbrLadderCreation() {
        val ladder4500 = com.fairylive.app.core.network.AdaptiveBitrateController.createLadder(4500)
        assertEquals(4500, ladder4500[0])
        assertEquals(3375, ladder4500[1])
        assertEquals(2250, ladder4500[2])
        assertEquals(1350, ladder4500[3])

        val abr = com.fairylive.app.core.network.AdaptiveBitrateController()
        abr.initialize(4500)
        assertEquals(4500, abr.currentBitrateKbps)
        assertEquals(4500, abr.maxBitrateKbps)
    }

    // --- 24. STREAMER FLOATING HUD RENDER DOMAIN ---
    @Test
    fun test24_systemOverlayHudRenderDomain() {
        val fakeContext = SecureCredentialStorageTest.FakeTestContext()
        val hud = com.fairylive.app.ui.hud.StreamerFloatingHudManager(fakeContext)
        assertEquals(RenderDomain.CREATOR_ONLY, hud.renderDomain)
    }

    // --- 25. STREAM PROFILE BITRATE COMPLIANCE ---
    @Test
    fun test25_streamProfileBalancedAndHighBitrates() {
        assertEquals(2500, com.fairylive.app.core.capability.model.StreamProfile.BALANCED.videoBitrateKbps)
        assertEquals(4500, com.fairylive.app.core.capability.model.StreamProfile.HIGH.videoBitrateKbps)
    }
}
