package com.fairylive.app

import com.fairylive.app.core.network.NetworkHealth
import com.fairylive.app.core.network.NetworkQualityMonitor
import com.fairylive.app.core.network.NetworkTransport
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class NetworkQualityMonitorTest {

    private lateinit var monitor: NetworkQualityMonitor

    @Before
    fun setUp() {
        monitor = NetworkQualityMonitor(
            slowWriteThresholdMs = 150L,
            criticalWriteThresholdMs = 500L,
            latencyHistorySize = 8
        )
        monitor.setNetworkStateForTesting(available = true, transport = NetworkTransport.WIFI)
        monitor.setRtmpConnected(true)
    }

    @Test
    fun testInitialState() {
        assertTrue(monitor.isNetworkAvailable)
        assertTrue(monitor.isRtmpConnected)
        assertEquals(NetworkTransport.WIFI, monitor.activeTransport)
        assertEquals(NetworkHealth.HEALTHY, monitor.currentHealth)
        assertEquals(0L, monitor.currentLatencyMs)
        assertEquals(0L, monitor.averageLatencyMs)
        assertEquals(0, monitor.consecutiveSlowWrites)
        assertEquals(0, monitor.consecutiveFailures)
    }

    @Test
    fun testCircularBufferLatencyAveraging() {
        // Feed 4 samples of 20ms
        for (i in 0 until 4) {
            monitor.recordPacketWrite(
                bytesWritten = 1000,
                writeDurationMs = 20L,
                queueDepth = 5,
                queueMaxCapacity = 90,
                droppedCount = 0L,
                nowElapsedMs = 1000L + i * 50
            )
        }
        assertEquals(20L, monitor.averageLatencyMs)
        assertEquals(20L, monitor.currentLatencyMs)

        // Feed 4 samples of 40ms -> average should be (4*20 + 4*40)/8 = 30ms
        for (i in 0 until 4) {
            monitor.recordPacketWrite(
                bytesWritten = 1000,
                writeDurationMs = 40L,
                queueDepth = 5,
                queueMaxCapacity = 90,
                droppedCount = 0L,
                nowElapsedMs = 1200L + i * 50
            )
        }
        assertEquals(30L, monitor.averageLatencyMs)
    }

    @Test
    fun testHealthTransitions() {
        // Fast healthy writes -> EXCELLENT
        for (i in 0 until 8) {
            monitor.recordPacketWrite(
                bytesWritten = 2000,
                writeDurationMs = 25L,
                queueDepth = 2,
                queueMaxCapacity = 90,
                droppedCount = 0L,
                nowElapsedMs = 2000L + i * 50
            )
        }
        assertEquals(NetworkHealth.EXCELLENT, monitor.currentHealth)

        // Moderate latency -> DEGRADED
        monitor.recordPacketWrite(
            bytesWritten = 2000,
            writeDurationMs = 180L, // >= slowWriteThresholdMs (150ms)
            queueDepth = 10,
            queueMaxCapacity = 90,
            droppedCount = 0L,
            nowElapsedMs = 2500L
        )
        assertEquals(NetworkHealth.DEGRADED, monitor.currentHealth)
        assertEquals(1, monitor.consecutiveSlowWrites)

        // 3 consecutive slow writes -> POOR
        monitor.recordPacketWrite(2000, 200L, 15, 90, 0L, 2550L)
        monitor.recordPacketWrite(2000, 210L, 20, 90, 0L, 2600L)
        assertEquals(NetworkHealth.POOR, monitor.currentHealth)
        assertEquals(3, monitor.consecutiveSlowWrites)

        // Severe latency (>= 500ms) or queue backpressure (>= 75%) -> CRITICAL
        monitor.recordPacketWrite(2000, 600L, 75, 90, 0L, 2650L)
        assertEquals(NetworkHealth.CRITICAL, monitor.currentHealth)
    }

    @Test
    fun testWriteFailureTriggersCritical() {
        assertEquals(NetworkHealth.HEALTHY, monitor.currentHealth)
        monitor.recordWriteFailure(java.io.IOException("Connection reset by peer"))
        assertEquals(NetworkHealth.CRITICAL, monitor.currentHealth)
        assertEquals(1, monitor.consecutiveFailures)
    }

    @Test
    fun testNetworkLostTriggersCritical() {
        monitor.setNetworkStateForTesting(available = false, transport = NetworkTransport.UNAVAILABLE)
        assertFalse(monitor.isNetworkAvailable)
        assertEquals(NetworkTransport.UNAVAILABLE, monitor.activeTransport)
        assertEquals(NetworkHealth.CRITICAL, monitor.currentHealth)
    }

    @Test
    fun testThroughputSlidingWindow() {
        // Record 100,000 bytes over 1000ms window -> 800,000 bps = 800 kbps
        monitor.recordPacketWrite(50000, 20L, 2, 90, 0L, nowElapsedMs = 1000L)
        monitor.recordPacketWrite(50000, 20L, 2, 90, 0L, nowElapsedMs = 2000L)

        val telemetry = monitor.currentTelemetry
        assertEquals(800000L, telemetry.throughputBps)
        assertEquals(800L, telemetry.throughputKbps)
    }
}
