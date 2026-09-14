package com.fairylive.app.core.audio.model

import com.fairylive.app.core.capability.model.EncoderType

enum class AudioCaptureState {
    IDLE,
    INITIALIZING,
    RECORDING,
    MUTED,
    PAUSED,
    STOPPING,
    ERROR;

    val isActive: Boolean
        get() = this == RECORDING || this == MUTED || this == PAUSED
}

data class AudioMetrics(
    val samplesRead: Long = 0L,
    val silenceFramesInserted: Long = 0L,
    val peakAmplitudePercent: Int = 0,
    val encodedFrames: Long = 0L,
    val bitrateKbps: Long = 0L,
    val sampleRate: Int = 44100,
    val channels: Int = 1,
    val isMuted: Boolean = false,
    val isDeviceAudio: Boolean = false,
    val encoderName: String = "",
    val encoderType: EncoderType = EncoderType.UNKNOWN
) {
    val isHardwareAccelerated: Boolean
        get() = encoderType == EncoderType.HARDWARE

    val isSoftwareOnly: Boolean
        get() = encoderType == EncoderType.SOFTWARE
}
