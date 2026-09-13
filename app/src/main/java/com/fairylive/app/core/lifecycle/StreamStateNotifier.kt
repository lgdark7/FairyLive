package com.fairylive.app.core.lifecycle

import java.util.concurrent.CopyOnWriteArrayList

/**
 * Thread-safe notifier for streaming lifecycle state changes.
 * Avoids allocations during state queries and preserves lifecycle safety.
 */
object StreamStateNotifier {

    fun interface Listener {
        fun onStateChanged(newState: StreamState, previousState: StreamState)
    }

    private val listeners = CopyOnWriteArrayList<Listener>()

    @Volatile
    var currentState: StreamState = StreamState.IDLE
        private set

    fun updateState(newState: StreamState) {
        if (currentState == newState) return
        val previous = currentState
        currentState = newState
        for (listener in listeners) {
            listener.onStateChanged(newState, previous)
        }
    }

    fun addListener(listener: Listener) {
        if (!listeners.contains(listener)) {
            listeners.add(listener)
        }
    }

    fun removeListener(listener: Listener) {
        listeners.remove(listener)
    }

    fun reset() {
        currentState = StreamState.IDLE
        listeners.clear()
    }
}
