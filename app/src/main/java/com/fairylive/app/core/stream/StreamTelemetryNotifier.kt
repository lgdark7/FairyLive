package com.fairylive.app.core.stream

import com.fairylive.app.core.stream.model.RtmpConnectionState
import com.fairylive.app.core.stream.model.StreamTelemetry
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Thread-safe observer for RTMP streaming connection state and performance metrics.
 */
object StreamTelemetryNotifier {

    fun interface StateListener {
        fun onConnectionStateChanged(newState: RtmpConnectionState, previousState: RtmpConnectionState)
    }

    fun interface TelemetryListener {
        fun onTelemetryUpdated(telemetry: StreamTelemetry)
    }

    private val stateListeners = CopyOnWriteArrayList<StateListener>()
    private val telemetryListeners = CopyOnWriteArrayList<TelemetryListener>()

    @Volatile
    var currentState: RtmpConnectionState = RtmpConnectionState.IDLE
        private set

    @Volatile
    var currentTelemetry: StreamTelemetry = StreamTelemetry()
        private set

    fun updateState(newState: RtmpConnectionState) {
        if (currentState == newState) return
        val previous = currentState
        currentState = newState
        currentTelemetry = currentTelemetry.copy(connectionState = newState)
        for (listener in stateListeners) {
            listener.onConnectionStateChanged(newState, previous)
        }
    }

    fun updateTelemetry(telemetry: StreamTelemetry) {
        currentTelemetry = telemetry
        for (listener in telemetryListeners) {
            listener.onTelemetryUpdated(telemetry)
        }
    }

    fun addStateListener(listener: StateListener) {
        if (!stateListeners.contains(listener)) {
            stateListeners.add(listener)
        }
    }

    fun removeStateListener(listener: StateListener) {
        stateListeners.remove(listener)
    }

    fun addTelemetryListener(listener: TelemetryListener) {
        if (!telemetryListeners.contains(listener)) {
            telemetryListeners.add(listener)
        }
    }

    fun removeTelemetryListener(listener: TelemetryListener) {
        telemetryListeners.remove(listener)
    }

    fun reset() {
        currentState = RtmpConnectionState.IDLE
        currentTelemetry = StreamTelemetry()
        stateListeners.clear()
        telemetryListeners.clear()
    }
}
