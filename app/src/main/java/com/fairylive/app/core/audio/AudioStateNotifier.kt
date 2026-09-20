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

    fun interface SilencedStateListener {
        fun onMicSilencedChanged(isSilenced: Boolean)
    }

    private val stateListeners = CopyOnWriteArrayList<StateListener>()
    private val metricsListeners = CopyOnWriteArrayList<MetricsListener>()
    private val silencedListeners = CopyOnWriteArrayList<SilencedStateListener>()

    @Volatile
    var isMicSilencedBySystem: Boolean = false
        private set

    @Volatile
    var currentState: AudioCaptureState = AudioCaptureState.IDLE
        private set

    @Volatile
    var currentMetrics: AudioMetrics = AudioMetrics()
        private set

    fun updateMicSilenced(silenced: Boolean) {
        if (isMicSilencedBySystem == silenced) return
        isMicSilencedBySystem = silenced
        currentMetrics = currentMetrics.copy(isMicSilencedBySystem = silenced)
        for (listener in silencedListeners) {
            listener.onMicSilencedChanged(silenced)
        }
        for (listener in metricsListeners) {
            listener.onAudioMetricsUpdated(currentMetrics)
        }
    }

    fun addSilencedListener(listener: SilencedStateListener) {
        if (!silencedListeners.contains(listener)) {
            silencedListeners.add(listener)
        }
    }

    fun removeSilencedListener(listener: SilencedStateListener) {
        silencedListeners.remove(listener)
    }

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
        isMicSilencedBySystem = false
        stateListeners.clear()
        metricsListeners.clear()
        silencedListeners.clear()
    }
}
