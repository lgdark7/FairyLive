package com.fairylive.app.core.compositor.resource

import android.content.res.AssetManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.opengl.GLES20
import android.util.Log
import com.fairylive.app.core.compositor.limits.CompositorLimits
import java.io.File
import java.io.InputStream
import java.util.concurrent.atomic.AtomicInteger

/**
 * Bounded, cached overlay resource and texture manager (M8).
 *
 * Requirements met:
 * 1. PNG / Image decode happens once per resource path (never in per-frame loop).
 * 2. Text / Goal / Widget / QR rasterization happens once per configuration state.
 * 3. Enforces strict memory caps ([CompositorLimits.MAX_CACHED_TEXTURES]).
 * 4. Explicit [release] frees both CPU Bitmaps and GPU texture handles.
 */
class OverlayResourceManager(
    private val maxCachedBitmaps: Int = CompositorLimits.MAX_CACHED_TEXTURES,
    private val maxBitmapBytes: Long = CompositorLimits.MAX_TEXTURE_MEMORY_BYTES
) {

    /** Runtime memory accounting emitted at session end and used by instrumentation. */
    data class TextureMemoryStats(
        val activeBytes: Long,
        val maxBytes: Long,
        val cachedCount: Int,
        val largestBytes: Long,
        val evictions: Long
    )

    private val bitmapCache = object : LinkedHashMap<String, CachedBitmap>(64, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, CachedBitmap>?): Boolean {
            val shouldEvict = size > maxCachedBitmaps
            if (shouldEvict && eldest != null) {
                totalBytes.addAndGet(-eldest.value.bytes.toInt())
                evictionCount++
            }
            return shouldEvict
        }
    }
    private val cacheLock = Any()
    private val totalBytes = AtomicInteger(0)
    private var largestBitmapBytes: Long = 0L
    private var evictionCount: Long = 0L

    /** GPU texture handles allocated by the renderer, tracked for clean disposal. */
    private val allocatedTextures = mutableSetOf<Int>()

    /**
     * Loads and decodes a PNG resource (or returns cached).
     */
    fun loadBitmap(resourcePath: String, assets: AssetManager): Bitmap? {
        synchronized(cacheLock) {
            bitmapCache[resourcePath]?.let { return it.bitmap }
        }

        val bitmap = decode(resourcePath, assets) ?: run {
            Log.w(TAG, "Failed to decode overlay resource: $resourcePath")
            return null
        }

        return putBitmap(resourcePath, bitmap)
    }

    /**
     * Gets or generates a cached Bitmap using a custom generator (used for text, goals, QR, etc.).
     */
    fun getOrPutBitmap(cacheKey: String, generator: () -> Bitmap?): Bitmap? {
        synchronized(cacheLock) {
            bitmapCache[cacheKey]?.let { return it.bitmap }
        }

        val generated = generator() ?: return null
        return putBitmap(cacheKey, generated)
    }

    private fun putBitmap(key: String, bitmap: Bitmap): Bitmap {
        val size = bitmap.byteCount.toLong()
        synchronized(cacheLock) {
            while (totalBytes.get() + size > maxBitmapBytes && bitmapCache.isNotEmpty()) {
                val oldestKey = bitmapCache.entries.first().key
                val removed = bitmapCache.remove(oldestKey)
                if (removed != null) {
                    totalBytes.addAndGet(-removed.bytes.toInt())
                    evictionCount++
                }
            }
            if (totalBytes.get() + size <= maxBitmapBytes) {
                bitmapCache[key] = CachedBitmap(bitmap, size)
                totalBytes.addAndGet(size.toInt())
                largestBitmapBytes = maxOf(largestBitmapBytes, size)
            } else {
                Log.w(TAG, "Overlay bitmap too large to cache (${size / 1024} KB) — returning transient")
            }
        }
        return bitmap
    }

    private fun decode(resourcePath: String, assets: AssetManager): Bitmap? {
        return try {
            val normalized = resourcePath.removeSuffix("/")
            val boundsOpts = BitmapFactory.Options().apply {
                inJustDecodeBounds = true
            }
            when {
                normalized.startsWith("asset://") || normalized.startsWith("assets://") -> {
                    val assetPath = normalized.replace(Regex("^assets?://"), "")
                    assets.open(assetPath).use { stream: InputStream ->
                        BitmapFactory.decodeStream(stream, null, boundsOpts)
                    }
                }
                resourcePath.startsWith("file://") -> {
                    BitmapFactory.decodeFile(resourcePath.removePrefix("file://"), boundsOpts)
                }
                else -> {
                    val f = File(resourcePath)
                    if (f.exists()) {
                        BitmapFactory.decodeFile(resourcePath, boundsOpts)
                    } else {
                        assets.open(resourcePath.trimStart('/')).use {
                            BitmapFactory.decodeStream(it, null, boundsOpts)
                        }
                    }
                }
            }

            var sampleSize = 1
            val maxDim = 1920
            while ((boundsOpts.outWidth / sampleSize) > maxDim || (boundsOpts.outHeight / sampleSize) > maxDim) {
                sampleSize *= 2
            }

            val opts = BitmapFactory.Options().apply {
                inPreferredConfig = Bitmap.Config.ARGB_8888
                inSampleSize = sampleSize
            }
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
                    if (f.exists()) {
                        BitmapFactory.decodeFile(resourcePath, opts)
                    } else {
                        assets.open(resourcePath.trimStart('/')).use {
                            BitmapFactory.decodeStream(it, null, opts)
                        }
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Decode error for $resourcePath: ${e.message}")
            null
        }
    }

    fun trackTexture(textureHandle: Int) {
        if (textureHandle != 0) {
            synchronized(cacheLock) {
                allocatedTextures.add(textureHandle)
            }
        }
    }

    fun isCached(key: String): Boolean = synchronized(cacheLock) { bitmapCache.containsKey(key) }

    fun cachedCount(): Int = synchronized(cacheLock) { bitmapCache.size }

    fun totalBitmapBytes(): Long = totalBytes.get().toLong()

    fun textureMemoryStats(): TextureMemoryStats = synchronized(cacheLock) {
        TextureMemoryStats(
            activeBytes = totalBytes.get().toLong(),
            maxBytes = maxBitmapBytes,
            cachedCount = bitmapCache.size,
            largestBytes = largestBitmapBytes,
            evictions = evictionCount
        )
    }

    /**
     * Memory pressure optimization (M12).
     * Evicts cached bitmaps based on system ComponentCallbacks2 trim level.
     */
    fun trimMemory(level: Int) {
        synchronized(cacheLock) {
            val initialCount = bitmapCache.size
            val initialBytes = totalBytes.get()
            if (level >= android.content.ComponentCallbacks2.TRIM_MEMORY_RUNNING_CRITICAL) {
                bitmapCache.clear()
                totalBytes.set(0)
                Log.w(TAG, "Aggressive memory trim (level $level): cleared all $initialCount bitmaps ($initialBytes bytes)")
            } else if (level >= android.content.ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW) {
                val iterator = bitmapCache.entries.iterator()
                var removed = 0
                val targetRemove = bitmapCache.size / 2
                while (iterator.hasNext() && removed < targetRemove) {
                    val entry = iterator.next()
                    totalBytes.addAndGet(-entry.value.bytes.toInt())
                    iterator.remove()
                    removed++
                }
                Log.w(TAG, "Moderate memory trim (level $level): pruned $removed / $initialCount bitmaps")
            }
            Unit
        }
    }

    /** Releases all cached Bitmaps and associated GPU textures. Must run on GL thread for GL deletes. */
    fun release() {
        synchronized(cacheLock) {
            bitmapCache.clear()
            totalBytes.set(0)

            if (allocatedTextures.isNotEmpty()) {
                val textures = allocatedTextures.toIntArray()
                GLES20.glDeleteTextures(textures.size, textures, 0)
                allocatedTextures.clear()
            }
        }
        Log.i(TAG, "OverlayResourceManager released")
    }

    private data class CachedBitmap(val bitmap: Bitmap, val bytes: Long)

    companion object {
        private const val TAG = "OverlayResourceMgr"
    }
}
