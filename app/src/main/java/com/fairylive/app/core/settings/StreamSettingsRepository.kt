package com.fairylive.app.core.settings

import android.content.Context
import android.content.SharedPreferences
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

    fun loadSettings(defaultProfile: StreamProfile = StreamProfile.LOW): StreamSettings {
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

        return StreamSettings(
            serverUrl = prefs.getString(KEY_SERVER_URL, "rtmp://live.fairylive.tv/live") ?: "rtmp://live.fairylive.tv/live",
            streamKey = streamKey ?: "",
            profile = profile,
            width = prefs.getInt(KEY_WIDTH, profile.width),
            height = prefs.getInt(KEY_HEIGHT, profile.height),
            fps = prefs.getInt(KEY_FPS, profile.fps),
            videoBitrateKbps = prefs.getInt(KEY_VIDEO_BITRATE, profile.videoBitrateKbps),
            audioBitrateKbps = prefs.getInt(KEY_AUDIO_BITRATE, profile.audioBitrateKbps),
            micEnabled = prefs.getBoolean(KEY_MIC_ENABLED, true)
        ).clamped()
    }

    fun saveSettings(settings: StreamSettings) {
        val clamped = settings.clamped()

        // 1. Encrypt and persist sensitive stream key in isolated secure storage
        secureStorage.storeStreamKey(clamped.streamKey)

        // 2. Persist non-sensitive operational parameters, explicitly purging any legacy plaintext stream key
        prefs.edit()
            .putString(KEY_SERVER_URL, clamped.serverUrl)
            .remove(KEY_LEGACY_STREAM_KEY)
            .putString(KEY_PROFILE, clamped.profile.name)
            .putInt(KEY_WIDTH, clamped.width)
            .putInt(KEY_HEIGHT, clamped.height)
            .putInt(KEY_FPS, clamped.fps)
            .putInt(KEY_VIDEO_BITRATE, clamped.videoBitrateKbps)
            .putInt(KEY_AUDIO_BITRATE, clamped.audioBitrateKbps)
            .putBoolean(KEY_MIC_ENABLED, clamped.micEnabled)
            .apply()
    }

    fun clearCredentials() {
        secureStorage.clearStreamKey()
        prefs.edit().remove(KEY_LEGACY_STREAM_KEY).apply()
    }

    companion object {
        private const val TAG = "StreamSettingsRepository"
        private const val PREFS_NAME = "fairy_live_settings"
        private const val KEY_SERVER_URL = "server_url"
        private const val KEY_LEGACY_STREAM_KEY = "stream_key"
        private const val KEY_PROFILE = "profile"
        private const val KEY_WIDTH = "width"
        private const val KEY_HEIGHT = "height"
        private const val KEY_FPS = "fps"
        private const val KEY_VIDEO_BITRATE = "video_bitrate"
        private const val KEY_AUDIO_BITRATE = "audio_bitrate"
        private const val KEY_MIC_ENABLED = "mic_enabled"
    }
}
