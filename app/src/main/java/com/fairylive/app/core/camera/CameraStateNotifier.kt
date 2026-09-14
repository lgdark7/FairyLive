package com.fairylive.app.core.camera

import java.util.concurrent.CopyOnWriteArrayList

/**
 * Thread-safe observer for camera hardware state and frame telemetry.
 */
object CameraStateNotifier {

    fun interface StateListener {
        fun onCameraStateChanged(newState: CameraState, previousState: CameraState)
    }

    fun interface MetricsListener {
        fun onCameraMetricsUpdated(metrics: CameraMetrics)
    }

    private val stateListeners = CopyOnWriteArrayList<StateListener>()
    private val metricsListeners = CopyOnWriteArrayList<MetricsListener>()

    @Volatile
    var currentState: CameraState = CameraState.IDLE
        private set

    @Volatile
    var currentMetrics: CameraMetrics = CameraMetrics()
        private set

    fun updateState(newState: CameraState) {
        if (currentState == newState) return
        val previous = currentState
        currentState = newState
        for (listener in stateListeners) {
            listener.onCameraStateChanged(newState, previous)
        }
    }

    fun updateMetrics(metrics: CameraMetrics) {
        currentMetrics = metrics
        for (listener in metricsListeners) {
            listener.onCameraMetricsUpdated(metrics)
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
        currentState = CameraState.IDLE
        currentMetrics = CameraMetrics()
        stateListeners.clear()
        metricsListeners.clear()
    }
}
