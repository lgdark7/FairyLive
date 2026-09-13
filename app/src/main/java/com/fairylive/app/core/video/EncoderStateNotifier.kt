package com.fairylive.app.core.video

import com.fairylive.app.core.video.model.EncoderMetrics
import com.fairylive.app.core.video.model.EncoderState
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Thread-safe observer for video encoder state and performance metrics telemetry.
 */
object EncoderStateNotifier {

    fun interface StateListener {
        fun onEncoderStateChanged(newState: EncoderState, previousState: EncoderState)
    }

    fun interface MetricsListener {
        fun onEncoderMetricsUpdated(metrics: EncoderMetrics)
    }

    private val stateListeners = CopyOnWriteArrayList<StateListener>()
    private val metricsListeners = CopyOnWriteArrayList<MetricsListener>()

    @Volatile
    var currentState: EncoderState = EncoderState.IDLE
        private set

    @Volatile
    var currentMetrics: EncoderMetrics = EncoderMetrics()
        private set

    fun updateState(newState: EncoderState) {
        if (currentState == newState) return
        val previous = currentState
        currentState = newState
        for (listener in stateListeners) {
            listener.onEncoderStateChanged(newState, previous)
        }
    }

    fun updateMetrics(metrics: EncoderMetrics) {
        currentMetrics = metrics
        for (listener in metricsListeners) {
            listener.onEncoderMetricsUpdated(metrics)
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
        currentState = EncoderState.IDLE
        currentMetrics = EncoderMetrics()
        stateListeners.clear()
        metricsListeners.clear()
    }
}
