package com.fairylive.app.core.compositor.animation

import android.content.Context
import android.media.AudioAttributes
import android.media.SoundPool
import android.util.Log
import java.util.concurrent.ConcurrentHashMap

/**
 * Independent audio playback controller for stream alerts.
 *
 * Decoupled from both:
 * 1. The GLES video compositor render thread (never blocks GL draws).
 * 2. The native AudioRecord / MediaCodec microphone capture pipeline.
 */
class AlertSoundController(private val context: Context) {

    private val soundPool: SoundPool = SoundPool.Builder()
        .setMaxStreams(3)
        .setAudioAttributes(
            AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .build()
        )
        .build()

    private val loadedSounds = ConcurrentHashMap<String, Int>()

    /** Plays an alert sound effect asynchronously. */
    fun playAlertSound(soundAssetPath: String, volume: Float = 0.8f) {
        val soundId = loadedSounds[soundAssetPath]
        if (soundId != null) {
            soundPool.play(soundId, volume, volume, 1, 0, 1.0f)
        } else {
            try {
                val cleanPath = soundAssetPath.removePrefix("asset://").removePrefix("assets://")
                val afd = context.assets.openFd(cleanPath)
                val id = soundPool.load(afd, 1)
                loadedSounds[soundAssetPath] = id
                soundPool.setOnLoadCompleteListener { pool, sampleId, status ->
                    if (status == 0 && sampleId == id) {
                        pool.play(sampleId, volume, volume, 1, 0, 1.0f)
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "Alert sound asset not found or failed to load: ${e.message}")
            }
        }
    }

    fun release() {
        try {
            soundPool.release()
            loadedSounds.clear()
            Log.i(TAG, "AlertSoundController released")
        } catch (e: Exception) {
            Log.w(TAG, "Error releasing SoundPool: ${e.message}")
        }
    }

    companion object {
        private const val TAG = "AlertSoundController"
    }
}
