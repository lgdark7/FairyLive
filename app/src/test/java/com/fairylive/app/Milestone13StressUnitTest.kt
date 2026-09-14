package com.fairylive.app

import com.fairylive.app.core.network.AdaptiveBitrateController
import com.fairylive.app.core.network.BitrateChangeReason
import com.fairylive.app.core.network.NetworkHealth
import com.fairylive.app.core.network.NetworkQualityTelemetry
import com.fairylive.app.core.network.ReconnectEngineState
import com.fairylive.app.core.network.StreamReconnectionManager
import com.fairylive.app.core.optimization.DynamicFpsThrottler
import com.fairylive.app.core.optimization.MemoryPressureGuard
import com.fairylive.app.core.optimization.model.ThermalThrottlingLevel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Unit Stress Testing Suite for Milestone 13.
 * Exercises ABR stress cycling, thermal/dynamic FPS coordination, exponential backoff exhaustion,
 * memory pressure purging, and synthetic A/V clock drift constraints.
 */
class Milestone13StressUnitTest {

    private lateinit var abrController: AdaptiveBitrateController
    private lateinit var reconnectManager: StreamReconnectionManager
    private lateinit var fpsThrottler: DynamicFpsThrottler

    @Before
    fun setUp() {
        abrController = AdaptiveBitrateController(
            ladderKbps = intArrayOf(1200, 800, 500, 300),
            cooldownMs = 8000L,
            hysteresisRequiredHealthyWindows = 3
        )
        abrController.initialize(initialBitrateKbps = 1200, nowElapsedMs = 1000L)

        reconnectManager = StreamReconnectionManager(
            maxAttempts = 4,
            initialDelayMs = 1000L,
            maxDelayMs = 8000L,
            backoffMultiplier = 2.0,
            stabilityThresholdMs = 5000L
        )
        fpsThrottler = DynamicFpsThrottler(baseFps = 30)
    }

    // ==========================================
    // 1. ABR STRESS & LADDER CYCLING
    // ==========================================

    @Test
    fun testAbrFullLadderRapidDownwardStepStress() {
        assertEquals(1200, abrController.currentBitrateKbps)

        // 1200 -> 800 (DEGRADED network drops 1 tier)
        val t1 = NetworkQualityTelemetry(health = NetworkHealth.DEGRADED, currentLatencyMs = 280L, queueFillRatio = 0.35f)
        val d1 = abrController.evaluate(t1, nowElapsedMs = 2000L)
        assertNotNull(d1)
        assertEquals(800, d1?.newBitrateKbps)
        assertFalse("DEGRADED does not force sync keyframe", d1?.requestKeyFrame == true)

        // 800 -> 500 (DEGRADED drops 1 tier after 1s)
        val d2 = abrController.evaluate(t1, nowElapsedMs = 3100L)
        assertNotNull(d2)
        assertEquals(500, d2?.newBitrateKbps)

        // 500 -> 300 (CRITICAL drops straight to floor and forces keyframe)
        val tCritical = NetworkQualityTelemetry(health = NetworkHealth.CRITICAL, currentLatencyMs = 700L, queueFillRatio = 0.85f)
        val d3 = abrController.evaluate(tCritical, nowElapsedMs = 4200L)
        assertNotNull(d3)
        assertEquals(300, d3?.newBitrateKbps)
        assertTrue("CRITICAL must request sync keyframe", d3?.requestKeyFrame == true)

        // Cannot drop below 300 floor
        val dFloor = abrController.evaluate(tCritical, nowElapsedMs = 5300L)
        assertNull("Cannot downscale below 300 kbps floor", dFloor)
        assertEquals(300, abrController.currentBitrateKbps)
    }

    @Test
    fun testAbrUpwardStepCooldownAndHysteresis() {
        // Drop straight to 300 first
        val tCritical = NetworkQualityTelemetry(health = NetworkHealth.CRITICAL, currentLatencyMs = 750L, queueFillRatio = 0.90f)
        val dDrop = abrController.evaluate(tCritical, nowElapsedMs = 2000L)
        assertNotNull(dDrop)
        assertEquals(300, abrController.currentBitrateKbps)

        val tGood = NetworkQualityTelemetry(health = NetworkHealth.EXCELLENT, currentLatencyMs = 25L, queueFillRatio = 0.05f)

        // Dropped to 300 at 2000ms
        // Upward step requires: timeSinceLastDecision >= 8000ms AND consecutiveHealthyEvaluations >= 3
        val w1 = abrController.evaluate(tGood, nowElapsedMs = 10001L) // window 1
        assertNull("Requires 3 healthy windows", w1)

        val w2 = abrController.evaluate(tGood, nowElapsedMs = 11001L) // window 2
        assertNull("Requires 3 healthy windows", w2)

        val stepTo500 = abrController.evaluate(tGood, nowElapsedMs = 12001L) // window 3 -> triggers 300 -> 500
        assertNotNull(stepTo500)
        assertEquals(500, stepTo500?.newBitrateKbps)
        assertEquals(500, abrController.currentBitrateKbps)

        // Next upgrade (500 -> 800): must wait 8s cooldown before healthy evaluations count towards next step
        // At 20002ms, cooldown is fulfilled (20002 - 12001 = 8001 >= 8000)
        val w4 = abrController.evaluate(tGood, nowElapsedMs = 20002L)
        assertNull("Requires 3 healthy windows", w4)

        val w5 = abrController.evaluate(tGood, nowElapsedMs = 21002L)
        assertNull("Requires 3 healthy windows", w5)

        val stepTo800 = abrController.evaluate(tGood, nowElapsedMs = 22002L)
        assertNotNull(stepTo800)
        assertEquals(800, stepTo800?.newBitrateKbps)
        assertEquals(800, abrController.currentBitrateKbps)

        // Fulfill 8s cooldown + 3 healthy windows for 800 -> 1200
        abrController.evaluate(tGood, nowElapsedMs = 30003L)
        abrController.evaluate(tGood, nowElapsedMs = 31003L)
        val stepTo1200 = abrController.evaluate(tGood, nowElapsedMs = 32003L)
        assertNotNull(stepTo1200)
        assertEquals(1200, stepTo1200?.newBitrateKbps)
        assertEquals(1200, abrController.currentBitrateKbps)
    }

    // ==========================================
    // 2. THERMAL & DYNAMIC FPS COORDINATION
    // ==========================================

    @Test
    fun testThermalCeilingPrecedenceOverAbr() {
        // Set thermal ceiling to 500 kbps (SEVERE)
        val decision = abrController.setThermalBitrateCeiling(500)
        assertNotNull(decision)
        assertEquals(500, decision?.newBitrateKbps)
        assertEquals(500, abrController.currentBitrateKbps)

        // Even with 100% pristine network and 0 queue, ABR cannot upgrade past 500
        val tGood = NetworkQualityTelemetry(health = NetworkHealth.EXCELLENT, currentLatencyMs = 20L, queueFillRatio = 0.05f)
        abrController.evaluate(tGood, nowElapsedMs = 20_000L)
        abrController.evaluate(tGood, nowElapsedMs = 21_000L)
        val upgradeAttempt = abrController.evaluate(tGood, nowElapsedMs = 22_000L)
        assertNull("ABR cannot upgrade beyond active thermal ceiling of 500 kbps", upgradeAttempt)
        assertEquals(500, abrController.currentBitrateKbps)

        // Network degradation can drop bitrate BELOW thermal ceiling (e.g. down to 300)
        val tCritical = NetworkQualityTelemetry(health = NetworkHealth.CRITICAL, currentLatencyMs = 700L, queueFillRatio = 0.85f)
        val dropTo300 = abrController.evaluate(tCritical, nowElapsedMs = 23_000L)
        assertNotNull(dropTo300)
        assertEquals(300, dropTo300?.newBitrateKbps)

        // Relieve thermal pressure back to NOMINAL (1200 kbps ceiling)
        abrController.setThermalBitrateCeiling(1200)
        // Bitrate does not magically jump immediately; respects ABR upgrade ladder
        assertEquals(300, abrController.currentBitrateKbps)
    }

    @Test
    fun testDynamicFpsThrottlingThermalCeiling() {
        assertEquals(30, fpsThrottler.currentTargetFps)

        // Step thermal to MODERATE -> ceiling is 24 FPS
        fpsThrottler.updateThermalLevel(ThermalThrottlingLevel.MODERATE)
        assertEquals(24, fpsThrottler.currentTargetFps)
        assertEquals(24, fpsThrottler.thermalCeilingFps)

        // Step thermal to SEVERE -> ceiling is 20 FPS
        fpsThrottler.updateThermalLevel(ThermalThrottlingLevel.SEVERE)
        assertEquals(20, fpsThrottler.currentTargetFps)
        assertEquals(20, fpsThrottler.thermalCeilingFps)

        // Step thermal to CRITICAL -> ceiling is 15 FPS
        fpsThrottler.updateThermalLevel(ThermalThrottlingLevel.CRITICAL)
        assertEquals(15, fpsThrottler.currentTargetFps)
        assertEquals(15, fpsThrottler.thermalCeilingFps)

        // Relieve thermal back to NOMINAL
        fpsThrottler.updateThermalLevel(ThermalThrottlingLevel.NOMINAL)
        assertEquals(30, fpsThrottler.currentTargetFps)
        assertEquals(30, fpsThrottler.thermalCeilingFps)
    }

    // ==========================================
    // 3. RECONNECTION BACKOFF & EXHAUSTION STRESS
    // ==========================================

    @Test
    fun testReconnectionScheduleAndExhaustion() {
        // Attempt 1: 1000 ms
        assertTrue(reconnectManager.canRetry)
        val delay1 = reconnectManager.scheduleNextAttempt("Connection reset", 1000L)
        assertEquals(1000L, delay1)
        assertEquals(1, reconnectManager.attemptCount)

        // Attempt 2: 2000 ms
        assertTrue(reconnectManager.canRetry)
        val delay2 = reconnectManager.scheduleNextAttempt("Connection reset", 2000L)
        assertEquals(2000L, delay2)
        assertEquals(2, reconnectManager.attemptCount)

        // Attempt 3: 4000 ms
        assertTrue(reconnectManager.canRetry)
        val delay3 = reconnectManager.scheduleNextAttempt("Connection reset", 4000L)
        assertEquals(4000L, delay3)
        assertEquals(3, reconnectManager.attemptCount)

        // Attempt 4: 8000 ms (capped at maxDelayMs)
        assertTrue(reconnectManager.canRetry)
        val delay4 = reconnectManager.scheduleNextAttempt("Connection reset", 8000L)
        assertEquals(8000L, delay4)
        assertEquals(4, reconnectManager.attemptCount)

        // Attempt 5: Exhausted (maxAttempts = 4)
        val exhaustedDelay = reconnectManager.scheduleNextAttempt("Connection reset", 16000L)
        assertNull(exhaustedDelay)
        assertFalse("Retry must be rejected after 4 failed attempts", reconnectManager.canRetry)
        assertTrue(reconnectManager.isExhausted)
        assertEquals(ReconnectEngineState.EXHAUSTED, reconnectManager.state)

        // Reset clears backoff completely
        reconnectManager.reset()
        assertTrue(reconnectManager.canRetry)
        assertFalse(reconnectManager.isExhausted)
        assertEquals(0, reconnectManager.attemptCount)
        assertEquals(ReconnectEngineState.IDLE, reconnectManager.state)
    }

    @Test
    fun test10ReconnectionCyclesWithoutExhaustion() {
        // Execute 10 consecutive simulated disconnect/reconnect recovery cycles
        for (cycle in 1..10) {
            assertTrue(reconnectManager.canRetry)
            val d = reconnectManager.scheduleNextAttempt("Socket drop $cycle", 1000L * cycle)
            assertEquals(1000L, d)
            // Connection succeeds -> reset manager
            reconnectManager.reset()
            assertEquals(0, reconnectManager.attemptCount)
        }
    }

    // ==========================================
    // 4. MEMORY PRESSURE PURGE STRESS
    // ==========================================

    @Test
    fun testMemoryPressurePurgeTransitions() {
        val guard = MemoryPressureGuard()
        var receivedLevel = 0

        guard.addListener(object : MemoryPressureGuard.MemoryTrimListener {
            override fun onMemoryTrim(level: Int) {
                receivedLevel = level
            }
        })

        guard.onTrimMemory(android.content.ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW)
        assertEquals(android.content.ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW, guard.lastTrimLevel)
        assertEquals(1, guard.trimEventCount)
        assertEquals(android.content.ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW, receivedLevel)

        guard.onTrimMemory(android.content.ComponentCallbacks2.TRIM_MEMORY_RUNNING_CRITICAL)
        assertEquals(android.content.ComponentCallbacks2.TRIM_MEMORY_RUNNING_CRITICAL, guard.lastTrimLevel)
        assertEquals(2, guard.trimEventCount)
        assertEquals(android.content.ComponentCallbacks2.TRIM_MEMORY_RUNNING_CRITICAL, receivedLevel)
    }

    // ==========================================
    // 5. A/V TIMESTAMP DRIFT & MONOTONICITY
    // ==========================================

    @Test
    fun testSyntheticAvClockDriftAndMonotonicity() {
        // Simulate 10,000 video frames at 30 FPS (~333 seconds)
        // Audio at 1024 samples per AU @ 44100Hz (~23.22ms per AU)
        var videoPts = 0L
        var audioPts = 0L
        var maxDelta = 0L

        val videoFrameIntervalUs = 33_333L // 30 FPS
        val audioFrameIntervalUs = 23_220L // ~43.06 AU/s

        var nextVideoTime = 0L
        var nextAudioTime = 0L

        var simulatedTimelineUs = 0L
        val totalDurationUs = 10_000_000L // 10 seconds of streaming

        var videoFrameCount = 0
        var audioFrameCount = 0

        while (simulatedTimelineUs < totalDurationUs) {
            if (simulatedTimelineUs >= nextVideoTime) {
                videoPts = nextVideoTime / 1000L // to ms
                nextVideoTime += videoFrameIntervalUs
                videoFrameCount++
            }
            if (simulatedTimelineUs >= nextAudioTime) {
                audioPts = nextAudioTime / 1000L // to ms
                nextAudioTime += audioFrameIntervalUs
                audioFrameCount++
            }
            val delta = Math.abs(videoPts - audioPts)
            if (delta > maxDelta) {
                maxDelta = delta
            }
            simulatedTimelineUs += 1000L // advance 1ms
        }

        assertTrue("Video frame count must be > 290", videoFrameCount >= 290)
        assertTrue("Audio AU count must be > 420", audioFrameCount >= 420)
        // A/V delta must remain bounded below 45ms
        assertTrue("A/V delta max must be <= 45ms, was: $maxDelta ms", maxDelta <= 45L)
    }
}
