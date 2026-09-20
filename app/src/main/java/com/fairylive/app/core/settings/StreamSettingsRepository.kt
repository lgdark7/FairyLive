package com.fairylive.app.core.settings

import android.content.Context
import android.content.SharedPreferences
import android.os.Build
import android.util.Log
import com.fairylive.app.core.capability.model.StreamProfile
import com.fairylive.app.core.security.SecureCredentialStorage

/**
 * Enterprise-grade repository managing streamer settings via SharedPreferences
 * with hardware/crypto-backed isolation for sensitive credentials (AES-256-GCM).
 * Non-sensitive stream configurations remain in fast SharedPreferences, while
 * stream keys and authentication tokens are encrypted via SecureCredentialStorage.
 */
class StreamSettingsRepository(
    context: Context,
    private val secureStorage: SecureCredentialStorage = SecureCredentialStorage(context)
) {

    private val prefs: SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun loadSettings(defaultProfile: StreamProfile = StreamProfile.BALANCED): StreamSettings {
        val profileName = prefs.getString(KEY_PROFILE, defaultProfile.name) ?: defaultProfile.name
        val profile = try {
            StreamProfile.valueOf(profileName)
        } catch (_: Exception) {
            defaultProfile
        }

        // 1. Resolve stream key with priority: Secure Storage -> Legacy SharedPreferences migration
        var streamKey = secureStorage.getStreamKey()
        if (streamKey == null) {
            val legacyKey = prefs.getString(KEY_LEGACY_STREAM_KEY, null)
            if (!legacyKey.isNullOrBlank()) {
                Log.i(TAG, "Migrating legacy stream key into secure encrypted storage")
                secureStorage.storeStreamKey(legacyKey)
                prefs.edit().remove(KEY_LEGACY_STREAM_KEY).apply()
                streamKey = legacyKey
            }
        }

        // 2. Resolve destination and sanitize stale development artifacts
        val destinationName = prefs.getString(KEY_DESTINATION, null)
        val rawServerUrl = prefs.getString(KEY_SERVER_URL, null)

        val destination = if (destinationName != null) {
            StreamDestination.fromName(destinationName)
        } else {
            // Unset destination: check if an old test endpoint was injected
            if (rawServerUrl != null && (rawServerUrl.contains("127.0.0.1") || rawServerUrl.contains("10.0.2.2") || rawServerUrl.contains("19350"))) {
                Log.w(TAG, "Sanitizing legacy/test localhost endpoint from stored preferences. Resetting to YouTube Live.")
                prefs.edit().putString(KEY_SERVER_URL, StreamDestination.YOUTUBE.defaultUrl).apply()
            }
            StreamDestination.YOUTUBE
        }

        val resolvedUrl = when (destination) {
            StreamDestination.YOUTUBE -> {
                // If YouTube is selected, guarantee it never points to localhost or empty
                if (rawServerUrl.isNullOrBlank() || rawServerUrl.contains("127.0.0.1") || rawServerUrl.contains("10.0.2.2") || rawServerUrl.contains("19350")) {
                    StreamDestination.YOUTUBE.defaultUrl
                } else {
                    rawServerUrl
                }
            }
            StreamDestination.LOCAL_TEST -> {
                rawServerUrl ?: StreamDestination.LOCAL_TEST.defaultUrl
            }
            StreamDestination.CUSTOM_RTMP -> {
                rawServerUrl ?: ""
            }
        }

        val defaultCaptureSource = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            CaptureSource.SINGLE_APP
        } else {
            CaptureSource.ENTIRE_SCREEN
        }
        val captureSourceName = prefs.getString(KEY_CAPTURE_SOURCE, null)
        val captureSource = if (captureSourceName != null) {
            try { CaptureSource.valueOf(captureSourceName) } catch (_: Exception) { defaultCaptureSource }
        } else {
            defaultCaptureSource
        }

        return StreamSettings(
            destination = destination,
            captureSource = captureSource,
            serverUrl = resolvedUrl,
            streamKey = streamKey ?: "",
            profile = profile,
            width = prefs.getInt(KEY_WIDTH, profile.width),
            height = prefs.getInt(KEY_HEIGHT, profile.height),
            fps = prefs.getInt(KEY_FPS, profile.fps),
            videoBitrateKbps = prefs.getInt(KEY_VIDEO_BITRATE, profile.videoBitrateKbps),
            audioBitrateKbps = prefs.getInt(KEY_AUDIO_BITRATE, profile.audioBitrateKbps),
            micEnabled = prefs.getBoolean(KEY_MIC_ENABLED, true),
            youtubeLiveUrl = prefs.getString(KEY_YOUTUBE_LIVE_URL, "") ?: ""
        ).clamped()
    }

    fun getYouTubeLiveUrl(): String = prefs.getString(KEY_YOUTUBE_LIVE_URL, "") ?: ""

    fun setYouTubeLiveUrl(url: String) {
        prefs.edit().putString(KEY_YOUTUBE_LIVE_URL, url.trim()).apply()
    }

    fun getCreatorName(): String = prefs.getString(KEY_CREATOR_NAME, "") ?: ""

    fun setCreatorName(name: String) {
        prefs.edit().putString(KEY_CREATOR_NAME, name.trim()).apply()
    }

    data class RecentStreamSession(
        val gameTitle: String = "Free Fire MAX",
        val packageName: String = "com.dts.freefiremax",
        val quality: String = "720p 30fps",
        val durationSeconds: Long = 0L,
        val timestampMs: Long = System.currentTimeMillis()
    )

    fun getRecentStreamSession(): RecentStreamSession? {
        val json = prefs.getString(KEY_RECENT_SESSION, null) ?: return null
        return try {
            val obj = org.json.JSONObject(json)
            RecentStreamSession(
                gameTitle = obj.optString("gameTitle", "Free Fire MAX"),
                packageName = obj.optString("packageName", "com.dts.freefiremax"),
                quality = obj.optString("quality", "720p 30fps"),
                durationSeconds = obj.optLong("durationSeconds", 0L),
                timestampMs = obj.optLong("timestampMs", System.currentTimeMillis())
            )
        } catch (_: Exception) {
            null
        }
    }

    fun saveRecentStreamSession(session: RecentStreamSession) {
        try {
            val obj = org.json.JSONObject().apply {
                put("gameTitle", session.gameTitle)
                put("packageName", session.packageName)
                put("quality", session.quality)
                put("durationSeconds", session.durationSeconds)
                put("timestampMs", session.timestampMs)
            }
            prefs.edit().putString(KEY_RECENT_SESSION, obj.toString()).apply()
        } catch (_: Exception) {}
    }

    fun saveSettings(settings: StreamSettings) {
        val clamped = settings.clamped()

        // 1. Encrypt and persist sensitive stream key in isolated secure storage
        secureStorage.storeStreamKey(clamped.streamKey)

        // 2. Persist non-sensitive operational parameters, explicitly purging any legacy plaintext stream key
        prefs.edit()
            .putString(KEY_DESTINATION, clamped.destination.name)
            .putString(KEY_CAPTURE_SOURCE, clamped.captureSource.name)
            .putString(KEY_SERVER_URL, clamped.serverUrl)
            .remove(KEY_LEGACY_STREAM_KEY)
            .putString(KEY_PROFILE, clamped.profile.name)
            .putInt(KEY_WIDTH, clamped.width)
            .putInt(KEY_HEIGHT, clamped.height)
            .putInt(KEY_FPS, clamped.fps)
            .putInt(KEY_VIDEO_BITRATE, clamped.videoBitrateKbps)
            .putInt(KEY_AUDIO_BITRATE, clamped.audioBitrateKbps)
            .putBoolean(KEY_MIC_ENABLED, clamped.micEnabled)
            .putString(KEY_YOUTUBE_LIVE_URL, clamped.youtubeLiveUrl)
            .apply()
    }

    fun clearCredentials() {
        secureStorage.clearStreamKey()
        prefs.edit().remove(KEY_LEGACY_STREAM_KEY).apply()
    }

    companion object {
        private const val TAG = "StreamSettingsRepository"
        private const val PREFS_NAME = "fairy_live_settings"
        private const val KEY_DESTINATION = "stream_destination"
        private const val KEY_CAPTURE_SOURCE = "capture_source"
        private const val KEY_SERVER_URL = "server_url"
        private const val KEY_LEGACY_STREAM_KEY = "stream_key"
        private const val KEY_PROFILE = "profile"
        private const val KEY_WIDTH = "width"
        private const val KEY_HEIGHT = "height"
        private const val KEY_FPS = "fps"
        private const val KEY_VIDEO_BITRATE = "video_bitrate"
        private const val KEY_AUDIO_BITRATE = "audio_bitrate"
        private const val KEY_MIC_ENABLED = "mic_enabled"
        private const val KEY_YOUTUBE_LIVE_URL = "youtube_live_url"
        private const val KEY_CREATOR_NAME = "creator_name"
        private const val KEY_RECENT_SESSION = "recent_stream_session"
    }
}
