package com.fairylive.app.core.audio

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioPlaybackCaptureConfiguration
import android.media.AudioRecord
import android.media.projection.MediaProjection
import android.os.Build
import android.util.Log
import androidx.annotation.RequiresApi
import com.fairylive.app.core.audio.model.AudioCaptureConfig

/**
 * Investigates and builds native internal device audio capture via Android 10+ (API 29)
 * AudioPlaybackCapture API.
 */
object AudioPlaybackCaptureHelper {

    private const val TAG = "AudioPlaybackHelper"

    data class PlaybackRecordResult(
        val record: AudioRecord,
        val isStereo: Boolean
    )

    /**
     * Checks if internal device audio capture is supported by this device and OS version.
     */
    fun isDeviceAudioSupported(): Boolean {
        return Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q
    }

    /**
     * Builds an AudioRecord targeting internal playback audio from games and media apps.
     * Tries primary channel configuration (Mono) first, then falls back to Stereo
     * if the device HAL requires stereo capture (common on Xiaomi, Realme, Vivo, Samsung, MediaTek).
     * Note: Subject to app manifest opt-outs (allowAudioPlaybackCapture=false) and call exclusions.
     */
    @RequiresApi(Build.VERSION_CODES.Q)
    fun createPlaybackCaptureRecord(
        mediaProjection: MediaProjection,
        config: AudioCaptureConfig
    ): PlaybackRecordResult? {
        // Attempt 1: Config's native channel mask (e.g. MONO)
        try {
            val playbackConfig = AudioPlaybackCaptureConfiguration.Builder(mediaProjection)
                .addMatchingUsage(AudioAttributes.USAGE_MEDIA)
                .addMatchingUsage(AudioAttributes.USAGE_GAME)
                .addMatchingUsage(AudioAttributes.USAGE_UNKNOWN)
                .build()

            val primaryFormat = AudioFormat.Builder()
                .setEncoding(config.audioFormat)
                .setSampleRate(config.sampleRate)
                .setChannelMask(config.channelConfig)
                .build()

            val rec = AudioRecord.Builder()
                .setAudioFormat(primaryFormat)
                .setBufferSizeInBytes(config.minBufferSize)
                .setAudioPlaybackCaptureConfig(playbackConfig)
                .build()

            if (rec.state == AudioRecord.STATE_INITIALIZED) {
                val isStereo = config.channelConfig == AudioFormat.CHANNEL_IN_STEREO
                Log.i(TAG, "Initialized native AudioPlaybackCapture (mono=${!isStereo})")
                return PlaybackRecordResult(rec, isStereo)
            } else {
                rec.release()
                Log.w(TAG, "Primary AudioPlaybackCapture record not initialized, attempting stereo fallback")
            }
        } catch (e: Exception) {
            Log.w(TAG, "Primary AudioPlaybackCapture creation failed (${e.message}), attempting stereo fallback")
        }

        // Attempt 2: Fallback to CHANNEL_IN_STEREO
        // Most OEM HALs that reject mono playback capture strictly require stereo.
        if (config.channelConfig != AudioFormat.CHANNEL_IN_STEREO) {
            try {
                val playbackConfig = AudioPlaybackCaptureConfiguration.Builder(mediaProjection)
                    .addMatchingUsage(AudioAttributes.USAGE_MEDIA)
                    .addMatchingUsage(AudioAttributes.USAGE_GAME)
                    .addMatchingUsage(AudioAttributes.USAGE_UNKNOWN)
                    .build()

                val stereoFormat = AudioFormat.Builder()
                    .setEncoding(config.audioFormat)
                    .setSampleRate(config.sampleRate)
                    .setChannelMask(AudioFormat.CHANNEL_IN_STEREO)
                    .build()

                val stereoBufferSize = AudioRecord.getMinBufferSize(
                    config.sampleRate,
                    AudioFormat.CHANNEL_IN_STEREO,
                    config.audioFormat
                ).coerceAtLeast(config.minBufferSize * 2)

                val stereoRec = AudioRecord.Builder()
                    .setAudioFormat(stereoFormat)
                    .setBufferSizeInBytes(stereoBufferSize)
                    .setAudioPlaybackCaptureConfig(playbackConfig)
                    .build()

                if (stereoRec.state == AudioRecord.STATE_INITIALIZED) {
                    Log.i(TAG, "Initialized fallback stereo AudioPlaybackCapture with real-time mono downmixing")
                    return PlaybackRecordResult(stereoRec, isStereo = true)
                } else {
                    stereoRec.release()
                    Log.w(TAG, "Fallback stereo AudioPlaybackCapture also failed to initialize")
                }
            } catch (e: Exception) {
                Log.w(TAG, "Failed to create fallback stereo AudioPlaybackCapture: ${e.message}")
            }
        }

        return null
    }
}
