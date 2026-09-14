package com.fairylive.app.core.network

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.os.SystemClock
import android.util.Log

/**
 * Physical transport type detected by Android ConnectivityManager.
 */
enum class NetworkTransport {
    WIFI,
    CELLULAR,
    ETHERNET,
    OTHER,
    UNAVAILABLE
}

/**
 * Evaluated health state of the active streaming connection.
 */
enum class NetworkHealth {
    EXCELLENT,
    HEALTHY,
    DEGRADED,
    POOR,
    CRITICAL
}

/**
 * Immutable snapshot of network quality and connection metrics.
 */
data class NetworkQualityTelemetry(
    val transport: NetworkTransport = NetworkTransport.UNAVAILABLE,
    val isNetworkAvailable: Boolean = false,
    val isRtmpConnected: Boolean = false,
    val health: NetworkHealth = NetworkHealth.HEALTHY,
    val currentLatencyMs: Long = 0L,
    val averageLatencyMs: Long = 0L,
    val throughputBps: Long = 0L,
    val throughputKbps: Long = 0L,
    val queueDepth: Int = 0,
    val queueMaxCapacity: Int = 0,
    val queueFillRatio: Float = 0f,
    val droppedPackets: Long = 0L,
    val consecutiveSlowWrites: Int = 0,
    val consecutiveFailures: Int = 0,
    val timestampMs: Long = 0L
)

/**
 * Real-time network monitor and connection health analyzer.
 * Tracks OS network capabilities, TCP socket write latencies, throughput sliding window,
 * and queue backpressure with zero object allocation on the hot packet-transmission path.
 */
class NetworkQualityMonitor(
    private val slowWriteThresholdMs: Long = 150L,
    private val criticalWriteThresholdMs: Long = 500L,
    private val latencyHistorySize: Int = 16
) {
    // OS Connectivity State
    @Volatile
    var activeTransport: NetworkTransport = NetworkTransport.UNAVAILABLE
        private set

    @Volatile
    var isNetworkAvailable: Boolean = false
        private set

    @Volatile
    var isRtmpConnected: Boolean = false
        private set

    // Hot-path latency tracking via primitive circular buffer (zero allocations)
    private val latencyHistory = LongArray(latencyHistorySize)
    private var latencyHead = 0
    private var latencySampleCount = 0
    private var latencyRunningSum = 0L

    // Hot-path throughput tracking over sliding window
    private var windowStartElapsedMs: Long = 0L
    private var bytesInWindow: Long = 0L
    private var lastCalculatedThroughputBps: Long = 0L

    // Health and backpressure primitives
    @Volatile
    var currentLatencyMs: Long = 0L
        private set

    @Volatile
    var consecutiveSlowWrites: Int = 0
        private set

    @Volatile
    var consecutiveFailures: Int = 0
        private set

    private var currentQueueDepth: Int = 0
    private var currentQueueMax: Int = 0
    private var currentDroppedPackets: Long = 0L

    @Volatile
    var currentHealth: NetworkHealth = NetworkHealth.HEALTHY
        private set

    private var connectivityManager: ConnectivityManager? = null
    private var networkCallback: ConnectivityManager.NetworkCallback? = null

    var onHealthChangedListener: ((NetworkHealth, NetworkQualityTelemetry) -> Unit)? = null

    /**
     * Registers network callback with Android ConnectivityManager.
     */
    fun register(context: Context) {
        try {
            val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            connectivityManager = cm ?: return

            // Initial capability probe
            val activeNet = cm.activeNetwork
            val caps = if (activeNet != null) cm.getNetworkCapabilities(activeNet) else null
            updateCapabilities(caps)

            val callback = object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) {
                    val c = cm.getNetworkCapabilities(network)
                    updateCapabilities(c)
                }

                override fun onCapabilitiesChanged(network: Network, capabilities: NetworkCapabilities) {
                    updateCapabilities(capabilities)
                }

                override fun onLost(network: Network) {
                    isNetworkAvailable = false
                    activeTransport = NetworkTransport.UNAVAILABLE
                    reevaluateHealth()
                }
            }
            networkCallback = callback
            cm.registerDefaultNetworkCallback(callback)
            Log.i(TAG, "NetworkQualityMonitor registered with ConnectivityManager")
        } catch (e: Exception) {
            Log.w(TAG, "Failed to register ConnectivityManager callback (test environment?): ${e.message}")
        }
    }

    /**
     * Unregisters the network callback.
     */
    fun unregister() {
        try {
            val cm = connectivityManager
            val cb = networkCallback
            if (cm != null && cb != null) {
                cm.unregisterNetworkCallback(cb)
            }
        } catch (e: Exception) {
            Log.w(TAG, "Error unregistering network callback: ${e.message}")
        } finally {
            networkCallback = null
            connectivityManager = null
        }
    }

    /**
     * Called when RTMP socket connection state changes.
     */
    fun setRtmpConnected(connected: Boolean) {
        isRtmpConnected = connected
        if (!connected) {
            consecutiveFailures++
        } else {
            consecutiveFailures = 0
        }
        reevaluateHealth()
    }

    /**
     * Records a completed packet write on the sender thread.
     * ZERO allocations on this hot path.
     */
    fun recordPacketWrite(
        bytesWritten: Int,
        writeDurationMs: Long,
        queueDepth: Int,
        queueMaxCapacity: Int,
        droppedCount: Long,
        nowElapsedMs: Long = SystemClock.elapsedRealtime()
    ) {
        currentLatencyMs = writeDurationMs
        currentQueueDepth = queueDepth
        currentQueueMax = queueMaxCapacity
        currentDroppedPackets = droppedCount

        // 1. Update circular buffer latency
        if (latencySampleCount < latencyHistorySize) {
            latencyRunningSum += writeDurationMs
            latencyHistory[latencyHead] = writeDurationMs
            latencySampleCount++
        } else {
            latencyRunningSum = latencyRunningSum - latencyHistory[latencyHead] + writeDurationMs
            latencyHistory[latencyHead] = writeDurationMs
        }
        latencyHead = (latencyHead + 1) % latencyHistorySize

        // 2. Track slow write counters
        if (writeDurationMs >= slowWriteThresholdMs) {
            consecutiveSlowWrites++
        } else {
            consecutiveSlowWrites = 0
        }
        consecutiveFailures = 0

        // 3. Update sliding window throughput
        if (windowStartElapsedMs == 0L) {
            windowStartElapsedMs = nowElapsedMs
            bytesInWindow = bytesWritten.toLong()
        } else {
            val elapsedInWindow = nowElapsedMs - windowStartElapsedMs
            bytesInWindow += bytesWritten
            if (elapsedInWindow >= 1000L) {
                lastCalculatedThroughputBps = if (elapsedInWindow > 0) {
                    (bytesInWindow * 8L * 1000L) / elapsedInWindow
                } else {
                    0L
                }
                windowStartElapsedMs = nowElapsedMs
                bytesInWindow = 0L
            }
        }

        // 4. Evaluate network health
        reevaluateHealth(nowElapsedMs)
    }

    /**
     * Records a socket write failure.
     */
    fun recordWriteFailure(error: Exception?, nowElapsedMs: Long = SystemClock.elapsedRealtime()) {
        consecutiveFailures++
        consecutiveSlowWrites++
        reevaluateHealth(nowElapsedMs)
    }

    /**
     * Resets all streaming telemetry primitives.
     */
    fun reset() {
        latencyHead = 0
        latencySampleCount = 0
        latencyRunningSum = 0L
        currentLatencyMs = 0L
        consecutiveSlowWrites = 0
        consecutiveFailures = 0
        windowStartElapsedMs = 0L
        bytesInWindow = 0L
        lastCalculatedThroughputBps = 0L
        currentQueueDepth = 0
        currentQueueMax = 0
        currentDroppedPackets = 0L
        currentHealth = NetworkHealth.HEALTHY
    }

    val averageLatencyMs: Long
        get() = if (latencySampleCount > 0) latencyRunningSum / latencySampleCount else 0L

    val currentTelemetry: NetworkQualityTelemetry
        get() {
            val fillRatio = if (currentQueueMax > 0) currentQueueDepth.toFloat() / currentQueueMax.toFloat() else 0f
            return NetworkQualityTelemetry(
                transport = activeTransport,
                isNetworkAvailable = isNetworkAvailable,
                isRtmpConnected = isRtmpConnected,
                health = currentHealth,
                currentLatencyMs = currentLatencyMs,
                averageLatencyMs = averageLatencyMs,
                throughputBps = lastCalculatedThroughputBps,
                throughputKbps = lastCalculatedThroughputBps / 1000L,
                queueDepth = currentQueueDepth,
                queueMaxCapacity = currentQueueMax,
                queueFillRatio = fillRatio,
                droppedPackets = currentDroppedPackets,
                consecutiveSlowWrites = consecutiveSlowWrites,
                consecutiveFailures = consecutiveFailures,
                timestampMs = System.currentTimeMillis()
            )
        }

    private fun reevaluateHealth(nowElapsedMs: Long = SystemClock.elapsedRealtime()) {
        val previousHealth = currentHealth
        val queueRatio = if (currentQueueMax > 0) currentQueueDepth.toFloat() / currentQueueMax.toFloat() else 0f
        val avgLatency = averageLatencyMs

        val newHealth = when {
            // Severe congestion or failure conditions
            consecutiveFailures > 0 || !isNetworkAvailable || (isRtmpConnected && avgLatency >= criticalWriteThresholdMs) || queueRatio >= 0.75f -> {
                NetworkHealth.CRITICAL
            }
            // Poor network
            consecutiveSlowWrites >= 3 || avgLatency >= 350L || queueRatio >= 0.50f -> {
                NetworkHealth.POOR
            }
            // Degraded network
            consecutiveSlowWrites >= 1 || avgLatency >= slowWriteThresholdMs || queueRatio >= 0.25f -> {
                NetworkHealth.DEGRADED
            }
            // Excellent network
            avgLatency in 1..49 && consecutiveSlowWrites == 0 && queueRatio <= 0.05f -> {
                NetworkHealth.EXCELLENT
            }
            // Default healthy
            else -> {
                NetworkHealth.HEALTHY
            }
        }

        currentHealth = newHealth
        if (newHealth != previousHealth) {
            Log.d(TAG, "Network health transition: $previousHealth -> $newHealth (avgLatency=${avgLatency}ms, queueRatio=${queueRatio})")
            onHealthChangedListener?.invoke(newHealth, currentTelemetry)
        }
    }

    private fun updateCapabilities(capabilities: NetworkCapabilities?) {
        if (capabilities == null) {
            isNetworkAvailable = false
            activeTransport = NetworkTransport.UNAVAILABLE
            reevaluateHealth()
            return
        }

        val hasInternet = capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
        isNetworkAvailable = hasInternet

        activeTransport = when {
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> NetworkTransport.WIFI
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> NetworkTransport.CELLULAR
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> NetworkTransport.ETHERNET
            else -> NetworkTransport.OTHER
        }
        Log.i(TAG, "Network updated: transport=$activeTransport, internet=$isNetworkAvailable")
        reevaluateHealth()
    }

    /**
     * Testing hook to simulate network availability and transport without Android framework dependencies.
     */
    fun setNetworkStateForTesting(available: Boolean, transport: NetworkTransport) {
        isNetworkAvailable = available
        activeTransport = transport
        reevaluateHealth()
    }

    companion object {
        private const val TAG = "NetworkQualityMonitor"
    }
}
