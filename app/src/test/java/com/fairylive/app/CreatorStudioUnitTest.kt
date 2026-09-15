package com.fairylive.app

import com.fairylive.app.core.camera.model.FacecamConfig
import com.fairylive.app.core.compositor.model.GoalOverlayConfig
import com.fairylive.app.core.compositor.model.LowerThirdOverlayConfig
import com.fairylive.app.core.compositor.model.OverlayElement
import com.fairylive.app.core.compositor.model.OverlayType
import com.fairylive.app.core.compositor.model.QrOverlayConfig
import com.fairylive.app.core.compositor.model.TextOverlayConfig
import com.fairylive.app.core.scene.BroadcastSceneManager
import com.fairylive.app.core.scene.model.BroadcastScene
import com.fairylive.app.core.scene.model.SceneBackgroundMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Milestone 15-A & 15-B: Unit test suite for Creator Studio scene manipulation,
 * overlay layer management, normalized coordinate integrity, and facecam positioning.
 */
class CreatorStudioUnitTest {

    private lateinit var manager: BroadcastSceneManager

    @Before
    fun setUp() {
        manager = BroadcastSceneManager()
    }

    @Test
    fun testCreateNewScene() {
        val newScene = manager.createNewScene("Stream Ending", SceneBackgroundMode.SOLID_COLOR)
        assertNotNull(newScene)
        assertEquals("Stream Ending", newScene.name)
        assertEquals(SceneBackgroundMode.SOLID_COLOR, newScene.backgroundMode)
        assertTrue(manager.allScenes.any { it.id == newScene.id })
    }

    @Test
    fun testDuplicateAndRenameScene() {
        val gameplay = manager.activeScene
        val copy = manager.duplicateScene(gameplay.id, "Gameplay Pro")
        assertNotNull(copy)
        assertEquals("Gameplay Pro", copy?.name)
        assertEquals(gameplay.overlayElements.size, copy?.overlayElements?.size)

        val renamed = manager.renameScene(copy!!.id, "Gameplay Master")
        assertTrue(renamed)
        assertEquals("Gameplay Master", manager.getScene(copy.id)?.name)
    }

    @Test
    fun testAddOverlayToActiveScene() {
        val txt = OverlayElement.createText(
            id = "custom_txt_1",
            config = TextOverlayConfig(text = "Hello Creators!"),
            x = 0.15f,
            y = 0.25f,
            width = 0.40f
        )
        val updatedScene = manager.addOverlayToActiveScene(txt)
        assertTrue(updatedScene.overlayElements.any { it.id == "custom_txt_1" })
        assertEquals(txt, manager.activeScene.overlayElements.first { it.id == "custom_txt_1" })
    }

    @Test
    fun testUpdateOverlayInActiveScene() {
        val txt = OverlayElement.createText(
            id = "elem_update_test",
            config = TextOverlayConfig(text = "Initial Text"),
            x = 0.1f,
            y = 0.1f
        )
        manager.addOverlayToActiveScene(txt)

        val modified = txt.copy(normalizedX = 0.35f, normalizedY = 0.50f)
        manager.updateOverlayInActiveScene(modified)

        val active = manager.activeScene.overlayElements.first { it.id == "elem_update_test" }
        assertEquals(0.35f, active.normalizedX, 0.001f)
        assertEquals(0.50f, active.normalizedY, 0.001f)
    }

    @Test
    fun testRemoveOverlayFromActiveScene() {
        val txt = OverlayElement.createText(
            id = "elem_to_delete",
            config = TextOverlayConfig(text = "Delete Me"),
            x = 0.1f,
            y = 0.1f
        )
        manager.addOverlayToActiveScene(txt)
        assertTrue(manager.activeScene.overlayElements.any { it.id == "elem_to_delete" })

        manager.removeOverlayFromActiveScene("elem_to_delete")
        assertFalse(manager.activeScene.overlayElements.any { it.id == "elem_to_delete" })
    }

    @Test
    fun testReorderLayersInActiveScene() {
        val elem1 = OverlayElement.createText("layer_1", TextOverlayConfig("L1"), 0.1f, 0.1f)
        val elem2 = OverlayElement.createGoal("layer_2", GoalOverlayConfig("L2", 10, 50), 0.2f, 0.2f)
        val elem3 = OverlayElement.createLowerThird("layer_3", LowerThirdOverlayConfig("L3", "Sub"), 0.3f, 0.3f)

        manager.addOverlayToActiveScene(elem1)
        manager.addOverlayToActiveScene(elem2)
        manager.addOverlayToActiveScene(elem3)

        val elements = manager.activeScene.overlayElements
        val idx1 = elements.indexOfFirst { it.id == "layer_1" }
        val idx3 = elements.indexOfFirst { it.id == "layer_3" }

        // Move layer_1 to position of layer_3
        manager.reorderLayersInActiveScene(idx1, idx3)

        val reordered = manager.activeScene.overlayElements
        assertTrue(reordered.indexOfFirst { it.id == "layer_1" } > reordered.indexOfFirst { it.id == "layer_2" })
    }

    @Test
    fun testUpdateFacecamInActiveScene() {
        val newFc = FacecamConfig(
            enabled = true,
            normalizedX = 0.50f,
            normalizedY = 0.60f,
            normalizedWidth = 0.35f,
            normalizedHeight = 0.35f,
            scale = 1.2f,
            mirrorHorizontal = false
        )
        manager.updateFacecamInActiveScene(newFc)

        val activeFc = manager.activeScene.facecamConfig
        assertTrue(activeFc.enabled)
        assertEquals(0.50f, activeFc.normalizedX, 0.001f)
        assertEquals(0.60f, activeFc.normalizedY, 0.001f)
        assertEquals(0.35f, activeFc.normalizedWidth, 0.001f)
        assertFalse(activeFc.mirrorHorizontal)
    }

    @Test
    fun testNormalizedCoordinatesInvariant() {
        val overlay = OverlayElement.createText(
            id = "boundary_check",
            config = TextOverlayConfig(text = "Clamped"),
            x = 1.5f,
            y = -0.5f,
            width = 2.0f
        ).sanitized()

        assertEquals(1.0f, overlay.normalizedX, 0.001f)
        assertEquals(0.0f, overlay.normalizedY, 0.001f)
        assertEquals(1.0f, overlay.normalizedWidth, 0.001f)
    }
}
