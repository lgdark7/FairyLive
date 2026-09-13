package com.fairylive.app.core.video.model

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
    val isHardwareAccelerated: Boolean = false,
    val width: Int = 0,
    val height: Int = 0,
    val isPaused: Boolean = false
)
