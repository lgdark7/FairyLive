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
}
