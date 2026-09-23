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

    /**
     * Formats current audio state and metrics into a comprehensive single-line diagnostic summary.
     * Useful for developer logs, bug reports, and telemetry verification.
     */
    fun getDiagnosticSummary(): String {
        val m = currentMetrics
        val state = currentState
        return buildString {
            append("State=").append(state)
            append(", Mic=").append(String.format(java.util.Locale.US, "%.1fdB (peak=%d%%)", m.micDb, m.micPeakPercent))
            append(", Game=").append(String.format(java.util.Locale.US, "%.1fdB (peak=%d%%)", m.gameDb, m.gamePeakPercent))
            append(", Master=").append(String.format(java.util.Locale.US, "%.1fdB (peak=%d%%)", m.masterDb, m.masterPeakPercent))
            append(", NoiseFloor=").append(String.format(java.util.Locale.US, "%.1fdB", m.noiseFloorDb))
            append(", GateOpen=").append(m.isGateOpen)
            append(", RingDepth=").append(m.ringBufferDepth).append("B")
            append(", AacFps=").append(String.format(java.util.Locale.US, "%.1f", m.aacFps))
            append(", AacBitrate=").append(m.bitrateKbps).append("kbps")
            append(", EncodedFrames=").append(m.encodedFrames)
            append(", RtmpAudioSent=").append(m.rtmpAudioPacketsSent)
            append(", SilencedByOS=").append(m.isMicSilencedBySystem)
            append(", GameActive=").append(m.isGameAudioActive)
        }
    }
}
