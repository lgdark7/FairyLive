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
    val micDb: Float = -60.0f,
    val gameDb: Float = -60.0f,
    val mediaDb: Float = -60.0f,
    val masterDb: Float = -60.0f,
    val micPeakPercent: Int = 0,
    val gamePeakPercent: Int = 0,
    val masterPeakPercent: Int = 0,
    val encodedFrames: Long = 0L,
    val bitrateKbps: Long = 0L,
    val aacFps: Float = 0.0f,
    val aacInputTimeoutCount: Long = 0L,
    val pcmUnderruns: Long = 0L,
    val ringBufferDepth: Int = 0,
    val lastPresentationTimeUs: Long = 0L,
    val rtmpAudioPacketsSent: Long = 0L,
    val sampleRate: Int = 44100,
    val channels: Int = 1,
    val isMuted: Boolean = false,
    val isDeviceAudio: Boolean = false,
    val isDeviceAudioStereo: Boolean = false,
    val isMicSilencedBySystem: Boolean = false,
    val isGameAudioActive: Boolean = false,
    val encoderName: String = "",
    val encoderType: EncoderType = EncoderType.UNKNOWN,
    val isGateOpen: Boolean = false,
    val isVoiceDetected: Boolean = false,
    val noiseFloorDb: Float = -50.0f
) {
    val aacBitrateKbps: Long
        get() = bitrateKbps
    val isHardwareAccelerated: Boolean
        get() = encoderType == EncoderType.HARDWARE

    val isSoftwareOnly: Boolean
        get() = encoderType == EncoderType.SOFTWARE
}
