package com.fairylive.app.core.stream.model

/**
 * Enterprise classification of streaming pipeline and network failure modes.
 * Ensures zero-guessing root cause diagnosis while safeguarding credentials.
 */
enum class StreamErrorType {
    EMPTY_STREAM_KEY,
    INVALID_URL_SCHEME,
    MALFORMED_URL,
    DNS_LOOKUP_FAILED,
    CONNECTION_REFUSED,
    CONNECTION_TIMEOUT,
    HANDSHAKE_FAILED,
    SERVER_CONNECT_REJECTED,
    STREAM_KEY_REJECTED,
    BROKEN_PIPE_PREMATURE_CLOSE,
    ENCODER_SURFACE_NOT_READY,
    PIPELINE_ALREADY_ACTIVE
}
