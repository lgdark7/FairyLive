package com.fairylive.app

import android.util.Range
import com.fairylive.app.core.camera.CameraCapabilityDetector
import com.fairylive.app.core.camera.CameraMetrics
import com.fairylive.app.core.camera.CameraState
import com.fairylive.app.core.camera.CameraStateNotifier
import com.fairylive.app.core.camera.model.CameraDeviceInfo
import com.fairylive.app.core.camera.model.CameraFacing
import com.fairylive.app.core.camera.model.CameraSize
import com.fairylive.app.core.camera.model.FacecamConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Unit test suite for M10: Facecam Configuration, Camera State Machine,
 * Coordinate Mathematics, and Sensor Aspect Resolution.
 */
class CameraIntegrationTest {

    @Before
    fun setUp() {
        CameraStateNotifier.reset()
    }

    @Test
    fun testFacecamConfigDefaults() {
        val config = FacecamConfig()
        assertFalse("Facecam must default to disabled", config.enabled)
        assertEquals(CameraFacing.FRONT, config.facing)
        assertEquals(0.70f, config.normalizedX, 0.001f)
        assertEquals(0.05f, config.normalizedY, 0.001f)
        assertEquals(0.26f, config.normalizedWidth, 0.001f)
        assertEquals(0.26f, config.normalizedHeight, 0.001f)
        assertEquals(1.0f, config.scale, 0.001f)
        assertEquals(1.0f, config.opacity, 0.001f)
        assertTrue("Front camera must default to horizontal mirror", config.mirrorHorizontal)
        assertTrue("Must default to crop to aspect", config.cropToAspect)
        assertEquals(1.0f, config.aspectRatio, 0.001f)
        assertEquals("Facecam PIP must sit at zIndex 5", 5, config.zIndex)
    }

    @Test
    fun testFacecamConfigSanitization() {
        val invalid = FacecamConfig(
            normalizedX = -0.5f,
            normalizedY = 1.8f,
            normalizedWidth = 0.01f, // below minimum 0.05
            normalizedHeight = 2.0f,
            scale = 10.0f,          // above max 5.0
            opacity = -0.2f
        )
        val clean = invalid.sanitized()
        assertEquals(0.0f, clean.normalizedX, 0.001f)
        assertEquals(1.0f, clean.normalizedY, 0.001f)
        assertEquals(0.05f, clean.normalizedWidth, 0.001f)
        assertEquals(1.0f, clean.normalizedHeight, 0.001f)
        assertEquals(5.0f, clean.scale, 0.001f)
        assertEquals(0.0f, clean.opacity, 0.001f)
    }

    @Test
    fun testFacecamResolvedPixelGeometry() {
        val config = FacecamConfig(
            normalizedX = 0.70f,
            normalizedY = 0.05f,
            normalizedWidth = 0.25f,
            cropToAspect = true,
            aspectRatio = 1.0f // Square PIP
        )
        val streamW = 720
        val streamH = 1280

        val left = config.resolvedLeft(streamW)
        val top = config.resolvedTop(streamH)
        val w = config.resolvedWidth(streamW)
        val h = config.resolvedHeight(streamW, streamH)

        assertEquals(504f, left, 0.01f) // 0.70 * 720
        assertEquals(64f, top, 0.01f)   // 0.05 * 1280
        assertEquals(180f, w, 0.01f)   // 0.25 * 720
        assertEquals(180f, h, 0.01f)   // 180 / 1.0 = 180 (square)
    }

    @Test
    fun testFacecamFourByThreeAspectRatio() {
        val config = FacecamConfig(
            normalizedWidth = 0.40f,
            cropToAspect = true,
            aspectRatio = 4f / 3f
        )
        val streamW = 1080
        val streamH = 1920

        val w = config.resolvedWidth(streamW)
        val h = config.resolvedHeight(streamW, streamH)

        assertEquals(432f, w, 0.01f)   // 0.40 * 1080 = 432
        assertEquals(324f, h, 0.01f)   // 432 / (4/3) = 324
    }

    @Test
    fun testCameraStateNotifierTransitions() {
        val observedStates = mutableListOf<CameraState>()
        CameraStateNotifier.addStateListener { new, _ ->
            observedStates.add(new)
        }

        CameraStateNotifier.updateState(CameraState.OPENING)
        CameraStateNotifier.updateState(CameraState.STREAMING)
        CameraStateNotifier.updateState(CameraState.SWITCHING)
        CameraStateNotifier.updateState(CameraState.STREAMING)
        CameraStateNotifier.updateState(CameraState.STOPPING)
        CameraStateNotifier.updateState(CameraState.IDLE)

        assertEquals(
            listOf(
                CameraState.OPENING,
                CameraState.STREAMING,
                CameraState.SWITCHING,
                CameraState.STREAMING,
                CameraState.STOPPING,
                CameraState.IDLE
            ),
            observedStates
        )
    }

    @Test
    fun testCameraMetricsEmission() {
        var lastMetrics: CameraMetrics? = null
        CameraStateNotifier.addMetricsListener {
            lastMetrics = it
        }

        CameraStateNotifier.updateMetrics(
            CameraMetrics(
                framesCaptured = 150L,
                currentFps = 29.8,
                activeCameraId = "1",
                facing = CameraFacing.FRONT,
                errorCount = 0L
            )
        )

        assertNotNull(lastMetrics)
        assertEquals(150L, lastMetrics!!.framesCaptured)
        assertEquals(29.8, lastMetrics!!.currentFps, 0.01)
        assertEquals("1", lastMetrics!!.activeCameraId)
        assertEquals(CameraFacing.FRONT, lastMetrics!!.facing)
    }

    @Test
    fun testOptimalSizeSelectionConservesGpuBandwidth() {
        val supportedSizes = listOf(
            CameraSize(3840, 2160), // 4K - too large for PIP
            CameraSize(1920, 1080), // 1080p
            CameraSize(1280, 720),  // 720p - optimal balance
            CameraSize(640, 480),   // 480p - preferred low-overhead
            CameraSize(320, 240)    // 240p
        )
        val cameraInfo = CameraDeviceInfo(
            cameraId = "0",
            facing = CameraFacing.FRONT,
            sensorOrientation = 270,
            supportedSizes = supportedSizes,
            supportedFpsRanges = listOf(Range(15, 30), Range(30, 30)),
            hardwareLevel = 1,
            isFrontFacing = true
        )

        // Target: 640x480 for PIP
        val optimal = CameraCapabilityDetector.getOptimalSize(cameraInfo, 640, 480)
        assertEquals(640, optimal.width)
        assertEquals(480, optimal.height)

        // Target: 1280x720
        val optimal720 = CameraCapabilityDetector.getOptimalSize(cameraInfo, 1280, 720)
        assertEquals(1280, optimal720.width)
        assertEquals(720, optimal720.height)
    }
}
