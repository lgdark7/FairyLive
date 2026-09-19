package com.fairylive.app.core.capture

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

    @Volatile
    var isCapturing: Boolean = false
        private set

    @Volatile
    var isPaused: Boolean = false
        private set

    private var totalFramesCaptured: Long = 0L
    private var lastFpsCalcTime: Long = 0L
    private var lastFrameCount: Long = 0L
    private var currentFps: Double = 0.0

    private val projectionCallback = object : MediaProjection.Callback() {
        override fun onStop() {
            stop()
        }

        override fun onCapturedContentResize(width: Int, height: Int) {
            if (width > 0 && height > 0 && (width != currentWidth || height != currentHeight)) {
                updateResolution(width, height, currentDpi)
            }
        }

        override fun onCapturedContentVisibilityChanged(isVisible: Boolean) {
            // Informative callback on Android 14+
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
        captureSource: com.fairylive.app.core.settings.CaptureSource = com.fairylive.app.core.settings.CaptureSource.SINGLE_APP
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

        val flags = DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR

        val vd = try {
            projection.createVirtualDisplay(
                VIRTUAL_DISPLAY_NAME,
                alignedWidth,
                alignedHeight,
                dpi,
                flags,
                targetSurface,
                virtualDisplayCallback,
                handler
            )
        } catch (se: SecurityException) {
            Log.e("FairyCapture", "createVirtualDisplay SecurityException: ${se.message}")
            null
        } catch (ie: IllegalStateException) {
            Log.e("FairyCapture", "createVirtualDisplay IllegalStateException: ${ie.message}")
            null
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
