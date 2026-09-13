package com.fairylive.app.core.common

import android.os.SystemClock

/**
 * Canonical session media clock for Fairy Live.
 * Anchors the broadcast origin t0 using the system monotonic nanosecond clock,
 * ensuring that video and audio presentation timestamps (PTS) share the identical time reference.
 */
object MediaClock {

    var nanoTimeSource: () -> Long = {
        try {
            val realtime = SystemClock.elapsedRealtimeNanos()
            if (realtime != 0L) realtime else System.nanoTime()
        } catch (_: Exception) {
            System.nanoTime()
        }
    }

    @Volatile
    var sessionOriginNs: Long = 0L
        private set

    @Volatile
    var isRunning: Boolean = false
        private set

    @Synchronized
    fun startSession(): Long {
        sessionOriginNs = nanoTimeSource()
        isRunning = true
        return sessionOriginNs
    }

    @Synchronized
    fun stopSession() {
        isRunning = false
        sessionOriginNs = 0L
    }

    /**
     * Converts a raw monotonic nanosecond timestamp into microseconds relative to session origin t0.
     */
    fun toSessionPtsUs(rawMonotonicNs: Long): Long {
        val origin = sessionOriginNs
        if (origin == 0L) return 0L
        val deltaNs = rawMonotonicNs - origin
        return (deltaNs / 1000L).coerceAtLeast(0L)
    }

    /**
     * Returns the elapsed session time in microseconds.
     */
    fun currentSessionTimeUs(): Long {
        val origin = sessionOriginNs
        if (origin == 0L) return 0L
        val now = nanoTimeSource()
        return ((now - origin) / 1000L).coerceAtLeast(0L)
    }
}
