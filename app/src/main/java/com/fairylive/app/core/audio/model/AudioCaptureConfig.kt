package com.fairylive.app.core.audio.model

import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaCodecInfo
import android.media.MediaFormat

/**
 * Immutable audio capture and encoding configuration.
 */
data class AudioCaptureConfig(
    val sampleRate: Int = 44100,
    val channelConfig: Int = AudioFormat.CHANNEL_IN_MONO,
    val audioFormat: Int = AudioFormat.ENCODING_PCM_16BIT,
    val bitrateKbps: Int = 96,
    val sourceType: AudioSourceType = AudioSourceType.MIC
) {
    val channelCount: Int
        get() = if (channelConfig == AudioFormat.CHANNEL_IN_STEREO) 2 else 1

    val bytesPerSample: Int
        get() = 2 // 16-bit PCM = 2 bytes per sample

    val bytesPerFrame: Int
        get() = channelCount * bytesPerSample

    val minBufferSize: Int
        get() = try {
            val min = AudioRecord.getMinBufferSize(sampleRate, channelConfig, audioFormat)
            if (min <= 0) 4096 else min.coerceAtLeast(2048)
        } catch (_: Exception) {
            4096
        }

    fun toMediaFormat(): MediaFormat {
        val format = MediaFormat.createAudioFormat(
            MediaFormat.MIMETYPE_AUDIO_AAC,
            sampleRate,
            channelCount
        )
        format.setInteger(
            MediaFormat.KEY_AAC_PROFILE,
            MediaCodecInfo.CodecProfileLevel.AACObjectLC
        )
        format.setInteger(MediaFormat.KEY_BIT_RATE, bitrateKbps * 1000)
        format.setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, minBufferSize * 2)
        return format
    }
}
