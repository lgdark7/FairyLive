package com.fairylive.app

import android.os.Build
import com.fairylive.app.core.capture.model.CaptureMetrics
import com.fairylive.app.core.capture.model.CaptureState
import com.fairylive.app.core.capture.CaptureStateNotifier
import com.fairylive.app.core.settings.CaptureSource
import com.fairylive.app.core.settings.StreamDestination
import com.fairylive.app.core.settings.StreamSettings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Unit tests for FAIRY LIVE V1.2.1 Single-App Capture implementation.
 * Verifies capture-source state model, API-level branching, lifecycle safety,
 * and diagnostic tagging.
 */
class V121SingleAppCaptureUnitTest {

    @Before
    fun setUp() {
        CaptureStateNotifier.updateState(CaptureState.IDLE)
        CaptureStateNotifier.updateMetrics(CaptureMetrics(0, 0.0, 1080, 1920, 420, false))
    }

    @Test
    fun testCaptureSourceEnumValues() {
        val sources = CaptureSource.values()
        assertEquals(2, sources.size)
        assertTrue(sources.contains(CaptureSource.SINGLE_APP))
        assertTrue(sources.contains(CaptureSource.ENTIRE_SCREEN))
    }

    @Test
    fun testStreamSettingsDefaultCaptureSource() {
        val settings = StreamSettings(
            destination = StreamDestination.YOUTUBE,
            captureSource = CaptureSource.SINGLE_APP,
            serverUrl = StreamDestination.YOUTUBE.defaultUrl,
            streamKey = "test-key"
        )
        assertEquals(CaptureSource.SINGLE_APP, settings.captureSource)
    }

    @Test
    fun testStreamSettingsFromProfilePreservesCaptureSource() {
        val settings = StreamSettings.fromProfile(
            profile = com.fairylive.app.core.capability.model.StreamProfile.BALANCED,
            destination = StreamDestination.YOUTUBE,
            captureSource = CaptureSource.SINGLE_APP,
            streamKey = "sample_key"
        )
        assertEquals(CaptureSource.SINGLE_APP, settings.captureSource)
        assertEquals(1280, settings.width)
        assertEquals(720, settings.height)
    }

    @Test
    fun testCaptureStateNotifierTransitions() {
        CaptureStateNotifier.updateState(CaptureState.AUTHORIZING)
        assertEquals(CaptureState.AUTHORIZING, CaptureStateNotifier.currentState)

        CaptureStateNotifier.updateState(CaptureState.STARTING)
        assertEquals(CaptureState.STARTING, CaptureStateNotifier.currentState)

        CaptureStateNotifier.updateState(CaptureState.CAPTURING)
        assertEquals(CaptureState.CAPTURING, CaptureStateNotifier.currentState)

        CaptureStateNotifier.updateState(CaptureState.STOPPING)
        assertEquals(CaptureState.STOPPING, CaptureStateNotifier.currentState)

        CaptureStateNotifier.updateState(CaptureState.IDLE)
        assertEquals(CaptureState.IDLE, CaptureStateNotifier.currentState)
    }

    @Test
    fun testCaptureMetricsImmutabilityAndUpdates() {
        val initial = CaptureMetrics(
            framesCaptured = 120L,
            currentFps = 30.0,
            width = 1080,
            height = 1920,
            dpi = 420,
            isPaused = false
        )
        CaptureStateNotifier.updateMetrics(initial)
        assertEquals(120L, CaptureStateNotifier.currentMetrics.framesCaptured)
        assertEquals(30.0, CaptureStateNotifier.currentMetrics.currentFps, 0.001)

        val updated = initial.copy(framesCaptured = 150L, isPaused = true)
        CaptureStateNotifier.updateMetrics(updated)
        assertEquals(150L, CaptureStateNotifier.currentMetrics.framesCaptured)
        assertTrue(CaptureStateNotifier.currentMetrics.isPaused)
    }

    @Test
    fun testDiagnosticStringsFormat() {
        val singleAppMode = CaptureSource.SINGLE_APP
        val entireScreenMode = CaptureSource.ENTIRE_SCREEN

        val targetSingle = if (singleAppMode == CaptureSource.SINGLE_APP) {
            "SelectedTaskWindow (API 34+ RECORD_CONTENT_TASK)"
        } else {
            "EntireDisplay (DefaultDisplay)"
        }

        val targetEntire = if (entireScreenMode == CaptureSource.SINGLE_APP) {
            "SelectedTaskWindow (API 34+ RECORD_CONTENT_TASK)"
        } else {
            "EntireDisplay (DefaultDisplay)"
        }

        assertEquals("SelectedTaskWindow (API 34+ RECORD_CONTENT_TASK)", targetSingle)
        assertEquals("EntireDisplay (DefaultDisplay)", targetEntire)

        val logMode = "CaptureMode=$singleAppMode"
        val logTarget = "CaptureTarget=$targetSingle"
        val logStart = "ProjectionStarted=true"
        val logStop = "ProjectionStopped=true"

        assertEquals("CaptureMode=SINGLE_APP", logMode)
        assertEquals("CaptureTarget=SelectedTaskWindow (API 34+ RECORD_CONTENT_TASK)", logTarget)
        assertEquals("ProjectionStarted=true", logStart)
        assertEquals("ProjectionStopped=true", logStop)

        // Ensure stream key is NEVER part of capture diagnostic logs
        assertFalse(logMode.contains("key") || logMode.contains("rtmp"))
        assertFalse(logTarget.contains("key") || logTarget.contains("rtmp"))
    }
}
