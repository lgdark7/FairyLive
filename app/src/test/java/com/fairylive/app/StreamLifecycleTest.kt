package com.fairylive.app

import com.fairylive.app.core.lifecycle.StreamState
import com.fairylive.app.core.lifecycle.StreamStateNotifier
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class StreamLifecycleTest {

    @Before
    fun setup() {
        StreamStateNotifier.reset()
    }

    @Test
    fun testStreamStateProperties() {
        assertTrue(StreamState.IDLE.isIdle)
        assertFalse(StreamState.IDLE.isActive)

        assertTrue(StreamState.STREAMING.isActive)
        assertFalse(StreamState.STREAMING.isIdle)

        assertTrue(StreamState.PAUSED.isActive)
        assertTrue(StreamState.ERROR.isIdle)
    }

    @Test
    fun testStreamStateNotifierTransitions() {
        val observedTransitions = mutableListOf<Pair<StreamState, StreamState>>()
        val listener = StreamStateNotifier.Listener { newState, previousState ->
            observedTransitions.add(previousState to newState)
        }

        StreamStateNotifier.addListener(listener)

        assertEquals(StreamState.IDLE, StreamStateNotifier.currentState)

        StreamStateNotifier.updateState(StreamState.READY)
        StreamStateNotifier.updateState(StreamState.STREAMING)
        StreamStateNotifier.updateState(StreamState.IDLE)

        assertEquals(3, observedTransitions.size)
        assertEquals(StreamState.IDLE to StreamState.READY, observedTransitions[0])
        assertEquals(StreamState.READY to StreamState.STREAMING, observedTransitions[1])
        assertEquals(StreamState.STREAMING to StreamState.IDLE, observedTransitions[2])

        StreamStateNotifier.removeListener(listener)
        StreamStateNotifier.updateState(StreamState.READY)
        assertEquals(3, observedTransitions.size)
    }
}
