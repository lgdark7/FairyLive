package com.fairylive.app.core.capture

import com.fairylive.app.core.capture.model.CaptureMetrics
import com.fairylive.app.core.capture.model.CaptureState
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Thread-safe observer for screen capture state and real-time metrics telemetry.
 */
object CaptureStateNotifier {

    fun interface StateListener {
        fun onCaptureStateChanged(newState: CaptureState, previousState: CaptureState)
    }

    fun interface MetricsListener {
        fun onCaptureMetricsUpdated(metrics: CaptureMetrics)
    }

    private val stateListeners = CopyOnWriteArrayList<StateListener>()
    private val metricsListeners = CopyOnWriteArrayList<MetricsListener>()

    @Volatile
    var currentState: CaptureState = CaptureState.IDLE
        private set

    @Volatile
    var currentMetrics: CaptureMetrics = CaptureMetrics()
        private set

    fun updateState(newState: CaptureState) {
        if (currentState == newState) return
        val previous = currentState
        currentState = newState
        for (listener in stateListeners) {
            listener.onCaptureStateChanged(newState, previous)
        }
    }

    fun updateMetrics(metrics: CaptureMetrics) {
        currentMetrics = metrics
        for (listener in metricsListeners) {
            listener.onCaptureMetricsUpdated(metrics)
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

    fun addMetricsListener(listener: MetricsListener) {
        if (!metricsListeners.contains(listener)) {
            metricsListeners.add(listener)
        }
    }

    fun removeMetricsListener(listener: MetricsListener) {
        metricsListeners.remove(listener)
    }

    fun reset() {
        currentState = CaptureState.IDLE
        currentMetrics = CaptureMetrics()
        stateListeners.clear()
        metricsListeners.clear()
    }
}
