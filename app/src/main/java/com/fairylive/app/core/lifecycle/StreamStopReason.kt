package com.fairylive.app.core.lifecycle

/**
 * Explicit enumeration of reasons why a live stream or screen capture session terminates.
 * Required for deterministic root-cause analysis and preventing silent reconnection loops.
 */
enum class StreamStopReason {
    STOP_REASON_USER,
    STOP_REASON_PROJECTION_REVOKED,
    STOP_REASON_SURFACE_DESTROYED,
    STOP_REASON_ENCODER_ERROR,
    STOP_REASON_AUDIO_ERROR,
    STOP_REASON_RTMP_ERROR,
    STOP_REASON_YOUTUBE_REJECTED,
    STOP_REASON_ORIENTATION_CHANGE,
    STOP_REASON_DUPLICATE_SESSION,
    STOP_REASON_EXCEPTION;

    val isUserInitiated: Boolean
        get() = this == STOP_REASON_USER

    val isFatalError: Boolean
        get() = this != STOP_REASON_USER && this != STOP_REASON_ORIENTATION_CHANGE
}
