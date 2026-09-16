package com.fairylive.app.core.scene

import android.content.Context
import android.content.SharedPreferences
import com.fairylive.app.core.camera.model.CameraFacing
import com.fairylive.app.core.camera.model.FacecamConfig
import com.fairylive.app.core.compositor.model.AlertOverlayConfig
import com.fairylive.app.core.compositor.model.GifOverlayConfig
import com.fairylive.app.core.compositor.model.GoalOverlayConfig
import com.fairylive.app.core.compositor.model.LowerThirdOverlayConfig
import com.fairylive.app.core.compositor.model.OverlayElement
import com.fairylive.app.core.compositor.model.OverlayType
import com.fairylive.app.core.compositor.model.QrOverlayConfig
import com.fairylive.app.core.compositor.model.TextOverlayConfig
import com.fairylive.app.core.compositor.model.WidgetOverlayConfig
import com.fairylive.app.core.scene.model.BroadcastScene
import com.fairylive.app.core.scene.model.SceneBackgroundMode
import org.json.JSONArray
import org.json.JSONObject

/**
 * Lightweight persistent storage for broadcast scenes using JSON in SharedPreferences.
 * Zero external dependencies (uses standard org.json).
 * Full support for all 9 overlay types and their complete configuration models.
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
        fc.put("facing", scene.facecamConfig.facing.name)
        fc.put("isCircle", scene.facecamConfig.isCircle)
        fc.put("showBorder", scene.facecamConfig.showBorder)
        fc.put("opacity", scene.facecamConfig.opacity.toDouble())
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
            elObj.put("normalizedHeight", el.normalizedHeight.toDouble())
            elObj.put("scale", el.scale.toDouble())
            elObj.put("rotationDegrees", el.rotationDegrees.toDouble())
            elObj.put("opacity", el.opacity.toDouble())
            elObj.put("visible", el.visible)
            elObj.put("locked", el.locked)
            elObj.put("zIndex", el.zIndex)
            elObj.put("cornerRadiusPx", el.cornerRadiusPx.toDouble())

            if (el.resourcePath != null) {
                elObj.put("resourcePath", el.resourcePath)
            }

            // Type-specific configs
            el.textConfig?.let { tc ->
                val to = JSONObject()
                to.put("text", tc.text)
                to.put("fontSizeSp", tc.fontSizeSp.toDouble())
                to.put("textColorRgba", tc.textColorRgba)
                to.put("backgroundColorRgba", tc.backgroundColorRgba)
                to.put("fontStyle", tc.fontStyle)
                to.put("paddingPx", tc.paddingPx)
                to.put("cornerRadiusPx", tc.cornerRadiusPx.toDouble())
                to.put("alignment", tc.alignment)
                elObj.put("textConfig", to)
            }

            el.goalConfig?.let { gc ->
                val go = JSONObject()
                go.put("title", gc.title)
                go.put("currentVal", gc.currentVal)
                go.put("targetVal", gc.targetVal)
                go.put("unitPrefix", gc.unitPrefix)
                go.put("barColorRgba", gc.barColorRgba)
                go.put("trackColorRgba", gc.trackColorRgba)
                go.put("backgroundColorRgba", gc.backgroundColorRgba)
                elObj.put("goalConfig", go)
            }

            el.lowerThirdConfig?.let { ltc ->
                val lto = JSONObject()
                lto.put("title", ltc.title)
                lto.put("subtitle", ltc.subtitle)
                lto.put("accentColorRgba", ltc.accentColorRgba)
                lto.put("backgroundColorRgba", ltc.backgroundColorRgba)
                lto.put("durationMs", ltc.durationMs)
                elObj.put("lowerThirdConfig", lto)
            }

            el.alertConfig?.let { ac ->
                val ao = JSONObject()
                ao.put("headline", ac.headline)
                ao.put("subtext", ac.subtext)
                ao.put("durationMs", ac.durationMs)
                ao.put("badgeColorRgba", ac.badgeColorRgba)
                ac.soundAsset?.let { ao.put("soundAsset", it) }
                ao.put("createdAtMs", ac.createdAtMs)
                elObj.put("alertConfig", ao)
            }

            el.qrConfig?.let { qc ->
                val qo = JSONObject()
                qo.put("payloadUrl", qc.payloadUrl)
                qo.put("foregroundColorRgba", qc.foregroundColorRgba)
                qo.put("backgroundColorRgba", qc.backgroundColorRgba)
                qo.put("borderPaddingModules", qc.borderPaddingModules)
                elObj.put("qrConfig", qo)
            }

            el.gifConfig?.let { gc ->
                val go = JSONObject()
                go.put("resourcePath", gc.resourcePath)
                go.put("playbackSpeed", gc.playbackSpeed.toDouble())
                go.put("isLooping", gc.isLooping)
                elObj.put("gifConfig", go)
            }

            el.widgetConfig?.let { wc ->
                val wo = JSONObject()
                wo.put("widgetType", wc.widgetType)
                wo.put("label", wc.label)
                wo.put("value", wc.value)
                wo.put("accentColorRgba", wc.accentColorRgba)
                elObj.put("widgetConfig", wo)
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
                val facingStr = fcObj.optString("facing", CameraFacing.FRONT.name)
                val facing = try { CameraFacing.valueOf(facingStr) } catch (_: Exception) { CameraFacing.FRONT }
                FacecamConfig(
                    enabled = fcObj.optBoolean("enabled", false),
                    facing = facing,
                    normalizedX = fcObj.optDouble("normalizedX", 0.70).toFloat(),
                    normalizedY = fcObj.optDouble("normalizedY", 0.04).toFloat(),
                    normalizedWidth = fcObj.optDouble("normalizedWidth", 0.26).toFloat(),
                    normalizedHeight = fcObj.optDouble("normalizedHeight", 0.26).toFloat(),
                    scale = fcObj.optDouble("scale", 1.0).toFloat(),
                    mirrorHorizontal = fcObj.optBoolean("mirrorHorizontal", true),
                    isCircle = fcObj.optBoolean("isCircle", false),
                    showBorder = fcObj.optBoolean("showBorder", false),
                    opacity = fcObj.optDouble("opacity", 1.0).toFloat()
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
                    val height = elObj.optDouble("normalizedHeight", 0.0).toFloat()
                    val scale = elObj.optDouble("scale", 1.0).toFloat()
                    val rot = elObj.optDouble("rotationDegrees", 0.0).toFloat()
                    val opacity = elObj.optDouble("opacity", 1.0).toFloat()
                    val visible = elObj.optBoolean("visible", true)
                    val locked = elObj.optBoolean("locked", false)
                    val zIndex = elObj.optInt("zIndex", 0)
                    val cornerRadius = elObj.optDouble("cornerRadiusPx", 0.0).toFloat()
                    val resPath = if (elObj.has("resourcePath")) elObj.optString("resourcePath") else null

                    // Type configs
                    var textCfg: TextOverlayConfig? = null
                    if (elObj.has("textConfig")) {
                        val tc = elObj.getJSONObject("textConfig")
                        textCfg = TextOverlayConfig(
                            text = tc.getString("text"),
                            fontSizeSp = tc.optDouble("fontSizeSp", 24.0).toFloat(),
                            textColorRgba = tc.optInt("textColorRgba", -1),
                            backgroundColorRgba = tc.optInt("backgroundColorRgba", 0),
                            fontStyle = tc.optInt("fontStyle", 0),
                            paddingPx = tc.optInt("paddingPx", 16),
                            cornerRadiusPx = tc.optDouble("cornerRadiusPx", 8.0).toFloat(),
                            alignment = tc.optString("alignment", "LEFT")
                        )
                    } else if (elObj.has("text")) {
                        // Backward compatibility
                        textCfg = TextOverlayConfig(text = elObj.getString("text"))
                    }

                    var goalCfg: GoalOverlayConfig? = null
                    if (elObj.has("goalConfig")) {
                        val gc = elObj.getJSONObject("goalConfig")
                        goalCfg = GoalOverlayConfig(
                            title = gc.getString("title"),
                            currentVal = gc.optInt("currentVal", 0),
                            targetVal = gc.optInt("targetVal", 100),
                            unitPrefix = gc.optString("unitPrefix", ""),
                            barColorRgba = gc.optInt("barColorRgba", 0),
                            trackColorRgba = gc.optInt("trackColorRgba", 0),
                            backgroundColorRgba = gc.optInt("backgroundColorRgba", 0)
                        )
                    }

                    var lowerThirdCfg: LowerThirdOverlayConfig? = null
                    if (elObj.has("lowerThirdConfig")) {
                        val ltc = elObj.getJSONObject("lowerThirdConfig")
                        lowerThirdCfg = LowerThirdOverlayConfig(
                            title = ltc.getString("title"),
                            subtitle = ltc.getString("subtitle"),
                            accentColorRgba = ltc.optInt("accentColorRgba", 0),
                            backgroundColorRgba = ltc.optInt("backgroundColorRgba", 0),
                            durationMs = ltc.optLong("durationMs", 0L)
                        )
                    }

                    var alertCfg: AlertOverlayConfig? = null
                    if (elObj.has("alertConfig")) {
                        val ac = elObj.getJSONObject("alertConfig")
                        alertCfg = AlertOverlayConfig(
                            headline = ac.getString("headline"),
                            subtext = ac.getString("subtext"),
                            durationMs = ac.optLong("durationMs", 4000L),
                            badgeColorRgba = ac.optInt("badgeColorRgba", 0),
                            soundAsset = if (ac.has("soundAsset")) ac.getString("soundAsset") else null,
                            createdAtMs = ac.optLong("createdAtMs", 0L)
                        )
                    }

                    var qrCfg: QrOverlayConfig? = null
                    if (elObj.has("qrConfig")) {
                        val qc = elObj.getJSONObject("qrConfig")
                        qrCfg = QrOverlayConfig(
                            payloadUrl = qc.getString("payloadUrl"),
                            foregroundColorRgba = qc.optInt("foregroundColorRgba", -16777216),
                            backgroundColorRgba = qc.optInt("backgroundColorRgba", -1),
                            borderPaddingModules = qc.optInt("borderPaddingModules", 2)
                        )
                    }

                    var gifCfg: GifOverlayConfig? = null
                    if (elObj.has("gifConfig")) {
                        val gc = elObj.getJSONObject("gifConfig")
                        gifCfg = GifOverlayConfig(
                            resourcePath = gc.getString("resourcePath"),
                            playbackSpeed = gc.optDouble("playbackSpeed", 1.0).toFloat(),
                            isLooping = gc.optBoolean("isLooping", true)
                        )
                    }

                    var widgetCfg: WidgetOverlayConfig? = null
                    if (elObj.has("widgetConfig")) {
                        val wc = elObj.getJSONObject("widgetConfig")
                        widgetCfg = WidgetOverlayConfig(
                            widgetType = wc.getString("widgetType"),
                            label = wc.getString("label"),
                            value = wc.getString("value"),
                            accentColorRgba = wc.optInt("accentColorRgba", 0)
                        )
                    }

                    overlayList.add(
                        OverlayElement(
                            id = elId,
                            type = type,
                            normalizedX = posX,
                            normalizedY = posY,
                            normalizedWidth = width,
                            normalizedHeight = height,
                            scale = scale,
                            rotationDegrees = rot,
                            opacity = opacity,
                            visible = visible,
                            locked = locked,
                            zIndex = zIndex,
                            cornerRadiusPx = cornerRadius,
                            textConfig = textCfg,
                            goalConfig = goalCfg,
                            lowerThirdConfig = lowerThirdCfg,
                            alertConfig = alertCfg,
                            qrConfig = qrCfg,
                            gifConfig = gifCfg,
                            widgetConfig = widgetCfg,
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
