package com.fairylive.app.core.audio

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioPlaybackCaptureConfiguration
import android.media.AudioRecord
import android.media.projection.MediaProjection
import android.os.Build
import androidx.annotation.RequiresApi
import com.fairylive.app.core.audio.model.AudioCaptureConfig

/**
 * Investigates and builds native internal device audio capture via Android 10+ (API 29)
 * AudioPlaybackCapture API.
 */
object AudioPlaybackCaptureHelper {

    /**
     * Checks if internal device audio capture is supported by this device and OS version.
     */
    fun isDeviceAudioSupported(): Boolean {
        return Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q
    }

    /**
     * Builds an AudioRecord targeting internal playback audio from games and media apps.
     * Note: Subject to app manifest opt-outs (allowAudioPlaybackCapture=false) and call exclusions.
     */
    @RequiresApi(Build.VERSION_CODES.Q)
    fun createPlaybackCaptureRecord(
        mediaProjection: MediaProjection,
        config: AudioCaptureConfig
    ): AudioRecord? {
        return try {
            val playbackConfig = AudioPlaybackCaptureConfiguration.Builder(mediaProjection)
                .addMatchingUsage(AudioAttributes.USAGE_MEDIA)
                .addMatchingUsage(AudioAttributes.USAGE_GAME)
                .addMatchingUsage(AudioAttributes.USAGE_UNKNOWN)
                .build()

            val audioFormat = AudioFormat.Builder()
                .setEncoding(config.audioFormat)
                .setSampleRate(config.sampleRate)
                .setChannelMask(config.channelConfig)
                .build()

            AudioRecord.Builder()
                .setAudioFormat(audioFormat)
                .setBufferSizeInBytes(config.minBufferSize)
                .setAudioPlaybackCaptureConfig(playbackConfig)
                .build()
        } catch (e: Exception) {
            android.util.Log.w("AudioPlaybackHelper", "Failed to create AudioPlaybackCapture AudioRecord", e)
            null
        }
    }
}
