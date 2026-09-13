package com.fairylive.app.core.lifecycle

enum class StreamState {
    IDLE,
    STARTING,
    READY,
    STREAMING,
    PAUSED,
    STOPPING,
    ERROR;

    val isActive: Boolean
        get() = this == STREAMING || this == PAUSED || this == STARTING

    val isIdle: Boolean
        get() = this == IDLE || this == ERROR
}
