package com.fairylive.app.core.settings

/**
 * Enterprise classification of streaming ingest destinations.
 * Enforces strict isolation between production broadcasting destinations (e.g. YouTube Live)
 * and development/acceptance local test servers.
 */
enum class StreamDestination(val displayName: String, val defaultUrl: String) {
    YOUTUBE("YouTube Live", "rtmp://a.rtmp.youtube.com:1935/live2"),
    CUSTOM_RTMP("Custom RTMP", ""),
    LOCAL_TEST("Local Test Server (Dev Only)", "rtmp://127.0.0.1:19350/live");

    val isProduction: Boolean
        get() = this == YOUTUBE || this == CUSTOM_RTMP

    companion object {
        fun fromName(name: String?): StreamDestination {
            if (name.isNullOrBlank()) return YOUTUBE
            return try {
                valueOf(name.trim().uppercase())
            } catch (_: Exception) {
                YOUTUBE
            }
        }
    }
}
