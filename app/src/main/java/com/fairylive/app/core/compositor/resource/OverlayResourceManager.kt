package com.fairylive.app.core.compositor.resource

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Log
import com.fairylive.app.core.compositor.model.OverlayType
import java.io.File
import java.io.InputStream
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/**
 * Bounded, cached overlay resource manager.
 *
 * - PNG decode happens exactly once per resource path (never per frame).
 * - Bitmaps are cached in a bounded LRU-ish cache (memory cap) — repeated frames
 *   reuse the decoded bitmap.
 * - Resource lifecycle: explicit [release] clears everything (called on
 *   compositor stop); each texture is uploaded once by the renderer.
 *
 * Thread-safety: decode is heavyweight → runs on caller thread; the cache read is
 * lock-free. The renderer reads bitmaps from the cache (never decodes in the
 * per-frame loop).
 */
class OverlayResourceManager(
    private val maxCachedBitmaps: Int = 8,
    private val maxBitmapBytes: Long = 24L * 1024 * 1024 // 24 MB cap
) {

    private val cache = object : LinkedHashMap<String, CachedBitmap>(64, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, CachedBitmap>?): Boolean {
            return size > maxCachedBitmaps
        }
    }
    private val cacheLock = Any()
    private val totalBytes = AtomicInteger(0)

    /**
     * Loads and decodes a PNG resource (or returns cached). Supports:
     * - asset path ("asset://logo.png")
     * - file path ("file:///data/.../logo.png" or raw path)
     * In M7 we primarily test with assets; file support is for creator-added images.
     */
    fun loadBitmap(resourcePath: String, assets: android.content.res.AssetManager): Bitmap? {
        synchronized(cacheLock) {
            cache[resourcePath]?.let { return it.bitmap }
        }

        val bitmap = decode(resourcePath, assets) ?: run {
            Log.w(TAG, "Failed to decode overlay resource: $resourcePath")
            return null
        }

        // Enforce memory cap: if adding exceeds the budget, evict oldest.
        val size = bitmap.byteCount.toLong()
        synchronized(cacheLock) {
            while (totalBytes.get() + size > maxBitmapBytes && cache.isNotEmpty()) {
                val oldest = cache.entries.first().key
                totalBytes.addAndGet(-(cache.remove(oldest)?.bytes ?: 0L).toInt())
            }
            if (totalBytes.get() + size <= maxBitmapBytes) {
                cache[resourcePath] = CachedBitmap(bitmap, size)
                totalBytes.addAndGet(size.toInt())
            } else {
                Log.w(TAG, "Overlay bitmap too large to cache (${size / 1024} KB) — returning transient")
            }
        }
        return bitmap
    }

    private fun decode(resourcePath: String, assets: android.content.res.AssetManager): Bitmap? {
        return try {
            val opts = BitmapFactory.Options().apply {
                inPreferredConfig = Bitmap.Config.ARGB_8888
            }
            val normalized = resourcePath.removeSuffix("/") // strip trailing slash
            when {
                normalized.startsWith("asset://") || normalized.startsWith("assets://") -> {
                    val assetPath = normalized.replace(Regex("^assets?://"), "")
                    assets.open(assetPath).use { stream: InputStream ->
                        BitmapFactory.decodeStream(stream, null, opts)
                    }
                }
                resourcePath.startsWith("file://") -> {
                    BitmapFactory.decodeFile(resourcePath.removePrefix("file://"), opts)
                }
                else -> {
                    val f = File(resourcePath)
                    if (f.exists()) BitmapFactory.decodeFile(resourcePath, opts) else null
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Decode error for $resourcePath: ${e.message}")
            null
        }
    }

    /** Returns true if a cached bitmap exists (for renderer texture upload). */
    fun isCached(resourcePath: String): Boolean = synchronized(cacheLock) { cache.containsKey(resourcePath) }

    fun cachedCount(): Int = synchronized(cacheLock) { cache.size }

    fun totalBitmapBytes(): Long = totalBytes.get().toLong()

    fun release() {
        synchronized(cacheLock) {
            cache.clear()
            totalBytes.set(0)
        }
    }

    private data class CachedBitmap(val bitmap: Bitmap, val bytes: Long)

    companion object {
        private const val TAG = "OverlayResourceMgr"
    }
}