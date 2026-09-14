package com.fairylive.app.core.scene

import com.fairylive.app.core.camera.model.FacecamConfig
import com.fairylive.app.core.compositor.model.OverlayElement
import com.fairylive.app.core.compositor.model.TextOverlayConfig
import com.fairylive.app.core.scene.model.BroadcastScene
import com.fairylive.app.core.scene.model.SceneBackgroundMode
import com.fairylive.app.core.scene.model.SceneTransition
import com.fairylive.app.core.scene.model.SceneTransitionType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Milestone 11: Unit test suite for Broadcast Scenes and Scene Management.
 */
class BroadcastSceneTest {

    private lateinit var manager: BroadcastSceneManager

    @Before
    fun setUp() {
        manager = BroadcastSceneManager()
    }

    @Test
    fun testDefaultScenesInitialized() {
        val scenes = manager.allScenes
        assertEquals(3, scenes.size)

        val gameplay = manager.getScene(BroadcastSceneManager.DEFAULT_SCENE_GAMEPLAY_ID)
        val chatting = manager.getScene(BroadcastSceneManager.DEFAULT_SCENE_CHATTING_ID)
        val brb = manager.getScene(BroadcastSceneManager.DEFAULT_SCENE_BRB_ID)

        assertNotNull(gameplay)
        assertNotNull(chatting)
        assertNotNull(brb)

        assertEquals("Gameplay", gameplay?.name)
        assertEquals("Just Chatting", chatting?.name)
        assertEquals("Be Right Back", brb?.name)
    }

    @Test
    fun testInitialActiveSceneIsGameplay() {
        assertEquals(BroadcastSceneManager.DEFAULT_SCENE_GAMEPLAY_ID, manager.activeScene.id)
        assertEquals("Gameplay", manager.activeScene.name)
    }

    @Test
    fun testSceneSwitchingChangesActiveScene() {
        val success = manager.switchScene(BroadcastSceneManager.DEFAULT_SCENE_CHATTING_ID, SceneTransition.CUT)
        assertTrue(success)
        assertEquals(BroadcastSceneManager.DEFAULT_SCENE_CHATTING_ID, manager.activeScene.id)
        assertEquals("Just Chatting", manager.activeScene.name)

        val successBrb = manager.switchScene(BroadcastSceneManager.DEFAULT_SCENE_BRB_ID, SceneTransition.CROSSFADE)
        assertTrue(successBrb)
        assertEquals(BroadcastSceneManager.DEFAULT_SCENE_BRB_ID, manager.activeScene.id)
        assertEquals("Be Right Back", manager.activeScene.name)
    }

    @Test
    fun testSceneSwitchingToUnknownIdReturnsFalse() {
        val success = manager.switchScene("unknown_scene_id", SceneTransition.CUT)
        assertFalse(success)
        // Active scene unchanged
        assertEquals(BroadcastSceneManager.DEFAULT_SCENE_GAMEPLAY_ID, manager.activeScene.id)
    }

    @Test
    fun testRegisterCustomScene() {
        val customScene = BroadcastScene(
            id = "custom_interview",
            name = "Dual Interview",
            overlayElements = listOf(
                OverlayElement.createText(
                    id = "txt_interview",
                    config = TextOverlayConfig(text = "Special Guest"),
                    x = 0.1f,
                    y = 0.9f
                )
            ),
            facecamConfig = FacecamConfig(enabled = true, scale = 1.2f)
        )

        val registered = manager.registerScene(customScene)
        assertTrue(registered)
        assertEquals(4, manager.allScenes.size)

        val retrieved = manager.getScene("custom_interview")
        assertNotNull(retrieved)
        assertEquals("Dual Interview", retrieved?.name)

        val switched = manager.switchScene("custom_interview")
        assertTrue(switched)
        assertEquals("custom_interview", manager.activeScene.id)
    }

    @Test
    fun testRemoveScenePreservesAtLeastOne() {
        // Remove scene 2
        val removed = manager.removeScene(BroadcastSceneManager.DEFAULT_SCENE_CHATTING_ID)
        assertTrue(removed)
        assertEquals(2, manager.allScenes.size)
        assertNull(manager.getScene(BroadcastSceneManager.DEFAULT_SCENE_CHATTING_ID))

        // Switch to BRB, then remove it — active scene should fall back to Gameplay
        manager.switchScene(BroadcastSceneManager.DEFAULT_SCENE_BRB_ID)
        assertEquals(BroadcastSceneManager.DEFAULT_SCENE_BRB_ID, manager.activeScene.id)

        val removedBrb = manager.removeScene(BroadcastSceneManager.DEFAULT_SCENE_BRB_ID)
        assertTrue(removedBrb)
        assertEquals(1, manager.allScenes.size)
        assertEquals(BroadcastSceneManager.DEFAULT_SCENE_GAMEPLAY_ID, manager.activeScene.id)

        // Attempt to remove the last scene — must fail
        val removedLast = manager.removeScene(BroadcastSceneManager.DEFAULT_SCENE_GAMEPLAY_ID)
        assertFalse(removedLast)
        assertEquals(1, manager.allScenes.size)
    }

    @Test
    fun testSceneTransitionConstants() {
        assertEquals(SceneTransitionType.CUT, SceneTransition.CUT.type)
        assertEquals(0L, SceneTransition.CUT.durationMs)

        assertEquals(SceneTransitionType.CROSSFADE, SceneTransition.CROSSFADE.type)
        assertEquals(300L, SceneTransition.CROSSFADE.durationMs)
    }

    @Test
    fun testSceneStateNotifierEvents() {
        var notifiedScene: BroadcastScene? = null
        val listener: (BroadcastScene) -> Unit = { scene ->
            notifiedScene = scene
        }

        SceneStateNotifier.addListener(listener)
        assertEquals(manager.activeScene.id, notifiedScene?.id)

        manager.switchScene(BroadcastSceneManager.DEFAULT_SCENE_CHATTING_ID, SceneTransition.CROSSFADE)
        assertEquals(BroadcastSceneManager.DEFAULT_SCENE_CHATTING_ID, notifiedScene?.id)

        SceneStateNotifier.removeListener(listener)
    }

    @Test
    fun testSceneStateNotifierTransitionProgress() {
        val from = manager.getScene(BroadcastSceneManager.DEFAULT_SCENE_GAMEPLAY_ID)!!
        val to = manager.getScene(BroadcastSceneManager.DEFAULT_SCENE_CHATTING_ID)!!

        var lastProgress = -1f
        val listener: (Float, BroadcastScene, BroadcastScene) -> Unit = { progress, _, _ ->
            lastProgress = progress
        }

        SceneStateNotifier.addTransitionListener(listener)
        SceneStateNotifier.notifyTransitionProgress(0.5f, from, to)
        assertEquals(0.5f, lastProgress, 0.001f)
        assertTrue(SceneStateNotifier.isTransitioning)

        SceneStateNotifier.notifyTransitionProgress(1.0f, from, to)
        assertEquals(1.0f, lastProgress, 0.001f)
        assertFalse(SceneStateNotifier.isTransitioning)

        SceneStateNotifier.removeTransitionListener(listener)
    }

    @Test
    fun testOverlayIsolationBetweenScenes() {
        val gameplay = manager.getScene(BroadcastSceneManager.DEFAULT_SCENE_GAMEPLAY_ID)!!
        val chatting = manager.getScene(BroadcastSceneManager.DEFAULT_SCENE_CHATTING_ID)!!
        val brb = manager.getScene(BroadcastSceneManager.DEFAULT_SCENE_BRB_ID)!!

        val gameplayOverlayIds = gameplay.overlayElements.map { it.id }.toSet()
        val chattingOverlayIds = chatting.overlayElements.map { it.id }.toSet()
        val brbOverlayIds = brb.overlayElements.map { it.id }.toSet()

        // Elements across different scenes must have distinct IDs
        assertTrue(gameplayOverlayIds.intersect(chattingOverlayIds).isEmpty())
        assertTrue(gameplayOverlayIds.intersect(brbOverlayIds).isEmpty())
        assertTrue(chattingOverlayIds.intersect(brbOverlayIds).isEmpty())
    }

    @Test
    fun testFacecamConfigPerScene() {
        val gameplay = manager.getScene(BroadcastSceneManager.DEFAULT_SCENE_GAMEPLAY_ID)!!
        val chatting = manager.getScene(BroadcastSceneManager.DEFAULT_SCENE_CHATTING_ID)!!
        val brb = manager.getScene(BroadcastSceneManager.DEFAULT_SCENE_BRB_ID)!!

        assertTrue("Gameplay should have Facecam enabled", gameplay.facecamConfig.enabled)
        assertEquals(0.26f, gameplay.facecamConfig.normalizedWidth, 0.01f)

        assertTrue("Chatting should have Facecam enabled", chatting.facecamConfig.enabled)
        assertEquals(0.40f, chatting.facecamConfig.normalizedWidth, 0.01f)

        assertFalse("BRB should have Facecam disabled", brb.facecamConfig.enabled)
    }

    @Test
    fun testSceneBackgroundModes() {
        val gameplay = manager.getScene(BroadcastSceneManager.DEFAULT_SCENE_GAMEPLAY_ID)!!
        val brb = manager.getScene(BroadcastSceneManager.DEFAULT_SCENE_BRB_ID)!!

        assertEquals(SceneBackgroundMode.SCREEN_CAPTURE, gameplay.backgroundMode)
        assertEquals(SceneBackgroundMode.SOLID_COLOR, brb.backgroundMode)
        assertEquals(0xFF101420.toInt(), brb.backgroundColorArgb)
    }
}
