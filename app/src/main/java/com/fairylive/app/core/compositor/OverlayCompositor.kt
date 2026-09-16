package com.fairylive.app.core.compositor

import android.content.Context
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import android.util.Log
import android.view.Surface
import com.fairylive.app.core.camera.model.FacecamConfig
import com.fairylive.app.core.compositor.model.OverlayElement
import com.fairylive.app.core.compositor.render.GlesRenderer
import com.fairylive.app.core.compositor.resource.OverlayResourceManager
import com.fairylive.app.core.scene.BroadcastSceneManager
import com.fairylive.app.core.scene.SceneStateNotifier
import com.fairylive.app.core.scene.model.BroadcastScene
import com.fairylive.app.core.scene.model.SceneTransition
import com.fairylive.app.core.scene.model.SceneTransitionType
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
    private val soundController = com.fairylive.app.core.compositor.animation.AlertSoundController(appContext)

    private var renderThread: HandlerThread? = null
    private var renderHandler: Handler? = null

    private val isRunning = AtomicBoolean(false)
    private val isPaused = AtomicBoolean(false)

    private var enabled: Boolean = true

    @Volatile
    private var available: Boolean = false

    private val captureFramesReceived = AtomicLong(0L)
    private val cameraFramesReceived = AtomicLong(0L)
    private val surfaceTextureUpdates = AtomicLong(0L)
    private val renderIterations = AtomicLong(0L)
    private val duplicateRendersSkipped = AtomicLong(0L)
    private val framesComposited = AtomicLong(0L)

    @Volatile
    var facecamConfig: FacecamConfig = FacecamConfig(enabled = false)
        private set

    private var lastFpsCalcTime: Long = 0L
    private var lastRenderFrameCount: Long = 0L
    private var lastSourceFrameCount: Long = 0L
    private var currentOutputFps: Double = 0.0
    private var currentSourceFps: Double = 0.0

    private var lastAuditLogTime: Long = 0L
    private var auditStartElapsed: Long = 0L

    val overlayScene: OverlayScene get() = scene

    private val sceneManager = BroadcastSceneManager()
    val broadcastSceneManager: BroadcastSceneManager get() = sceneManager

    @Volatile
    private var isTransitionActive: Boolean = false
    @Volatile
    private var transitionStartTimeMs: Long = 0L
    @Volatile
    private var transitionDurationMs: Long = 0L
    @Volatile
    private var outboundScene: BroadcastScene? = null
    @Volatile
    private var targetScene: BroadcastScene? = null

    fun isEnabled(): Boolean = enabled

    val isStarted: Boolean get() = isRunning.get()

    val fpsThrottler = com.fairylive.app.core.optimization.DynamicFpsThrottler(baseFps = 30)
    private var lastCompositedTimeNs = 0L

    fun trimMemory(level: Int) {
        renderHandler?.post {
            resources.trimMemory(level)
        }
    }

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
    fun start(encoderSurface: Surface, width: Int, height: Int, fps: Int = 30): Surface {
        if (isRunning.get()) {
            stop()
        }

        CompositorStateNotifier.updateState(CompositorState.STARTING)

        val thread = HandlerThread("FairyCompositorThread").apply { start() }
        this.renderThread = thread
        val handler = Handler(thread.looper)
        this.renderHandler = handler

        fpsThrottler.forceFps(fps)
        lastCompositedTimeNs = 0L

        captureFramesReceived.set(0L)
        surfaceTextureUpdates.set(0L)
        renderIterations.set(0L)
        duplicateRendersSkipped.set(0L)
        framesComposited.set(0L)
        lastFpsCalcTime = SystemClock.elapsedRealtime()
        auditStartElapsed = lastFpsCalcTime
        lastAuditLogTime = lastFpsCalcTime
        lastRenderFrameCount = 0L
        lastSourceFrameCount = 0L
        currentOutputFps = 0.0
        currentSourceFps = 0.0

        // Blocking: initialize GL on the render thread (the EGL context belongs to it).
        val initResult = handler.postWithResult {
            try {
                val captureSurface = renderer.initialize(
                    encoderSurface,
                    width,
                    height,
                    appContext.assets,
                    handler
                )
                // Auto-drive: capture & camera frame arrivals trigger composition.
                renderer.onFrameAvailable = {
                    onCaptureFrameAvailable()
                }
                renderer.onCameraFrameAvailable = {
                    onCameraFrameAvailable()
                }
                isRunning.set(true)
                available = true
                applySceneImmediate(sceneManager.activeScene)
                CompositorStateNotifier.updateState(CompositorState.RUNNING)
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
     * has arrived. If and ONLY if a brand-new capture buffer timestamp is detected
     * via [GlesRenderer.consumeNewFrame], it renders. If no new capture buffer is
     * available, it safely skips rendering and increments [duplicateRendersSkipped],
     * preventing duplicate frame encoding.
     */
    private var pollRunnable: Runnable? = null

    private fun startRenderLoop() {
        pollRunnable?.let { renderHandler?.removeCallbacks(it) }
        val poll = object : Runnable {
            override fun run() {
                if (isRunning.get() && available) {
                    renderFrame(fromCallback = false)
                    renderHandler?.postDelayed(this, POLL_INTERVAL_MS)
                }
            }
        }
        pollRunnable = poll
        renderHandler?.postDelayed(poll, POLL_INTERVAL_MS)
    }

    /** Queues a frame to be composed + presented. Call from the capture thread or listener. */
    fun onCaptureFrameAvailable() {
        if (!isRunning.get() || !available) return
        captureFramesReceived.incrementAndGet()
        renderHandler?.post {
            renderFrame(fromCallback = true)
        }
    }

    /** Queues a frame to be composed + presented from camera preview. */
    fun onCameraFrameAvailable() {
        if (!isRunning.get() || !available) return
        cameraFramesReceived.incrementAndGet()
        renderHandler?.post {
            renderFrame(fromCallback = true)
        }
    }

    /**
     * Render-loop body — invoked on the render thread by the SurfaceTexture
     * frame-available callback ([fromCallback] = true) or the poll fallback ([fromCallback] = false).
     */
    private fun renderFrame(fromCallback: Boolean = false) {
        if (!isRunning.get() || !available || isPaused.get()) return

        // Do not render until at least one frame has arrived from capture or camera
        if (captureFramesReceived.get() == 0L && cameraFramesReceived.get() == 0L) {
            return
        }

        val nowNs = com.fairylive.app.core.common.MediaClock.nanoTimeSource()
        val intervalNs = fpsThrottler.currentFrameIntervalNs
        val elapsedSinceLastNs = nowNs - lastCompositedTimeNs

        if (fromCallback) {
            if (elapsedSinceLastNs < intervalNs * 0.85) {
                // Low-end dynamic frame throttling: drop frame to maintain steady cadence
                duplicateRendersSkipped.incrementAndGet()
                return
            }
        } else {
            // Periodic poll check: only render if at least one frame interval has elapsed since the last render
            if (elapsedSinceLastNs < intervalNs) {
                duplicateRendersSkipped.incrementAndGet()
                return
            }
        }

        val isCaptureNew = renderer.consumeNewFrame(force = fromCallback)
        val isCameraNew = if (facecamConfig.enabled) renderer.consumeNewCameraFrame(force = fromCallback) else false
        if (isCaptureNew || isCameraNew) {
            surfaceTextureUpdates.incrementAndGet()
        }
        if (isTransitionActive) {
            val now = SystemClock.elapsedRealtime()
            val elapsed = now - transitionStartTimeMs
            val progress = (elapsed.toFloat() / transitionDurationMs.toFloat()).coerceIn(0f, 1f)

            val from = outboundScene
            val to = targetScene
            if (from != null && to != null) {
                SceneStateNotifier.notifyTransitionProgress(progress, from, to)
                for (elem in from.overlayElements) {
                    scene.get("out_" + elem.id)?.let {
                        it.liveOpacity = elem.opacity * (1f - progress)
                    }
                }
                for (elem in to.overlayElements) {
                    scene.get("in_" + elem.id)?.let {
                        it.liveOpacity = elem.opacity * progress
                    }
                }
            }
            if (progress >= 1f) {
                isTransitionActive = false
                targetScene?.let { applySceneImmediate(it) }
            }
        }
        val renderStartNs = System.nanoTime()
        if (enabled) {
            renderer.drawFrame(scene, resources, facecamConfig, presentationTimeNs = nowNs)
        } else {
            // Disabled: forward capture to the encoder unchanged (pass-through)
            renderer.drawFrame(OverlayScene(), resources, null, presentationTimeNs = nowNs)
        }
        val renderEndNs = System.nanoTime()
        fpsThrottler.recordRenderTime((renderEndNs - renderStartNs) / 1_000_000L)
        lastCompositedTimeNs = nowNs

        val count = renderIterations.incrementAndGet()
        framesComposited.set(count)
        updateMetrics()
    }

    fun getCameraSurface(width: Int = 640, height: Int = 480): Surface? {
        val handler = renderHandler ?: return null
        return handler.postWithResult {
            renderer.getCameraSurface(width, height)
        }
    }

    fun updateFacecamConfig(config: FacecamConfig) {
        this.facecamConfig = config.sanitized()
        Log.i(TAG, "Facecam config updated: enabled=${config.enabled}, facing=${config.facing}, x=${config.normalizedX}, y=${config.normalizedY}")
    }

    fun toggleFacecam(enable: Boolean? = null): Boolean {
        val target = enable ?: !facecamConfig.enabled
        facecamConfig = facecamConfig.copy(enabled = target)
        Log.i(TAG, "Facecam toggled: $target")
        return target
    }

    private fun updateMetrics() {
        val now = SystemClock.elapsedRealtime()
        val diff = now - lastFpsCalcTime
        if (diff >= 1000L) {
            val rendersInWindow = renderIterations.get() - lastRenderFrameCount
            val sourceInWindow = surfaceTextureUpdates.get() - lastSourceFrameCount
            currentOutputFps = (rendersInWindow * 1000.0) / diff
            currentSourceFps = (sourceInWindow * 1000.0) / diff
            lastFpsCalcTime = now
            lastRenderFrameCount = renderIterations.get()
            lastSourceFrameCount = surfaceTextureUpdates.get()

            CompositorStateNotifier.updateMetrics(
                CompositorMetrics(
                    captureFrames = captureFramesReceived.get(),
                    surfaceTextureUpdates = surfaceTextureUpdates.get(),
                    renderIterations = renderIterations.get(),
                    duplicateRendersSkipped = duplicateRendersSkipped.get(),
                    framesComposited = renderIterations.get(),
                    outputFps = currentOutputFps,
                    sourceFps = currentSourceFps,
                    overlayCount = scene.size,
                    state = CompositorStateNotifier.currentState,
                    lastFrameTimeMs = now
                )
            )
        }

        if (now - lastAuditLogTime >= 5000L) {
            val elapsedSec = (now - auditStartElapsed) / 1000.0
            if (elapsedSec > 0.0) {
                Log.i(
                    TAG,
                    "COMPOSITOR-AUDIT captureFrames=${captureFramesReceived.get()} " +
                        "textureUpdates=${surfaceTextureUpdates.get()} " +
                        "renders=${renderIterations.get()} " +
                        "dupesSkipped=${duplicateRendersSkipped.get()} " +
                        "elapsed=${"%.1f".format(elapsedSec)}s " +
                        "sourceFps=${"%.1f".format(surfaceTextureUpdates.get() / elapsedSec)} " +
                        "renderFps=${"%.1f".format(renderIterations.get() / elapsedSec)}"
                )
            }
            lastAuditLogTime = now
        }
    }

    @Synchronized
    fun stop() {
        if (!isRunning.get() && renderThread == null) return
        val now = SystemClock.elapsedRealtime()
        val elapsedSec = (now - auditStartElapsed) / 1000.0
        val memory = resources.textureMemoryStats()
        Log.i(
            TAG,
            "COMPOSITOR-FINAL-AUDIT captureFrames=${captureFramesReceived.get()} " +
                "textureUpdates=${surfaceTextureUpdates.get()} " +
                "renders=${renderIterations.get()} " +
                "dupesSkipped=${duplicateRendersSkipped.get()} " +
                "overlays=${scene.size} elapsed=${"%.1f".format(elapsedSec)}s " +
                "sourceFps=${"%.1f".format(if (elapsedSec > 0) surfaceTextureUpdates.get() / elapsedSec else 0.0)} " +
                "renderFps=${"%.1f".format(if (elapsedSec > 0) renderIterations.get() / elapsedSec else 0.0)} " +
                "texActive=${memory.activeBytes} texMax=${memory.maxBytes} texCount=${memory.cachedCount} " +
                "texLargest=${memory.largestBytes} texEvictions=${memory.evictions}"
        )

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
        soundController.release()
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

    /**
     * Seamlessly transitions the compositor to a target [BroadcastScene].
     * If [transition] is [SceneTransitionType.CUT], the change is atomic on the next frame.
     * If [transition] is [SceneTransitionType.CROSSFADE], alpha is smoothly interpolated.
     * RTMP connection, MediaCodec encoders, and audio pipeline remain 100% active and continuous.
     */
    fun switchScene(sceneId: String, transition: SceneTransition = SceneTransition.CUT): Boolean {
        val target = sceneManager.getScene(sceneId) ?: run {
            Log.w(TAG, "Cannot switch to unknown scene: $sceneId")
            return false
        }
        return applyScene(target, transition)
    }

    fun applyScene(target: BroadcastScene, transition: SceneTransition = SceneTransition.CUT): Boolean {
        val current = sceneManager.activeScene
        sceneManager.registerScene(target)
        if (target.id == current.id && !isTransitionActive && target === current) {
            Log.d(TAG, "Scene ${target.id} is already active")
            return true
        }

        sceneManager.switchScene(target.id, transition)

        val handler = renderHandler
        if (handler == null) {
            applySceneImmediate(target)
            return true
        }

        handler.post {
            if (transition.type == SceneTransitionType.CUT || transition.durationMs <= 0L) {
                applySceneImmediate(target)
            } else {
                startSceneTransition(current, target, transition)
            }
        }
        return true
    }

    private fun applySceneImmediate(target: BroadcastScene) {
        clearOverlays()
        for (element in target.overlayElements) {
            addOverlay(element)
        }
        updateFacecamConfig(target.facecamConfig)
        renderer.backgroundMode = target.backgroundMode
        renderer.backgroundColorArgb = target.backgroundColorArgb
        isTransitionActive = false
        outboundScene = null
        targetScene = null
    }

    private fun startSceneTransition(from: BroadcastScene, to: BroadcastScene, transition: SceneTransition) {
        outboundScene = from
        targetScene = to
        transitionStartTimeMs = SystemClock.elapsedRealtime()
        transitionDurationMs = transition.durationMs
        isTransitionActive = true

        scene.clear()
        for (elem in from.overlayElements) {
            val layer = scene.add(elem.copy(id = "out_" + elem.id))
            layer.liveOpacity = elem.opacity
        }
        for (elem in to.overlayElements) {
            val layer = scene.add(elem.copy(id = "in_" + elem.id))
            layer.liveOpacity = 0.0f
        }
        updateFacecamConfig(to.facecamConfig)
        renderer.backgroundMode = to.backgroundMode
        renderer.backgroundColorArgb = to.backgroundColorArgb
        sceneManager.switchScene(to.id, transition)
    }

    /** Triggers a real-time broadcast alert overlay with optional sound and automatic dismissal. */
    fun triggerAlert(
        headline: String = "NEW FOLLOWER!",
        subtext: String = "Alex Live joined the stream!",
        durationMs: Long = com.fairylive.app.core.compositor.limits.CompositorLimits.DEFAULT_ALERT_DURATION_MS,
        soundAsset: String? = null
    ) {
        val config = com.fairylive.app.core.compositor.model.AlertOverlayConfig(
            headline = headline,
            subtext = subtext,
            durationMs = durationMs,
            soundAsset = soundAsset,
            createdAtMs = SystemClock.elapsedRealtime()
        )
        val alertElement = OverlayElement.createAlert(
            id = "stream_alert",
            config = config,
            x = 0.15f,
            y = 0.10f,
            width = 0.70f,
            zIndex = 50
        )
        addOverlay(alertElement)
        if (soundAsset != null) {
            soundController.playAlertSound(soundAsset)
        }
        renderHandler?.postDelayed({
            removeOverlay("stream_alert")
        }, durationMs)
    }

    /** Configures a single watermark overlay scene (M7 baseline for A/B comparison). */
    fun addSampleSingleOverlayScene() {
        addOverlay(
            OverlayElement.createImage(
                id = "watermark",
                resourcePath = DEFAULT_OVERLAY_ASSET,
                x = 0.76f,
                y = 0.88f,
                width = 0.18f,
                opacity = 0.85f,
                zIndex = 10
            )
        )
    }

    /** Configures a realistic broadcast multi-overlay scene (M8). */
    fun addSampleMultiOverlayScene() {
        // 1. Watermark logo (PNG, bottom-right)
        addOverlay(
            OverlayElement.createImage(
                id = "watermark",
                resourcePath = DEFAULT_OVERLAY_ASSET,
                x = 0.76f,
                y = 0.88f,
                width = 0.18f,
                opacity = 0.85f,
                zIndex = 10
            )
        )
        // 2. Stream Title Banner (TEXT, top-left)
        addOverlay(
            OverlayElement.createText(
                id = "stream_title",
                config = com.fairylive.app.core.compositor.model.TextOverlayConfig(
                    text = "FAIRY LIVE STUDIO",
                    fontSizeSp = 18f,
                    textColorRgba = android.graphics.Color.WHITE,
                    backgroundColorRgba = android.graphics.Color.argb(200, 16, 24, 38)
                ),
                x = 0.04f,
                y = 0.04f,
                width = 0.44f,
                zIndex = 15
            )
        )
        // 3. Follower Goal (GOAL, top-right)
        addOverlay(
            OverlayElement.createGoal(
                id = "follower_goal",
                config = com.fairylive.app.core.compositor.model.GoalOverlayConfig(
                    title = "Followers",
                    currentVal = 340,
                    targetVal = 500,
                    unitPrefix = ""
                ),
                x = 0.50f,
                y = 0.04f,
                width = 0.46f,
                zIndex = 15
            )
        )
        // 4. Diagnostic Stats Widget (WIDGET, bottom-left)
        addOverlay(
            OverlayElement.createWidget(
                id = "live_widget",
                config = com.fairylive.app.core.compositor.model.WidgetOverlayConfig(
                    widgetType = "STATS",
                    label = "LIVE STATS",
                    value = "1080p | 44.1k"
                ),
                x = 0.04f,
                y = 0.90f,
                width = 0.38f,
                zIndex = 15
            )
        )
        // 5. Animated Orbit GIF (GIF, bottom-left above stats)
        addOverlay(
            OverlayElement.createGif(
                id = "animated_orbit",
                config = com.fairylive.app.core.compositor.model.GifOverlayConfig(
                    resourcePath = "asset://sample_animation.gif",
                    playbackSpeed = 1.0f,
                    isLooping = true
                ),
                x = 0.04f,
                y = 0.78f,
                width = 0.12f,
                zIndex = 12
            )
        )
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