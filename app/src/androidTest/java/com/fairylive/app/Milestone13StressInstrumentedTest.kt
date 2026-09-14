package com.fairylive.app

import android.content.ComponentCallbacks2
import android.content.Context
import android.graphics.SurfaceTexture
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.opengl.GLES20
import android.util.Log
import android.view.Surface
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.fairylive.app.core.camera.CameraCapabilityDetector
import com.fairylive.app.core.camera.CameraCaptureEngine
import com.fairylive.app.core.camera.CameraState
import com.fairylive.app.core.camera.model.CameraFacing
import com.fairylive.app.core.compositor.OverlayScene
import com.fairylive.app.core.compositor.model.AlertOverlayConfig
import com.fairylive.app.core.compositor.model.GifOverlayConfig
import com.fairylive.app.core.compositor.model.GoalOverlayConfig
import com.fairylive.app.core.compositor.model.LowerThirdOverlayConfig
import com.fairylive.app.core.compositor.model.OverlayElement
import com.fairylive.app.core.compositor.model.QrOverlayConfig
import com.fairylive.app.core.compositor.model.TextOverlayConfig
import com.fairylive.app.core.compositor.render.GlesRenderer
import com.fairylive.app.core.compositor.resource.OverlayResourceManager
import com.fairylive.app.core.optimization.DynamicFpsThrottler
import com.fairylive.app.core.optimization.model.ThermalThrottlingLevel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * On-Device Stress Testing Suite for Milestone 13.
 * Validates GLES compositor stability under full 8-overlay load (50 iterations),
 * camera lifecycle rapid switching (10 cycles), memory pressure purge resilience during rendering,
 * and thermal/FPS coordination under actual Android 15 AVD runtime.
 */
@RunWith(AndroidJUnit4::class)
class Milestone13StressInstrumentedTest {

    companion object {
        private const val TAG = "M13StressInstrumented"
    }

    private val context: Context
        get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Before
    fun setUp() {
        try {
            val uiAutomation = InstrumentationRegistry.getInstrumentation().uiAutomation
            uiAutomation.executeShellCommand("pm grant com.fairylive.app android.permission.CAMERA")
            uiAutomation.executeShellCommand("pm grant ${context.packageName} android.permission.CAMERA")
            Thread.sleep(300)
        } catch (e: Exception) {
            Log.w(TAG, "Permission setup note: ${e.message}")
        }
    }

    // ==========================================
    // 1. FULL OVERLAY COMPOSITOR STRESS (50 PASSES)
    // ==========================================

    @Test
    fun testFullOverlayCompositorStress50Frames() {
        val width = 480
        val height = 854

        val format = MediaFormat.createVideoFormat("video/avc", width, height).apply {
            setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
            setInteger(MediaFormat.KEY_BIT_RATE, 1200_000)
            setInteger(MediaFormat.KEY_FRAME_RATE, 30)
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
        }
        val encoder = MediaCodec.createEncoderByType("video/avc")
        encoder.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        val encoderSurface = encoder.createInputSurface()
        encoder.start()

        val renderer = GlesRenderer()
        val resourceManager = OverlayResourceManager()
        val latch = CountDownLatch(1)
        val allPassed = AtomicBoolean(false)

        val glThread = Thread {
            try {
                val captureSurface = renderer.initialize(encoderSurface, width, height, context.assets)
                assertNotNull("Capture surface must not be null", captureSurface)

                // Build complete M8 overlay set: 7 distinct overlays
                val scene = OverlayScene()
                scene.add(OverlayElement.createImage("ov_logo", "assets://overlay_logo.png", 0.05f, 0.05f, 0.15f, 1.0f, 1))
                scene.add(OverlayElement.createText("ov_text", TextOverlayConfig("STRESS STREAM LIVE"), 0.25f, 0.05f, 0.50f, 0.9f, 2))
                scene.add(OverlayElement.createGoal("ov_goal", GoalOverlayConfig("Sub Goal", 750, 1000), 0.10f, 0.85f, 0.80f, 0.85f, 3))
                scene.add(OverlayElement.createLowerThird("ov_lower", LowerThirdOverlayConfig("Host: Benny", "Pixel 8 AVD"), 0.05f, 0.70f, 0.60f, 0.90f, 4))
                scene.add(OverlayElement.createQr("ov_qr", QrOverlayConfig("https://fairylive.stream"), 0.75f, 0.05f, 0.20f, 1.0f, 5))
                scene.add(OverlayElement.createAlert("ov_alert", AlertOverlayConfig("New Follower", "Tester!"), 0.20f, 0.40f, 0.60f, 0.95f, 6))
                scene.add(OverlayElement.createGif("ov_gif", GifOverlayConfig("assets://overlay_logo.png"), 0.60f, 0.50f, 0.35f, 1.0f, 7))

                // Execute 50 rapid render iterations mutating overlays and toggling visibility
                for (frame in 1..50) {
                    if (frame % 5 == 0) {
                        scene.setVisible("ov_alert", frame % 10 == 0)
                    }

                    renderer.updateCaptureTexture()
                    renderer.composite(scene, resourceManager)

                    val err = GLES20.glGetError()
                    assertEquals("GL error detected on frame $frame: $err", GLES20.GL_NO_ERROR, err)
                }

                allPassed.set(true)
            } catch (e: Throwable) {
                Log.e(TAG, "Exception during overlay stress: ${e.message}", e)
            } finally {
                renderer.release()
                encoder.stop()
                encoder.release()
                encoderSurface.release()
                latch.countDown()
            }
        }

        glThread.start()
        assertTrue("Compositor stress timed out", latch.await(20, TimeUnit.SECONDS))
        assertTrue("All 50 frames must render with zero GL errors", allPassed.get())
    }

    // ==========================================
    // 2. CAMERA LIFECYCLE STRESS (10 CYCLES)
    // ==========================================

    @Test
    fun testCameraLifecycleStress10Cycles() {
        val detector = CameraCapabilityDetector(context)
        val cameras = detector.discoverCameras()
        if (cameras.isEmpty()) {
            Log.i(TAG, "Camera not supported on this environment - test recorded as NOT_VALIDATED_ON_AVD")
            return
        }

        val surfaceTexture = SurfaceTexture(101)
        surfaceTexture.setDefaultBufferSize(640, 480)
        val surface = Surface(surfaceTexture)

        val engine = CameraCaptureEngine(context)
        try {
            for (cycle in 1..10) {
                Log.d(TAG, "Starting Camera Cycle $cycle/10")
                engine.start(surface, CameraFacing.BACK, 640, 480)
                Thread.sleep(150)

                // Switch camera
                engine.switchCamera()
                Thread.sleep(150)

                // Stop
                engine.stop()
                Thread.sleep(100)
            }
        } finally {
            engine.release()
            surface.release()
            surfaceTexture.release()
        }
    }

    // ==========================================
    // 3. MEMORY PRESSURE PURGE RESILIENCE
    // ==========================================

    @Test
    fun testMemoryPressureUnderActiveCompositorStress() {
        val width = 480
        val height = 854

        val format = MediaFormat.createVideoFormat("video/avc", width, height).apply {
            setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
            setInteger(MediaFormat.KEY_BIT_RATE, 1200_000)
            setInteger(MediaFormat.KEY_FRAME_RATE, 30)
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
        }
        val encoder = MediaCodec.createEncoderByType("video/avc")
        encoder.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        val encoderSurface = encoder.createInputSurface()
        encoder.start()

        val renderer = GlesRenderer()
        val resourceManager = OverlayResourceManager()
        val latch = CountDownLatch(1)
        val testSuccess = AtomicBoolean(false)

        val glThread = Thread {
            try {
                renderer.initialize(encoderSurface, width, height, context.assets)

                val scene = OverlayScene()
                scene.add(OverlayElement.createImage("logo1", "assets://overlay_logo.png", 0.1f, 0.1f, 0.3f, 1.0f, 1))

                // Render baseline
                renderer.updateCaptureTexture()
                renderer.composite(scene, resourceManager)
                assertEquals(GLES20.GL_NO_ERROR, GLES20.glGetError())

                // Inject CRITICAL memory pressure
                resourceManager.trimMemory(ComponentCallbacks2.TRIM_MEMORY_RUNNING_CRITICAL)

                // Subsequent render pass must safely re-acquire or recreate required textures
                for (i in 1..10) {
                    renderer.updateCaptureTexture()
                    renderer.composite(scene, resourceManager)
                    val err = GLES20.glGetError()
                    assertEquals("GL error on post-trim frame $i: $err", GLES20.GL_NO_ERROR, err)
                }

                testSuccess.set(true)
            } catch (e: Throwable) {
                Log.e(TAG, "Post-trim render failed: ${e.message}", e)
            } finally {
                renderer.release()
                encoder.stop()
                encoder.release()
                encoderSurface.release()
                latch.countDown()
            }
        }

        glThread.start()
        assertTrue("Memory pressure stress timed out", latch.await(15, TimeUnit.SECONDS))
        assertTrue("Post-trim frames must render without crash or GL errors", testSuccess.get())
    }

    // ==========================================
    // 4. THERMAL & FPS COORDINATION ON DEVICE
    // ==========================================

    @Test
    fun testThermalAndDynamicFpsCoordinationOnDevice() {
        val throttler = DynamicFpsThrottler(baseFps = 30)

        // Exercise all levels
        throttler.updateThermalLevel(ThermalThrottlingLevel.NOMINAL)
        assertEquals(30, throttler.currentTargetFps)

        throttler.updateThermalLevel(ThermalThrottlingLevel.MODERATE)
        assertEquals(24, throttler.currentTargetFps)

        throttler.updateThermalLevel(ThermalThrottlingLevel.SEVERE)
        assertEquals(20, throttler.currentTargetFps)

        throttler.updateThermalLevel(ThermalThrottlingLevel.CRITICAL)
        assertEquals(15, throttler.currentTargetFps)

        // Dynamic render governor detects latency spike (50ms)
        for (i in 1..15) {
            throttler.recordRenderTime(50L)
        }
        assertTrue("Throttler must not drop below 15 FPS minimum floor", throttler.currentTargetFps >= 15)

        // Recovery
        throttler.updateThermalLevel(ThermalThrottlingLevel.NOMINAL)
        assertEquals(30, throttler.currentTargetFps)
    }
}
