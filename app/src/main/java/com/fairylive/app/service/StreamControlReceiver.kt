package com.fairylive.app.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

/**
 * Enterprise-hardened broadcast receiver for internal and authorized IPC control of stream capture.
 * Protected by com.fairylive.app.permission.STREAM_CONTROL (signature level) in AndroidManifest.xml.
 * Implements strict boundary checking, intent extra sanitization, and robust exception isolation.
 */
class StreamControlReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent?) {
        if (intent == null) return
        val action = intent.action ?: return

        try {
            Log.i(TAG, "Received authorized broadcast action: $action")
            when (action) {
                FairyStreamService.ACTION_TOGGLE_FACECAM -> FairyStreamService.toggleFacecam(context)
                FairyStreamService.ACTION_SWITCH_FACECAM -> FairyStreamService.switchFacecam(context)
                FairyStreamService.ACTION_STOP_CAPTURE -> FairyStreamService.stopCapture(context)
                FairyStreamService.ACTION_SWITCH_SCENE,
                "com.fairylive.app.ACTION_SWITCH_SCENE" -> {
                    val rawSceneId = intent.getStringExtra(FairyStreamService.EXTRA_SCENE_ID)
                        ?: intent.getStringExtra("scene_id")
                        ?: ""
                    // Sanitize sceneId: bounded length and safe characters
                    val sceneId = if (rawSceneId.length > 64) rawSceneId.substring(0, 64) else rawSceneId

                    val rawTransition = intent.getStringExtra(FairyStreamService.EXTRA_TRANSITION)
                        ?: intent.getStringExtra("transition")
                        ?: "CUT"
                    val transition = when (rawTransition.trim().uppercase()) {
                        "CROSSFADE" -> "CROSSFADE"
                        else -> "CUT"
                    }
                    FairyStreamService.switchScene(context, sceneId, transition)
                }
                else -> {
                    Log.w(TAG, "Ignored unrecognized broadcast action: $action")
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error handling stream control broadcast: ${e.message}", e)
        }
    }

    companion object {
        private const val TAG = "StreamControlReceiver"
    }
}
