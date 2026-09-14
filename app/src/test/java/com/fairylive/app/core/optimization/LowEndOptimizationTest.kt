package com.fairylive.app.core.optimization

import android.content.ComponentCallbacks2
import com.fairylive.app.core.optimization.model.DeviceProfile
import com.fairylive.app.core.optimization.model.DeviceTier
import com.fairylive.app.core.optimization.model.ThermalThrottlingLevel
import org.junit.Assert.*
import org.junit.Test

class LowEndOptimizationTest {

    @Test
    fun testDeviceProfileClassificationLowEnd() {
        // Low RAM device or RAM < 3GB or cores <= 4
        val profile1 = DeviceProfileDetector.classify(totalRamMb = 2048, isLowRam = true, cores = 4)
        assertEquals(DeviceTier.LOW_END, profile1.tier)
        assertEquals(24, profile1.recommendedFps)
        assertEquals(900, profile1.recommendedBitrateKbps)
        assertFalse(profile1.enableHighQualityOverlays)

        val profile2 = DeviceProfileDetector.classify(totalRamMb = 1536, isLowRam = false, cores = 4)
        assertEquals(DeviceTier.LOW_END, profile2.tier)
    }

    @Test
    fun testDeviceProfileClassificationMidRange() {
        val profile = DeviceProfileDetector.classify(totalRamMb = 4096, isLowRam = false, cores = 6)
        assertEquals(DeviceTier.MID_RANGE, profile.tier)
        assertEquals(30, profile.recommendedFps)
        assertEquals(1200, profile.recommendedBitrateKbps)
        assertTrue(profile.enableHighQualityOverlays)
    }

    @Test
    fun testDeviceProfileClassificationFlagship() {
        val profile = DeviceProfileDetector.classify(totalRamMb = 8192, isLowRam = false, cores = 8)
        assertEquals(DeviceTier.FLAGSHIP, profile.tier)
        assertEquals(30, profile.recommendedFps)
        assertEquals(1500, profile.recommendedBitrateKbps)
        assertTrue(profile.enableHighQualityOverlays)
    }

    @Test
    fun testThermalMitigationTransitions() {
        val mgr = ThermalMitigationManager()

        var lastLevel = ThermalThrottlingLevel.NOMINAL
        var lastStatus = -1
        mgr.addListener(object : ThermalMitigationManager.ThermalListener {
            override fun onThermalLevelChanged(level: ThermalThrottlingLevel, rawStatus: Int) {
                lastLevel = level
                lastStatus = rawStatus
            }
        })

        mgr.setThermalStatusForTesting(0)
        assertEquals(ThermalThrottlingLevel.NOMINAL, mgr.currentLevel)

        mgr.setThermalStatusForTesting(2)
        assertEquals(ThermalThrottlingLevel.MODERATE, mgr.currentLevel)
        assertEquals(ThermalThrottlingLevel.MODERATE, lastLevel)
        assertEquals(2, lastStatus)

        mgr.setThermalStatusForTesting(3)
        assertEquals(ThermalThrottlingLevel.SEVERE, mgr.currentLevel)
        assertEquals(ThermalThrottlingLevel.SEVERE, lastLevel)

        mgr.setThermalStatusForTesting(4)
        assertEquals(ThermalThrottlingLevel.CRITICAL, mgr.currentLevel)
        assertEquals(ThermalThrottlingLevel.CRITICAL, lastLevel)

        mgr.setThermalStatusForTesting(1)
        assertEquals(ThermalThrottlingLevel.NOMINAL, mgr.currentLevel)
    }

    @Test
    fun testMemoryPressureGuardCallbacks() {
        val guard = MemoryPressureGuard()
        var reportedLevel = -1
        guard.addListener(object : MemoryPressureGuard.MemoryTrimListener {
            override fun onMemoryTrim(level: Int) {
                reportedLevel = level
            }
        })

        guard.trimMemoryForTesting(ComponentCallbacks2.TRIM_MEMORY_RUNNING_MODERATE)
        assertEquals(ComponentCallbacks2.TRIM_MEMORY_RUNNING_MODERATE, guard.lastTrimLevel)
        assertEquals(ComponentCallbacks2.TRIM_MEMORY_RUNNING_MODERATE, reportedLevel)
        assertEquals(1, guard.trimEventCount)

        guard.trimMemoryForTesting(ComponentCallbacks2.TRIM_MEMORY_RUNNING_CRITICAL)
        assertEquals(ComponentCallbacks2.TRIM_MEMORY_RUNNING_CRITICAL, guard.lastTrimLevel)
        assertEquals(2, guard.trimEventCount)
    }

    @Test
    fun testDynamicFpsThrottlerThermalScaling() {
        val throttler = DynamicFpsThrottler(baseFps = 30)
        assertEquals(30, throttler.currentTargetFps)
        assertEquals(1_000_000_000L / 30, throttler.currentFrameIntervalNs)

        throttler.updateThermalLevel(ThermalThrottlingLevel.MODERATE)
        assertEquals(24, throttler.currentTargetFps)
        assertEquals(1_000_000_000L / 24, throttler.currentFrameIntervalNs)

        throttler.updateThermalLevel(ThermalThrottlingLevel.SEVERE)
        assertEquals(20, throttler.currentTargetFps)
        assertEquals(1_000_000_000L / 20, throttler.currentFrameIntervalNs)

        throttler.updateThermalLevel(ThermalThrottlingLevel.CRITICAL)
        assertEquals(15, throttler.currentTargetFps)
        assertEquals(1_000_000_000L / 15, throttler.currentFrameIntervalNs)

        throttler.updateThermalLevel(ThermalThrottlingLevel.NOMINAL)
        assertEquals(30, throttler.currentTargetFps)
    }

    @Test
    fun testDynamicFpsThrottlerRenderLagAdjustment() {
        val throttler = DynamicFpsThrottler(baseFps = 30)
        assertEquals(30, throttler.currentTargetFps)

        // Simulate 10 slow frames (> 32ms)
        for (i in 0 until 10) {
            throttler.recordRenderTime(35L)
        }
        assertEquals("FPS must throttle down under heavy render lag", 24, throttler.currentTargetFps)

        // Simulate another 10 slow frames
        for (i in 0 until 10) {
            throttler.recordRenderTime(35L)
        }
        assertEquals("FPS must throttle down further", 20, throttler.currentTargetFps)
    }

    @Test
    fun testDynamicFpsThrottlerRecovery() {
        val throttler = DynamicFpsThrottler(baseFps = 30)
        throttler.forceFps(20)
        assertEquals(20, throttler.currentTargetFps)

        // Simulate 35 fast frames (< 18ms)
        for (i in 0 until 40) {
            throttler.recordRenderTime(10L)
        }
        assertTrue("FPS must step up toward baseline", throttler.currentTargetFps > 20)
    }

    @Test
    fun testDynamicFpsThrottlerThermalCeilingPreventsOverRecovery() {
        val throttler = DynamicFpsThrottler(baseFps = 30)
        throttler.updateThermalLevel(ThermalThrottlingLevel.MODERATE) // Ceiling = 24
        assertEquals(24, throttler.currentTargetFps)
        assertEquals(24, throttler.thermalCeilingFps)

        // Force to 15 to simulate heavy render lag
        throttler.forceFps(15)
        assertEquals(15, throttler.currentTargetFps)

        // 50 fast frames (< 18ms)
        for (i in 0 until 50) {
            throttler.recordRenderTime(10L)
        }
        // It must recover up to the ceiling (24), but NEVER exceed 24!
        assertEquals(20, throttler.currentTargetFps)

        for (i in 0 until 50) {
            throttler.recordRenderTime(10L)
        }
        assertEquals(24, throttler.currentTargetFps)

        // More fast frames must NOT push it to 30 because thermal ceiling is 24
        for (i in 0 until 50) {
            throttler.recordRenderTime(10L)
        }
        assertEquals("FPS must remain clamped at thermal ceiling of 24", 24, throttler.currentTargetFps)
    }

    @Test
    fun testAbrThermalCeilingEnforcementAndRecoveryClamping() {
        val abr = com.fairylive.app.core.network.AdaptiveBitrateController(cooldownMs = 0L)
        abr.initialize(1200, nowElapsedMs = 1000L)
        assertEquals(1200, abr.currentBitrateKbps)
        assertEquals(0, abr.thermalCeilingTierIndex)

        // 1. Enforce thermal ceiling to 800 kbps (MODERATE)
        val decision = abr.setThermalBitrateCeiling(800, nowElapsedMs = 1100L)
        assertNotNull(decision)
        assertEquals(800, abr.currentBitrateKbps)
        assertEquals(com.fairylive.app.core.network.BitrateChangeReason.THERMAL_THROTTLING, decision?.reason)
        assertTrue("Thermal step down must request IDR keyframe", decision?.requestKeyFrame == true)
        assertEquals(1, abr.thermalCeilingTierIndex)

        // 2. Simulate healthy network evaluations — recovery must be clamped at 800 kbps
        val healthyTelem = com.fairylive.app.core.network.NetworkQualityTelemetry(
            health = com.fairylive.app.core.network.NetworkHealth.EXCELLENT,
            throughputKbps = 5000,
            averageLatencyMs = 20,
            queueDepth = 1,
            queueFillRatio = 0.01f
        )
        for (i in 1..5) {
            val recDecision = abr.evaluate(healthyTelem, nowElapsedMs = 2000L + i * 100L)
            assertNull("Recovery cannot exceed thermal ceiling (800 kbps)", recDecision)
            assertEquals(800, abr.currentBitrateKbps)
        }

        // 3. Network degrades to POOR — ABR drops down to 300 kbps (below thermal ceiling)
        val poorTelem = com.fairylive.app.core.network.NetworkQualityTelemetry(
            health = com.fairylive.app.core.network.NetworkHealth.POOR,
            throughputKbps = 300,
            averageLatencyMs = 400,
            queueDepth = 40,
            queueFillRatio = 0.45f
        )
        val dropDecision = abr.evaluate(poorTelem, nowElapsedMs = 3000L)
        assertNotNull(dropDecision)
        assertEquals(300, abr.currentBitrateKbps)

        // 4. Network recovers to HEALTHY — ABR climbs back up, but only up to 800 kbps
        val rec1 = abr.evaluate(healthyTelem, nowElapsedMs = 3100L)
        assertNull(rec1) // Waiting for hysteresis (3 windows)
        abr.evaluate(healthyTelem, nowElapsedMs = 3200L)
        val recStep1 = abr.evaluate(healthyTelem, nowElapsedMs = 3300L)
        assertNotNull(recStep1)
        assertEquals(500, abr.currentBitrateKbps) // Step up 300 -> 500

        // Step up 500 -> 800
        abr.evaluate(healthyTelem, nowElapsedMs = 3400L)
        abr.evaluate(healthyTelem, nowElapsedMs = 3500L)
        val recStep2 = abr.evaluate(healthyTelem, nowElapsedMs = 3600L)
        assertNotNull(recStep2)
        assertEquals(800, abr.currentBitrateKbps)

        // Subsequent healthy evaluations CANNOT exceed 800 kbps
        for (i in 1..5) {
            val recClamp = abr.evaluate(healthyTelem, nowElapsedMs = 3700L + i * 100L)
            assertNull("Recovery clamped at thermal ceiling", recClamp)
            assertEquals(800, abr.currentBitrateKbps)
        }

        // 5. Thermal recovers to NOMINAL (1200 kbps)
        abr.setThermalBitrateCeiling(1200, nowElapsedMs = 4500L)
        assertEquals(0, abr.thermalCeilingTierIndex)

        // Now ABR is permitted to recover from 800 -> 1200 kbps
        abr.evaluate(healthyTelem, nowElapsedMs = 4600L)
        abr.evaluate(healthyTelem, nowElapsedMs = 4700L)
        val fullRec = abr.evaluate(healthyTelem, nowElapsedMs = 4800L)
        assertNotNull(fullRec)
        assertEquals(1200, abr.currentBitrateKbps)
    }
}
