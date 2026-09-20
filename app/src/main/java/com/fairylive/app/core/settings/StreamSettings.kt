package com.fairylive.app.core.settings

import com.fairylive.app.core.capability.model.StreamProfile

/**
 * Encapsulates streamer configuration, destination provider, and encoder parameters.
 * Clamps dimensions to valid encoder boundaries (multiples of 2 or 16).
 */
data class StreamSettings(
    val destination: StreamDestination = StreamDestination.YOUTUBE,
    val captureSource: CaptureSource = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.UPSIDE_DOWN_CAKE) CaptureSource.SINGLE_APP else CaptureSource.ENTIRE_SCREEN,
    val serverUrl: String = StreamDestination.YOUTUBE.defaultUrl,
    val streamKey: String = "",
    val profile: StreamProfile = StreamProfile.LOW,
    val width: Int = 854,
    val height: Int = 480,
    val fps: Int = 24,
    val videoBitrateKbps: Int = 1200,
    val audioBitrateKbps: Int = 96,
    val micEnabled: Boolean = true,
    val youtubeLiveUrl: String = ""
) {
    /**
     * Sanitizes settings to enforce hardware encoder alignment rules and
     * guarantees production destinations never point to localhost.
     */
    fun clamped(): StreamSettings {
        val safeWidth = (width.coerceIn(320, 1920) / 2) * 2
        val safeHeight = (height.coerceIn(240, 1080) / 2) * 2
        val safeFps = fps.coerceIn(15, 60)
        val safeVideoBitrate = videoBitrateKbps.coerceIn(500, 8000)
        val safeAudioBitrate = audioBitrateKbps.coerceIn(64, 320)

        val resolvedUrl = when (destination) {
            StreamDestination.YOUTUBE -> {
                // Production invariant: YouTube destination must resolve to official YouTube ingest
                if (serverUrl.isBlank() || serverUrl.contains("127.0.0.1") || serverUrl.contains("localhost") || serverUrl.contains("10.0.2.2")) {
                    StreamDestination.YOUTUBE.defaultUrl
                } else {
                    serverUrl
                }
            }
            StreamDestination.LOCAL_TEST -> {
                if (serverUrl.isBlank() || serverUrl == StreamDestination.YOUTUBE.defaultUrl) {
                    StreamDestination.LOCAL_TEST.defaultUrl
                } else {
                    serverUrl
                }
            }
            StreamDestination.CUSTOM_RTMP -> serverUrl
        }

        return copy(
            serverUrl = resolvedUrl,
            width = safeWidth,
            height = safeHeight,
            fps = safeFps,
            videoBitrateKbps = safeVideoBitrate,
            audioBitrateKbps = safeAudioBitrate
        )
    }

    companion object {
        fun fromProfile(
            profile: StreamProfile,
            destination: StreamDestination = StreamDestination.YOUTUBE,
            captureSource: CaptureSource = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.UPSIDE_DOWN_CAKE) CaptureSource.SINGLE_APP else CaptureSource.ENTIRE_SCREEN,
            serverUrl: String = "",
            streamKey: String = "",
            youtubeLiveUrl: String = ""
        ): StreamSettings {
            val effectiveUrl = if (serverUrl.isNotBlank()) {
                serverUrl
            } else {
                destination.defaultUrl
            }

            return StreamSettings(
                destination = destination,
                captureSource = captureSource,
                serverUrl = effectiveUrl,
                streamKey = streamKey,
                profile = profile,
                width = profile.width,
                height = profile.height,
                fps = profile.fps,
                videoBitrateKbps = profile.videoBitrateKbps,
                audioBitrateKbps = profile.audioBitrateKbps,
                micEnabled = true,
                youtubeLiveUrl = youtubeLiveUrl
            )
        }
    }
}
