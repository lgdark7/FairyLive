package com.fairylive.app.core.optimization

import android.content.ComponentCallbacks2
import android.content.res.Configuration
import android.util.Log
import java.util.concurrent.CopyOnWriteArrayList

class MemoryPressureGuard : ComponentCallbacks2 {

    companion object {
        private const val TAG = "MemoryPressureGuard"
    }

    interface MemoryTrimListener {
        fun onMemoryTrim(level: Int)
    }

    private val listeners = CopyOnWriteArrayList<MemoryTrimListener>()

    @Volatile
    var lastTrimLevel: Int = 0
        private set

    @Volatile
    var trimEventCount: Int = 0
        private set

    fun addListener(listener: MemoryTrimListener) {
        listeners.add(listener)
    }

    fun removeListener(listener: MemoryTrimListener) {
        listeners.remove(listener)
    }

    override fun onTrimMemory(level: Int) {
        lastTrimLevel = level
        trimEventCount++
        Log.i(TAG, "onTrimMemory received: level=$level (total events: $trimEventCount)")

        when (level) {
            ComponentCallbacks2.TRIM_MEMORY_RUNNING_MODERATE -> {
                Log.d(TAG, "Moderate memory pressure: clearing non-essential scratch buffers")
            }
            ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW -> {
                Log.w(TAG, "Low memory warning: pruning inactive caches and image pools")
            }
            ComponentCallbacks2.TRIM_MEMORY_RUNNING_CRITICAL,
            ComponentCallbacks2.TRIM_MEMORY_COMPLETE -> {
                Log.e(TAG, "CRITICAL memory pressure: aggressively purging overlay bitmaps and hinting GC")
                System.gc()
            }
        }

        for (listener in listeners) {
            try {
                listener.onMemoryTrim(level)
            } catch (e: Exception) {
                Log.e(TAG, "Error in MemoryTrimListener: ${e.message}")
            }
        }
    }

    override fun onConfigurationChanged(newConfig: Configuration) {}

    override fun onLowMemory() {
        onTrimMemory(ComponentCallbacks2.TRIM_MEMORY_RUNNING_CRITICAL)
    }

    /**
     * For testing / deterministic validation: inject a trim level directly.
     */
    fun trimMemoryForTesting(level: Int) {
        onTrimMemory(level)
    }
}
