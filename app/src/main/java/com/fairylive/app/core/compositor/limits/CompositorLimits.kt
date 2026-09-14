package com.fairylive.app.core.compositor.limits

/**
 * Explicit documented performance constraints for FAIRY LIVE.
 *
 * Designed to guarantee rock-solid stability on low-end Android devices
 * (2GB–4GB RAM, entry-level SoCs, constrained GPU VRAM).
 */
object CompositorLimits {

    /**
     * Maximum number of active overlays allowed in a scene simultaneously.
     * Prevents excessive GLES draw calls and matrix computations per frame.
     */
    const val MAX_ACTIVE_OVERLAYS = 10

    /**
     * Maximum GPU texture memory budget in bytes (24 MB).
     * Textures are admitted and LRU-evicted based on estimated RGBA memory footprint
     * (width * height * 4 bytes).
     */
    const val MAX_TEXTURE_MEMORY_BYTES = 24L * 1024 * 1024

    /**
     * Maximum number of textures retained in the GPU texture cache.
     * Secondary count limit alongside [MAX_TEXTURE_MEMORY_BYTES].
     */
    const val MAX_CACHED_TEXTURES = 32

    /**
     * Maximum number of concurrent animated GIFs allowed in a scene.
     * Each active GIF allocates exactly ONE reusable Bitmap buffer (width * height * 4 bytes)
     * in RAM, decoded on-demand via native [android.graphics.Movie].
     */
    const val MAX_ACTIVE_GIFS = 2

    /**
     * Maximum texture width or height in pixels.
     * Any asset exceeding this is downscaled on load to prevent OOM.
     */
    const val MAX_TEXTURE_DIMENSION = 1024

    /**
     * Maximum number of pending/active alerts in the alert queue.
     * Additional alerts are dropped or queued with strict eviction.
     */
    const val MAX_CONCURRENT_ALERTS = 3

    /**
     * Default duration for animated alerts in milliseconds.
     */
    const val DEFAULT_ALERT_DURATION_MS = 4000L
}
