package com.fairylive.app

import com.fairylive.app.core.network.ReconnectEngineState
import com.fairylive.app.core.network.StreamReconnectionManager
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class StreamReconnectionManagerTest {

    private lateinit var manager: StreamReconnectionManager

    @Before
    fun setUp() {
        manager = StreamReconnectionManager(
            maxAttempts = 4,
            initialDelayMs = 1000L,
            maxDelayMs = 8000L,
            backoffMultiplier = 2.0,
            stabilityThresholdMs = 5000L
        )
    }

    @Test
    fun testInitialState() {
        assertEquals(0, manager.attemptCount)
        assertEquals(ReconnectEngineState.IDLE, manager.state)
        assertTrue(manager.canRetry)
        assertFalse(manager.isExhausted)
    }

    @Test
    fun testExponentialBackoffDelays() {
        // Attempt 1: 1000ms
        val delay1 = manager.scheduleNextAttempt("Connection reset", 1000L)
        assertEquals(1000L, delay1)
        assertEquals(1, manager.attemptCount)
        assertEquals(ReconnectEngineState.SCHEDULED, manager.state)

        // Attempt 2: 2000ms
        val delay2 = manager.scheduleNextAttempt("Connection reset", 2000L)
        assertEquals(2000L, delay2)
        assertEquals(2, manager.attemptCount)

        // Attempt 3: 4000ms
        val delay3 = manager.scheduleNextAttempt("Connection reset", 4000L)
        assertEquals(4000L, delay3)
        assertEquals(3, manager.attemptCount)

        // Attempt 4: 8000ms (capped at maxDelayMs)
        val delay4 = manager.scheduleNextAttempt("Connection reset", 8000L)
        assertEquals(8000L, delay4)
        assertEquals(4, manager.attemptCount)

        // Attempt 5: Exhausted -> returns null
        val delay5 = manager.scheduleNextAttempt("Connection reset", 16000L)
        assertNull("Schedule must return null when max attempts are exhausted", delay5)
        assertTrue(manager.isExhausted)
        assertFalse(manager.canRetry)
        assertEquals(ReconnectEngineState.EXHAUSTED, manager.state)
    }

    @Test
    fun testConnectionRestorationAndStabilityReset() {
        // Run 2 attempts
        manager.scheduleNextAttempt("Socket drop", 1000L)
        manager.scheduleNextAttempt("Socket drop", 2000L)
        assertEquals(2, manager.attemptCount)

        // Connection restored
        manager.onConnectionRestored(nowMs = 5000L)
        assertEquals(ReconnectEngineState.STABILIZING, manager.state)

        // Check before stability threshold (4000ms < 5000ms) -> should NOT reset
        val stabilizedTooSoon = manager.checkStability(nowMs = 9000L)
        assertFalse(stabilizedTooSoon)
        assertEquals(2, manager.attemptCount)

        // Check after stability threshold (6000ms >= 5000ms) -> resets to IDLE
        val stabilized = manager.checkStability(nowMs = 11000L)
        assertTrue(stabilized)
        assertEquals(0, manager.attemptCount)
        assertEquals(ReconnectEngineState.IDLE, manager.state)
    }
}
