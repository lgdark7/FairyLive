package com.fairylive.app.core.capture.model

enum class CaptureState {
    IDLE,
    AUTHORIZING,
    STARTING,
    CAPTURING,
    PAUSED,
    STOPPING,
    ERROR;

    val isActive: Boolean
        get() = this == CAPTURING || this == PAUSED || this == STARTING

    val isCapturing: Boolean
        get() = this == CAPTURING
}

data class CaptureMetrics(
    val framesCaptured: Long = 0L,
    val currentFps: Double = 0.0,
    val width: Int = 0,
    val height: Int = 0,
    val dpi: Int = 0,
    val isPaused: Boolean = false
)
