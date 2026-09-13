package com.fairylive.app.core.compositor

import android.content.Context
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import android.util.Log
import android.view.Surface
import com.fairylive.app.core.compositor.model.OverlayElement
import com.fairylive.app.core.compositor.render.GlesRenderer
import com.fairylive.app.core.compositor.resource.OverlayResourceManager
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * Broadcast overlay compositor (M7).
 *
 * Owns the [GlesRenderer] on a dedicated render thread and the [OverlayScene].
 *
 * Lifecycle:
 *   [start]  — creates the render thread + GL context, returns the Surface the
 *              VirtualDisplay should draw into (compositor input).
 *   [stop]   — signals the render loop to halt, releases GL + resources.
 *   [setEnabled] — toggles the whole compositor (pass-through vs composited).
 *
 * The compositor runs entirely in the BROADCAST domain — it never renders over
 * the creator's Android UI. The Dashboard only mutates the [OverlayScene].
 */
class OverlayCompositor(
    private val appContext: Context
) {

    private val renderer = GlesRenderer()
    private val resources = OverlayResourceManager()
    private val scene = OverlayScene()

    private var renderThread: HandlerThread? = null
    private var renderHandler: Handler? = null

    private val isRunning = AtomicBoolean(false)
    private val isPaused = AtomicBoolean(false)

    private var enabled: Boolean = true

    @Volatile
    private var available: Boolean = false

    private val framesComposited = AtomicLong(0L)
    private var lastFpsCalcTime: Long = 0L
    private var lastFrameCount: Long = 0L
    private var currentFps: Double = 0.0

    val overlayScene: OverlayScene get() = scene

    fun isEnabled(): Boolean = enabled

    @Synchronized
    fun setEnabled(value: Boolean) {
        if (enabled == value) return
        enabled = value
        CompositorStateNotifier.updateState(
            if (value) CompositorState.RUNNING else CompositorState.IDLE
        )
        Log.i(TAG, "Compositor ${if (value) "enabled" else "disabled"}")
    }

    @Synchronized
    fun start(encoderSurface: Surface, width: Int, height: Int): Surface {
        if (isRunning.get()) {
            stop()
        }

        CompositorStateNotifier.updateState(CompositorState.STARTING)

        val thread = HandlerThread("FairyCompositorThread").apply { start() }
        this.renderThread = thread
        val handler = Handler(thread.looper)
        this.renderHandler = handler

        framesComposited.set(0L)
        lastFpsCalcTime = SystemClock.elapsedRealtime()
        lastFrameCount = 0L
        currentFps = 0.0

        // Blocking: initialize GL on the render thread (the EGL context belongs to it).
        val initResult = handler.postWithResult {
            try {
                val captureSurface = renderer.initialize(
                    encoderSurface,
                    width,
                    height,
                    appContext.assets
                )
                // Auto-drive: every new capture frame triggers a composite + present.
                // The SurfaceTexture callback fires on an arbitrary thread, so the
                // actual GL work must be marshalled onto this render thread (the EGL
                // context belongs to it — GL calls from another thread no-op).
                renderer.onFrameAvailable = {
                    renderHandler?.post {
                        if (isRunning.get() && available) {
                            renderFrame()
                        }
                    }
                }
                isRunning.set(true)
                available = true
                CompositorStateNotifier.updateState(CompositorState.RUNNING)
                renderer.updateCaptureTexture() // warm up
                captureSurface
            } catch (e: Exception) {
                Log.e(TAG, "Renderer initialize failed: ${e.message}", e)
                CompositorStateNotifier.updateState(CompositorState.ERROR)
                available = false
                null
            }
        }

        if (initResult == null) {
            stop()
            throw IllegalStateException("Compositor could not initialize the GL renderer")
        }

        startRenderLoop()
        return initResult
    }

    /**
     * Event-driven render loop with a poll fallback.
     *
     * Primary: the SurfaceTexture frame-available callback posts to this handler.
     * Fallback: a periodic poll (every 16ms) checks whether a new capture frame
     * has arrived and draws it. This guarantees video flows even when callback
     * delivery is unreliable (e.g. swiftshader emulator / some OEM devices),
     * without busy-waiting — the poll no-ops when no new frame is pending.
     */
    private var pollRunnable: Runnable? = null

    private fun startRenderLoop() {
        pollRunnable?.let { renderHandler?.removeCallbacks(it) }
        val poll = object : Runnable {
            override fun run() {
                if (isRunning.get() && available) {
                    renderFrame()
                    renderHandler?.postDelayed(this, POLL_INTERVAL_MS)
                }
            }
        }
        pollRunnable = poll
        renderHandler?.postDelayed(poll, POLL_INTERVAL_MS)
    }

    /** Queues a frame to be composed + presented. Call from the capture thread. */
    fun onCaptureFrameAvailable() {
        if (!isRunning.get() || !available) return
        renderHandler?.post {
            if (!isRunning.get()) return@post
            renderer.updateCaptureTexture()
            if (isPaused.get()) return@post
            if (!enabled) {
                // Disabled: still must forward the frame to the encoder (pass-through)
                renderer.drawFrame(OverlayScene(), resources)
            } else {
                renderer.drawFrame(scene, resources)
            }
            framesComposited.incrementAndGet()
            updateMetrics()
        }
    }

    /** Render-loop body — invoked by the SurfaceTexture frame-available callback. */
    private fun renderFrame() {
        if (!isRunning.get() || !available || isPaused.get()) return
        if (!renderer.consumeNewFrame()) return // no new capture frame — skip (cheap no-op)
        if (enabled) {
            renderer.drawFrame(scene, resources)
        } else {
            // Disabled: forward capture to the encoder unchanged (pass-through)
            renderer.drawFrame(OverlayScene(), resources)
        }
        framesComposited.incrementAndGet()
        updateMetrics()
    }

    private fun updateMetrics() {
        val now = SystemClock.elapsedRealtime()
        val diff = now - lastFpsCalcTime
        if (diff >= 1000L) {
            val framesInWindow = framesComposited.get() - lastFrameCount
            currentFps = (framesInWindow * 1000.0) / diff
            lastFpsCalcTime = now
            lastFrameCount = framesComposited.get()

            CompositorStateNotifier.updateMetrics(
                CompositorMetrics(
                    framesComposited = framesComposited.get(),
                    outputFps = currentFps,
                    overlayCount = scene.size,
                    state = CompositorStateNotifier.currentState,
                    lastFrameTimeMs = now
                )
            )
        }
    }

    @Synchronized
    fun stop() {
        if (!isRunning.get() && renderThread == null) return
        CompositorStateNotifier.updateState(CompositorState.STOPPING)
        isRunning.set(false)
        available = false

        pollRunnable?.let { renderHandler?.removeCallbacks(it) }
        pollRunnable = null

        renderHandler?.post {
            renderer.release()
        }
        renderThread?.quitSafely()
        renderHandler = null
        renderThread = null

        resources.release()
        scene.clear()

        isPaused.set(false)
        CompositorStateNotifier.updateState(CompositorState.IDLE)
        CompositorStateNotifier.updateMetrics(CompositorMetrics())
        Log.i(TAG, "Compositor stopped")
    }

    @Synchronized
    fun pause() {
        if (!isRunning.get()) return
        isPaused.set(true)
        CompositorStateNotifier.updateState(CompositorState.PAUSED)
    }

    @Synchronized
    fun resume() {
        if (!isRunning.get()) return
        isPaused.set(false)
        CompositorStateNotifier.updateState(CompositorState.RUNNING)
    }

    // ---- scene mutation helpers (UI-facing; thread-safe) ----

    fun addOverlay(element: OverlayElement) {
        scene.add(element)
    }

    fun removeOverlay(id: String) {
        scene.remove(id)
    }

    fun clearOverlays() {
        scene.clear()
    }

    fun updateOverlay(id: String, newElement: OverlayElement) {
        scene.updateElement(id, newElement)
    }

    /** Loads + caches resources (decode is not in the per-frame loop). */
    fun preloadResource(path: String): Boolean {
        return resources.loadBitmap(path, appContext.assets) != null
    }

    fun cachedResourceCount(): Int = resources.cachedCount()

    fun setOverlayOpacity(id: String, opacity: Float) {
        scene.get(id)?.let { it.liveOpacity = opacity.coerceIn(0f, 1f) }
    }

    fun setOverlayVisible(id: String, visible: Boolean) {
        scene.get(id)?.let { it.liveVisible = visible }
    }

    fun setOverlayScale(id: String, scale: Float) {
        scene.get(id)?.let { it.liveScale = scale.coerceIn(0.1f, 5f) }
    }

    companion object {
        private const val TAG = "OverlayCompositor"

        /** Poll interval for the render-loop fallback (16ms ≈ 60fps cadence). */
        private const val POLL_INTERVAL_MS = 16L

        /** Default test overlay (watermark) — asset path, loaded on first frame. */
        const val DEFAULT_OVERLAY_ASSET = "asset://overlay_logo.png"
    }
}

/** Small helper: run a block on the thread's handler and wait for the result. */
private fun Handler.postWithResult(block: () -> Surface?): Surface? {
    val result = java.util.concurrent.atomic.AtomicReference<Surface?>()
    val latch = java.util.concurrent.CountDownLatch(1)
    post {
        try {
            result.set(block())
        } finally {
            latch.countDown()
        }
    }
    try {
        latch.await(3000L, java.util.concurrent.TimeUnit.MILLISECONDS)
    } catch (_: InterruptedException) {
        Thread.currentThread().interrupt()
    }
    return result.get()
}