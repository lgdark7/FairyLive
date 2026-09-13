package com.fairylive.app.core.compositor

import java.util.concurrent.CopyOnWriteArrayList

/** Composition-render state (independent of the creator UI / stream state). */
enum class CompositorState {
    IDLE,      // not created / released
    STARTING,
    RUNNING,   // render loop active
    PAUSED,
    ERROR,
    STOPPING
}

/** Snapshot of compositor performance for telemetry (no per-frame UI redraw loops). */
data class CompositorMetrics(
    val framesComposited: Long = 0L,
    val outputFps: Double = 0.0,
    val overlayCount: Int = 0,
    val state: CompositorState = CompositorState.IDLE,
    val lastFrameTimeMs: Long = 0L
)

/**
 * Thread-safe notifier so the Dashboard can reflect compositor state without a
 * per-frame UI loop. Mirrors the existing EncoderStateNotifier / CaptureStateNotifier pattern.
 */
object CompositorStateNotifier {
    @Volatile
    var currentState: CompositorState = CompositorState.IDLE
        private set

    @Volatile
    var currentMetrics: CompositorMetrics = CompositorMetrics()
        private set

    private val stateListeners = CopyOnWriteArrayList<() -> Unit>()
    private val metricsListeners = CopyOnWriteArrayList<() -> Unit>()

    fun updateState(state: CompositorState) {
        currentState = state
        stateListeners.forEach { runCatching { it.invoke() } }
    }

    fun updateMetrics(metrics: CompositorMetrics) {
        currentMetrics = metrics.copy(state = currentState)
        metricsListeners.forEach { runCatching { it.invoke() } }
    }

    fun addStateListener(listener: () -> Unit) {
        stateListeners.add(listener)
    }

    fun removeStateListener(listener: () -> Unit) {
        stateListeners.remove(listener)
    }

    fun addMetricsListener(listener: () -> Unit) {
        metricsListeners.add(listener)
    }

    fun removeMetricsListener(listener: () -> Unit) {
        metricsListeners.remove(listener)
    }
}