package com.fairylive.app

import com.fairylive.app.core.network.AdaptiveBitrateController
import com.fairylive.app.core.network.BitrateChangeReason
import com.fairylive.app.core.network.NetworkHealth
import com.fairylive.app.core.network.NetworkQualityTelemetry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class AdaptiveBitrateControllerTest {

    private lateinit var controller: AdaptiveBitrateController

    @Before
    fun setUp() {
        controller = AdaptiveBitrateController(
            ladderKbps = intArrayOf(1200, 800, 500, 300),
            cooldownMs = 3000L,
            hysteresisRequiredHealthyWindows = 3
        )
        controller.initialize(1200, nowElapsedMs = 1000L)
    }

    @Test
    fun testInitialization() {
        assertEquals(1200, controller.currentBitrateKbps)
        assertEquals(0, controller.currentTierIndex)
        assertEquals(1200, controller.maxBitrateKbps)
        assertEquals(300, controller.minBitrateKbps)
    }

    @Test
    fun testCriticalCongestionTriggersEmergencyDropAndKeyFrame() {
        val criticalTelemetry = NetworkQualityTelemetry(
            health = NetworkHealth.CRITICAL,
            currentLatencyMs = 650L,
            queueFillRatio = 0.85f
        )

        val decision = controller.evaluate(criticalTelemetry, nowElapsedMs = 2000L)
        assertNotNull(decision)
        assertEquals(1200, decision?.oldBitrateKbps)
        assertEquals(300, decision?.newBitrateKbps) // Dropped straight to lowest tier
        assertEquals(BitrateChangeReason.CRITICAL_CONGESTION, decision?.reason)
        assertTrue(decision?.requestKeyFrame == true) // Must request sync keyframe
        assertEquals(300, controller.currentBitrateKbps)
    }

    @Test
    fun testPoorNetworkTriggersFastDropTwoTiers() {
        val poorTelemetry = NetworkQualityTelemetry(
            health = NetworkHealth.POOR,
            currentLatencyMs = 380L,
            queueFillRatio = 0.55f
        )

        val decision = controller.evaluate(poorTelemetry, nowElapsedMs = 2000L)
        assertNotNull(decision)
        assertEquals(1200, decision?.oldBitrateKbps)
        assertEquals(500, decision?.newBitrateKbps) // Tier 0 -> Tier 2 (500 kbps)
        assertEquals(BitrateChangeReason.POOR_NETWORK, decision?.reason)
        assertTrue(decision?.requestKeyFrame == true)
    }

    @Test
    fun testDegradedNetworkTriggersStepDownOneTier() {
        val degradedTelemetry = NetworkQualityTelemetry(
            health = NetworkHealth.DEGRADED,
            currentLatencyMs = 180L,
            queueFillRatio = 0.35f
        )

        val decision = controller.evaluate(degradedTelemetry, nowElapsedMs = 3000L)
        assertNotNull(decision)
        assertEquals(1200, decision?.oldBitrateKbps)
        assertEquals(800, decision?.newBitrateKbps) // Tier 0 -> Tier 1 (800 kbps)
        assertEquals(BitrateChangeReason.QUEUE_BACKPRESSURE, decision?.reason)
    }

    @Test
    fun testConservativeRecoveryRequiresHysteresisAndCooldown() {
        // First drop to 500 kbps
        val poorTelemetry = NetworkQualityTelemetry(health = NetworkHealth.POOR)
        controller.evaluate(poorTelemetry, nowElapsedMs = 2000L)
        assertEquals(500, controller.currentBitrateKbps)

        val healthyTelemetry = NetworkQualityTelemetry(health = NetworkHealth.HEALTHY)

        // Single healthy evaluation -> NO increase (hysteresis protection)
        val decision1 = controller.evaluate(healthyTelemetry, nowElapsedMs = 3000L)
        assertNull("Bitrate must not increase on a single healthy sample", decision1)
        assertEquals(500, controller.currentBitrateKbps)

        // Second healthy evaluation within cooldown -> NO increase
        val decision2 = controller.evaluate(healthyTelemetry, nowElapsedMs = 4000L)
        assertNull("Bitrate must not increase before cooldown expires", decision2)
        assertEquals(500, controller.currentBitrateKbps)

        // Third healthy evaluation with cooldown passed (now 6000ms, elapsed 4000ms > 3000ms) -> Steps up 1 tier to 800 kbps
        val decision3 = controller.evaluate(healthyTelemetry, nowElapsedMs = 6000L)
        assertNotNull("Bitrate should step up after sustained healthy conditions and cooldown", decision3)
        assertEquals(500, decision3?.oldBitrateKbps)
        assertEquals(800, decision3?.newBitrateKbps)
        assertEquals(BitrateChangeReason.SUSTAINED_HEALTHY_RECOVERY, decision3?.reason)
        assertEquals(800, controller.currentBitrateKbps)
    }
}
