package com.fairylive.app.core.optimization

import android.util.Log
import com.fairylive.app.core.optimization.model.ThermalThrottlingLevel
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

class DynamicFpsThrottler(
    val baseFps: Int = 30
) {
    companion object {
        private const val TAG = "DynamicFpsThrottler"
    }

    private val targetFps = AtomicInteger(baseFps)
    private val frameIntervalNs = AtomicLong(1_000_000_000L / baseFps)

    private val rollingRenderTimesMs = LongArray(10)
    private var rollingIndex = 0
    private var rollingCount = 0
    private var recoveryCounter = 0

    val currentTargetFps: Int
        get() = targetFps.get()

    val currentFrameIntervalNs: Long
        get() = frameIntervalNs.get()

    fun updateThermalLevel(level: ThermalThrottlingLevel) {
        val newTarget = when (level) {
            ThermalThrottlingLevel.NOMINAL -> baseFps
            ThermalThrottlingLevel.MODERATE -> minOf(baseFps, 24)
            ThermalThrottlingLevel.SEVERE -> minOf(baseFps, 20)
            ThermalThrottlingLevel.CRITICAL -> 15
        }
        applyTargetFps(newTarget, "thermal level: $level")
    }

    fun recordRenderTime(renderTimeMs: Long) {
        rollingRenderTimesMs[rollingIndex] = renderTimeMs
        rollingIndex = (rollingIndex + 1) % rollingRenderTimesMs.size
        if (rollingCount < rollingRenderTimesMs.size) rollingCount++

        if (rollingCount >= rollingRenderTimesMs.size) {
            var sum = 0L
            for (i in 0 until rollingCount) sum += rollingRenderTimesMs[i]
            val avg = sum / rollingCount

            if (avg > 32 && targetFps.get() > 15) {
                // High frame render latency detected: step down FPS to prevent queue stalls
                val newFps = when (targetFps.get()) {
                    30 -> 24
                    24 -> 20
                    else -> 15
                }
                recoveryCounter = 0
                rollingCount = 0
                applyTargetFps(newFps, "high render latency: ${avg}ms")
            } else if (avg < 18 && targetFps.get() < baseFps) {
                recoveryCounter++
                if (recoveryCounter >= 30) {
                    val newFps = when (targetFps.get()) {
                        15 -> 20
                        20 -> 24
                        else -> baseFps
                    }
                    recoveryCounter = 0
                    applyTargetFps(newFps, "render latency recovered: ${avg}ms")
                }
            }
        }
    }

    fun forceFps(fps: Int) {
        applyTargetFps(fps, "forceFps")
    }

    private fun applyTargetFps(newFps: Int, reason: String) {
        val oldFps = targetFps.getAndSet(newFps)
        if (oldFps != newFps) {
            val newInterval = 1_000_000_000L / newFps
            frameIntervalNs.set(newInterval)
            Log.w(TAG, "Dynamic FPS throttled: $oldFps -> $newFps FPS ($newInterval ns) [Reason: $reason]")
        }
    }
}
