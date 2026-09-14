package com.fairylive.app.core.scene

import com.fairylive.app.core.scene.model.BroadcastScene
import com.fairylive.app.core.scene.model.SceneTransition
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Observable telemetry and event bus for broadcast scenes and transitions.
 */
object SceneStateNotifier {

    @Volatile
    private var _currentScene: BroadcastScene = BroadcastSceneManager.createDefaultGameplayScene()

    val currentScene: BroadcastScene
        get() = _currentScene

    @Volatile
    var isTransitioning: Boolean = false
        private set

    @Volatile
    var transitionProgress: Float = 1.0f
        private set

    private val sceneListeners = CopyOnWriteArrayList<(BroadcastScene) -> Unit>()
    private val transitionListeners = CopyOnWriteArrayList<(Float, BroadcastScene, BroadcastScene) -> Unit>()

    fun addListener(listener: (BroadcastScene) -> Unit) {
        sceneListeners.add(listener)
        listener(_currentScene)
    }

    fun removeListener(listener: (BroadcastScene) -> Unit) {
        sceneListeners.remove(listener)
    }

    fun addTransitionListener(listener: (Float, BroadcastScene, BroadcastScene) -> Unit) {
        transitionListeners.add(listener)
    }

    fun removeTransitionListener(listener: (Float, BroadcastScene, BroadcastScene) -> Unit) {
        transitionListeners.remove(listener)
    }

    fun updateScene(scene: BroadcastScene, transition: SceneTransition = SceneTransition.CUT) {
        _currentScene = scene
        if (transition.durationMs <= 0L) {
            isTransitioning = false
            transitionProgress = 1.0f
        } else {
            isTransitioning = true
            transitionProgress = 0.0f
        }
        for (listener in sceneListeners) {
            try {
                listener(scene)
            } catch (_: Exception) {
            }
        }
    }

    fun notifyTransitionProgress(progress: Float, from: BroadcastScene, to: BroadcastScene) {
        transitionProgress = progress.coerceIn(0f, 1f)
        isTransitioning = progress < 1.0f
        for (listener in transitionListeners) {
            try {
                listener(transitionProgress, from, to)
            } catch (_: Exception) {
            }
        }
    }
}
