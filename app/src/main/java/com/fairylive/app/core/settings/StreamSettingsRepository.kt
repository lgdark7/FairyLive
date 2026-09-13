package com.fairylive.app.core.settings

import android.content.Context
import android.content.SharedPreferences
import com.fairylive.app.core.capability.model.StreamProfile

/**
 * Lightweight repository managing streamer settings via SharedPreferences.
 * Zero-dependency and instant access.
 */
class StreamSettingsRepository(context: Context) {

    private val prefs: SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun loadSettings(defaultProfile: StreamProfile = StreamProfile.LOW): StreamSettings {
        val profileName = prefs.getString(KEY_PROFILE, defaultProfile.name) ?: defaultProfile.name
        val profile = try {
            StreamProfile.valueOf(profileName)
        } catch (_: Exception) {
            defaultProfile
        }

        return StreamSettings(
            serverUrl = prefs.getString(KEY_SERVER_URL, "rtmp://live.fairylive.tv/live") ?: "rtmp://live.fairylive.tv/live",
            streamKey = prefs.getString(KEY_STREAM_KEY, "") ?: "",
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
        prefs.edit()
            .putString(KEY_SERVER_URL, clamped.serverUrl)
            .putString(KEY_STREAM_KEY, clamped.streamKey)
            .putString(KEY_PROFILE, clamped.profile.name)
            .putInt(KEY_WIDTH, clamped.width)
            .putInt(KEY_HEIGHT, clamped.height)
            .putInt(KEY_FPS, clamped.fps)
            .putInt(KEY_VIDEO_BITRATE, clamped.videoBitrateKbps)
            .putInt(KEY_AUDIO_BITRATE, clamped.audioBitrateKbps)
            .putBoolean(KEY_MIC_ENABLED, clamped.micEnabled)
            .apply()
    }

    companion object {
        private const val PREFS_NAME = "fairy_live_settings"
        private const val KEY_SERVER_URL = "server_url"
        private const val KEY_STREAM_KEY = "stream_key"
        private const val KEY_PROFILE = "profile"
        private const val KEY_WIDTH = "width"
        private const val KEY_HEIGHT = "height"
        private const val KEY_FPS = "fps"
        private const val KEY_VIDEO_BITRATE = "video_bitrate"
        private const val KEY_AUDIO_BITRATE = "audio_bitrate"
        private const val KEY_MIC_ENABLED = "mic_enabled"
    }
}
