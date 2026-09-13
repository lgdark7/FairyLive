package com.fairylive.app

import com.fairylive.app.core.capture.CaptureStateNotifier
import com.fairylive.app.core.capture.model.CaptureMetrics
import com.fairylive.app.core.capture.model.CaptureState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class ScreenCaptureTest {

    @Before
    fun setup() {
        CaptureStateNotifier.reset()
    }

    @Test
    fun testCaptureStateProperties() {
        assertFalse(CaptureState.IDLE.isActive)
        assertFalse(CaptureState.IDLE.isCapturing)

        assertTrue(CaptureState.STARTING.isActive)
        assertFalse(CaptureState.STARTING.isCapturing)

        assertTrue(CaptureState.CAPTURING.isActive)
        assertTrue(CaptureState.CAPTURING.isCapturing)

        assertTrue(CaptureState.PAUSED.isActive)
        assertFalse(CaptureState.PAUSED.isCapturing)
    }

    @Test
    fun testCaptureStateNotifierTransitions() {
        val observedStates = mutableListOf<CaptureState>()
        val listener = CaptureStateNotifier.StateListener { newState, _ ->
            observedStates.add(newState)
        }

        CaptureStateNotifier.addStateListener(listener)

        CaptureStateNotifier.updateState(CaptureState.AUTHORIZING)
        CaptureStateNotifier.updateState(CaptureState.STARTING)
        CaptureStateNotifier.updateState(CaptureState.CAPTURING)
        CaptureStateNotifier.updateState(CaptureState.PAUSED)
        CaptureStateNotifier.updateState(CaptureState.CAPTURING)
        CaptureStateNotifier.updateState(CaptureState.STOPPING)
        CaptureStateNotifier.updateState(CaptureState.IDLE)

        assertEquals(7, observedStates.size)
        assertEquals(CaptureState.AUTHORIZING, observedStates[0])
        assertEquals(CaptureState.STARTING, observedStates[1])
        assertEquals(CaptureState.CAPTURING, observedStates[2])
        assertEquals(CaptureState.PAUSED, observedStates[3])
        assertEquals(CaptureState.CAPTURING, observedStates[4])
        assertEquals(CaptureState.STOPPING, observedStates[5])
        assertEquals(CaptureState.IDLE, observedStates[6])

        CaptureStateNotifier.removeStateListener(listener)
        CaptureStateNotifier.updateState(CaptureState.STARTING)
        assertEquals(7, observedStates.size)
    }

    @Test
    fun testCaptureMetricsUpdates() {
        val metricsUpdates = mutableListOf<CaptureMetrics>()
        val listener = CaptureStateNotifier.MetricsListener { metrics ->
            metricsUpdates.add(metrics)
        }

        CaptureStateNotifier.addMetricsListener(listener)

        val metrics1 = CaptureMetrics(
            framesCaptured = 60L,
            currentFps = 30.0,
            width = 1080,
            height = 2400,
            dpi = 420,
            isPaused = false
        )
        CaptureStateNotifier.updateMetrics(metrics1)

        val metrics2 = metrics1.copy(isPaused = true, currentFps = 0.0)
        CaptureStateNotifier.updateMetrics(metrics2)

        assertEquals(2, metricsUpdates.size)
        assertEquals(60L, metricsUpdates[0].framesCaptured)
        assertEquals(30.0, metricsUpdates[0].currentFps, 0.001)
        assertFalse(metricsUpdates[0].isPaused)

        assertTrue(metricsUpdates[1].isPaused)
        assertEquals(0.0, metricsUpdates[1].currentFps, 0.001)
    }
}
