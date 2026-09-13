package com.fairylive.app.core.video.model

import com.fairylive.app.core.capability.model.EncoderType

enum class EncoderState {
    IDLE,
    CONFIGURING,
    ENCODING,
    PAUSED,
    STOPPING,
    ERROR;

    val isEncoding: Boolean
        get() = this == ENCODING

    val isActive: Boolean
        get() = this == ENCODING || this == PAUSED || this == CONFIGURING
}

data class EncoderMetrics(
    val framesEncoded: Long = 0L,
    val keyframesEncoded: Long = 0L,
    val outputFps: Double = 0.0,
    val bitrateKbps: Long = 0L,
    val lastLatencyMs: Long = 0L,
    val codecName: String = "",
    val encoderType: EncoderType = EncoderType.UNKNOWN,
    val width: Int = 0,
    val height: Int = 0,
    val isPaused: Boolean = false
) {
    val isHardwareAccelerated: Boolean
        get() = encoderType == EncoderType.HARDWARE

    val isSoftwareOnly: Boolean
        get() = encoderType == EncoderType.SOFTWARE
}
