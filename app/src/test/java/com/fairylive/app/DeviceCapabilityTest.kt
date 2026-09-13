package com.fairylive.app

import com.fairylive.app.core.capability.DeviceCapabilityDetector
import com.fairylive.app.core.capability.model.CpuMetrics
import com.fairylive.app.core.capability.model.RamMetrics
import com.fairylive.app.core.capability.model.StreamProfile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DeviceCapabilityTest {

    private val detector = DeviceCapabilityDetector()

    @Test
    fun testLowEndRamConstrainedDeviceRecommendsLowProfile() {
        val constrainedRam = RamMetrics(
            totalMemMb = 2048L,
            availMemMb = 800L,
            isLowRamDevice = true,
            memoryClassMb = 128
        )
        val normalCpu = CpuMetrics(coreCount = 8, primaryAbi = "arm64-v8a")

        assertTrue("RAM under 3GB must be marked constrained", constrainedRam.isConstrained)
        val profile = detector.calculateRecommendedProfile(constrainedRam, normalCpu, hasHwH264 = true)
        assertEquals(StreamProfile.LOW, profile)
    }

    @Test
    fun testConstrainedCpuRecommendsLowProfile() {
        val goodRam = RamMetrics(
            totalMemMb = 4096L,
            availMemMb = 2000L,
            isLowRamDevice = false,
            memoryClassMb = 256
        )
        val weakCpu = CpuMetrics(coreCount = 4, primaryAbi = "arm64-v8a")

        assertTrue("CPU with <= 4 cores must be marked constrained", weakCpu.isConstrained)
        val profile = detector.calculateRecommendedProfile(goodRam, weakCpu, hasHwH264 = true)
        assertEquals(StreamProfile.LOW, profile)
    }

    @Test
    fun testMissingHardwareEncoderRecommendsLowProfile() {
        val goodRam = RamMetrics(
            totalMemMb = 6144L,
            availMemMb = 3000L,
            isLowRamDevice = false,
            memoryClassMb = 512
        )
        val goodCpu = CpuMetrics(coreCount = 8, primaryAbi = "arm64-v8a")

        val profile = detector.calculateRecommendedProfile(goodRam, goodCpu, hasHwH264 = false)
        assertEquals("Missing hardware encoder must fallback to LOW profile", StreamProfile.LOW, profile)
    }

    @Test
    fun testMidTierDeviceRecommendsBalancedProfile() {
        val midRam = RamMetrics(
            totalMemMb = 4096L,
            availMemMb = 2000L,
            isLowRamDevice = false,
            memoryClassMb = 256
        )
        val goodCpu = CpuMetrics(coreCount = 8, primaryAbi = "arm64-v8a")

        assertFalse(midRam.isConstrained)
        assertFalse(goodCpu.isConstrained)

        val profile = detector.calculateRecommendedProfile(midRam, goodCpu, hasHwH264 = true)
        assertEquals(StreamProfile.BALANCED, profile)
    }

    @Test
    fun testHighTierDeviceRecommendsHighProfile() {
        val highRam = RamMetrics(
            totalMemMb = 8192L,
            availMemMb = 4000L,
            isLowRamDevice = false,
            memoryClassMb = 512
        )
        val highCpu = CpuMetrics(coreCount = 8, primaryAbi = "arm64-v8a")

        val profile = detector.calculateRecommendedProfile(highRam, highCpu, hasHwH264 = true)
        assertEquals(StreamProfile.HIGH, profile)
    }
}
