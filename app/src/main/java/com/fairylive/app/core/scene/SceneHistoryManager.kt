package com.fairylive.app.core.scene

import com.fairylive.app.core.scene.model.BroadcastScene

/**
 * Manages an undo/redo stack for scene modifications in Creator Studio.
 * Maintains immutable snapshots with a bounded depth to prevent memory pressure.
 */
class SceneHistoryManager(private val maxDepth: Int = 30) {

    private val undoStack = ArrayDeque<BroadcastScene>()
    private val redoStack = ArrayDeque<BroadcastScene>()

    val canUndo: Boolean get() = undoStack.isNotEmpty()
    val canRedo: Boolean get() = redoStack.isNotEmpty()

    /**
     * Records the state before a mutation. Clears the redo stack.
     */
    fun pushSnapshot(currentScene: BroadcastScene) {
        if (undoStack.size >= maxDepth) {
            undoStack.removeFirst()
        }
        undoStack.addLast(currentScene.copy())
        redoStack.clear()
    }

    /**
     * Undoes the last change, returning the previous scene state.
     * Pushes the currentState onto the redo stack.
     */
    fun undo(currentState: BroadcastScene): BroadcastScene? {
        if (!canUndo) return null
        val previousState = undoStack.removeLast()
        if (redoStack.size >= maxDepth) {
            redoStack.removeFirst()
        }
        redoStack.addLast(currentState.copy())
        return previousState
    }

    /**
     * Redoes the previously undone change.
     * Pushes currentState onto the undo stack.
     */
    fun redo(currentState: BroadcastScene): BroadcastScene? {
        if (!canRedo) return null
        val nextState = redoStack.removeLast()
        if (undoStack.size >= maxDepth) {
            undoStack.removeFirst()
        }
        undoStack.addLast(currentState.copy())
        return nextState
    }

    fun clear() {
        undoStack.clear()
        redoStack.clear()
    }
}
