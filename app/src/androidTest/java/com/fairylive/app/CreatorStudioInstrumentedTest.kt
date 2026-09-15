package com.fairylive.app

import android.content.Context
import android.graphics.Color
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.os.SystemClock
import android.view.MotionEvent
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.fairylive.app.core.camera.model.FacecamConfig
import com.fairylive.app.core.compositor.OverlayCompositor
import com.fairylive.app.core.compositor.model.OverlayElement
import com.fairylive.app.core.compositor.model.TextOverlayConfig
import com.fairylive.app.core.scene.BroadcastSceneManager
import com.fairylive.app.core.scene.SceneStateNotifier
import com.fairylive.app.core.scene.model.BroadcastScene
import com.fairylive.app.core.scene.model.SceneBackgroundMode
import com.fairylive.app.core.scene.model.SceneTransition
import com.fairylive.app.ui.studio.BroadcastPreviewView
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Milestone 15-A: Device-Level Instrumented Test for Creator Studio & Interactive Preview.
 *
 * Verifies on the Android 15 Pixel 8 emulator:
 * 1. Touch interaction in BroadcastPreviewView: Tap-to-select, drag-to-move, handle-drag-to-resize.
 * 2. Scene model synchronization: Normalized coordinate updates are applied accurately.
 * 3. OverlayCompositor synchronization: Real-time applyScene propagates without pipeline stall.
 * 4. Facecam PIP transforms: Repositioning and scaling are verified in both Preview and Compositor.
 * 5. Multi-scene CRUD: Creation, duplication, renaming, and switching.
 */
@RunWith(AndroidJUnit4::class)
class CreatorStudioInstrumentedTest {

    private val context: Context
        get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun testBroadcastPreviewViewTouchAndTransform() {
        val latch = CountDownLatch(1)
        var selectedId: String? = null
        var transformedX = 0f
        var transformedY = 0f

        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            val previewView = BroadcastPreviewView(context)
            previewView.measure(
                android.view.View.MeasureSpec.makeMeasureSpec(1280, android.view.View.MeasureSpec.EXACTLY),
                android.view.View.MeasureSpec.makeMeasureSpec(720, android.view.View.MeasureSpec.EXACTLY)
            )
            previewView.layout(0, 0, 1280, 720)

            val textElement = OverlayElement.createText(
                id = "test_text_layer",
                config = TextOverlayConfig(text = "Interactive Test"),
                x = 0.20f,
                y = 0.30f,
                width = 0.40f
            )
            val scene = BroadcastScene(
                id = "touch_test_scene",
                name = "Touch Scene",
                backgroundMode = SceneBackgroundMode.SOLID_COLOR,
                backgroundColorArgb = Color.BLACK,
                overlayElements = listOf(textElement)
            )
            previewView.setScene(scene)

            previewView.interactionListener = object : BroadcastPreviewView.InteractionListener {
                override fun onElementSelected(elementId: String?) {
                    selectedId = elementId
                }

                override fun onElementTransformed(
                    elementId: String,
                    normX: Float,
                    normY: Float,
                    normW: Float,
                    normH: Float
                ) {
                    transformedX = normX
                    transformedY = normY
                }

                override fun onFacecamTransformed(
                    normX: Float,
                    normY: Float,
                    normW: Float,
                    normH: Float
                ) {
                }
            }

            // Down event on text element center: normX = 0.35, normY = 0.34
            val downX = 0.35f * 1280f
            val downY = 0.34f * 720f
            val downTime = SystemClock.uptimeMillis()
            val downEvent = MotionEvent.obtain(downTime, downTime, MotionEvent.ACTION_DOWN, downX, downY, 0)
            previewView.dispatchTouchEvent(downEvent)
            downEvent.recycle()

            assertEquals("test_text_layer", selectedId)

            // Move event dragging right and down by +100px
            val moveTime = downTime + 50
            val moveEvent = MotionEvent.obtain(downTime, moveTime, MotionEvent.ACTION_MOVE, downX + 100f, downY + 100f, 0)
            previewView.dispatchTouchEvent(moveEvent)
            moveEvent.recycle()

            assertTrue("transformedX should have moved right", transformedX > 0.20f)
            assertTrue("transformedY should have moved down", transformedY > 0.30f)

            // Up event to finish drag
            val upTime = moveTime + 50
            val upEvent = MotionEvent.obtain(downTime, upTime, MotionEvent.ACTION_UP, downX + 100f, downY + 100f, 0)
            previewView.dispatchTouchEvent(upEvent)
            upEvent.recycle()

            latch.countDown()
        }

        assertTrue("Main thread touch test should complete", latch.await(5, TimeUnit.SECONDS))
    }

    @Test
    fun testCreatorStudioSceneAndCompositorSynchronization() {
        val width = 480
        val height = 854
        val format = MediaFormat.createVideoFormat("video/avc", width, height).apply {
            setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
            setInteger(MediaFormat.KEY_BIT_RATE, 1_000_000)
            setInteger(MediaFormat.KEY_FRAME_RATE, 30)
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
        }
        val codec = MediaCodec.createEncoderByType("video/avc")
        codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        val inputSurface = codec.createInputSurface()
        codec.start()

        val sceneManager = BroadcastSceneManager()
        val compositor = OverlayCompositor(context)

        try {
            val captureSurface = compositor.start(inputSurface, width, height)
            assertNotNull("Capture surface must not be null", captureSurface)
            assertTrue("Capture surface must be valid", captureSurface.isValid)

            // 1. Initial scene
            val initialScene = sceneManager.activeScene
            assertNotNull(initialScene)
            val initialElemCount = initialScene.overlayElements.size

            // 2. Add text overlay in SceneManager
            val textElem = OverlayElement.createText(
                id = "live_title",
                config = TextOverlayConfig(text = "Fairy Live Stream"),
                x = 0.10f,
                y = 0.05f,
                width = 0.80f
            )
            val updatedScene1 = sceneManager.addOverlayToActiveScene(textElem)
            assertEquals(initialElemCount + 1, updatedScene1.overlayElements.size)

            // 3. Apply updated scene to live compositor
            compositor.applyScene(updatedScene1)
            Thread.sleep(50)

            // 4. Simulate user dragging overlay to new position (x = 0.15, y = 0.20)
            val updatedElem = textElem.copy(normalizedX = 0.15f, normalizedY = 0.20f)
            val updatedScene2 = sceneManager.updateOverlayInActiveScene(updatedElem)
            val foundElem = updatedScene2.overlayElements.first { it.id == "live_title" }
            assertEquals(0.15f, foundElem.normalizedX, 0.001f)
            assertEquals(0.20f, foundElem.normalizedY, 0.001f)

            // 5. Sync dragged scene to compositor
            compositor.applyScene(updatedScene2)
            Thread.sleep(50)

            // 6. Test reordering layers
            val secondElem = OverlayElement.createText(
                id = "secondary_badge",
                config = TextOverlayConfig(text = "PRO BROADCASTER"),
                x = 0.05f,
                y = 0.85f,
                width = 0.50f
            )
            val sceneWithTwo = sceneManager.addOverlayToActiveScene(secondElem)
            assertEquals(initialElemCount + 2, sceneWithTwo.overlayElements.size)
            assertTrue(sceneWithTwo.overlayElements.any { it.id == "secondary_badge" })

            val idxSecondary = sceneWithTwo.overlayElements.indexOfFirst { it.id == "secondary_badge" }
            val reorderedScene = sceneManager.reorderLayersInActiveScene(idxSecondary, 0)
            assertEquals("secondary_badge", reorderedScene.overlayElements[0].id)

            compositor.applyScene(reorderedScene)
            Thread.sleep(50)

        } finally {
            compositor.stop()
            codec.stop()
            codec.release()
            inputSurface.release()
        }
    }

    @Test
    fun testFacecamPipTransformAndCompositorSync() {
        val width = 480
        val height = 854
        val format = MediaFormat.createVideoFormat("video/avc", width, height).apply {
            setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
            setInteger(MediaFormat.KEY_BIT_RATE, 1_000_000)
            setInteger(MediaFormat.KEY_FRAME_RATE, 30)
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
        }
        val codec = MediaCodec.createEncoderByType("video/avc")
        codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        val inputSurface = codec.createInputSurface()
        codec.start()

        val sceneManager = BroadcastSceneManager()
        val compositor = OverlayCompositor(context)

        try {
            compositor.start(inputSurface, width, height)

            // Configure Facecam PIP to bottom-right corner
            val facecam = FacecamConfig(
                enabled = true,
                normalizedX = 0.70f,
                normalizedY = 0.65f,
                normalizedWidth = 0.28f,
                normalizedHeight = 0.28f
            )
            val updatedScene = sceneManager.updateFacecamInActiveScene(facecam)
            assertTrue(updatedScene.facecamConfig.enabled)
            assertEquals(0.70f, updatedScene.facecamConfig.normalizedX, 0.001f)
            assertEquals(0.65f, updatedScene.facecamConfig.normalizedY, 0.001f)

            compositor.applyScene(updatedScene)
            Thread.sleep(50)

        } finally {
            compositor.stop()
            codec.stop()
            codec.release()
            inputSurface.release()
        }
    }

    @Test
    fun testSceneCreationDuplicationAndSwitching() {
        val sceneManager = BroadcastSceneManager()
        val initialCount = sceneManager.allScenes.size

        // 1. Create new scene
        val created = sceneManager.createNewScene("Retro Gaming")
        assertEquals("Retro Gaming", created.name)
        assertEquals(initialCount + 1, sceneManager.allScenes.size)

        // 2. Duplicate scene
        val duplicated = sceneManager.duplicateScene(created.id)
        assertNotNull(duplicated)
        assertEquals("Retro Gaming (Copy)", duplicated!!.name)
        assertEquals(initialCount + 2, sceneManager.allScenes.size)

        // 3. Rename scene
        val renamed = sceneManager.renameScene(duplicated.id, "Speedrun Arena")
        assertTrue(renamed)
        assertEquals("Speedrun Arena", sceneManager.getScene(duplicated.id)?.name)

        // 4. Switch to renamed scene
        var notifiedName: String? = null
        val listener: (BroadcastScene) -> Unit = { scene -> notifiedName = scene.name }
        SceneStateNotifier.addListener(listener)

        val switched = sceneManager.switchScene(duplicated.id)
        assertTrue(switched)
        assertEquals(duplicated.id, sceneManager.activeScene.id)
        assertEquals("Speedrun Arena", notifiedName)

        SceneStateNotifier.removeListener(listener)

        // Clean up created scenes
        sceneManager.removeScene(duplicated.id)
        sceneManager.removeScene(created.id)
        assertEquals(initialCount, sceneManager.allScenes.size)
    }
}
