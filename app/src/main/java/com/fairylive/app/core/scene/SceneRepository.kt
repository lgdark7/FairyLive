package com.fairylive.app.core.scene

import android.content.Context
import android.content.SharedPreferences
import com.fairylive.app.core.camera.model.FacecamConfig
import com.fairylive.app.core.compositor.model.OverlayElement
import com.fairylive.app.core.compositor.model.OverlayType
import com.fairylive.app.core.compositor.model.TextOverlayConfig
import com.fairylive.app.core.scene.model.BroadcastScene
import com.fairylive.app.core.scene.model.SceneBackgroundMode
import org.json.JSONArray
import org.json.JSONObject

/**
 * Lightweight persistent storage for broadcast scenes using JSON in SharedPreferences.
 * Zero external dependencies (uses standard org.json).
 */
class SceneRepository(context: Context) {

    private val prefs: SharedPreferences = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun saveScenes(scenes: List<BroadcastScene>) {
        val array = JSONArray()
        for (scene in scenes) {
            array.put(serializeScene(scene))
        }
        prefs.edit().putString(KEY_SCENES_JSON, array.toString()).apply()
    }

    fun loadScenes(): List<BroadcastScene> {
        val jsonStr = prefs.getString(KEY_SCENES_JSON, null) ?: return emptyList()
        val result = mutableListOf<BroadcastScene>()
        try {
            val array = JSONArray(jsonStr)
            for (i in 0 until array.length()) {
                val obj = array.getJSONObject(i)
                deserializeScene(obj)?.let { result.add(it) }
            }
        } catch (e: Exception) {
            return emptyList()
        }
        return result
    }

    fun saveActiveSceneId(sceneId: String) {
        prefs.edit().putString(KEY_ACTIVE_SCENE_ID, sceneId).apply()
    }

    fun loadActiveSceneId(): String? {
        return prefs.getString(KEY_ACTIVE_SCENE_ID, null)
    }

    private fun serializeScene(scene: BroadcastScene): JSONObject {
        val obj = JSONObject()
        obj.put("id", scene.id)
        obj.put("name", scene.name)
        obj.put("orderIndex", scene.orderIndex)
        obj.put("backgroundMode", scene.backgroundMode.name)
        obj.put("backgroundColorArgb", scene.backgroundColorArgb)

        // Facecam
        val fc = JSONObject()
        fc.put("enabled", scene.facecamConfig.enabled)
        fc.put("normalizedX", scene.facecamConfig.normalizedX.toDouble())
        fc.put("normalizedY", scene.facecamConfig.normalizedY.toDouble())
        fc.put("normalizedWidth", scene.facecamConfig.normalizedWidth.toDouble())
        fc.put("normalizedHeight", scene.facecamConfig.normalizedHeight.toDouble())
        fc.put("scale", scene.facecamConfig.scale.toDouble())
        fc.put("mirrorHorizontal", scene.facecamConfig.mirrorHorizontal)
        obj.put("facecam", fc)

        // Overlays
        val overlays = JSONArray()
        for (el in scene.overlayElements) {
            val elObj = JSONObject()
            elObj.put("id", el.id)
            elObj.put("type", el.type.name)
            elObj.put("normalizedX", el.normalizedX.toDouble())
            elObj.put("normalizedY", el.normalizedY.toDouble())
            elObj.put("normalizedWidth", el.normalizedWidth.toDouble())
            elObj.put("opacity", el.opacity.toDouble())
            elObj.put("visible", el.visible)
            elObj.put("zIndex", el.zIndex)
            if (el.textConfig != null) {
                elObj.put("text", el.textConfig.text)
            }
            if (el.resourcePath != null) {
                elObj.put("resourcePath", el.resourcePath)
            }
            overlays.put(elObj)
        }
        obj.put("overlays", overlays)

        return obj
    }

    private fun deserializeScene(obj: JSONObject): BroadcastScene? {
        return try {
            val id = obj.getString("id")
            val name = obj.getString("name")
            val orderIndex = obj.optInt("orderIndex", 0)
            val bgModeStr = obj.optString("backgroundMode", SceneBackgroundMode.SCREEN_CAPTURE.name)
            val bgMode = try { SceneBackgroundMode.valueOf(bgModeStr) } catch (_: Exception) { SceneBackgroundMode.SCREEN_CAPTURE }
            val bgColor = obj.optInt("backgroundColorArgb", 0xFF000000.toInt())

            val fcObj = obj.optJSONObject("facecam")
            val facecam = if (fcObj != null) {
                FacecamConfig(
                    enabled = fcObj.optBoolean("enabled", false),
                    normalizedX = fcObj.optDouble("normalizedX", 0.70).toFloat(),
                    normalizedY = fcObj.optDouble("normalizedY", 0.04).toFloat(),
                    normalizedWidth = fcObj.optDouble("normalizedWidth", 0.26).toFloat(),
                    normalizedHeight = fcObj.optDouble("normalizedHeight", 0.26).toFloat(),
                    scale = fcObj.optDouble("scale", 1.0).toFloat(),
                    mirrorHorizontal = fcObj.optBoolean("mirrorHorizontal", true)
                )
            } else {
                FacecamConfig()
            }

            val overlayList = mutableListOf<OverlayElement>()
            val overlaysArr = obj.optJSONArray("overlays")
            if (overlaysArr != null) {
                for (i in 0 until overlaysArr.length()) {
                    val elObj = overlaysArr.getJSONObject(i)
                    val elId = elObj.getString("id")
                    val typeStr = elObj.getString("type")
                    val type = try { OverlayType.valueOf(typeStr) } catch (_: Exception) { OverlayType.TEXT }
                    val posX = elObj.optDouble("normalizedX", 0.0).toFloat()
                    val posY = elObj.optDouble("normalizedY", 0.0).toFloat()
                    val width = elObj.optDouble("normalizedWidth", 0.3).toFloat()
                    val opacity = elObj.optDouble("opacity", 1.0).toFloat()
                    val visible = elObj.optBoolean("visible", true)
                    val zIndex = elObj.optInt("zIndex", 0)
                    val text = if (elObj.has("text")) elObj.optString("text") else null
                    val resPath = if (elObj.has("resourcePath")) elObj.optString("resourcePath") else null

                    val textCfg = if (text != null && type == OverlayType.TEXT) TextOverlayConfig(text = text) else null

                    overlayList.add(
                        OverlayElement(
                            id = elId,
                            type = type,
                            normalizedX = posX,
                            normalizedY = posY,
                            normalizedWidth = width,
                            opacity = opacity,
                            visible = visible,
                            zIndex = zIndex,
                            textConfig = textCfg,
                            resourcePath = resPath
                        )
                    )
                }
            }

            BroadcastScene(
                id = id,
                name = name,
                overlayElements = overlayList,
                facecamConfig = facecam,
                backgroundMode = bgMode,
                backgroundColorArgb = bgColor,
                orderIndex = orderIndex
            )
        } catch (e: Exception) {
            null
        }
    }

    companion object {
        private const val TAG = "SceneRepository"
        private const val PREFS_NAME = "fairy_scenes_prefs"
        private const val KEY_SCENES_JSON = "key_scenes_json"
        private const val KEY_ACTIVE_SCENE_ID = "key_active_scene_id"
    }
}
