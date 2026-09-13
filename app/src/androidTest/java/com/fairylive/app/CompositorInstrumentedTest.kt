package com.fairylive.app

import android.content.Context
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.fairylive.app.core.compositor.OverlayScene
import com.fairylive.app.core.compositor.model.OverlayElement
import com.fairylive.app.core.compositor.model.OverlayType
import com.fairylive.app.core.compositor.render.GlesRenderer
import com.fairylive.app.core.compositor.resource.OverlayResourceManager
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * On-device validation of the M7 broadcast compositor.
 *
 * Proves the GLES renderer can:
 *   1. initialize against a real MediaCodec input Surface (EGL context + programs),
 *   2. compose a capture + overlay scene into the encoder surface,
 *   3. release cleanly.
 * The GL thread lifecycle mirrors the production compositor thread.
 */
@RunWith(AndroidJUnit4::class)
class CompositorInstrumentedTest {

    private val context: Context
        get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun testRendererInitializesAgainstEncoderSurface() {
        val width = 480
        val height = 1056

        // Create a real AVC encoder input surface (the renderer's output target)
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
        val initLatch = CountDownLatch(1)
        val captureSurfaceHolder = arrayOfNulls<android.view.Surface>(1)
        val errorHolder = arrayOfNulls<Throwable>(1)

        // GL context must be created on a dedicated thread (same as the compositor thread)
        val glThread = Thread {
            try {
                val captureSurface = renderer.initialize(encoderSurface, width, height, context.assets)
                captureSurfaceHolder[0] = captureSurface
                assertNotNull(captureSurface)

                // A frame must be presentable: capture texture + one overlay
                val scene = OverlayScene()
                scene.add(
                    OverlayElement(
                        id = "test_logo",
                        type = OverlayType.PNG,
                        normalizedX = 0.7f,
                        normalizedY = 0.8f,
                        normalizedWidth = 0.2f,
                        opacity = 0.9f,
                        zIndex = 5,
                        resourcePath = "assets://overlay_logo.png"
                    )
                )
                renderer.updateCaptureTexture()
                renderer.composite(scene, OverlayResourceManager())
                // Sample a grid of pixels across the overlay's expected region and print all.
                // The overlay spans normalized x∈[0.7,0.9], y∈[0.8,1.0] in stream top-down
                // coords → in GL y-up, stream row 0.9*1056 from top = GL row 106 (near bottom).
                val overlay = scene.find("test_logo")!!
                assertTrue("Overlay texture must be uploaded", overlay.textureHandle != 0)
                val buf = java.nio.ByteBuffer.allocateDirect(4).order(java.nio.ByteOrder.nativeOrder())
                val results = StringBuilder()
                val xs = listOf(0.7f, 0.75f, 0.8f, 0.85f, 0.9f)
                val ys = listOf(0.8f, 0.85f, 0.9f, 0.95f, 1.0f)
                var hit = false
                for (fy in ys) {
                    for (fx in xs) {
                        // stream top-down → GL y-up
                        val pxX = (fx * width).toInt()
                        val glY = (height - (fy * height).toInt())
                        buf.rewind()
                        android.opengl.GLES20.glReadPixels(pxX, glY, 1, 1, android.opengl.GLES20.GL_RGBA, android.opengl.GLES20.GL_UNSIGNED_BYTE, buf)
                        buf.rewind()
                        val r = buf.get().toInt() and 0xff
                        val g = buf.get().toInt() and 0xff
                        val b = buf.get().toInt() and 0xff
                        val a = buf.get().toInt() and 0xff
                        if (a > 200) hit = true
                        results.append("(${fx},${fy})=($r,$g,$b,$a) ")
                    }
                    results.append("\n")
                }
                val glErr = android.opengl.GLES20.glGetError()
                Log.i("CompositorInstrumentedTest", "GL error after composite: $glErr")
                Log.i("CompositorInstrumentedTest", "Overlay grid readback:\n$results")
                Log.i("CompositorInstrumentedTest", "textureHandle=${overlay.textureHandle} srcW=${overlay.sourceWidth} srcH=${overlay.sourceHeight}")
                assertTrue("At least one overlay pixel must be opaque (grid=$results)", hit)
                renderer.drawFrame(OverlayScene(), OverlayResourceManager()) // present (empty scene — just swaps)
                renderer.release()
            } catch (t: Throwable) {
                errorHolder[0] = t
            } finally {
                initLatch.countDown()
            }
        }
        glThread.start()
        assertTrue("Renderer init must complete in time", initLatch.await(10, TimeUnit.SECONDS))

        if (errorHolder[0] != null) {
            throw AssertionError("Renderer failed on GL thread", errorHolder[0])
        }
        assertNotNull("Renderer must return a valid capture surface", captureSurfaceHolder[0])

        encoder.signalEndOfInputStream()
        encoder.stop()
        encoder.release()
    }

    @Test
    fun testMultiOverlaySceneRendering() {
        val width = 480
        val height = 1056
        val format = MediaFormat.createVideoFormat("video/avc", width, height).apply {
            setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
            setInteger(MediaFormat.KEY_BIT_RATE, 1_200_000)
            setInteger(MediaFormat.KEY_FRAME_RATE, 24)
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
        }

        val encoder = MediaCodec.createEncoderByType("video/avc")
        encoder.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        val encoderSurface = encoder.createInputSurface()
        encoder.start()

        val renderer = GlesRenderer()
        val initLatch = CountDownLatch(1)
        val errorHolder = arrayOfNulls<Throwable>(1)

        val glThread = Thread {
            try {
                renderer.initialize(encoderSurface, width, height, context.assets)
                val resources = OverlayResourceManager()
                val scene = OverlayScene()

                // 1. Watermark logo
                scene.add(OverlayElement.createImage("logo", "assets://overlay_logo.png", 0.7f, 0.8f, 0.2f, zIndex = 5))
                // 2. Styled text banner
                scene.add(OverlayElement.createText("txt", com.fairylive.app.core.compositor.model.TextOverlayConfig("TEST TITLE"), 0.05f, 0.05f, 0.4f, zIndex = 10))
                // 3. Goal progress
                scene.add(OverlayElement.createGoal("goal", com.fairylive.app.core.compositor.model.GoalOverlayConfig("Subs", 100, 200), 0.5f, 0.05f, 0.45f, zIndex = 15))
                // 4. Widget
                scene.add(OverlayElement.createWidget("stat", com.fairylive.app.core.compositor.model.WidgetOverlayConfig("STATS", "FPS", "24.0"), 0.05f, 0.90f, 0.35f, zIndex = 15))

                renderer.composite(scene, resources)

                // Verify textures uploaded for all 4 overlays
                org.junit.Assert.assertEquals(4, scene.size)
                scene.orderedLayers.forEach { layer ->
                    assertTrue("Layer ${layer.element.id} must have valid texture handle", layer.textureHandle != 0)
                }

                renderer.drawFrame(scene, resources)
                renderer.release()
                resources.release()
            } catch (t: Throwable) {
                errorHolder[0] = t
            } finally {
                initLatch.countDown()
            }
        }
        glThread.start()
        assertTrue("Multi-overlay GL thread did not finish", initLatch.await(10, TimeUnit.SECONDS))
        if (errorHolder[0] != null) throw errorHolder[0]!!

        encoder.signalEndOfInputStream()
        encoder.stop()
        encoder.release()
    }
}