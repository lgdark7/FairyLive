package com.fairylive.app.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

class StreamControlReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        val action = intent?.action ?: return
        Log.i("StreamControlReceiver", "Received broadcast action: $action")
        when (action) {
            FairyStreamService.ACTION_TOGGLE_FACECAM -> FairyStreamService.toggleFacecam(context)
            FairyStreamService.ACTION_SWITCH_FACECAM -> FairyStreamService.switchFacecam(context)
            FairyStreamService.ACTION_STOP_CAPTURE -> FairyStreamService.stopCapture(context)
            FairyStreamService.ACTION_SWITCH_SCENE,
            "com.fairylive.app.ACTION_SWITCH_SCENE" -> {
                val sceneId = intent.getStringExtra(FairyStreamService.EXTRA_SCENE_ID)
                    ?: intent.getStringExtra("scene_id")
                    ?: ""
                val transition = intent.getStringExtra(FairyStreamService.EXTRA_TRANSITION)
                    ?: intent.getStringExtra("transition")
                    ?: "CUT"
                FairyStreamService.switchScene(context, sceneId, transition)
            }
        }
    }
}
