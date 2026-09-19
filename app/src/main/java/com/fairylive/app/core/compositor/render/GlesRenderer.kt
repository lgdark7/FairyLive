package com.fairylive.app.core.compositor.render

import android.graphics.Bitmap
import android.graphics.SurfaceTexture
import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.EGLContext
import android.opengl.EGLDisplay
import android.opengl.EGLExt
import android.opengl.EGLSurface
import android.opengl.GLES11Ext
import android.opengl.GLES20
import android.opengl.GLUtils
import android.os.Handler
import android.util.Log
import android.view.Surface
import com.fairylive.app.core.camera.model.FacecamConfig
import com.fairylive.app.core.compositor.OverlayLayer
import com.fairylive.app.core.compositor.OverlayScene
import com.fairylive.app.core.compositor.animation.GifStreamPlayer
import com.fairylive.app.core.compositor.model.OverlayType
import com.fairylive.app.core.compositor.resource.OverlayResourceManager
import com.fairylive.app.core.scene.model.SceneBackgroundMode
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import kotlin.math.cos
import kotlin.math.sin

/**
 * GLES2 renderer for the broadcast compositor (M7).
 *
 * Renders: capture texture (fullscreen) + ordered overlays -> encoder input Surface.
 * Domain separation: this is the BROADCAST composition domain. It never touches
 * the creator's Android UI (Dashboard) — the two are fully independent.
 *
 * GPU path (zero CPU copies in the per-frame loop):
 *   VirtualDisplay -> SurfaceTexture (this renderer) -> EGL window Surface (encoder input)
 *
 * Overlay textures are uploaded once from Bitmap (via [OverlayResourceManager]) and
 * kept GPU-resident; only the layer transform uniforms change per frame.
 */
class GlesRenderer {

    private var eglDisplay: EGLDisplay = EGL14.EGL_NO_DISPLAY
    private var eglContext: EGLContext = EGL14.EGL_NO_CONTEXT
    private var eglSurface: EGLSurface = EGL14.EGL_NO_SURFACE

    private var captureTextureId: Int = 0
    private var captureSurfaceTexture: SurfaceTexture? = null
    private var captureSurface: Surface? = null

    private var cameraTextureId: Int = 0
    private var cameraSurfaceTexture: SurfaceTexture? = null
    private var cameraSurface: Surface? = null
    private var facecamProgram: Int = 0

    private val captureTexMatrix = FloatArray(16)
    private val cameraTexMatrix = FloatArray(16)
    private val cameraModelMatrix = FloatArray(16)
    private var lastCameraTimestampNanos: Long = -1L
    private val newCameraAvailable = java.util.concurrent.atomic.AtomicBoolean(false)

    @Volatile
    var onCameraFrameAvailable: (() -> Unit)? = null

    private var encoderSurface: Surface? = null
    private var streamWidth: Int = 0
    private var streamHeight: Int = 0
    private var captureSourceWidth: Int = 0
    private var captureSourceHeight: Int = 0
    private var assetManager: android.content.res.AssetManager? = null
    var context: android.content.Context? = null

    private var captureProgram: Int = 0
    private var overlayProgram: Int = 0
    private var gifStreamPlayer: GifStreamPlayer? = null

    // Fullscreen quad (2D, 2 verts each) — NDC position + UV
    private val quadVerts = directBuffer(floatArrayOf(-1f, -1f, 1f, -1f, -1f, 1f, 1f, 1f))
    private val quadUvs = directBuffer(floatArrayOf(0f, 1f, 1f, 1f, 0f, 0f, 1f, 0f))
    private var captureQuadUvs: FloatBuffer = directBuffer(floatArrayOf(0f, 0f, 1f, 0f, 0f, 1f, 1f, 1f))
    private var captureQuadVerts: FloatBuffer = directBuffer(floatArrayOf(-1f, -1f, 1f, -1f, -1f, 1f, 1f, 1f))
    private var captureGeometryState: com.fairylive.app.core.capture.model.CaptureGeometryState =
        com.fairylive.app.core.capture.model.CaptureGeometryState()
    @Volatile
    private var isCaptureVisible: Boolean = true

    val currentGeometryState: com.fairylive.app.core.capture.model.CaptureGeometryState
        get() = captureGeometryState

    // Single overlay quad (-0.5..0.5) centered at origin for center-pivot scale & rotation
    private val overlayVerts = directBuffer(
        floatArrayOf(
            -0.5f, -0.5f,
             0.5f, -0.5f,
            -0.5f,  0.5f,
             0.5f,  0.5f
        )
    )
    private val overlayUvs = directBuffer(
        floatArrayOf(
            0f, 1f,
            1f, 1f,
            0f, 0f,
            1f, 0f
        )
    )

    // Centered Facecam quad (-0.5..0.5) for scale & rotation about center
    private val facecamVerts = directBuffer(
        floatArrayOf(
            -0.5f, -0.5f,
             0.5f, -0.5f,
            -0.5f,  0.5f,
             0.5f,  0.5f
        )
    )
    private val facecamUvs = directBuffer(
        floatArrayOf(
            0f, 0f,
            1f, 0f,
            0f, 1f,
            1f, 1f
        )
    )
    private val facecamMirroredUvs = directBuffer(
        floatArrayOf(
            1f, 0f,
            0f, 0f,
            1f, 1f,
            0f, 1f
        )
    )

    private var initialized = false

    /** Invoked on the render thread whenever the capture SurfaceTexture has a new frame. */
    @Volatile
    var onFrameAvailable: (() -> Unit)? = null

    @Volatile
    var backgroundMode: SceneBackgroundMode = SceneBackgroundMode.SCREEN_CAPTURE

    @Volatile
    var backgroundColorArgb: Int = 0xFF000000.toInt()

    private var lastCaptureTimestampNanos: Long = -1L

    /**
     * Creates the EGL context, capture SurfaceTexture, and shaders. Returns the
     * [Surface] that the VirtualDisplay should render into (the compositor input).
     */
    fun initialize(
        encoderSurface: Surface,
        width: Int,
        height: Int,
        assets: android.content.res.AssetManager,
        renderHandler: Handler? = null
    ): Surface {
        require(encoderSurface.isValid) { "Encoder input surface is invalid" }
        this.encoderSurface = encoderSurface
        this.streamWidth = width
        this.streamHeight = height
        this.assetManager = assets
        this.lastCaptureTimestampNanos = -1L

        eglDisplay = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
        check(eglDisplay != EGL14.EGL_NO_DISPLAY) { "eglGetDisplay failed" }

        val version = IntArray(2)
        check(EGL14.eglInitialize(eglDisplay, version, 0, version, 1)) { "eglInitialize failed" }

        val configs = arrayOfNulls<EGLConfig>(1)
        val numConfigs = IntArray(1)
        val configAttribs = intArrayOf(
            EGL14.EGL_RED_SIZE, 8,
            EGL14.EGL_GREEN_SIZE, 8,
            EGL14.EGL_BLUE_SIZE, 8,
            EGL14.EGL_ALPHA_SIZE, 8,
            EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT,
            EGL14.EGL_SURFACE_TYPE, EGL14.EGL_WINDOW_BIT,
            EGL14.EGL_NONE
        )
        check(
            EGL14.eglChooseConfig(eglDisplay, configAttribs, 0, configs, 0, configs.size, numConfigs, 0) &&
                numConfigs[0] > 0 && configs[0] != null
        ) { "eglChooseConfig failed" }

        val contextAttribs = intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, EGL14.EGL_NONE)
        eglContext = EGL14.eglCreateContext(eglDisplay, configs[0], EGL14.EGL_NO_CONTEXT, contextAttribs, 0)
        check(eglContext != EGL14.EGL_NO_CONTEXT) { "eglCreateContext failed" }

        eglSurface = EGL14.eglCreateWindowSurface(
            eglDisplay, configs[0], encoderSurface,
            intArrayOf(EGL14.EGL_NONE), 0
        )
        check(eglSurface != EGL14.EGL_NO_SURFACE) { "eglCreateWindowSurface failed" }

        check(EGL14.eglMakeCurrent(eglDisplay, eglSurface, eglSurface, eglContext)) { "eglMakeCurrent failed" }

        // Capture input texture (OES) + SurfaceTexture the VirtualDisplay renders into
        captureTextureId = createOesTexture()
        captureSurfaceTexture = SurfaceTexture(captureTextureId).apply {
            setDefaultBufferSize(width, height)
            setOnFrameAvailableListener({
                Log.i(TAG, ">>> NATIVE onFrameAvailable FIRED! <<<")
                onFrameAvailable?.invoke()
            }, renderHandler)
        }
        captureSurface = Surface(captureSurfaceTexture)
        android.opengl.Matrix.setIdentityM(captureTexMatrix, 0)

        captureProgram = createProgram(CAPTURE_VERTEX_SHADER, CAPTURE_FRAGMENT)
        overlayProgram = createProgram(OVERLAY_VERTEX_SHADER, OVERLAY_FRAGMENT)
        gifStreamPlayer = GifStreamPlayer(assets)

        // Camera input texture (OES) + SurfaceTexture for broadcast facecam
        cameraTextureId = createOesTexture()
        cameraSurfaceTexture = SurfaceTexture(cameraTextureId).apply {
            setDefaultBufferSize(width / 2, height / 2)
            setOnFrameAvailableListener({
                newCameraAvailable.set(true)
                onCameraFrameAvailable?.invoke()
            }, renderHandler)
        }
        cameraSurface = Surface(cameraSurfaceTexture)
        android.opengl.Matrix.setIdentityM(cameraTexMatrix, 0)
        facecamProgram = createProgram(FACECAM_VERTEX_SHADER, FACECAM_FRAGMENT)

        GLES20.glClearColor(0f, 0f, 0f, 1f)
        initialized = true
        this.captureSourceWidth = width
        this.captureSourceHeight = height
        updateCaptureQuad()

        Log.i(TAG, "Renderer initialized: ${width}x${height}, captureSurface=$captureSurface, cameraSurface=$cameraSurface")
        return captureSurface!!
    }

    private fun updateCaptureQuad() {
        if (streamWidth <= 0 || streamHeight <= 0 || captureSourceWidth <= 0 || captureSourceHeight <= 0) {
            return
        }

        val state = com.fairylive.app.core.capture.model.CaptureGeometryState.calculate(
            sourceWidth = captureSourceWidth,
            sourceHeight = captureSourceHeight,
            outputWidth = streamWidth,
            outputHeight = streamHeight,
            rotation = 0,
            visibility = isCaptureVisible
        )
        this.captureGeometryState = state

        val uLeft = state.cropLeft
        val uRight = 1.0f - state.cropRight
        val vBottom = state.cropBottom
        val vTop = 1.0f - state.cropTop

        // Symmetrical center crop UV coordinates
        val uvs = floatArrayOf(
            uLeft,  vBottom,
            uRight, vBottom,
            uLeft,  vTop,
            uRight, vTop
        )
        captureQuadUvs = directBuffer(uvs)

        // In landscape gaming mode, quad fills full [-1, 1] canvas (scale=1.0)
        // In portrait mode, pillarbox quad width by scale
        val scaleX = if (state.isLandscape) 1.0f else state.scale
        val scaleY = 1.0f

        val verts = floatArrayOf(
            -scaleX, -scaleY,
             scaleX, -scaleY,
            -scaleX,  scaleY,
             scaleX,  scaleY
        )
        captureQuadVerts = directBuffer(verts)
        Log.i(TAG, "[CAPTURE-CHAIN] Capture quad updated: source=${captureSourceWidth}x${captureSourceHeight} (aspect=${state.sourceAspectRatio}), output=${streamWidth}x${streamHeight} (aspect=${state.outputAspectRatio}), crop=[L=${state.cropLeft}, R=${state.cropRight}, T=${state.cropTop}, B=${state.cropBottom}], scale=$scaleX, visible=${state.visibility}")
    }

    /**
     * Updates visibility state when app switches in/out of foreground.
     * When visible = true, triggers geometry re-evaluation to prevent stale dimensions.
     */
    fun onVisibilityChanged(visible: Boolean) {
        this.isCaptureVisible = visible
        Log.i(TAG, "[CAPTURE-CHAIN] GlesRenderer visibility changed: $visible")
        if (visible) {
            updateCaptureQuad()
        }
    }

    /** Exposes the camera preview input surface for Facecam capture. */
    fun getCameraSurface(width: Int = 640, height: Int = 480): Surface? {
        if (!initialized) return null
        cameraSurfaceTexture?.setDefaultBufferSize(width, height)
        return cameraSurface
    }

    /** Call from the render thread when a new capture frame should be consumed. */
    fun updateCaptureTexture() {
        val st = captureSurfaceTexture ?: return
        try {
            st.updateTexImage()
        } catch (e: Exception) {
            Log.w(TAG, "updateTexImage skipped: ${e.message}")
        }
    }

    /**
     * Consumes a new capture frame if one is pending.
     * Returns true when a genuinely new capture buffer was latched,
     * false if no new frame was available (duplicate texture prevented).
     */
    fun consumeNewFrame(force: Boolean = false): Boolean {
        val st = captureSurfaceTexture ?: return false
        if (!initialized) return false
        return try {
            st.updateTexImage()
            st.getTransformMatrix(captureTexMatrix)
            val currentTs = st.timestamp
            val isDifferent = (currentTs != 0L && currentTs != lastCaptureTimestampNanos) || force
            if (isDifferent) {
                lastCaptureTimestampNanos = currentTs
                true
            } else {
                false
            }
        } catch (e: Exception) {
            Log.w(TAG, "consumeNewFrame skipped: ${e.message}")
            false
        }
    }

    /**
     * Updates capture source dimensions on orientation change or task resize.
     * The broadcast canvas (streamWidth x streamHeight) remains fixed to the encoder target.
     */
    fun updateDimensions(width: Int, height: Int) {
        if (!initialized) return
        this.captureSourceWidth = width
        this.captureSourceHeight = height
        captureSurfaceTexture?.setDefaultBufferSize(width, height)
        updateCaptureQuad()
        Log.i(TAG, "[CAPTURE-CHAIN] GlesRenderer capture dimensions updated: ${width}x${height}, canvas remains ${streamWidth}x${streamHeight}")
    }

    /**
     * Consumes a new camera frame if one is pending.
     * Returns true when a new camera buffer was latched.
     */
    fun consumeNewCameraFrame(force: Boolean = false): Boolean {
        val st = cameraSurfaceTexture ?: return false
        if (!initialized) return false
        return try {
            st.updateTexImage()
            st.getTransformMatrix(cameraTexMatrix)
            val currentTs = st.timestamp
            val isDifferent = (currentTs != 0L && currentTs != lastCameraTimestampNanos) || force
            if (isDifferent) {
                lastCameraTimestampNanos = currentTs
                true
            } else {
                false
            }
        } catch (e: Exception) {
            Log.w(TAG, "consumeNewCameraFrame skipped: ${e.message}")
            false
        }
    }

    /**
     * Returns the timestamp of the most recently latched capture texture, in nanoseconds.
     */
    val currentCaptureTimestampNanos: Long
        get() = captureSurfaceTexture?.timestamp ?: 0L

    private var lastPresentedTimestampNs = 0L

    /** Draws the composed frame to the encoder surface. Must run on the GL thread. */
    fun drawFrame(
        scene: OverlayScene,
        resources: OverlayResourceManager,
        facecamConfig: FacecamConfig? = null,
        presentationTimeNs: Long = 0L
    ) {
        if (!initialized) return
        if (eglDisplay == EGL14.EGL_NO_DISPLAY || eglSurface == EGL14.EGL_NO_SURFACE) return

        composite(scene, resources, facecamConfig)

        val targetPtsNs = if (presentationTimeNs > 0L) {
            presentationTimeNs
        } else {
            val captureTs = captureSurfaceTexture?.timestamp ?: 0L
            if (captureTs > 0L && captureTs > lastPresentedTimestampNs) captureTs else com.fairylive.app.core.common.MediaClock.nanoTimeSource()
        }

        // Strict monotonicity check for MediaCodec EGL presentation timestamp
        val ptsNs = if (targetPtsNs > lastPresentedTimestampNs) {
            targetPtsNs
        } else {
            lastPresentedTimestampNs + 1_000_000L // Ensure strictly monotonic progression (>= 1ms)
        }
        lastPresentedTimestampNs = ptsNs

        EGLExt.eglPresentationTimeANDROID(eglDisplay, eglSurface, ptsNs)
        EGL14.eglSwapBuffers(eglDisplay, eglSurface)
    }

    /** Overload for backwards compatibility. */
    fun drawFrame(scene: OverlayScene, resources: OverlayResourceManager) {
        drawFrame(scene, resources, null, 0L)
    }

    /**
     * Draws the composed scene without swapping. Test-only primitive so the test
     * can `glReadPixels` the framebuffer before the swap consumes it.
     */
    fun composite(
        scene: OverlayScene,
        resources: OverlayResourceManager,
        facecamConfig: FacecamConfig? = null
    ) {
        if (!initialized) return
        if (eglDisplay == EGL14.EGL_NO_DISPLAY || eglSurface == EGL14.EGL_NO_SURFACE) return

        GLES20.glViewport(0, 0, streamWidth, streamHeight)
        val r = ((backgroundColorArgb shr 16) and 0xFF) / 255.0f
        val g = ((backgroundColorArgb shr 8) and 0xFF) / 255.0f
        val b = (backgroundColorArgb and 0xFF) / 255.0f
        val a = ((backgroundColorArgb ushr 24) and 0xFF) / 255.0f
        GLES20.glClearColor(r, g, b, a)
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)

        if (backgroundMode == SceneBackgroundMode.SCREEN_CAPTURE) {
            drawCapture()
        }
        if (facecamConfig != null && facecamConfig.enabled) {
            drawFacecam(facecamConfig)
        }
        scene.orderedLayers.forEach { layer -> drawOverlay(layer, resources) }
    }

    /** Overload for backwards compatibility. */
    fun composite(scene: OverlayScene, resources: OverlayResourceManager) {
        composite(scene, resources, null)
    }

    fun drawFacecam(config: FacecamConfig) {
        if (cameraTextureId == 0 || facecamProgram == 0 || !config.enabled) return

        val left = config.resolvedLeft(streamWidth)
        val top = config.resolvedTop(streamHeight)
        val w = config.resolvedWidth(streamWidth)
        val h = config.resolvedHeight(streamWidth, streamHeight)

        // Normalized clip-space transform (-1..1 NDC)
        val clipX = left * 2f / streamWidth - 1f
        val clipY = 1f - top * 2f / streamHeight
        val clipW = w * 2f / streamWidth
        val clipH = h * 2f / streamHeight

        val rot = config.rotationDegrees * (kotlin.math.PI.toFloat()) / 180f
        val cosR = cos(rot)
        val sinR = sin(rot)

        val centerX = clipX + clipW / 2f
        val centerY = clipY - clipH / 2f

        cameraModelMatrix[0] = cosR * clipW
        cameraModelMatrix[1] = sinR * clipW
        cameraModelMatrix[2] = 0f
        cameraModelMatrix[3] = 0f

        cameraModelMatrix[4] = -sinR * clipH
        cameraModelMatrix[5] = cosR * clipH
        cameraModelMatrix[6] = 0f
        cameraModelMatrix[7] = 0f

        cameraModelMatrix[8] = 0f
        cameraModelMatrix[9] = 0f
        cameraModelMatrix[10] = 1f
        cameraModelMatrix[11] = 0f

        cameraModelMatrix[12] = centerX
        cameraModelMatrix[13] = centerY
        cameraModelMatrix[14] = 0f
        cameraModelMatrix[15] = 1f

        GLES20.glUseProgram(facecamProgram)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, cameraTextureId)

        val posLoc = GLES20.glGetAttribLocation(facecamProgram, "aPosition")
        val uvLoc = GLES20.glGetAttribLocation(facecamProgram, "aTexCoord")
        GLES20.glEnableVertexAttribArray(posLoc)
        GLES20.glEnableVertexAttribArray(uvLoc)

        val uvBuffer = if (config.mirrorHorizontal) facecamMirroredUvs else facecamUvs
        GLES20.glVertexAttribPointer(posLoc, 2, GLES20.GL_FLOAT, false, 0, facecamVerts)
        GLES20.glVertexAttribPointer(uvLoc, 2, GLES20.GL_FLOAT, false, 0, uvBuffer)

        GLES20.glUniform1i(GLES20.glGetUniformLocation(facecamProgram, "uTexture"), 0)
        GLES20.glUniformMatrix4fv(GLES20.glGetUniformLocation(facecamProgram, "uModel"), 1, false, cameraModelMatrix, 0)
        GLES20.glUniformMatrix4fv(GLES20.glGetUniformLocation(facecamProgram, "uTexMatrix"), 1, false, cameraTexMatrix, 0)
        GLES20.glUniform1f(GLES20.glGetUniformLocation(facecamProgram, "uOpacity"), config.opacity.coerceIn(0f, 1f))

        GLES20.glEnable(GLES20.GL_BLEND)
        GLES20.glBlendFunc(GLES20.GL_SRC_ALPHA, GLES20.GL_ONE_MINUS_SRC_ALPHA)

        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)

        GLES20.glDisable(GLES20.GL_BLEND)
        GLES20.glDisableVertexAttribArray(posLoc)
        GLES20.glDisableVertexAttribArray(uvLoc)
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, 0)
    }

    private fun drawCapture() {
        if (captureTextureId == 0 || captureProgram == 0) return
        GLES20.glUseProgram(captureProgram)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, captureTextureId)

        val posLoc = GLES20.glGetAttribLocation(captureProgram, "aPosition")
        val uvLoc = GLES20.glGetAttribLocation(captureProgram, "aTexCoord")
        val uTexMatrixLoc = GLES20.glGetUniformLocation(captureProgram, "uTexMatrix")

        GLES20.glEnableVertexAttribArray(posLoc)
        GLES20.glEnableVertexAttribArray(uvLoc)
        GLES20.glVertexAttribPointer(posLoc, 2, GLES20.GL_FLOAT, false, 0, captureQuadVerts)
        GLES20.glVertexAttribPointer(uvLoc, 2, GLES20.GL_FLOAT, false, 0, captureQuadUvs)
        GLES20.glUniform1i(GLES20.glGetUniformLocation(captureProgram, "uTexture"), 0)
        if (uTexMatrixLoc >= 0) {
            GLES20.glUniformMatrix4fv(uTexMatrixLoc, 1, false, captureTexMatrix, 0)
        }

        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)

        GLES20.glDisableVertexAttribArray(posLoc)
        GLES20.glDisableVertexAttribArray(uvLoc)
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, 0)
    }

    private fun drawOverlay(layer: OverlayLayer, resources: OverlayResourceManager) {
        if (!layer.liveVisible || layer.liveOpacity <= 0f) return
        if (overlayProgram == 0) return

        // Static overlays upload once. GIFs reuse their single Bitmap and only replace
        // the GPU texture when Movie's timeline has actually advanced to a new frame.
        val isGif = layer.element.type == OverlayType.GIF
        val gifFrame = if (isGif) {
            layer.element.gifConfig?.let { config ->
                gifStreamPlayer?.getFrame(config, android.os.SystemClock.elapsedRealtime())
            }
        } else {
            null
        }
        val needsUpload = layer.textureHandle == 0 || (isGif && gifFrame?.second == true) || layer.dirty
        if (needsUpload) {
            val bitmap = gifFrame?.first ?: resolveLayerBitmap(layer, resources)
            if (bitmap != null) {
                if ((isGif || layer.dirty) && layer.textureHandle != 0) {
                    GLES20.glDeleteTextures(1, intArrayOf(layer.textureHandle), 0)
                }
                layer.textureHandle = uploadBitmap(bitmap)
                resources.trackTexture(layer.textureHandle)
                layer.sourceWidth = bitmap.width
                layer.sourceHeight = bitmap.height
                layer.dirty = false
            } else {
                return // resource failed to load — skip this layer
            }
        }

        val t = layer.transform
        val w = t.resolvedWidth(streamWidth)
        val h = t.resolvedHeight(streamWidth, streamHeight, layer.sourceAspect)
        val left = t.resolvedLeft(streamWidth)
        val top = t.resolvedTop(streamWidth, streamHeight, layer.sourceAspect)

        // normalized clip-space transform for this overlay
        val clipX = left * 2f / streamWidth - 1f
        val clipY = 1f - top * 2f / streamHeight
        val clipW = w * 2f / streamWidth
        val clipH = h * 2f / streamHeight
        val rot = t.rotationDegrees * (kotlin.math.PI.toFloat()) / 180f
        val cosR = cos(rot)
        val sinR = sin(rot)

        // rotation about the quad center, then translate
        val centerX = clipX + clipW / 2f
        val centerY = clipY - clipH / 2f
        val modelMatrix = FloatArray(16)

        // Build model matrix (column-major): translation * rotation * scale.
        // The quad is a unit square (0..1) in x/y; the scale terms map it to the
        // full clip-space width/height (NOT half), centered at (centerX, centerY).
        modelMatrix[0] = cosR * clipW            // m00
        modelMatrix[1] = sinR * clipW            // m10  (y-up rotation)
        modelMatrix[4] = -sinR * clipH           // m01
        modelMatrix[5] = cosR * clipH            // m11
        modelMatrix[12] = centerX                // m30
        modelMatrix[13] = centerY                // m31
        modelMatrix[10] = 1f
        modelMatrix[15] = 1f

        GLES20.glUseProgram(overlayProgram)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, layer.textureHandle)

        val posLoc = GLES20.glGetAttribLocation(overlayProgram, "aPosition")
        val uvLoc = GLES20.glGetAttribLocation(overlayProgram, "aTexCoord")
        GLES20.glEnableVertexAttribArray(posLoc)
        GLES20.glEnableVertexAttribArray(uvLoc)
        GLES20.glVertexAttribPointer(posLoc, 2, GLES20.GL_FLOAT, false, 0, overlayVerts)
        GLES20.glVertexAttribPointer(uvLoc, 2, GLES20.GL_FLOAT, false, 0, overlayUvs)

        GLES20.glUniform1i(GLES20.glGetUniformLocation(overlayProgram, "uTexture"), 0)
        GLES20.glUniformMatrix4fv(GLES20.glGetUniformLocation(overlayProgram, "uModel"), 1, false, modelMatrix, 0)
        GLES20.glUniform1f(GLES20.glGetUniformLocation(overlayProgram, "uOpacity"), layer.liveOpacity.coerceIn(0f, 1f))

        // blend for transparency
        GLES20.glEnable(GLES20.GL_BLEND)
        GLES20.glBlendFunc(GLES20.GL_SRC_ALPHA, GLES20.GL_ONE_MINUS_SRC_ALPHA)

        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)

        GLES20.glDisable(GLES20.GL_BLEND)
        GLES20.glDisableVertexAttribArray(posLoc)
        GLES20.glDisableVertexAttribArray(uvLoc)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, 0)
    }

    private fun resolveLayerBitmap(layer: OverlayLayer, resources: OverlayResourceManager): Bitmap? {
        val element = layer.element
        val assets = assetManager ?: return null
        return when (element.type) {
            OverlayType.IMAGE, OverlayType.PNG -> {
                element.resourcePath?.let { resources.loadBitmap(it, assets) }
            }
            OverlayType.TEXT -> {
                element.textConfig?.let { config ->
                    val key = "text_${element.id}_${config.hashCode()}"
                    resources.getOrPutBitmap(key) {
                        com.fairylive.app.core.compositor.rasterizer.TextOverlayRasterizer.rasterize(config)
                    }
                }
            }
            OverlayType.GOAL -> {
                element.goalConfig?.let { config ->
                    val key = "goal_${element.id}_${config.currentVal}_${config.targetVal}"
                    resources.getOrPutBitmap(key) {
                        com.fairylive.app.core.compositor.rasterizer.GoalOverlayRasterizer.rasterize(config)
                    }
                }
            }
            OverlayType.LOWER_THIRD -> {
                element.lowerThirdConfig?.let { config ->
                    val key = "l3_${element.id}_${config.title}_${config.subtitle}"
                    resources.getOrPutBitmap(key) {
                        com.fairylive.app.core.compositor.rasterizer.LowerThirdOverlayRasterizer.rasterize(config)
                    }
                }
            }
            OverlayType.QR -> {
                element.qrConfig?.let { config ->
                    val key = "qr_${element.id}_${config.payloadUrl}"
                    resources.getOrPutBitmap(key) {
                        com.fairylive.app.core.compositor.rasterizer.QrOverlayRasterizer.rasterize(config)
                    }
                }
            }
            OverlayType.ALERT -> {
                element.alertConfig?.let { config ->
                    val key = "alert_${element.id}_${config.headline}_${config.subtext}"
                    resources.getOrPutBitmap(key) {
                        com.fairylive.app.core.compositor.rasterizer.AlertOverlayRasterizer.rasterize(config)
                    }
                }
            }
            OverlayType.WIDGET -> {
                element.widgetConfig?.let { config ->
                    val key = "widget_${element.id}_${config.label}_${config.value}"
                    resources.getOrPutBitmap(key) {
                        com.fairylive.app.core.compositor.rasterizer.WidgetOverlayRasterizer.rasterize(config)
                    }
                }
            }
            OverlayType.GIF -> {
                element.gifConfig?.let { config ->
                    val streamTime = android.os.SystemClock.elapsedRealtime()
                    gifStreamPlayer?.getFrame(config, streamTime)?.first
                }
            }
            OverlayType.WEB -> {
                element.webConfig?.let { config ->
                    context?.let { ctx ->
                        OffscreenWebRenderer.getInstance(ctx).getOrRenderBitmap(element.id, config)
                    } ?: com.fairylive.app.core.compositor.rasterizer.WebOverlayRasterizer.rasterizePlaceholder(config)
                }
            }
            OverlayType.YOUTUBE_SUBSCRIBER -> {
                element.youtubeSubscriberConfig?.let { config ->
                    val key = "ytsub_${element.id}_${config.subscriberCountFormatted}_${config.channelTitle}"
                    resources.getOrPutBitmap(key) {
                        com.fairylive.app.core.compositor.rasterizer.YouTubeSubscriberRasterizer.rasterize(config)
                    }
                }
            }
        }
    }

    private fun uploadBitmap(bitmap: Bitmap): Int {
        val textures = IntArray(1)
        GLES20.glGenTextures(1, textures, 0)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, textures[0])
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
        GLUtils.texImage2D(GLES20.GL_TEXTURE_2D, 0, bitmap, 0)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, 0)
        return textures[0]
    }

    /** Releases every GL resource. Call on the GL thread. */
    fun release() {
        if (!initialized) return
        GLES20.glDeleteTextures(1, intArrayOf(captureTextureId), 0)
        captureTextureId = 0

        if (cameraTextureId != 0) {
            GLES20.glDeleteTextures(1, intArrayOf(cameraTextureId), 0)
            cameraTextureId = 0
        }

        if (captureProgram != 0) GLES20.glDeleteProgram(captureProgram)
        if (overlayProgram != 0) GLES20.glDeleteProgram(overlayProgram)
        if (facecamProgram != 0) GLES20.glDeleteProgram(facecamProgram)
        captureProgram = 0
        overlayProgram = 0
        facecamProgram = 0

        captureSurface?.release()
        captureSurface = null
        captureSurfaceTexture?.release()
        captureSurfaceTexture = null

        cameraSurface?.release()
        cameraSurface = null
        cameraSurfaceTexture?.release()
        cameraSurfaceTexture = null

        if (eglDisplay != EGL14.EGL_NO_DISPLAY) {
            EGL14.eglMakeCurrent(eglDisplay, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT)
            if (eglSurface != EGL14.EGL_NO_SURFACE) EGL14.eglDestroySurface(eglDisplay, eglSurface)
            if (eglContext != EGL14.EGL_NO_CONTEXT) EGL14.eglDestroyContext(eglDisplay, eglContext)
            EGL14.eglReleaseThread()
            EGL14.eglTerminate(eglDisplay)
        }
        eglDisplay = EGL14.EGL_NO_DISPLAY
        eglContext = EGL14.EGL_NO_CONTEXT
        eglSurface = EGL14.EGL_NO_SURFACE
        encoderSurface = null
        lastCaptureTimestampNanos = -1L
        lastCameraTimestampNanos = -1L
        lastPresentedTimestampNs = 0L
        gifStreamPlayer?.release()
        gifStreamPlayer = null
        initialized = false
        Log.i(TAG, "Renderer released")
    }

    fun isInitialized(): Boolean = initialized

    private fun createOesTexture(): Int {
        val tex = IntArray(1)
        GLES20.glGenTextures(1, tex, 0)
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, tex[0])
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, 0)
        return tex[0]
    }

    private fun createProgram(vertexSrc: String, fragmentSrc: String): Int {
        val vs = compileShader(GLES20.GL_VERTEX_SHADER, vertexSrc)
        val fs = compileShader(GLES20.GL_FRAGMENT_SHADER, fragmentSrc)
        if (vs == 0 || fs == 0) return 0
        val program = GLES20.glCreateProgram()
        GLES20.glAttachShader(program, vs)
        GLES20.glAttachShader(program, fs)
        GLES20.glLinkProgram(program)
        val status = IntArray(1)
        GLES20.glGetProgramiv(program, GLES20.GL_LINK_STATUS, status, 0)
        if (status[0] == 0) {
            Log.e(TAG, "Program link failed: ${GLES20.glGetProgramInfoLog(program)}")
            GLES20.glDeleteProgram(program)
            return 0
        }
        GLES20.glDeleteShader(vs)
        GLES20.glDeleteShader(fs)
        return program
    }

    private fun compileShader(type: Int, src: String): Int {
        val shader = GLES20.glCreateShader(type)
        GLES20.glShaderSource(shader, src)
        GLES20.glCompileShader(shader)
        val status = IntArray(1)
        GLES20.glGetShaderiv(shader, GLES20.GL_COMPILE_STATUS, status, 0)
        if (status[0] == 0) {
            Log.e(TAG, "Shader compile failed: ${GLES20.glGetShaderInfoLog(shader)}")
            GLES20.glDeleteShader(shader)
            return 0
        }
        return shader
    }

    private fun directBuffer(data: FloatArray): FloatBuffer {
        val bb = ByteBuffer.allocateDirect(data.size * 4).order(ByteOrder.nativeOrder())
        val fb = bb.asFloatBuffer()
        fb.put(data)
        fb.flip()
        return fb
    }

    companion object {
        private const val TAG = "GlesRenderer"

        private const val VERTEX_SHADER = """
            attribute vec4 aPosition;
            attribute vec2 aTexCoord;
            varying vec2 vTexCoord;
            void main() {
                gl_Position = aPosition;
                vTexCoord = aTexCoord;
            }
        """

        private const val CAPTURE_VERTEX_SHADER = """
            attribute vec4 aPosition;
            attribute vec2 aTexCoord;
            uniform mat4 uTexMatrix;
            varying vec2 vTexCoord;
            void main() {
                gl_Position = aPosition;
                vTexCoord = (uTexMatrix * vec4(aTexCoord, 0.0, 1.0)).xy;
            }
        """

        private const val CAPTURE_FRAGMENT = """
            #extension GL_OES_EGL_image_external : require
            precision mediump float;
            varying vec2 vTexCoord;
            uniform samplerExternalOES uTexture;
            void main() {
                gl_FragColor = texture2D(uTexture, vTexCoord);
            }
        """

        private const val OVERLAY_VERTEX_SHADER = """
            attribute vec4 aPosition;
            attribute vec2 aTexCoord;
            uniform mat4 uModel;
            varying vec2 vTexCoord;
            void main() {
                gl_Position = uModel * aPosition;
                vTexCoord = aTexCoord;
            }
        """

        private const val OVERLAY_FRAGMENT = """
            precision mediump float;
            varying vec2 vTexCoord;
            uniform sampler2D uTexture;
            uniform float uOpacity;
            void main() {
                vec4 texColor = texture2D(uTexture, vTexCoord);
                gl_FragColor = vec4(texColor.rgb, texColor.a * uOpacity);
            }
        """

        private const val FACECAM_VERTEX_SHADER = """
            attribute vec4 aPosition;
            attribute vec2 aTexCoord;
            uniform mat4 uModel;
            uniform mat4 uTexMatrix;
            varying vec2 vTexCoord;
            void main() {
                gl_Position = uModel * aPosition;
                vTexCoord = (uTexMatrix * vec4(aTexCoord, 0.0, 1.0)).xy;
            }
        """

        private const val FACECAM_FRAGMENT = """
            #extension GL_OES_EGL_image_external : require
            precision mediump float;
            varying vec2 vTexCoord;
            uniform samplerExternalOES uTexture;
            uniform float uOpacity;
            void main() {
                vec4 col = texture2D(uTexture, vTexCoord);
                gl_FragColor = vec4(col.rgb, col.a * uOpacity);
            }
        """
    }
}