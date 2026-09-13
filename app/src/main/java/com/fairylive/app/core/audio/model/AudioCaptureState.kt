package com.fairylive.app.core.audio.model

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
    val peakAmplitudePercent: Int = 0,
    val encodedFrames: Long = 0L,
    val bitrateKbps: Long = 0L,
    val sampleRate: Int = 44100,
    val channels: Int = 1,
    val isMuted: Boolean = false,
    val isDeviceAudio: Boolean = false
)
