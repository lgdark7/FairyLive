package com.fairylive.app

import com.fairylive.app.core.capture.model.CaptureGeometryState
import com.fairylive.app.core.compositor.model.*
import com.fairylive.app.core.network.NetworkHealth
import com.fairylive.app.core.network.NetworkQualityMonitor
import org.junit.Assert.*
import org.junit.Test

class V122CaptureAndBitrateParityUnitTest {

    @Test
    fun test01_deterministicGamingSymmetricCropPolicy_20to9() {
        // Free Fire MAX source: 2400x1080 (20:9)
        // Broadcast canvas: 1920x1080 (16:9)
        val geometry = CaptureGeometryState.calculate(
            sourceWidth = 2400,
            sourceHeight = 1080,
            outputWidth = 1920,
            outputHeight = 1080
        )

        assertTrue(geometry.isLandscape)
        assertEquals(2400f / 1080f, geometry.sourceAspectRatio, 0.001f)
        assertEquals(1920f / 1080f, geometry.outputAspectRatio, 0.001f)

        // Symmetrical crop: 10% on left, 10% on right
        assertEquals(0.10f, geometry.cropLeft, 0.001f)
        assertEquals(0.10f, geometry.cropRight, 0.001f)
        assertEquals(0.0f, geometry.cropTop, 0.001f)
        assertEquals(0.0f, geometry.cropBottom, 0.001f)
        assertEquals(1.0f, geometry.scale, 0.001f)

        // Visible source width is 1920 source pixels (2400 * 0.80)
        assertEquals(1920f, geometry.visibleSourceWidth, 0.1f)
        assertEquals(1080f, geometry.visibleSourceHeight, 0.1f)
    }

    @Test
    fun test01b_deterministicScaleModeFit_20to9() {
        val geometry = CaptureGeometryState.calculate(
            sourceWidth = 2400,
            sourceHeight = 1080,
            outputWidth = 1920,
            outputHeight = 1080,
            scaleMode = com.fairylive.app.core.capture.model.ScaleMode.FIT
        )

        assertTrue(geometry.isLandscape)
        // Zero cropping in FIT mode
        assertEquals(0.0f, geometry.cropLeft, 0.001f)
        assertEquals(0.0f, geometry.cropRight, 0.001f)
        assertEquals(0.0f, geometry.cropTop, 0.001f)
        assertEquals(0.0f, geometry.cropBottom, 0.001f)
        assertEquals(1.0f, geometry.scaleX, 0.001f)
        // Letterboxed vertically to preserve full 20:9 aspect ratio
        val expectedScaleY = (1920f / 1080f) / (2400f / 1080f) // 1920 / 2400 = 0.80
        assertEquals(expectedScaleY, geometry.scaleY, 0.001f)
        assertEquals(2400f, geometry.visibleSourceWidth, 0.1f)
        assertEquals(1080f, geometry.visibleSourceHeight, 0.1f)
    }

    @Test
    fun test02_deterministicGamingCropPolicy_4to3() {
        // 4:3 source (e.g. 1440x1080)
        // Output: 1920x1080 (16:9)
        val geometry = CaptureGeometryState.calculate(
            sourceWidth = 1440,
            sourceHeight = 1080,
            outputWidth = 1920,
            outputHeight = 1080
        )

        assertTrue(geometry.isLandscape)
        // Excess vertical cropped symmetrically
        assertEquals(0.0f, geometry.cropLeft, 0.001f)
        assertEquals(0.0f, geometry.cropRight, 0.001f)
        val expectedCropY = (1.0f - (1440f / 1080f) / (1920f / 1080f)) / 2.0f // (1 - 0.75) / 2 = 0.125
        assertEquals(expectedCropY, geometry.cropTop, 0.001f)
        assertEquals(expectedCropY, geometry.cropBottom, 0.001f)
    }

    @Test
    fun test03_deterministicPortraitPillarboxPolicy() {
        // Portrait phone launcher: 1080x2400
        // Output: 1920x1080 (16:9)
        val geometry = CaptureGeometryState.calculate(
            sourceWidth = 1080,
            sourceHeight = 2400,
            outputWidth = 1920,
            outputHeight = 1080
        )

        assertFalse(geometry.isLandscape)
        assertEquals(0.0f, geometry.cropLeft, 0.001f)
        assertEquals(0.0f, geometry.cropRight, 0.001f)
        assertEquals(0.0f, geometry.cropTop, 0.001f)
        assertEquals(0.0f, geometry.cropBottom, 0.001f)
        // Pillarbox scale
        val expectedScale = (1080f / 2400f) / (1920f / 1080f) // 0.45 / 1.7777 = 0.253125
        assertEquals(expectedScale, geometry.scale, 0.001f)
    }

    @Test
    fun test04_appSwitchSimulation5Cycles_noCollapse() {
        // Simulates 5 consecutive cycles of switching between Free Fire MAX and another app
        for (cycle in 1..5) {
            // 1. Free Fire MAX in foreground
            val ffState = CaptureGeometryState.calculate(2400, 1080, 1920, 1080, rotation = 1, visibility = true)
            assertEquals("Cycle $cycle Free Fire scale must be 1.0", 1.0f, ffState.scale, 0.001f)
            assertEquals("Cycle $cycle Free Fire cropLeft must be 0.10", 0.10f, ffState.cropLeft, 0.001f)
            assertEquals("Cycle $cycle Free Fire cropRight must be 0.10", 0.10f, ffState.cropRight, 0.001f)
            assertTrue("Cycle $cycle Free Fire must be landscape", ffState.isLandscape)

            // 2. Switch away to portrait app / launcher
            val portraitState = CaptureGeometryState.calculate(1080, 2400, 1920, 1080, rotation = 0, visibility = false)
            assertFalse("Cycle $cycle Launcher is portrait", portraitState.isLandscape)

            // 3. Return to Free Fire MAX
            val restoredState = CaptureGeometryState.calculate(2400, 1080, 1920, 1080, rotation = 1, visibility = true)
            assertEquals("Cycle $cycle Restored scale must be 1.0", 1.0f, restoredState.scale, 0.001f)
            assertEquals("Cycle $cycle Restored cropLeft must be 0.10", 0.10f, restoredState.cropLeft, 0.001f)
            assertEquals("Cycle $cycle Restored cropRight must be 0.10", 0.10f, restoredState.cropRight, 0.001f)
            assertEquals("Cycle $cycle Restored visible width must be 1920px", 1920f, restoredState.visibleSourceWidth, 0.1f)
            assertEquals("Cycle $cycle Restored visible height must be 1080px", 1080f, restoredState.visibleSourceHeight, 0.1f)
        }
    }

    @Test
    fun test05_overlayCoordinateConversion_sourceToOutput() {
        // Free Fire 2400x1080 source -> 1920x1080 broadcast
        val geometry = CaptureGeometryState.calculate(2400, 1080, 1920, 1080)

        // Test coordinate: x = 1600 out of 2400, y = 540 out of 1080
        // In source, cropLeft is 240px (10% of 2400).
        // The visible span in source is 240..2160 (width = 1920).
        // Expected output X: (1600 - 240) / 1920 = 1360 / 1920 = 0.7083333...
        // Expected output Y: 540 / 1080 = 0.50
        val (normOutX, normOutY) = OverlayCoordinateConverter.sourceToNormalizedOutput(1600f, 540f, geometry)
        assertEquals(1360f / 1920f, normOutX, 0.001f)
        assertEquals(0.50f, normOutY, 0.001f)

        // Round trip conversion
        val (roundTripX, roundTripY) = OverlayCoordinateConverter.normalizedOutputToSource(normOutX, normOutY, geometry)
        assertEquals(1600f, roundTripX, 0.01f)
        assertEquals(540f, roundTripY, 0.01f)
    }

    @Test
    fun test06_overlayTransform_imageTextMoveResizeRotate() {
        val geometry = CaptureGeometryState.calculate(2400, 1080, 1920, 1080)

        // 1. IMAGE overlay placed at 1600/2400
        val (imgNormX, imgNormY) = OverlayCoordinateConverter.sourceToNormalizedOutput(1600f, 200f, geometry)
        val imgElem = OverlayElement.createImage(
            id = "test_img",
            resourcePath = "badges/mvp.png",
            x = imgNormX,
            y = imgNormY,
            width = 0.20f
        )
        val imgTransform = OverlayTransform.fromElement(imgElem)
        assertEquals(imgNormX, imgTransform.normalizedX, 0.001f)
        assertEquals(imgNormY, imgTransform.normalizedY, 0.001f)
        assertEquals(0.20f * 1920f, imgTransform.resolvedWidth(1920), 0.1f)

        // 2. TEXT overlay
        val textElem = OverlayElement.createText(
            id = "test_txt",
            config = TextOverlayConfig(text = "VICTORY!"),
            x = 0.10f,
            y = 0.80f,
            width = 0.30f
        )
        val textTransform = OverlayTransform.fromElement(textElem)
        assertEquals(0.10f, textTransform.normalizedX, 0.001f)
        assertEquals(0.80f, textTransform.normalizedY, 0.001f)

        // 3. MOVE
        val movedElem = textElem.copy(normalizedX = 0.50f, normalizedY = 0.50f)
        val movedTransform = OverlayTransform.fromElement(movedElem)
        assertEquals(0.50f, movedTransform.normalizedX, 0.001f)
        assertEquals(0.50f, movedTransform.normalizedY, 0.001f)

        // 4. RESIZE
        val resizedElem = movedElem.copy(scale = 1.5f, normalizedWidth = 0.40f)
        val resizedTransform = OverlayTransform.fromElement(resizedElem)
        assertEquals(1.5f, resizedTransform.scale, 0.001f)
        assertEquals(0.40f * 1920f * 1.5f, resizedTransform.resolvedWidth(1920), 0.1f)

        // 5. ROTATE
        val rotatedElem = resizedElem.copy(rotationDegrees = 45f)
        val rotatedTransform = OverlayTransform.fromElement(rotatedElem)
        assertEquals(45f, rotatedTransform.rotationDegrees, 0.001f)
    }

    @Test
    fun test07_networkQualityMonitor_lowThroughputWithoutCongestionStaysHealthy() {
        val monitor = NetworkQualityMonitor()
        monitor.setNetworkStateForTesting(available = true, transport = com.fairylive.app.core.network.NetworkTransport.WIFI)
        monitor.setRtmpConnected(true)

        // Send 20KB over 1000ms with fast 5ms write latency and 0 queue depth
        // Throughput = 160 kbps (previously triggered CRITICAL due to lastCalculatedThroughputBps in 1..250_000L)
        monitor.recordPacketWrite(
            bytesWritten = 20000,
            writeDurationMs = 5L,
            queueDepth = 0,
            queueMaxCapacity = 100,
            droppedCount = 0L,
            nowElapsedMs = 1000L
        )

        // Health must remain HEALTHY/EXCELLENT because there is NO congestion signal
        assertNotEquals("Low throughput alone must NEVER trigger CRITICAL when queue is empty", NetworkHealth.CRITICAL, monitor.currentHealth)
        assertNotEquals("Low throughput alone must NEVER trigger POOR when queue is empty", NetworkHealth.POOR, monitor.currentHealth)
        assertTrue(monitor.currentHealth == NetworkHealth.HEALTHY || monitor.currentHealth == NetworkHealth.EXCELLENT)
    }

    @Test
    fun test08_networkQualityMonitor_realCongestionTriggersDegradedPoorCritical() {
        val monitor = NetworkQualityMonitor()
        monitor.setNetworkStateForTesting(available = true, transport = com.fairylive.app.core.network.NetworkTransport.WIFI)
        monitor.setRtmpConnected(true)

        // 1. Moderate latency (180ms) -> DEGRADED
        monitor.recordPacketWrite(
            bytesWritten = 2000,
            writeDurationMs = 180L,
            queueDepth = 10,
            queueMaxCapacity = 100,
            droppedCount = 0L,
            nowElapsedMs = 1000L
        )
        assertEquals(NetworkHealth.DEGRADED, monitor.currentHealth)

        // 2. Three consecutive slow writes -> POOR
        monitor.recordPacketWrite(2000, 200L, 15, 100, 0L, 1050L)
        monitor.recordPacketWrite(2000, 210L, 20, 100, 0L, 1100L)
        assertEquals(NetworkHealth.POOR, monitor.currentHealth)

        // 3. Queue backpressure (>= 75%) or severe latency (>= 500ms) -> CRITICAL
        monitor.recordPacketWrite(2000, 600L, 80, 100, 0L, 1150L)
        assertEquals(NetworkHealth.CRITICAL, monitor.currentHealth)
    }
}
