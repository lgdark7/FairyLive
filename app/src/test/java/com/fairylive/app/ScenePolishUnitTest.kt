package com.fairylive.app

import com.fairylive.app.core.camera.model.FacecamConfig
import com.fairylive.app.core.compositor.model.OverlayElement
import com.fairylive.app.core.compositor.model.TextOverlayConfig
import com.fairylive.app.core.scene.BroadcastSceneManager
import com.fairylive.app.core.scene.SceneHistoryManager
import com.fairylive.app.core.scene.model.BroadcastScene
import org.junit.Assert.*
import org.junit.Test

class ScenePolishUnitTest {

    @Test
    fun testUndoRedoStackOperations() {
        val history = SceneHistoryManager(maxDepth = 5)
        val initialScene = BroadcastSceneManager.createDefaultGameplayScene()

        assertFalse("Initial undo stack should be empty", history.canUndo)
        assertFalse("Initial redo stack should be empty", history.canRedo)

        // 1. Push state A
        history.pushSnapshot(initialScene)
        assertTrue("Undo should be available after snapshot", history.canUndo)

        // 2. Mutate to state B
        val stateB = initialScene.copy(name = "Mutated B")
        history.pushSnapshot(stateB)

        // 3. Mutate to state C
        val stateC = stateB.copy(name = "Mutated C")

        // 4. Undo from C -> B
        val undoneB = history.undo(stateC)
        assertNotNull(undoneB)
        assertEquals("Mutated B", undoneB!!.name)
        assertTrue("Redo should now be available", history.canRedo)

        // 5. Undo from B -> A
        val undoneA = history.undo(undoneB)
        assertNotNull(undoneA)
        assertEquals("Gameplay", undoneA!!.name)

        // 6. Redo from A -> B
        val redoneB = history.redo(undoneA)
        assertNotNull(redoneB)
        assertEquals("Mutated B", redoneB!!.name)

        // 7. Redo from B -> C
        val redoneC = history.redo(redoneB)
        assertNotNull(redoneC)
        assertEquals("Mutated C", redoneC!!.name)
        assertFalse("Redo should be exhausted", history.canRedo)
    }

    @Test
    fun testSceneManagerUndoRedoIntegration() {
        val manager = BroadcastSceneManager()
        val initialOverlayCount = manager.activeScene.overlayElements.size

        // 1. Add element (auto pushes snapshot)
        val elem = OverlayElement.createText(
            id = "test_undo_elem",
            config = TextOverlayConfig(text = "Hello Undo"),
            x = 0.1f,
            y = 0.1f
        )
        manager.addOverlayToActiveScene(elem)
        assertEquals(initialOverlayCount + 1, manager.activeScene.overlayElements.size)
        assertTrue(manager.history.canUndo)

        // 2. Undo addition
        val undone = manager.undoActiveScene()
        assertNotNull(undone)
        assertEquals(initialOverlayCount, manager.activeScene.overlayElements.size)
        assertNull(manager.activeScene.overlayElements.firstOrNull { it.id == "test_undo_elem" })
        assertTrue(manager.history.canRedo)

        // 3. Redo addition
        val redone = manager.redoActiveScene()
        assertNotNull(redone)
        assertEquals(initialOverlayCount + 1, manager.activeScene.overlayElements.size)
        assertNotNull(manager.activeScene.overlayElements.firstOrNull { it.id == "test_undo_elem" })
    }

    @Test
    fun testHistoryMaxDepthEviction() {
        val history = SceneHistoryManager(maxDepth = 3)
        val base = BroadcastSceneManager.createDefaultGameplayScene()

        for (i in 1..5) {
            history.pushSnapshot(base.copy(name = "Step $i"))
        }

        // With depth 3, the earliest steps (1 and 2) must have been evicted.
        // Stack top is Step 5, then Step 4, then Step 3.
        var count = 0
        var cur = base.copy(name = "Step 6")
        while (history.canUndo) {
            cur = history.undo(cur)!!
            count++
        }
        assertEquals(3, count)
        assertEquals("Step 3", cur.name)
    }

    @Test
    fun testOverlayElementContentMutation() {
        val manager = BroadcastSceneManager()
        val elem = OverlayElement.createText(
            id = "editable_text",
            config = TextOverlayConfig(text = "Initial Text"),
            x = 0.2f,
            y = 0.2f
        )
        manager.addOverlayToActiveScene(elem)

        // Mutate text content
        val updated = elem.copy(textConfig = elem.textConfig?.copy(text = "Updated Text"))
        manager.updateOverlayInActiveScene(updated)
        val active = manager.activeScene.overlayElements.first { it.id == "editable_text" }
        assertEquals("Updated Text", active.textConfig?.text)

        // Mutate opacity
        val dimmed = active.copy(opacity = 0.5f)
        manager.updateOverlayInActiveScene(dimmed)
        val finalActive = manager.activeScene.overlayElements.first { it.id == "editable_text" }
        assertEquals(0.5f, finalActive.opacity, 0.001f)
    }
}
