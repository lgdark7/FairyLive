package com.fairylive.app.core.audio

import com.fairylive.app.core.audio.model.AudioCaptureState
import com.fairylive.app.core.audio.model.AudioMetrics
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Thread-safe observer for audio capture state and audio level metrics telemetry.
 */
object AudioStateNotifier {

    fun interface StateListener {
        fun onAudioStateChanged(newState: AudioCaptureState, previousState: AudioCaptureState)
    }

    fun interface MetricsListener {
        fun onAudioMetricsUpdated(metrics: AudioMetrics)
    }

    private val stateListeners = CopyOnWriteArrayList<StateListener>()
    private val metricsListeners = CopyOnWriteArrayList<MetricsListener>()

    @Volatile
    var currentState: AudioCaptureState = AudioCaptureState.IDLE
        private set

    @Volatile
    var currentMetrics: AudioMetrics = AudioMetrics()
        private set

    fun updateState(newState: AudioCaptureState) {
        if (currentState == newState) return
        val previous = currentState
        currentState = newState
        for (listener in stateListeners) {
            listener.onAudioStateChanged(newState, previous)
        }
    }

    fun updateMetrics(metrics: AudioMetrics) {
        currentMetrics = metrics
        for (listener in metricsListeners) {
            listener.onAudioMetricsUpdated(metrics)
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
        currentState = AudioCaptureState.IDLE
        currentMetrics = AudioMetrics()
        stateListeners.clear()
        metricsListeners.clear()
    }
}
