package com.fairylive.app.core.network

import android.os.SystemClock
import android.util.Log

/**
 * Reasons explaining dynamic bitrate adjustments.
 */
enum class BitrateChangeReason {
    INITIAL_SETUP,
    CRITICAL_CONGESTION,
    POOR_NETWORK,
    DEGRADED_LATENCY,
    QUEUE_BACKPRESSURE,
    SUSTAINED_HEALTHY_RECOVERY,
    THERMAL_THROTTLING,
    MANUAL_OVERRIDE
}

/**
 * Observable record of an adaptive bitrate decision.
 */
data class BitrateDecision(
    val oldBitrateKbps: Int,
    val newBitrateKbps: Int,
    val reason: BitrateChangeReason,
    val requestKeyFrame: Boolean,
    val timestampElapsedMs: Long = SystemClock.elapsedRealtime(),
    val wallTimeMs: Long = System.currentTimeMillis()
)

/**
 * Production Adaptive Bitrate Controller for live streaming.
 * Operates on a discrete bitrate ladder, enforcing fast downscaling on network congestion,
 * conservative step-up recovery with hysteresis, and anti-oscillation cooldowns.
 */
class AdaptiveBitrateController(
    val ladderKbps: IntArray = intArrayOf(1200, 800, 500, 300),
    val cooldownMs: Long = 3000L,
    val hysteresisRequiredHealthyWindows: Int = 3
) {
    init {
        require(ladderKbps.isNotEmpty()) { "Bitrate ladder must not be empty" }
        // Verify ladder is in descending order
        for (i in 0 until ladderKbps.size - 1) {
            require(ladderKbps[i] > ladderKbps[i + 1]) { "Bitrate ladder must be strictly descending" }
        }
    }

    val maxBitrateKbps: Int = ladderKbps.first()
    val minBitrateKbps: Int = ladderKbps.last()

    var currentTierIndex: Int = 0
        private set

    var thermalCeilingTierIndex: Int = 0
        private set

    val currentBitrateKbps: Int
        get() = ladderKbps[currentTierIndex]

    fun setThermalBitrateCeiling(ceilingKbps: Int, nowElapsedMs: Long = SystemClock.elapsedRealtime()): BitrateDecision? {
        val targetIndex = findClosestTierIndex(ceilingKbps)
        val ceilingChanged = (thermalCeilingTierIndex != targetIndex)
        thermalCeilingTierIndex = targetIndex
        if (ceilingChanged) {
            consecutiveHealthyEvaluations = 0
            lastDecisionTimeMs = nowElapsedMs
        }
        if (currentTierIndex < targetIndex) {
            val oldKbps = currentBitrateKbps
            currentTierIndex = targetIndex

            val decision = BitrateDecision(
                oldBitrateKbps = oldKbps,
                newBitrateKbps = currentBitrateKbps,
                reason = BitrateChangeReason.THERMAL_THROTTLING,
                requestKeyFrame = true,
                timestampElapsedMs = nowElapsedMs
            )
            lastDecision = decision
            Log.w(TAG, "ABR Thermal Ceiling Enforced: $oldKbps -> $currentBitrateKbps kbps")
            onBitrateAdjustmentListener?.invoke(decision)
            return decision
        }
        return null
    }

    private var lastDecisionTimeMs: Long = 0L
    private var consecutiveHealthyEvaluations: Int = 0

    var lastDecision: BitrateDecision? = null
        private set

    var onBitrateAdjustmentListener: ((BitrateDecision) -> Unit)? = null

    /**
     * Initializes controller with a base target bitrate.
     */
    fun initialize(initialBitrateKbps: Int, nowElapsedMs: Long = SystemClock.elapsedRealtime()) {
        val closestIndex = findClosestTierIndex(initialBitrateKbps)
        currentTierIndex = closestIndex
        lastDecisionTimeMs = nowElapsedMs
        consecutiveHealthyEvaluations = 0

        val decision = BitrateDecision(
            oldBitrateKbps = initialBitrateKbps,
            newBitrateKbps = ladderKbps[closestIndex],
            reason = BitrateChangeReason.INITIAL_SETUP,
            requestKeyFrame = false,
            timestampElapsedMs = nowElapsedMs
        )
        lastDecision = decision
        Log.i(TAG, "Initialized ABR at ${ladderKbps[closestIndex]}kbps (tier $closestIndex)")
        onBitrateAdjustmentListener?.invoke(decision)
    }

    /**
     * Evaluates network telemetry to determine whether to scale bitrate up, down, or maintain.
     *
     * Rules:
     * - Fast downscaling: If network is CRITICAL, drop immediately to lowest tier (or +2 tiers).
     * - POOR: drop 1-2 tiers.
     * - DEGRADED: drop 1 tier.
     * - Conservative recovery: Only step up 1 tier after [hysteresisRequiredHealthyWindows] sustained
     *   healthy/excellent evaluations AND [cooldownMs] has elapsed since the last decision.
     */
    fun evaluate(telemetry: NetworkQualityTelemetry, nowElapsedMs: Long = SystemClock.elapsedRealtime()): BitrateDecision? {
        val timeSinceLastDecision = nowElapsedMs - lastDecisionTimeMs

        // 1. Congestion Downscaling Path (Fast Action)
        when (telemetry.health) {
            NetworkHealth.CRITICAL -> {
                // Emergency drop to lowest safe tier
                if (currentTierIndex < ladderKbps.size - 1) {
                    val oldKbps = currentBitrateKbps
                    currentTierIndex = ladderKbps.size - 1
                    lastDecisionTimeMs = nowElapsedMs
                    consecutiveHealthyEvaluations = 0

                    val decision = BitrateDecision(
                        oldBitrateKbps = oldKbps,
                        newBitrateKbps = currentBitrateKbps,
                        reason = BitrateChangeReason.CRITICAL_CONGESTION,
                        requestKeyFrame = true,
                        timestampElapsedMs = nowElapsedMs
                    )
                    lastDecision = decision
                    Log.w(TAG, "ABR Emergency Downscale: $oldKbps -> $currentBitrateKbps kbps [CRITICAL]")
                    onBitrateAdjustmentListener?.invoke(decision)
                    return decision
                }
            }

            NetworkHealth.POOR -> {
                // Drop 2 tiers (or to lowest available)
                val targetTier = (currentTierIndex + 2).coerceAtMost(ladderKbps.size - 1)
                if (targetTier > currentTierIndex) {
                    val oldKbps = currentBitrateKbps
                    currentTierIndex = targetTier
                    lastDecisionTimeMs = nowElapsedMs
                    consecutiveHealthyEvaluations = 0

                    val decision = BitrateDecision(
                        oldBitrateKbps = oldKbps,
                        newBitrateKbps = currentBitrateKbps,
                        reason = BitrateChangeReason.POOR_NETWORK,
                        requestKeyFrame = true,
                        timestampElapsedMs = nowElapsedMs
                    )
                    lastDecision = decision
                    Log.w(TAG, "ABR Fast Downscale: $oldKbps -> $currentBitrateKbps kbps [POOR]")
                    onBitrateAdjustmentListener?.invoke(decision)
                    return decision
                }
            }

            NetworkHealth.DEGRADED -> {
                // Drop 1 tier if not already at lowest tier and cooldown elapsed (or urgent)
                if (currentTierIndex < ladderKbps.size - 1 && (timeSinceLastDecision >= 1000L)) {
                    val oldKbps = currentBitrateKbps
                    currentTierIndex++
                    lastDecisionTimeMs = nowElapsedMs
                    consecutiveHealthyEvaluations = 0

                    val reason = if (telemetry.queueFillRatio >= 0.30f) {
                        BitrateChangeReason.QUEUE_BACKPRESSURE
                    } else {
                        BitrateChangeReason.DEGRADED_LATENCY
                    }

                    val decision = BitrateDecision(
                        oldBitrateKbps = oldKbps,
                        newBitrateKbps = currentBitrateKbps,
                        reason = reason,
                        requestKeyFrame = false,
                        timestampElapsedMs = nowElapsedMs
                    )
                    lastDecision = decision
                    Log.i(TAG, "ABR Downscale: $oldKbps -> $currentBitrateKbps kbps [$reason]")
                    onBitrateAdjustmentListener?.invoke(decision)
                    return decision
                }
            }

            NetworkHealth.HEALTHY, NetworkHealth.EXCELLENT -> {
                // 2. Recovery Upscaling Path (Conservative Action with Hysteresis & Cooldown)
                consecutiveHealthyEvaluations++

                if (currentTierIndex > thermalCeilingTierIndex &&
                    timeSinceLastDecision >= cooldownMs &&
                    consecutiveHealthyEvaluations >= hysteresisRequiredHealthyWindows
                ) {
                    // Safe to step up exactly 1 tier
                    val oldKbps = currentBitrateKbps
                    currentTierIndex--
                    lastDecisionTimeMs = nowElapsedMs
                    consecutiveHealthyEvaluations = 0 // Reset counter for next tier

                    val decision = BitrateDecision(
                        oldBitrateKbps = oldKbps,
                        newBitrateKbps = currentBitrateKbps,
                        reason = BitrateChangeReason.SUSTAINED_HEALTHY_RECOVERY,
                        requestKeyFrame = false,
                        timestampElapsedMs = nowElapsedMs
                    )
                    lastDecision = decision
                    Log.i(TAG, "ABR Step-Up Recovery: $oldKbps -> $currentBitrateKbps kbps [HEALTHY x$hysteresisRequiredHealthyWindows]")
                    onBitrateAdjustmentListener?.invoke(decision)
                    return decision
                }
            }
        }

        return null
    }

    /**
     * Finds the nearest tier index for an arbitrary bitrate.
     */
    private fun findClosestTierIndex(kbps: Int): Int {
        var bestIndex = 0
        var minDiff = Int.MAX_VALUE
        for (i in ladderKbps.indices) {
            val diff = Math.abs(ladderKbps[i] - kbps)
            if (diff < minDiff) {
                minDiff = diff
                bestIndex = i
            }
        }
        return bestIndex
    }

    companion object {
        private const val TAG = "AdaptiveBitrateCtrl"
    }
}
