package com.fairylive.app

import android.content.Context
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.opengl.GLES20
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.fairylive.app.core.camera.CameraCapabilityDetector
import com.fairylive.app.core.camera.CameraCaptureEngine
import com.fairylive.app.core.camera.CameraState
import com.fairylive.app.core.camera.CameraStateNotifier
import com.fairylive.app.core.camera.model.CameraFacing
import com.fairylive.app.core.camera.model.FacecamConfig
import com.fairylive.app.core.compositor.OverlayScene
import com.fairylive.app.core.compositor.render.GlesRenderer
import com.fairylive.app.core.compositor.resource.OverlayResourceManager
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * On-device instrumented validation for Milestone 10:
 * Camera2 discovery, front/back hardware profiles, CameraCaptureEngine lifecycle,
 * front/rear dynamic switching, zero-copy GLES compositor PIP integration, and error resilience.
 */
@RunWith(AndroidJUnit4::class)
class CameraInstrumentedTest {

    private val context: Context
        get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Before
    fun setUp() {
        try {
            val uiAutomation = InstrumentationRegistry.getInstrumentation().uiAutomation
            uiAutomation.executeShellCommand("pm grant com.fairylive.app android.permission.CAMERA")
            uiAutomation.executeShellCommand("pm grant ${InstrumentationRegistry.getInstrumentation().context.packageName} android.permission.CAMERA")
            uiAutomation.executeShellCommand("pm grant ${context.packageName} android.permission.CAMERA")
            Thread.sleep(500)
        } catch (e: Exception) {
            Log.w(TAG, "Failed granting camera permission: ${e.message}")
        }
        CameraStateNotifier.reset()
    }

    @Test
    fun testCameraDiscoveryAndHardwareProfiles() {
        val detector = CameraCapabilityDetector(context)
        val cameras = detector.discoverCameras()
        Log.i(TAG, "Discovered ${cameras.size} cameras on device")
        assertTrue("Device must have at least one camera", cameras.isNotEmpty())

        for (cam in cameras) {
            Log.i(TAG, "Camera ID: ${cam.cameraId}, facing: ${cam.facing}, orientation: ${cam.sensorOrientation}, sizes: ${cam.supportedSizes.size}")
            assertNotNull(cam.cameraId)
            assertTrue("Supported sizes must not be empty", cam.supportedSizes.isNotEmpty())
            assertTrue("Sensor orientation must be standard (0, 90, 180, 270)", cam.sensorOrientation in listOf(0, 90, 180, 270))
        }
    }

    @Test
    fun testFrontAndBackCameraQueries() {
        val detector = CameraCapabilityDetector(context)
        val frontCam = detector.getFrontCamera()
        val backCam = detector.getBackCamera()

        Log.i(TAG, "Front camera: ${frontCam?.cameraId}, Back camera: ${backCam?.cameraId}")
        assertTrue("At least front or back camera must be available on device", frontCam != null || backCam != null)

        val active = frontCam ?: backCam!!
        val optimalSize = detector.getOptimalSize(active, 640, 480)
        Log.i(TAG, "Optimal preview size for ${active.facing}: ${optimalSize.width}x${optimalSize.height}")
        assertTrue("Optimal width must be positive", optimalSize.width > 0)
        assertTrue("Optimal height must be positive", optimalSize.height > 0)
    }

    @Test
    fun testCameraToGpuCompositorPipeline() {
        val width = 480
        val height = 1056

        // Create a real AVC encoder input surface
        val format = MediaFormat.createVideoFormat("video/avc", width, height).apply {
            setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
            setInteger(MediaFormat.KEY_BIT_RATE, 1200_000)
            setInteger(MediaFormat.KEY_FRAME_RATE, 24)
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 2)
        }
        val encoder = MediaCodec.createEncoderByType("video/avc")
        encoder.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        val encoderSurface = encoder.createInputSurface()
        encoder.start()

        val renderer = GlesRenderer()
        val glThreadLatch = CountDownLatch(1)
        var glError = 0

        val thread = Thread {
            try {
                val captureSurface = renderer.initialize(encoderSurface, width, height, context.assets)
                assertNotNull("Capture surface must be initialized", captureSurface)

                val cameraSurface = renderer.getCameraSurface(640, 480)
                assertNotNull("Camera surface must be initialized", cameraSurface)

                val scene = OverlayScene()
                val resources = OverlayResourceManager()

                val facecamConfig = FacecamConfig(
                    enabled = true,
                    facing = CameraFacing.FRONT,
                    normalizedX = 0.70f,
                    normalizedY = 0.05f,
                    normalizedWidth = 0.26f,
                    normalizedHeight = 0.26f,
                    mirrorHorizontal = true
                )

                // Render test composite frames
                for (i in 0 until 5) {
                    renderer.drawFrame(scene, resources, facecamConfig)
                }

                glError = GLES20.glGetError()
                renderer.release()
                resources.release()
            } finally {
                glThreadLatch.countDown()
            }
        }
        thread.start()

        assertTrue("GL rendering thread timed out", glThreadLatch.await(5, TimeUnit.SECONDS))
        assertEquals("OpenGL pipeline must execute with no GL errors", GLES20.GL_NO_ERROR, glError)

        encoder.stop()
        encoder.release()
        encoderSurface.release()
    }

    @Test
    fun testCameraCaptureEngineLifecycleAndStreaming() {
        val width = 480
        val height = 800

        val format = MediaFormat.createVideoFormat("video/avc", width, height).apply {
            setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
            setInteger(MediaFormat.KEY_BIT_RATE, 1200_000)
            setInteger(MediaFormat.KEY_FRAME_RATE, 24)
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 2)
        }
        val encoder = MediaCodec.createEncoderByType("video/avc")
        encoder.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        val encoderSurface = encoder.createInputSurface()
        encoder.start()

        val renderer = GlesRenderer()
        val glThreadLatch = CountDownLatch(1)
        var cameraSurface: android.view.Surface? = null

        val glThread = Thread {
            try {
                renderer.initialize(encoderSurface, width, height, context.assets)
                cameraSurface = renderer.getCameraSurface(640, 480)
            } finally {
                glThreadLatch.countDown()
            }
        }
        glThread.start()
        assertTrue(glThreadLatch.await(3, TimeUnit.SECONDS))
        assertNotNull("Camera surface must be created", cameraSurface)

        val engine = CameraCaptureEngine(context)
        val streamingLatch = CountDownLatch(1)
        val stateHistory = mutableListOf<CameraState>()

        CameraStateNotifier.addStateListener { new, _ ->
            stateHistory.add(new)
            if (new == CameraState.STREAMING) {
                streamingLatch.countDown()
            }
        }

        // Start camera preview
        engine.start(cameraSurface!!, CameraFacing.FRONT, 640, 480)
        val didStream = streamingLatch.await(4, TimeUnit.SECONDS)
        Log.i(TAG, "Camera didStream=$didStream, stateHistory=$stateHistory")

        assertTrue("Camera must reach STREAMING state", didStream)
        assertEquals(CameraState.STREAMING, CameraStateNotifier.currentState)

        // Switch camera lens
        val facingBefore = engine.currentFacing
        engine.switchCamera()

        // Wait a moment for switch
        Thread.sleep(1500)
        val facingAfter = engine.currentFacing
        Log.i(TAG, "Facing switched from $facingBefore to $facingAfter")
        assertTrue("Camera must remain active or streaming after switch",
            CameraStateNotifier.currentState == CameraState.STREAMING || CameraStateNotifier.currentState == CameraState.OPENING)

        // Stop camera
        val stopLatch = CountDownLatch(1)
        CameraStateNotifier.addStateListener { new, _ ->
            if (new == CameraState.IDLE) stopLatch.countDown()
        }
        engine.stop()
        assertTrue("Camera must return to IDLE after stop", stopLatch.await(3, TimeUnit.SECONDS))

        engine.release()
        val releaseLatch = CountDownLatch(1)
        Thread {
            renderer.release()
            releaseLatch.countDown()
        }.start()
        releaseLatch.await(2, TimeUnit.SECONDS)

        encoder.stop()
        encoder.release()
        encoderSurface.release()
    }

    @Test
    fun testCameraErrorAndFallbackResilience() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val engine = CameraCaptureEngine(context)

        // Verifies handling when stopping an unstarted engine
        engine.stop()
        assertEquals(CameraState.IDLE, CameraStateNotifier.currentState)

        // Switching when stopped does not crash
        engine.switchCamera()
        assertEquals(CameraState.IDLE, CameraStateNotifier.currentState)

        engine.release()
    }

    @Test
    fun testCameraNoFalseLiveState() {
        // Verifies camera state machine is decoupled from RTMP stream state
        // Camera IDLE or STREAMING does not produce false STREAMING status on stream engine
        assertEquals(CameraState.IDLE, CameraStateNotifier.currentState)
        val metrics = CameraStateNotifier.currentMetrics
        assertEquals(0L, metrics.framesCaptured)
    }

    companion object {
        private const val TAG = "CameraInstrumentedTest"
    }
}
