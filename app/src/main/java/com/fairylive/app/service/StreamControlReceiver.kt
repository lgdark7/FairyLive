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
        }
    }
}
