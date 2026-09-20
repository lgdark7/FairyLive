package com.fairylive.app.core.audio

import android.content.Context
import android.util.Log

/**
 * Enterprise-grade persistence and state repository for the 4-channel DSP Audio Studio.
 *
 * Guarantees that adjustments made to:
 * - Device Microphone Volume (0 - 150%)
 * - Game & System Audio Volume (0 - 150%)
 * - Media & BGM Volume (0 - 100%)
 * - Master Stream Output Volume (0 - 100%)
 * - Mute states (Mic, Game, Media)
 * - DSP Toggles (Noise Suppression/Gate, AGC, Compressor, Limiter)
 * - Studio Presets (Gaming, Voice Focus, Music, Balanced, Low-End)
 *
 * are permanently persisted across dialog closes, HUD toggles, stream restarts,
 * and app launches.
 */
object AudioStudioRepository {

    private const val TAG = "AudioStudioRepository"
    private const val PREFS_NAME = "fairy_audio_studio_prefs"

    private const val KEY_MIC_VOL = "mic_volume"
    private const val KEY_GAME_VOL = "game_volume"
    private const val KEY_MEDIA_VOL = "media_volume"
    private const val KEY_MASTER_VOL = "master_volume"

    private const val KEY_MIC_MUTED = "mic_muted"
    private const val KEY_GAME_MUTED = "game_muted"
    private const val KEY_MEDIA_MUTED = "media_muted"

    private const val KEY_NOISE_GATE = "noise_gate_enabled"
    private const val KEY_GOD_MODE_NOISE = "god_mode_noise_enabled"
    private const val KEY_AGC = "agc_enabled"
    private const val KEY_COMPRESSOR = "compressor_enabled"
    private const val KEY_LIMITER = "limiter_enabled"
    private const val KEY_PRESET = "active_preset"

    @Volatile var micVolume: Float = 0.9f
    @Volatile var gameVolume: Float = 0.8f
    @Volatile var mediaVolume: Float = 0.5f
    @Volatile var masterVolume: Float = 1.0f

    @Volatile var isMicMuted: Boolean = false
    @Volatile var isGameMuted: Boolean = false
    @Volatile var isMediaMuted: Boolean = false

    @Volatile var isNoiseSuppressionEnabled: Boolean = true
    @Volatile var isGodModeNoiseEnabled: Boolean = true
    @Volatile var isAgcEnabled: Boolean = false
    @Volatile var isCompressorEnabled: Boolean = true
    @Volatile var isLimiterEnabled: Boolean = true
    @Volatile var activePreset: String = "GAMING"

    @Volatile private var isInitialized: Boolean = false

    @Synchronized
    fun init(context: Context) {
        if (isInitialized) return
        try {
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            micVolume = prefs.getFloat(KEY_MIC_VOL, 0.9f)
            gameVolume = prefs.getFloat(KEY_GAME_VOL, 0.8f)
            mediaVolume = prefs.getFloat(KEY_MEDIA_VOL, 0.5f)
            masterVolume = prefs.getFloat(KEY_MASTER_VOL, 1.0f)

            isMicMuted = prefs.getBoolean(KEY_MIC_MUTED, false)
            isGameMuted = prefs.getBoolean(KEY_GAME_MUTED, false)
            isMediaMuted = prefs.getBoolean(KEY_MEDIA_MUTED, false)

            isNoiseSuppressionEnabled = prefs.getBoolean(KEY_NOISE_GATE, true)
            isGodModeNoiseEnabled = prefs.getBoolean(KEY_GOD_MODE_NOISE, true)
            isAgcEnabled = prefs.getBoolean(KEY_AGC, false)
            isCompressorEnabled = prefs.getBoolean(KEY_COMPRESSOR, true)
            isLimiterEnabled = prefs.getBoolean(KEY_LIMITER, true)
            activePreset = prefs.getString(KEY_PRESET, "GAMING") ?: "GAMING"
            isInitialized = true
            Log.i(TAG, "Initialized AudioStudioRepository (mic=${micVolume}, game=${gameVolume}, media=${mediaVolume}, master=${masterVolume}, ns=${isNoiseSuppressionEnabled}, godModeNs=${isGodModeNoiseEnabled})")
        } catch (e: Exception) {
            Log.w(TAG, "Failed to load AudioStudio prefs: ${e.message}")
        }
    }

    @Synchronized
    fun saveVolumes(
        context: Context,
        mic: Float? = null,
        game: Float? = null,
        media: Float? = null,
        master: Float? = null,
        micMuted: Boolean? = null,
        gameMuted: Boolean? = null,
        mediaMuted: Boolean? = null
    ) {
        init(context)
        mic?.let { micVolume = it.coerceIn(0.0f, 2.0f) }
        game?.let { gameVolume = it.coerceIn(0.0f, 2.0f) }
        media?.let { mediaVolume = it.coerceIn(0.0f, 2.0f) }
        master?.let { masterVolume = it.coerceIn(0.0f, 1.0f) }
        micMuted?.let { isMicMuted = it }
        gameMuted?.let { isGameMuted = it }
        mediaMuted?.let { isMediaMuted = it }

        try {
            val editor = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit()
            mic?.let { editor.putFloat(KEY_MIC_VOL, it) }
            game?.let { editor.putFloat(KEY_GAME_VOL, it) }
            media?.let { editor.putFloat(KEY_MEDIA_VOL, it) }
            master?.let { editor.putFloat(KEY_MASTER_VOL, it) }
            micMuted?.let { editor.putBoolean(KEY_MIC_MUTED, it) }
            gameMuted?.let { editor.putBoolean(KEY_GAME_MUTED, it) }
            mediaMuted?.let { editor.putBoolean(KEY_MEDIA_MUTED, it) }
            editor.apply()
        } catch (e: Exception) {
            Log.w(TAG, "Failed to save audio volumes: ${e.message}")
        }
    }

    @Synchronized
    fun saveDsp(
        context: Context,
        noiseGate: Boolean? = null,
        godMode: Boolean? = null,
        agc: Boolean? = null,
        compressor: Boolean? = null,
        limiter: Boolean? = null
    ) {
        init(context)
        noiseGate?.let { isNoiseSuppressionEnabled = it }
        godMode?.let { isGodModeNoiseEnabled = it }
        agc?.let { isAgcEnabled = it }
        compressor?.let { isCompressorEnabled = it }
        limiter?.let { isLimiterEnabled = it }

        try {
            val editor = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit()
            noiseGate?.let { editor.putBoolean(KEY_NOISE_GATE, it) }
            godMode?.let { editor.putBoolean(KEY_GOD_MODE_NOISE, it) }
            agc?.let { editor.putBoolean(KEY_AGC, it) }
            compressor?.let { editor.putBoolean(KEY_COMPRESSOR, it) }
            limiter?.let { editor.putBoolean(KEY_LIMITER, it) }
            editor.apply()
        } catch (e: Exception) {
            Log.w(TAG, "Failed to save DSP filters: ${e.message}")
        }
    }

    @Synchronized
    fun savePreset(context: Context, presetName: String) {
        init(context)
        activePreset = presetName
        try {
            context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit()
                .putString(KEY_PRESET, presetName)
                .apply()
        } catch (e: Exception) {
            Log.w(TAG, "Failed to save active preset: ${e.message}")
        }
    }
}
