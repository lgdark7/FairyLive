package com.fairylive.app.core.settings

import com.fairylive.app.core.capability.model.StreamProfile

/**
 * Encapsulates streamer configuration and encoder parameters.
 * Clamps dimensions to valid encoder boundaries (multiples of 2 or 16).
 */
data class StreamSettings(
    val serverUrl: String = "rtmp://live.fairylive.tv/live",
    val streamKey: String = "",
    val profile: StreamProfile = StreamProfile.LOW,
    val width: Int = 854,
    val height: Int = 480,
    val fps: Int = 24,
    val videoBitrateKbps: Int = 1200,
    val audioBitrateKbps: Int = 96,
    val micEnabled: Boolean = true
) {
    /**
     * Sanitizes settings to enforce hardware encoder alignment rules.
     */
    fun clamped(): StreamSettings {
        val safeWidth = (width.coerceIn(320, 1920) / 2) * 2
        val safeHeight = (height.coerceIn(240, 1080) / 2) * 2
        val safeFps = fps.coerceIn(15, 60)
        val safeVideoBitrate = videoBitrateKbps.coerceIn(500, 8000)
        val safeAudioBitrate = audioBitrateKbps.coerceIn(64, 320)

        return copy(
            width = safeWidth,
            height = safeHeight,
            fps = safeFps,
            videoBitrateKbps = safeVideoBitrate,
            audioBitrateKbps = safeAudioBitrate
        )
    }

    companion object {
        fun fromProfile(profile: StreamProfile, serverUrl: String = "", streamKey: String = ""): StreamSettings {
            return StreamSettings(
                serverUrl = serverUrl.ifEmpty { "rtmp://live.fairylive.tv/live" },
                streamKey = streamKey,
                profile = profile,
                width = profile.width,
                height = profile.height,
                fps = profile.fps,
                videoBitrateKbps = profile.videoBitrateKbps,
                audioBitrateKbps = profile.audioBitrateKbps,
                micEnabled = true
            )
        }
    }
}
