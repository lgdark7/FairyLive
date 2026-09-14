package com.fairylive.app.core.scene

import android.util.Log
import com.fairylive.app.core.camera.model.FacecamConfig
import com.fairylive.app.core.compositor.model.GoalOverlayConfig
import com.fairylive.app.core.compositor.model.OverlayElement
import com.fairylive.app.core.compositor.model.QrOverlayConfig
import com.fairylive.app.core.compositor.model.TextOverlayConfig
import com.fairylive.app.core.scene.model.BroadcastScene
import com.fairylive.app.core.scene.model.SceneBackgroundMode
import com.fairylive.app.core.scene.model.SceneTransition
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Thread-safe scene manager managing broadcast scene presets and transitions (M11).
 */
class BroadcastSceneManager(
    initialScenes: List<BroadcastScene> = defaultScenes()
) {
    private val scenes = CopyOnWriteArrayList<BroadcastScene>(initialScenes)

    @Volatile
    private var _activeScene: BroadcastScene = scenes.firstOrNull() ?: createDefaultGameplayScene()

    val activeScene: BroadcastScene
        get() = _activeScene

    val allScenes: List<BroadcastScene>
        get() = scenes.toList()

    fun getScene(sceneId: String): BroadcastScene? {
        return scenes.firstOrNull { it.id == sceneId }
    }

    @Synchronized
    fun switchScene(sceneId: String, transition: SceneTransition = SceneTransition.CUT): Boolean {
        val target = getScene(sceneId) ?: run {
            Log.w(TAG, "Unknown sceneId requested: $sceneId")
            return false
        }
        _activeScene = target
        SceneStateNotifier.updateScene(target, transition)
        Log.i(TAG, "Switched active scene to '${target.name}' ($sceneId) via ${transition.type}")
        return true
    }

    @Synchronized
    fun registerScene(scene: BroadcastScene): Boolean {
        val existingIndex = scenes.indexOfFirst { it.id == scene.id }
        if (existingIndex >= 0) {
            scenes[existingIndex] = scene
            if (_activeScene.id == scene.id) {
                _activeScene = scene
                SceneStateNotifier.updateScene(scene, SceneTransition.CUT)
            }
        } else {
            scenes.add(scene)
        }
        return true
    }

    @Synchronized
    fun removeScene(sceneId: String): Boolean {
        if (scenes.size <= 1) {
            Log.w(TAG, "Cannot remove the only remaining scene")
            return false
        }
        val target = getScene(sceneId) ?: return false
        scenes.remove(target)
        if (_activeScene.id == sceneId) {
            val fallback = scenes.first()
            _activeScene = fallback
            SceneStateNotifier.updateScene(fallback, SceneTransition.CUT)
        }
        return true
    }

    companion object {
        private const val TAG = "BroadcastSceneManager"

        const val DEFAULT_SCENE_GAMEPLAY_ID = "scene_gameplay"
        const val DEFAULT_SCENE_CHATTING_ID = "scene_chatting"
        const val DEFAULT_SCENE_BRB_ID = "scene_brb"

        fun createDefaultGameplayScene(): BroadcastScene {
            return BroadcastScene(
                id = DEFAULT_SCENE_GAMEPLAY_ID,
                name = "Gameplay",
                overlayElements = listOf(
                    OverlayElement.createText(
                        id = "txt_watermark",
                        config = TextOverlayConfig(
                            text = "FAIRY LIVE STREAM",
                            fontSizeSp = 14f
                        ),
                        x = 0.04f,
                        y = 0.04f,
                        width = 0.35f,
                        opacity = 0.85f,
                        zIndex = 10
                    )
                ),
                facecamConfig = FacecamConfig(
                    enabled = true,
                    normalizedX = 0.70f,
                    normalizedY = 0.04f,
                    normalizedWidth = 0.26f,
                    normalizedHeight = 0.26f,
                    scale = 1.0f,
                    mirrorHorizontal = true
                ),
                backgroundMode = SceneBackgroundMode.SCREEN_CAPTURE,
                orderIndex = 0
            )
        }

        fun createDefaultChattingScene(): BroadcastScene {
            return BroadcastScene(
                id = DEFAULT_SCENE_CHATTING_ID,
                name = "Just Chatting",
                overlayElements = listOf(
                    OverlayElement.createText(
                        id = "txt_chat_header",
                        config = TextOverlayConfig(
                            text = "💬 JUST CHATTING & QA",
                            fontSizeSp = 18f
                        ),
                        x = 0.05f,
                        y = 0.04f,
                        width = 0.50f,
                        opacity = 0.95f,
                        zIndex = 10
                    ),
                    OverlayElement.createGoal(
                        id = "goal_subs",
                        config = GoalOverlayConfig(
                            title = "Sub Goal",
                            currentVal = 75,
                            targetVal = 100,
                            unitPrefix = "★ "
                        ),
                        x = 0.05f,
                        y = 0.12f,
                        width = 0.45f,
                        opacity = 0.90f,
                        zIndex = 10
                    )
                ),
                facecamConfig = FacecamConfig(
                    enabled = true,
                    normalizedX = 0.55f,
                    normalizedY = 0.05f,
                    normalizedWidth = 0.40f,
                    normalizedHeight = 0.40f,
                    scale = 1.0f,
                    mirrorHorizontal = true
                ),
                backgroundMode = SceneBackgroundMode.SCREEN_CAPTURE,
                orderIndex = 1
            )
        }

        fun createDefaultBrbScene(): BroadcastScene {
            return BroadcastScene(
                id = DEFAULT_SCENE_BRB_ID,
                name = "Be Right Back",
                overlayElements = listOf(
                    OverlayElement.createText(
                        id = "txt_brb_title",
                        config = TextOverlayConfig(
                            text = "☕ BE RIGHT BACK!",
                            fontSizeSp = 24f
                        ),
                        x = 0.25f,
                        y = 0.42f,
                        width = 0.50f,
                        opacity = 1.0f,
                        zIndex = 20
                    ),
                    OverlayElement.createQr(
                        id = "qr_social",
                        config = QrOverlayConfig(
                            payloadUrl = "fairy.live"
                        ),
                        x = 0.38f,
                        y = 0.55f,
                        width = 0.24f,
                        opacity = 0.95f,
                        zIndex = 20
                    )
                ),
                facecamConfig = FacecamConfig(
                    enabled = false
                ),
                backgroundMode = SceneBackgroundMode.SOLID_COLOR,
                backgroundColorArgb = 0xFF101420.toInt(),
                orderIndex = 2
            )
        }

        fun defaultScenes(): List<BroadcastScene> {
            return listOf(
                createDefaultGameplayScene(),
                createDefaultChattingScene(),
                createDefaultBrbScene()
            )
        }
    }
}
