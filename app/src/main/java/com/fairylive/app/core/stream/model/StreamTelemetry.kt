package com.fairylive.app.core.stream.model

/**
 * Lifecycle states of the RTMP streaming transport.
 */
enum class RtmpConnectionState {
    IDLE,
    CONNECTING,
    HANDSHAKING,
    CONNECTED,
    CREATING_STREAM,
    PUBLISHING,
    RECONNECTING,
    STOPPING,
    ERROR;

    val isStreaming: Boolean
        get() = this == PUBLISHING

    val isActive: Boolean
        get() = this != IDLE && this != ERROR
}

/**
 * Real-time streaming network telemetry metrics.
 */
data class StreamTelemetry(
    val connectionState: RtmpConnectionState = RtmpConnectionState.IDLE,
    val bytesSent: Long = 0L,
    val bitrateBps: Long = 0L,
    val packetsSent: Long = 0L,
    val videoFramesSent: Long = 0L,
    val audioFramesSent: Long = 0L,
    val droppedFrames: Long = 0L,
    val queueDepth: Int = 0,
    val networkLatencyMs: Long = 0L,
    val durationMs: Long = 0L,
    val reconnectAttempts: Int = 0,
    val lastError: String? = null
) {
    val bitrateKbps: Long
        get() = bitrateBps / 1000L
}
