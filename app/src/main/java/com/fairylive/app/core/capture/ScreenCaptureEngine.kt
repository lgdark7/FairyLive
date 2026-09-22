package com.fairylive.app.core.capture

import android.content.Context
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import android.util.Log
import android.view.Display
import android.view.Surface
import com.fairylive.app.core.capture.model.CaptureMetrics
import com.fairylive.app.core.capture.model.CaptureState

/**
 * High-performance, zero-copy screen capture engine powered by native MediaProjection and VirtualDisplay.
 * Completely independent from video encoders, UI layouts, and transport layers.
 */
class ScreenCaptureEngine {

    private var mediaProjection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var internalImageReader: ImageReader? = null
    private var captureSurface: Surface? = null

    private var handlerThread: HandlerThread? = null
    private var captureHandler: Handler? = null

    private var currentWidth: Int = 0
    private var currentHeight: Int = 0
    private var currentDpi: Int = 0

    private var displayManager: DisplayManager? = null
    private var displayListener: DisplayManager.DisplayListener? = null

    @Volatile
    var isCapturing: Boolean = false
        private set

    @Volatile
    var isPaused: Boolean = false
        private set

    var totalFramesCaptured: Long = 0L
        private set
    private var lastFpsCalcTime: Long = 0L
    private var lastFrameCount: Long = 0L
    private var currentFps: Double = 0.0

    var onResolutionChanged: ((Int, Int) -> Unit)? = null
    var onVisibilityChanged: ((Boolean) -> Unit)? = null
    private var captureSourceMode: com.fairylive.app.core.settings.CaptureSource =
        com.fairylive.app.core.settings.CaptureSource.SINGLE_APP

    private val projectionCallback = object : MediaProjection.Callback() {
        override fun onStop() {
            Log.i("ScreenCaptureEngine", "MediaProjection.Callback.onStop: Capture session ended; cleaning up virtual display while stream continues in BRB mode")
            stop()
        }

        override fun onCapturedContentResize(width: Int, height: Int) {
            Log.i("ScreenCaptureEngine", "[CAPTURE-CHAIN] onCapturedContentResize: reported=${width}x${height}, current=${currentWidth}x${currentHeight}")
            if (width <= 0 || height <= 0) return

            val dm = displayManager
            val display = dm?.getDisplay(Display.DEFAULT_DISPLAY)
            val rotation = display?.rotation ?: Surface.ROTATION_0
            val isLandscape = (rotation == Surface.ROTATION_90 || rotation == Surface.ROTATION_270)

            // If the physical device is in landscape orientation, or capturing a single app (game),
            // ensure dimensions are oriented to landscape (e.g. 2400x1080)
            val adjustedW = if ((isLandscape || captureSourceMode == com.fairylive.app.core.settings.CaptureSource.SINGLE_APP) && width < height) height else width
            val adjustedH = if ((isLandscape || captureSourceMode == com.fairylive.app.core.settings.CaptureSource.SINGLE_APP) && width < height) width else height

            Log.i("ScreenCaptureEngine", "[CAPTURE-CHAIN] onCapturedContentResize: adjusted=${adjustedW}x${adjustedH}, rotation=$rotation")
            if (adjustedW != currentWidth || adjustedH != currentHeight) {
                updateResolution(adjustedW, adjustedH, currentDpi)
            } else {
                onResolutionChanged?.invoke(adjustedW, adjustedH)
            }
        }

        override fun onCapturedContentVisibilityChanged(isVisible: Boolean) {
            Log.i("ScreenCaptureEngine", "[CAPTURE-CHAIN] onCapturedContentVisibilityChanged: isVisible=$isVisible")
            onVisibilityChanged?.invoke(isVisible)

            if (isVisible) {
                // When returning from app switch, re-query display geometry and ensure landscape orientation
                // is immediately restored for gaming sessions without relying on stale matrices.
                val dm = displayManager
                val display = dm?.getDisplay(Display.DEFAULT_DISPLAY)
                val rotation = display?.rotation ?: Surface.ROTATION_0
                val isLandscape = (rotation == Surface.ROTATION_90 || rotation == Surface.ROTATION_270)

                val metrics = android.util.DisplayMetrics()
                @Suppress("DEPRECATION")
                display?.getRealMetrics(metrics)
                val realW = metrics.widthPixels
                val realH = metrics.heightPixels

                val targetW: Int
                val targetH: Int
                if (isLandscape || captureSourceMode == com.fairylive.app.core.settings.CaptureSource.SINGLE_APP) {
                    targetW = maxOf(realW, realH)
                    targetH = minOf(realW, realH)
                } else {
                    targetW = minOf(realW, realH)
                    targetH = maxOf(realW, realH)
                }

                Log.i("ScreenCaptureEngine", "[CAPTURE-CHAIN] Restoring visibility geometry: target=${targetW}x${targetH} (current=${currentWidth}x${currentHeight})")
                if (targetW > 0 && targetH > 0 && (targetW != currentWidth || targetH != currentHeight)) {
                    updateResolution(targetW, targetH, currentDpi)
                } else {
                    onResolutionChanged?.invoke(currentWidth, currentHeight)
                }
            }
        }
    }

    private val virtualDisplayCallback = object : VirtualDisplay.Callback() {
        override fun onPaused() {
            isPaused = true
            CaptureStateNotifier.updateState(CaptureState.PAUSED)
        }

        override fun onResumed() {
            isPaused = false
            CaptureStateNotifier.updateState(CaptureState.CAPTURING)
        }

        override fun onStopped() {
            isCapturing = false
            CaptureStateNotifier.updateState(CaptureState.IDLE)
        }
    }

    /**
     * Starts screen capture into [customSurface] or an internal zero-copy reader.
     */
    @Synchronized
    fun start(
        projection: MediaProjection,
        width: Int,
        height: Int,
        dpi: Int,
        customSurface: Surface? = null,
        captureSource: com.fairylive.app.core.settings.CaptureSource = com.fairylive.app.core.settings.CaptureSource.SINGLE_APP,
        context: Context? = null
    ) {
        if (isCapturing) {
            stop()
        }

        val alignedWidth = (width / 2) * 2
        val alignedHeight = (height / 2) * 2

        CaptureStateNotifier.updateState(CaptureState.STARTING)

        Log.i("FairyCapture", "CaptureMode=$captureSource")
        val targetDesc = if (captureSource == com.fairylive.app.core.settings.CaptureSource.SINGLE_APP) {
            "SelectedTaskWindow (API 34+ RECORD_CONTENT_TASK)"
        } else {
            "EntireDisplay (DefaultDisplay)"
        }
        Log.i("FairyCapture", "CaptureTarget=$targetDesc")
        Log.i("FairyCapture", "ProjectionStarted=true")

        this.mediaProjection = projection
        this.captureSourceMode = captureSource
        this.currentWidth = alignedWidth
        this.currentHeight = alignedHeight
        this.currentDpi = dpi
        this.isPaused = false
        this.totalFramesCaptured = 0L
        this.lastFpsCalcTime = SystemClock.elapsedRealtime()
        this.lastFrameCount = 0L
        this.currentFps = 0.0

        val thread = HandlerThread("FairyCaptureThread").apply { start() }
        this.handlerThread = thread
        val handler = Handler(thread.looper)
        this.captureHandler = handler

        // Register orientation/display listener if context is available
        if (context != null) {
            val dm = context.getSystemService(Context.DISPLAY_SERVICE) as? DisplayManager
            this.displayManager = dm
            if (dm != null) {
                val listener = object : DisplayManager.DisplayListener {
                    override fun onDisplayAdded(displayId: Int) {}
                    override fun onDisplayRemoved(displayId: Int) {}
                    override fun onDisplayChanged(displayId: Int) {
                        if (displayId == Display.DEFAULT_DISPLAY && isCapturing) {
                            handleDisplayChange(dm)
                        }
                    }
                }
                this.displayListener = listener
                try {
                    dm.registerDisplayListener(listener, handler)
                } catch (e: Exception) {
                    Log.w("ScreenCaptureEngine", "Failed to register DisplayListener: ${e.message}")
                }
            }
        }

        // Mandatory callback registration before VirtualDisplay on Android 14+
        projection.registerCallback(projectionCallback, handler)

        val targetSurface: Surface
        if (customSurface != null) {
            targetSurface = customSurface
            this.captureSurface = customSurface
        } else {
            // Zero-copy ImageReader configured with bounded buffer pool (maxImages = 3)
            val reader = ImageReader.newInstance(alignedWidth, alignedHeight, PixelFormat.RGBA_8888, 3)
            reader.setOnImageAvailableListener({ ir ->
                val image = try {
                    ir.acquireLatestImage()
                } catch (_: Exception) {
                    null
                } ?: return@setOnImageAvailableListener

                try {
                    if (!isPaused) {
                        totalFramesCaptured++
                        val now = SystemClock.elapsedRealtime()
                        val diff = now - lastFpsCalcTime
                        if (totalFramesCaptured == 1L || diff >= 1000L) {
                            val framesInWindow = totalFramesCaptured - lastFrameCount
                            currentFps = if (diff > 0) (framesInWindow * 1000.0) / diff else 0.0
                            lastFpsCalcTime = now
                            lastFrameCount = totalFramesCaptured

                            android.util.Log.i("FairyCapture", "Frame #$totalFramesCaptured captured at ${currentWidth}x${currentHeight} (FPS: $currentFps)")

                            CaptureStateNotifier.updateMetrics(
                                CaptureMetrics(
                                    framesCaptured = totalFramesCaptured,
                                    currentFps = currentFps,
                                    width = currentWidth,
                                    height = currentHeight,
                                    dpi = currentDpi,
                                    isPaused = isPaused
                                )
                            )
                        }
                    }
                } finally {
                    image.close() // Return buffer immediately
                }
            }, handler)

            this.internalImageReader = reader
            targetSurface = reader.surface
            this.captureSurface = targetSurface
        }

        // Resilient flag candidates:
        // 1. AUTO_MIRROR (standard full-display mirroring)
        // 2. 0 (default fallback for task-based virtual displays on OEM ROMs)
        // 3. PUBLIC (some vendor HALs like Xiaomi / Samsung require PUBLIC for off-screen rendering)
        var vd: VirtualDisplay? = null
        val flagCandidates = intArrayOf(
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            0,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_PUBLIC
        )

        for (candidateFlag in flagCandidates) {
            try {
                vd = projection.createVirtualDisplay(
                    VIRTUAL_DISPLAY_NAME,
                    alignedWidth,
                    alignedHeight,
                    dpi,
                    candidateFlag,
                    targetSurface,
                    virtualDisplayCallback,
                    handler
                )
                if (vd != null) {
                    Log.i("FairyCapture", "createVirtualDisplay succeeded with flag: $candidateFlag")
                    break
                }
            } catch (e: Exception) {
                Log.w("FairyCapture", "createVirtualDisplay failed with flag $candidateFlag: ${e.message}")
            }
        }

        if (vd == null) {
            // VirtualDisplay creation failed (e.g. projection stopped/task session not applied).
            // Do NOT mark CAPTURING — clean up resources first, then surface ERROR.
            Log.e("FairyCapture", "createVirtualDisplay returned null — projection likely stopped or ContentRecordingSession failed")
            isCapturing = false
            stop()  // releases handler thread, callback, resources (sets IDLE internally)
            CaptureStateNotifier.updateState(CaptureState.ERROR)
            com.fairylive.app.core.common.CreatorErrorNotifier.postError(
                title = "Screen Capture Could Not Start",
                message = "Android could not create the capture display. The capture session may have been interrupted or the system did not grant the recording session.",
                resolution = "Tap GO LIVE again to retry. If this persists, force-stop and reopen the app."
            )
            return
        }
        this.virtualDisplay = vd
        com.fairylive.app.core.lifecycle.StreamLifecycleDiagnostics.virtualDisplayCreated(alignedWidth, alignedHeight, dpi)

        isCapturing = true
        CaptureStateNotifier.updateState(CaptureState.CAPTURING)
        CaptureStateNotifier.updateMetrics(
            CaptureMetrics(
                framesCaptured = 0L,
                currentFps = 0.0,
                width = alignedWidth,
                height = alignedHeight,
                dpi = dpi,
                isPaused = false
            )
        )
    }

    /**
     * Temporarily pauses capture without releasing VirtualDisplay.
     */
    @Synchronized
    fun pause() {
        if (!isCapturing || isPaused) return
        isPaused = true
        virtualDisplay?.surface = null
        CaptureStateNotifier.updateState(CaptureState.PAUSED)
        CaptureStateNotifier.updateMetrics(
            CaptureStateNotifier.currentMetrics.copy(isPaused = true, currentFps = 0.0)
        )
    }

    /**
     * Resumes capture with original surface.
     */
    @Synchronized
    fun resume() {
        if (!isCapturing || !isPaused) return
        isPaused = false
        virtualDisplay?.surface = captureSurface
        CaptureStateNotifier.updateState(CaptureState.CAPTURING)
        CaptureStateNotifier.updateMetrics(
            CaptureStateNotifier.currentMetrics.copy(isPaused = false)
        )
    }

    /**
     * Reconfigures display resolution on orientation or configuration changes.
     */
    @Synchronized
    fun updateResolution(newWidth: Int, newHeight: Int, newDpi: Int) {
        if (!isCapturing) return
        val alignedWidth = (newWidth / 2) * 2
        val alignedHeight = (newHeight / 2) * 2
        if (alignedWidth == currentWidth && alignedHeight == currentHeight && newDpi == currentDpi) {
            return
        }

        this.currentWidth = alignedWidth
        this.currentHeight = alignedHeight
        this.currentDpi = newDpi

        virtualDisplay?.resize(alignedWidth, alignedHeight, newDpi)
        onResolutionChanged?.invoke(alignedWidth, alignedHeight)
        com.fairylive.app.core.lifecycle.StreamLifecycleDiagnostics.virtualDisplayCreated(alignedWidth, alignedHeight, newDpi)

        if (internalImageReader != null) {
            val handler = captureHandler ?: return
            val oldReader = internalImageReader
            val newReader = ImageReader.newInstance(alignedWidth, alignedHeight, PixelFormat.RGBA_8888, 3)
            newReader.setOnImageAvailableListener({ ir ->
                val image = try {
                    ir.acquireLatestImage()
                } catch (_: Exception) {
                    null
                } ?: return@setOnImageAvailableListener

                try {
                    if (!isPaused) {
                        totalFramesCaptured++
                    }
                } finally {
                    image.close()
                }
            }, handler)

            this.internalImageReader = newReader
            this.captureSurface = newReader.surface
            virtualDisplay?.surface = newReader.surface
            oldReader?.close()
        }

        CaptureStateNotifier.updateMetrics(
            CaptureStateNotifier.currentMetrics.copy(
                width = alignedWidth,
                height = alignedHeight,
                dpi = newDpi
            )
        )
    }

    private fun handleDisplayChange(dm: DisplayManager) {
        val display = dm.getDisplay(Display.DEFAULT_DISPLAY) ?: return
        val rotation = display.rotation
        val isLandscape = (rotation == Surface.ROTATION_90 || rotation == Surface.ROTATION_270)

        val metrics = android.util.DisplayMetrics()
        @Suppress("DEPRECATION")
        display.getRealMetrics(metrics)
        val realW = metrics.widthPixels
        val realH = metrics.heightPixels

        // When capturing a single app (e.g. Free Fire MAX), the target task is a fixed landscape game.
        // If the user temporarily switches to a portrait launcher/app, do NOT shrink the VirtualDisplay
        // to portrait if it would letterbox the game when returning!
        val targetW: Int
        val targetH: Int
        if (captureSourceMode == com.fairylive.app.core.settings.CaptureSource.SINGLE_APP) {
            targetW = maxOf(realW, realH)
            targetH = minOf(realW, realH)
        } else {
            targetW = if (isLandscape) maxOf(realW, realH) else minOf(realW, realH)
            targetH = if (isLandscape) minOf(realW, realH) else maxOf(realW, realH)
        }

        Log.i("ScreenCaptureEngine", "[CAPTURE-CHAIN] Display changed: rotation=$rotation (isLandscape=$isLandscape, singleApp=${captureSourceMode == com.fairylive.app.core.settings.CaptureSource.SINGLE_APP}), target=${targetW}x${targetH}")
        if (targetW != currentWidth || targetH != currentHeight) {
            updateResolution(targetW, targetH, currentDpi)
        }
    }

    /**
     * Complete teardown of virtual display, media projection, and background handlers.
     */
    @Synchronized
    fun stop() {
        if (!isCapturing && mediaProjection == null && virtualDisplay == null) return

        CaptureStateNotifier.updateState(CaptureState.STOPPING)
        isCapturing = false
        isPaused = false

        Log.i("FairyCapture", "ProjectionStopped=true")
        com.fairylive.app.core.lifecycle.StreamLifecycleDiagnostics.projectionStopped()

        displayListener?.let { listener ->
            try {
                displayManager?.unregisterDisplayListener(listener)
            } catch (_: Exception) {}
        }
        displayListener = null
        displayManager = null

        try {
            virtualDisplay?.release()
        } catch (_: Exception) {}
        virtualDisplay = null

        try {
            mediaProjection?.unregisterCallback(projectionCallback)
            mediaProjection?.stop()
        } catch (_: Exception) {}
        mediaProjection = null

        try {
            internalImageReader?.close()
        } catch (_: Exception) {}
        internalImageReader = null
        captureSurface = null

        handlerThread?.quitSafely()
        handlerThread = null
        captureHandler = null

        CaptureStateNotifier.updateState(CaptureState.IDLE)
        CaptureStateNotifier.updateMetrics(
            CaptureMetrics(
                framesCaptured = totalFramesCaptured,
                currentFps = 0.0,
                width = currentWidth,
                height = currentHeight,
                dpi = currentDpi,
                isPaused = false
            )
        )
    }

    companion object {
        const val VIRTUAL_DISPLAY_NAME = "FairyLiveCaptureDisplay"
    }
}
