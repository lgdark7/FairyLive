package com.fairylive.app.core.network

import android.os.SystemClock
import android.util.Log

/**
 * Lifecycle state of the reconnection engine.
 */
enum class ReconnectEngineState {
    IDLE,
    SCHEDULED,
    RECONNECTING,
    STABILIZING,
    EXHAUSTED
}

/**
 * Observable telemetry for a reconnection attempt.
 */
data class ReconnectAttemptTelemetry(
    val attemptNumber: Int,
    val maxAttempts: Int,
    val delayMs: Long,
    val reason: String,
    val state: ReconnectEngineState,
    val timestampMs: Long = System.currentTimeMillis()
)

/**
 * Orchestrates bounded exponential backoff reconnection for RTMP streaming.
 * Prevents reconnection storms, caps maximum backoff delay, and enforces strict
 * failure termination when max attempts are reached without leaving the stream falsely LIVE.
 */
class StreamReconnectionManager(
    val maxAttempts: Int = 4,
    val initialDelayMs: Long = 1000L,
    val maxDelayMs: Long = 8000L,
    val backoffMultiplier: Double = 2.0,
    val stabilityThresholdMs: Long = 5000L
) {
    var attemptCount: Int = 0
        private set

    var state: ReconnectEngineState = ReconnectEngineState.IDLE
        private set

    private var lastAttemptTimestampMs: Long = 0L
    private var connectedTimestampMs: Long = 0L

    var lastTelemetry: ReconnectAttemptTelemetry? = null
        private set

    var onAttemptScheduledListener: ((ReconnectAttemptTelemetry) -> Unit)? = null
    var onExhaustedListener: ((ReconnectAttemptTelemetry) -> Unit)? = null

    /**
     * Calculates delay for the next attempt and schedules it.
     * Returns delayMs if attempt allowed, or null if attempts are exhausted.
     */
    fun scheduleNextAttempt(reason: String, nowMs: Long = SystemClock.elapsedRealtime()): Long? {
        if (attemptCount >= maxAttempts) {
            state = ReconnectEngineState.EXHAUSTED
            val telemetry = ReconnectAttemptTelemetry(
                attemptNumber = attemptCount,
                maxAttempts = maxAttempts,
                delayMs = 0L,
                reason = reason,
                state = state
            )
            lastTelemetry = telemetry
            Log.e(TAG, "Reconnection attempts exhausted ($attemptCount/$maxAttempts). Reason: $reason")
            onExhaustedListener?.invoke(telemetry)
            return null
        }

        attemptCount++
        state = ReconnectEngineState.SCHEDULED
        lastAttemptTimestampMs = nowMs

        // Exponential backoff: delay = initialDelay * (multiplier ^ (attempt - 1))
        val rawDelay = (initialDelayMs * Math.pow(backoffMultiplier, (attemptCount - 1).toDouble())).toLong()
        val delayMs = rawDelay.coerceAtMost(maxDelayMs)

        val telemetry = ReconnectAttemptTelemetry(
            attemptNumber = attemptCount,
            maxAttempts = maxAttempts,
            delayMs = delayMs,
            reason = reason,
            state = state
        )
        lastTelemetry = telemetry
        Log.i(TAG, "Scheduled reconnect attempt #$attemptCount/$maxAttempts in ${delayMs}ms. Reason: $reason")
        onAttemptScheduledListener?.invoke(telemetry)
        return delayMs
    }

    /**
     * Call when an actual reconnection connection attempt starts.
     */
    fun onAttemptStarted() {
        state = ReconnectEngineState.RECONNECTING
    }

    /**
     * Call when RTMP connection is successfully re-established and enters PUBLISHING.
     */
    fun onConnectionRestored(nowMs: Long = SystemClock.elapsedRealtime()) {
        state = ReconnectEngineState.STABILIZING
        connectedTimestampMs = nowMs
        Log.i(TAG, "RTMP connection restored on attempt #$attemptCount. Stabilizing...")
    }

    /**
     * Periodic check to confirm connection has remained stable past [stabilityThresholdMs].
     * Resets attempt count upon confirmed stability.
     */
    fun checkStability(nowMs: Long = SystemClock.elapsedRealtime()): Boolean {
        if (state == ReconnectEngineState.STABILIZING && connectedTimestampMs > 0L) {
            if (nowMs - connectedTimestampMs >= stabilityThresholdMs) {
                Log.i(TAG, "Stream connection confirmed stable for ${stabilityThresholdMs}ms. Resetting reconnect counters.")
                reset()
                return true
            }
        }
        return false
    }

    /**
     * Resets reconnect state to IDLE.
     */
    fun reset() {
        attemptCount = 0
        state = ReconnectEngineState.IDLE
        lastAttemptTimestampMs = 0L
        connectedTimestampMs = 0L
    }

    val isExhausted: Boolean
        get() = state == ReconnectEngineState.EXHAUSTED

    val canRetry: Boolean
        get() = attemptCount < maxAttempts && state != ReconnectEngineState.EXHAUSTED

    companion object {
        private const val TAG = "StreamReconnectMgr"
    }
}
