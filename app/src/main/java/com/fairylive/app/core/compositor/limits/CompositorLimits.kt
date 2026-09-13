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
     * Maximum number of textures retained in the GPU texture LRU cache.
     * At 512x512 RGBA (~1 MB each), 32 textures consume ~32 MB VRAM maximum,
     * safely below the 120 MB total app memory budget.
     */
    const val MAX_CACHED_TEXTURES = 32

    /**
     * Maximum number of decoded animated frames buffered concurrently in RAM.
     * Prevents loading complete multi-megabyte GIFs into memory.
     */
    const val MAX_ANIMATED_FRAMES_BUFFERED = 4

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
