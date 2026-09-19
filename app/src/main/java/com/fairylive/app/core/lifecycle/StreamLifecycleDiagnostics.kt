package com.fairylive.app.core.lifecycle

import android.util.Log
import com.fairylive.app.core.settings.StreamConfigurationValidator
import java.util.concurrent.ConcurrentLinkedQueue

/**
 * Thread-safe lifecycle diagnostics logger and event tracker for FAIRY LIVE.
 * Implements strict credential-safe diagnostics without disclosing keys or passwords.
 */
object StreamLifecycleDiagnostics {

    private const val TAG = "FairyLifecycle"

    data class LifecycleEvent(
        val tag: String,
        val details: String = "",
        val timestampMs: Long = System.currentTimeMillis()
    )

    private val eventHistory = ConcurrentLinkedQueue<LifecycleEvent>()
    private const val MAX_HISTORY = 100

    fun log(tag: String, details: String = "") {
        val safeDetails = sanitize(details)
        val event = LifecycleEvent(tag = tag, details = safeDetails)
        eventHistory.add(event)
        while (eventHistory.size > MAX_HISTORY) {
            eventHistory.poll()
        }

        if (safeDetails.isNotBlank()) {
            Log.i(TAG, "$tag: $safeDetails")
        } else {
            Log.i(TAG, tag)
        }
    }

    // Explicit convenience helpers for mandatory lifecycle gates
    fun captureRequested(source: String) = log("CAPTURE_REQUESTED", "source=$source")
    fun capturePermissionGranted() = log("CAPTURE_PERMISSION_GRANTED")
    fun captureSourceSelected(mode: String) = log("CAPTURE_SOURCE_SELECTED", mode)
    fun projectionCreated() = log("PROJECTION_CREATED")
    fun virtualDisplayCreated(width: Int, height: Int, dpi: Int) =
        log("VIRTUAL_DISPLAY_CREATED", "${width}x${height}@${dpi}dpi")
    fun surfaceCreated(surfaceTag: String) = log("SURFACE_CREATED", surfaceTag)
    fun encoderStarted(width: Int, height: Int, fps: Int, mime: String) =
        log("ENCODER_STARTED", "${width}x${height}@${fps}fps ($mime)")
    fun audioStarted(sampleRate: Int, channels: Int) =
        log("AUDIO_STARTED", "${sampleRate}Hz, ${channels}ch")
    fun rtmpConnecting(serverUrl: String, streamKey: String) =
        log("RTMP_CONNECTING", StreamConfigurationValidator.formatSafeEndpoint(serverUrl, streamKey))
    fun rtmpConnected() = log("RTMP_CONNECTED")
    fun rtmpHandshakeComplete() = log("RTMP_HANDSHAKE_COMPLETE")
    fun rtmpPublishStarted() = log("RTMP_PUBLISH_STARTED")
    fun firstVideoFrame(ptsUs: Long) = log("FIRST_VIDEO_FRAME", "ptsUs=$ptsUs")
    fun firstAudioFrame(ptsUs: Long) = log("FIRST_AUDIO_FRAME", "ptsUs=$ptsUs")
    fun streamRunning() = log("STREAM_RUNNING")
    fun streamStopRequested(reason: StreamStopReason, initiator: String) =
        log("STREAM_STOP_REQUESTED", "reason=$reason, by=$initiator")
    fun streamStopReason(reason: StreamStopReason, details: String) =
        log("STREAM_STOP_REASON", "$reason: $details")
    fun projectionStopped() = log("PROJECTION_STOPPED")
    fun encoderStopped() = log("ENCODER_STOPPED")
    fun rtmpDisconnected() = log("RTMP_DISCONNECTED")

    fun getRecentEvents(): List<LifecycleEvent> = eventHistory.toList()

    fun clear() {
        eventHistory.clear()
    }

    private fun sanitize(input: String): String {
        // Redact any raw stream key or sensitive token patterns if present
        return input.replace(Regex("""(live_[a-zA-Z0-9_-]{4})[a-zA-Z0-9_-]+"""), "$1••••••••")
    }
}
