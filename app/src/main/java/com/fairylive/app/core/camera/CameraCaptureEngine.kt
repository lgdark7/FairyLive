package com.fairylive.app.core.camera

import android.annotation.SuppressLint
import android.content.Context
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CaptureRequest
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import android.util.Log
import android.view.Surface
import com.fairylive.app.core.camera.model.CameraDeviceInfo
import com.fairylive.app.core.camera.model.CameraFacing
import com.fairylive.app.core.camera.model.CameraSize
import com.fairylive.app.core.permission.PermissionManager
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * Pure-native Camera2 capture engine for the broadcast Facecam pipeline.
 *
 * Directs camera preview output into an OpenGL ES SurfaceTexture (OES texture)
 * for zero-copy compositing directly onto the live stream.
 *
 * Lifecycle and switching:
 * - Thread-safe state machine: IDLE -> OPENING -> STREAMING -> SWITCHING -> STOPPING -> IDLE
 * - Sequential front <-> back switching with clean session/device teardown
 * - Zero allocations on the active capture path
 */
class CameraCaptureEngine(
    private val context: Context
) {

    private val detector = CameraCapabilityDetector(context)
    private val cameraManager by lazy {
        context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
    }

    private var cameraThread: HandlerThread? = null
    private var cameraHandler: Handler? = null

    private var cameraDevice: CameraDevice? = null
    private var captureSession: CameraCaptureSession? = null
    private var targetSurface: Surface? = null

    @Volatile
    var currentFacing: CameraFacing = CameraFacing.FRONT
        private set

    @Volatile
    var activeCameraId: String? = null
        private set

    @Volatile
    var activePreviewSize: CameraSize = CameraSize(640, 480)
        private set

    @Volatile
    var sensorOrientation: Int = 0
        private set

    private val isSwitching = AtomicBoolean(false)
    private val frameCounter = AtomicLong(0L)
    private val errorCounter = AtomicLong(0L)

    private var lastFpsCalcTime = 0L
    private var lastFrameCount = 0L
    private var currentFps = 0.0

    init {
        ensureThread()
    }

    private fun ensureThread() {
        if (cameraThread == null || !cameraThread!!.isAlive) {
            val thread = HandlerThread("FairyCameraThread").apply { start() }
            cameraThread = thread
            cameraHandler = Handler(thread.looper)
        }
    }

    /**
     * Starts camera preview into the specified output [Surface] (typically from GlesRenderer).
     */
    fun start(
        surface: Surface,
        preferredFacing: CameraFacing = CameraFacing.FRONT,
        targetWidth: Int = 640,
        targetHeight: Int = 480
    ) {
        if (!PermissionManager.hasCameraPermission(context)) {
            Log.e(TAG, "Cannot start camera: CAMERA permission not granted")
            CameraStateNotifier.updateState(CameraState.ERROR)
            return
        }

        ensureThread()
        cameraHandler?.post {
            if (CameraStateNotifier.currentState == CameraState.STREAMING ||
                CameraStateNotifier.currentState == CameraState.OPENING
            ) {
                Log.w(TAG, "Camera already running or opening; stopping previous session first")
                internalStop()
            }

            this.targetSurface = surface
            this.currentFacing = preferredFacing

            val camera = when (preferredFacing) {
                CameraFacing.FRONT -> detector.getFrontCamera() ?: detector.getBackCamera()
                CameraFacing.BACK -> detector.getBackCamera() ?: detector.getFrontCamera()
                else -> detector.discoverCameras().firstOrNull()
            }

            if (camera == null) {
                Log.e(TAG, "No suitable camera found on device")
                CameraStateNotifier.updateState(CameraState.ERROR)
                return@post
            }

            activeCameraId = camera.cameraId
            currentFacing = camera.facing
            sensorOrientation = camera.sensorOrientation
            activePreviewSize = detector.getOptimalSize(camera, targetWidth, targetHeight)

            frameCounter.set(0L)
            lastFpsCalcTime = SystemClock.elapsedRealtime()
            lastFrameCount = 0L
            currentFps = 0.0

            openCameraDevice(camera.cameraId)
        }
    }

    @SuppressLint("MissingPermission")
    private fun openCameraDevice(cameraId: String) {
        CameraStateNotifier.updateState(CameraState.OPENING)
        try {
            cameraManager.openCamera(cameraId, deviceStateCallback, cameraHandler)
            Log.i(TAG, "Opening camera ID: $cameraId (${currentFacing}) at ${activePreviewSize.width}x${activePreviewSize.height}")
        } catch (e: Exception) {
            Log.e(TAG, "Exception opening camera $cameraId: ${e.message}", e)
            errorCounter.incrementAndGet()
            CameraStateNotifier.updateState(CameraState.ERROR)
        }
    }

    private val deviceStateCallback = object : CameraDevice.StateCallback() {
        override fun onOpened(camera: CameraDevice) {
            Log.i(TAG, "Camera device opened: ${camera.id}")
            cameraDevice = camera
            createCaptureSession(camera)
        }

        override fun onDisconnected(camera: CameraDevice) {
            Log.w(TAG, "Camera device disconnected: ${camera.id}")
            camera.close()
            cameraDevice = null
            CameraStateNotifier.updateState(CameraState.IDLE)
        }

        override fun onError(camera: CameraDevice, error: Int) {
            val errorMsg = when (error) {
                ERROR_CAMERA_IN_USE -> "Camera in use"
                ERROR_MAX_CAMERAS_IN_USE -> "Max cameras in use"
                ERROR_CAMERA_DISABLED -> "Camera disabled by policy"
                ERROR_CAMERA_DEVICE -> "Fatal camera device error"
                ERROR_CAMERA_SERVICE -> "Camera service error"
                else -> "Unknown error ($error)"
            }
            Log.e(TAG, "Camera error on ${camera.id}: $errorMsg")
            errorCounter.incrementAndGet()
            camera.close()
            cameraDevice = null
            captureSession = null
            CameraStateNotifier.updateState(CameraState.ERROR)
        }
    }

    private fun createCaptureSession(camera: CameraDevice) {
        val surface = targetSurface ?: return
        try {
            @Suppress("DEPRECATION")
            camera.createCaptureSession(
                listOf(surface),
                object : CameraCaptureSession.StateCallback() {
                    override fun onConfigured(session: CameraCaptureSession) {
                        if (cameraDevice == null) {
                            Log.w(TAG, "Camera was closed before session configured")
                            return
                        }
                        captureSession = session
                        try {
                            val requestBuilder = camera.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW).apply {
                                addTarget(surface)
                                set(CaptureRequest.CONTROL_MODE, CaptureRequest.CONTROL_MODE_AUTO)
                                set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_VIDEO)
                                set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON)
                            }
                            session.setRepeatingRequest(
                                requestBuilder.build(),
                                captureCallback,
                                cameraHandler
                            )
                            CameraStateNotifier.updateState(CameraState.STREAMING)
                            isSwitching.set(false)
                            Log.i(TAG, "Camera preview session successfully started for ID ${camera.id}")
                        } catch (e: Exception) {
                            Log.e(TAG, "Failed to start repeating preview request: ${e.message}", e)
                            CameraStateNotifier.updateState(CameraState.ERROR)
                        }
                    }

                    override fun onConfigureFailed(session: CameraCaptureSession) {
                        Log.e(TAG, "Camera capture session configuration failed")
                        errorCounter.incrementAndGet()
                        CameraStateNotifier.updateState(CameraState.ERROR)
                        isSwitching.set(false)
                    }
                },
                cameraHandler
            )
        } catch (e: Exception) {
            Log.e(TAG, "Exception creating capture session: ${e.message}", e)
            CameraStateNotifier.updateState(CameraState.ERROR)
            isSwitching.set(false)
        }
    }

    private val captureCallback = object : CameraCaptureSession.CaptureCallback() {
        override fun onCaptureCompleted(
            session: CameraCaptureSession,
            request: CaptureRequest,
            result: android.hardware.camera2.TotalCaptureResult
        ) {
            val total = frameCounter.incrementAndGet()
            val now = SystemClock.elapsedRealtime()
            val elapsed = now - lastFpsCalcTime
            if (elapsed >= 1000L) {
                val framesInWindow = total - lastFrameCount
                currentFps = (framesInWindow * 1000.0) / elapsed
                lastFpsCalcTime = now
                lastFrameCount = total

                CameraStateNotifier.updateMetrics(
                    CameraMetrics(
                        framesCaptured = total,
                        currentFps = currentFps,
                        activeCameraId = activeCameraId,
                        facing = currentFacing,
                        width = activePreviewSize.width,
                        height = activePreviewSize.height,
                        errorCount = errorCounter.get()
                    )
                )
            }
        }
    }

    /**
     * Safely switches between front and rear camera with sequential teardown.
     */
    fun switchCamera() {
        if (!isSwitching.compareAndSet(false, true)) {
            Log.w(TAG, "Camera switch already in progress, ignoring duplicate request")
            return
        }

        ensureThread()
        cameraHandler?.post {
            val nextFacing = if (currentFacing == CameraFacing.FRONT) CameraFacing.BACK else CameraFacing.FRONT
            Log.i(TAG, "Switching camera from $currentFacing to $nextFacing...")
            CameraStateNotifier.updateState(CameraState.SWITCHING)

            internalStop()

            val nextCamera = when (nextFacing) {
                CameraFacing.FRONT -> detector.getFrontCamera() ?: detector.getBackCamera()
                CameraFacing.BACK -> detector.getBackCamera() ?: detector.getFrontCamera()
                else -> detector.discoverCameras().firstOrNull()
            }

            if (nextCamera == null) {
                Log.e(TAG, "Target camera facing $nextFacing not available")
                isSwitching.set(false)
                CameraStateNotifier.updateState(CameraState.ERROR)
                return@post
            }

            activeCameraId = nextCamera.cameraId
            currentFacing = nextCamera.facing
            sensorOrientation = nextCamera.sensorOrientation
            activePreviewSize = detector.getOptimalSize(nextCamera, 640, 480)

            openCameraDevice(nextCamera.cameraId)
        }
    }

    /**
     * Halts camera preview and releases hardware resources.
     */
    fun stop() {
        ensureThread()
        cameraHandler?.post {
            internalStop()
            CameraStateNotifier.updateState(CameraState.IDLE)
        }
    }

    private fun internalStop() {
        try {
            captureSession?.stopRepeating()
            captureSession?.close()
        } catch (e: Exception) {
            Log.w(TAG, "Exception closing capture session: ${e.message}")
        }
        captureSession = null

        try {
            cameraDevice?.close()
        } catch (e: Exception) {
            Log.w(TAG, "Exception closing camera device: ${e.message}")
        }
        cameraDevice = null
    }

    /**
     * Completely destroys the camera engine and its handler thread.
     */
    fun release() {
        stop()
        cameraThread?.quitSafely()
        cameraThread = null
        cameraHandler = null
    }

    companion object {
        private const val TAG = "CameraCaptureEngine"
    }
}
