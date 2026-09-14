package com.fairylive.app

import android.content.Context
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.fairylive.app.core.compositor.OverlayCompositor
import com.fairylive.app.core.scene.BroadcastSceneManager
import com.fairylive.app.core.scene.SceneStateNotifier
import com.fairylive.app.core.scene.model.BroadcastScene
import com.fairylive.app.core.scene.model.SceneTransition
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * On-device instrumentation tests for Milestone 11 Broadcast Scenes & Runtime Switching.
 *
 * Verifies on the Android 15 Pixel 8 emulator that:
 * 1. Compositor starts and loads initial active scene.
 * 2. Seamless runtime switching between Gameplay, Just Chatting, and BRB scenes.
 * 3. Crossfade transition interpolates smoothly without crashing.
 * 4. Active MediaCodec and EGL rendering loop remain uninterrupted across rapid switches.
 */
@RunWith(AndroidJUnit4::class)
class SceneInstrumentedTest {

    private val context: Context
        get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun testCompositorSceneSwitchingOnDevice() {
        val width = 480
        val height = 1056

        val format = MediaFormat.createVideoFormat("video/avc", width, height).apply {
            setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
            setInteger(MediaFormat.KEY_BIT_RATE, 1_200_000)
            setInteger(MediaFormat.KEY_FRAME_RATE, 24)
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 2)
        }
        val encoder = MediaCodec.createEncoderByType("video/avc")
        encoder.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        val encoderSurface = encoder.createInputSurface()
        encoder.start()

        val compositor = OverlayCompositor(context)
        val captureSurface = compositor.start(encoderSurface, width, height)
        assertNotNull("Capture surface must be non-null", captureSurface)
        assertTrue("Capture surface must be valid", captureSurface.isValid)

        try {
            // Initial scene is Gameplay
            val initialScene = compositor.broadcastSceneManager.activeScene
            assertEquals(BroadcastSceneManager.DEFAULT_SCENE_GAMEPLAY_ID, initialScene.id)

            // Switch to Just Chatting
            val switch1 = compositor.switchScene(BroadcastSceneManager.DEFAULT_SCENE_CHATTING_ID, SceneTransition.CUT)
            assertTrue("Switch to Chatting must succeed", switch1)
            Thread.sleep(150)
            assertEquals(BroadcastSceneManager.DEFAULT_SCENE_CHATTING_ID, compositor.broadcastSceneManager.activeScene.id)

            // Switch to BRB
            val switch2 = compositor.switchScene(BroadcastSceneManager.DEFAULT_SCENE_BRB_ID, SceneTransition.CUT)
            assertTrue("Switch to BRB must succeed", switch2)
            Thread.sleep(150)
            assertEquals(BroadcastSceneManager.DEFAULT_SCENE_BRB_ID, compositor.broadcastSceneManager.activeScene.id)

            // Switch back to Gameplay
            val switch3 = compositor.switchScene(BroadcastSceneManager.DEFAULT_SCENE_GAMEPLAY_ID, SceneTransition.CUT)
            assertTrue("Switch back to Gameplay must succeed", switch3)
            Thread.sleep(150)
            assertEquals(BroadcastSceneManager.DEFAULT_SCENE_GAMEPLAY_ID, compositor.broadcastSceneManager.activeScene.id)
        } finally {
            compositor.stop()
            encoder.stop()
            encoder.release()
            encoderSurface.release()
        }
    }

    @Test
    fun testCrossfadeTransitionLifecycleOnDevice() {
        val width = 480
        val height = 1056

        val format = MediaFormat.createVideoFormat("video/avc", width, height).apply {
            setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
            setInteger(MediaFormat.KEY_BIT_RATE, 1_200_000)
            setInteger(MediaFormat.KEY_FRAME_RATE, 24)
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 2)
        }
        val encoder = MediaCodec.createEncoderByType("video/avc")
        encoder.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        val encoderSurface = encoder.createInputSurface()
        encoder.start()

        val compositor = OverlayCompositor(context)
        compositor.start(encoderSurface, width, height)

        val transitionLatch = CountDownLatch(1)
        var observedProgress = false

        val transitionListener: (Float, BroadcastScene, BroadcastScene) -> Unit = { progress, _, _ ->
            if (progress in 0.01f..0.99f) {
                observedProgress = true
            }
            if (progress >= 1.0f) {
                transitionLatch.countDown()
            }
        }
        SceneStateNotifier.addTransitionListener(transitionListener)

        try {
            // Trigger 200ms crossfade
            val switchSuccess = compositor.switchScene(
                BroadcastSceneManager.DEFAULT_SCENE_CHATTING_ID,
                SceneTransition(com.fairylive.app.core.scene.model.SceneTransitionType.CROSSFADE, 200L)
            )
            assertTrue(switchSuccess)

            // Simulate frames being driven
            for (i in 0 until 15) {
                compositor.onCaptureFrameAvailable()
                Thread.sleep(20)
            }

            val completed = transitionLatch.await(1, TimeUnit.SECONDS)
            assertTrue("Transition progress must be observed or completed", observedProgress || completed)
            assertEquals(BroadcastSceneManager.DEFAULT_SCENE_CHATTING_ID, compositor.broadcastSceneManager.activeScene.id)
        } finally {
            SceneStateNotifier.removeTransitionListener(transitionListener)
            compositor.stop()
            encoder.stop()
            encoder.release()
            encoderSurface.release()
        }
    }

    @Test
    fun testRapidSceneSwitchingStability() {
        val width = 480
        val height = 1056

        val format = MediaFormat.createVideoFormat("video/avc", width, height).apply {
            setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
            setInteger(MediaFormat.KEY_BIT_RATE, 1_200_000)
            setInteger(MediaFormat.KEY_FRAME_RATE, 24)
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 2)
        }
        val encoder = MediaCodec.createEncoderByType("video/avc")
        encoder.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        val encoderSurface = encoder.createInputSurface()
        encoder.start()

        val compositor = OverlayCompositor(context)
        compositor.start(encoderSurface, width, height)

        try {
            val sceneIds = listOf(
                BroadcastSceneManager.DEFAULT_SCENE_GAMEPLAY_ID,
                BroadcastSceneManager.DEFAULT_SCENE_CHATTING_ID,
                BroadcastSceneManager.DEFAULT_SCENE_BRB_ID
            )

            // Rapidly toggle across scenes 10 times
            for (i in 0 until 10) {
                val targetId = sceneIds[i % sceneIds.size]
                val switched = compositor.switchScene(targetId, SceneTransition.CUT)
                assertTrue("Switch #$i must succeed", switched)
                compositor.onCaptureFrameAvailable()
                Thread.sleep(30)
            }

            // Compositor must remain healthy and running
            assertTrue("Compositor must remain running", compositor.isStarted)
        } finally {
            compositor.stop()
            encoder.stop()
            encoder.release()
            encoderSurface.release()
        }
    }
}
