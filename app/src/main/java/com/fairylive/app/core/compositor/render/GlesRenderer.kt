package com.fairylive.app.core.compositor.render

import android.graphics.Bitmap
import android.graphics.SurfaceTexture
import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.EGLContext
import android.opengl.EGLDisplay
import android.opengl.EGLSurface
import android.opengl.GLES11Ext
import android.opengl.GLES20
import android.opengl.GLUtils
import android.util.Log
import android.view.Surface
import com.fairylive.app.core.compositor.OverlayLayer
import com.fairylive.app.core.compositor.OverlayScene
import com.fairylive.app.core.compositor.resource.OverlayResourceManager
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

    private var encoderSurface: Surface? = null
    private var streamWidth: Int = 0
    private var streamHeight: Int = 0
    private var assetManager: android.content.res.AssetManager? = null

    private var captureProgram: Int = 0
    private var overlayProgram: Int = 0

    // Fullscreen quad (2D, 2 verts each) — NDC position + UV
    private val quadVerts = directBuffer(floatArrayOf(-1f, -1f, 1f, -1f, -1f, 1f, 1f, 1f))
    private val quadUvs = directBuffer(floatArrayOf(0f, 1f, 1f, 1f, 0f, 0f, 1f, 0f))

    // Single overlay quad (bottom-left origin for the transform math) — 4 corners, 6 indices
    private val overlayVerts = directBuffer(
        floatArrayOf(
            0f, 0f,
            1f, 0f,
            0f, 1f,
            1f, 1f
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

    private var initialized = false

    /** Invoked on the render thread whenever the capture SurfaceTexture has a new frame. */
    @Volatile
    var onFrameAvailable: (() -> Unit)? = null

    /**
     * Creates the EGL context, capture SurfaceTexture, and shaders. Returns the
     * [Surface] that the VirtualDisplay should render into (the compositor input).
     */
    fun initialize(
        encoderSurface: Surface,
        width: Int,
        height: Int,
        assets: android.content.res.AssetManager
    ): Surface {
        require(encoderSurface.isValid) { "Encoder input surface is invalid" }
        this.encoderSurface = encoderSurface
        this.streamWidth = width
        this.streamHeight = height
        this.assetManager = assets

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
            setOnFrameAvailableListener({ onFrameAvailable?.invoke() }, null)
        }
        captureSurface = Surface(captureSurfaceTexture)

        captureProgram = createProgram(VERTEX_SHADER, CAPTURE_FRAGMENT)
        overlayProgram = createProgram(OVERLAY_VERTEX_SHADER, OVERLAY_FRAGMENT)

        GLES20.glClearColor(0f, 0f, 0f, 1f)
        initialized = true

        Log.i(TAG, "Renderer initialized: ${width}x${height}, captureSurface=$captureSurface")
        return captureSurface!!
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
     * Consumes a new capture frame if one is pending. Returns true when a frame
     * was actually updated, false otherwise (no-op). Used by the poll fallback:
     * the poll calls this every 16ms and only draws when it returns true.
     */
    fun consumeNewFrame(): Boolean {
        val st = captureSurfaceTexture ?: return false
        if (!initialized) return false
        return try {
            st.updateTexImage()
            true
        } catch (e: Exception) {
            false // no new frame yet — updateTexImage throws IllegalStateException
        }
    }

    /** Draws the composed frame to the encoder surface. Must run on the GL thread. */
    fun drawFrame(scene: OverlayScene, resources: OverlayResourceManager) {
        if (!initialized) return
        if (eglDisplay == EGL14.EGL_NO_DISPLAY || eglSurface == EGL14.EGL_NO_SURFACE) return

        composite(scene, resources)
        EGL14.eglSwapBuffers(eglDisplay, eglSurface)
    }

    /**
     * Draws the composed scene without swapping. Test-only primitive so the test
     * can `glReadPixels` the framebuffer before the swap consumes it.
     */
    fun composite(scene: OverlayScene, resources: OverlayResourceManager) {
        if (!initialized) return
        if (eglDisplay == EGL14.EGL_NO_DISPLAY || eglSurface == EGL14.EGL_NO_SURFACE) return

        GLES20.glViewport(0, 0, streamWidth, streamHeight)
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)

        drawCapture()
        scene.orderedLayers.forEach { layer -> drawOverlay(layer, resources) }
    }

    private fun drawCapture() {
        if (captureTextureId == 0 || captureProgram == 0) return
        GLES20.glUseProgram(captureProgram)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, captureTextureId)

        val posLoc = GLES20.glGetAttribLocation(captureProgram, "aPosition")
        val uvLoc = GLES20.glGetAttribLocation(captureProgram, "aTexCoord")
        GLES20.glEnableVertexAttribArray(posLoc)
        GLES20.glEnableVertexAttribArray(uvLoc)
        GLES20.glVertexAttribPointer(posLoc, 2, GLES20.GL_FLOAT, false, 0, quadVerts)
        GLES20.glVertexAttribPointer(uvLoc, 2, GLES20.GL_FLOAT, false, 0, quadUvs)
        GLES20.glUniform1i(GLES20.glGetUniformLocation(captureProgram, "uTexture"), 0)

        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)

        GLES20.glDisableVertexAttribArray(posLoc)
        GLES20.glDisableVertexAttribArray(uvLoc)
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, 0)
    }

    private fun drawOverlay(layer: OverlayLayer, resources: OverlayResourceManager) {
        if (!layer.liveVisible || layer.liveOpacity <= 0f) return
        if (overlayProgram == 0) return

        // Ensure the overlay texture is uploaded once
        if (layer.textureHandle == 0) {
            val assets = assetManager
            val bitmap = if (assets != null) {
                layer.element.resourcePath?.let { resources.loadBitmap(it, assets) }
            } else {
                null
            }
            if (bitmap != null) {
                layer.textureHandle = uploadBitmap(bitmap)
                layer.sourceWidth = bitmap.width
                layer.sourceHeight = bitmap.height
            } else {
                return // resource failed to load — skip this layer
            }
        }

        val t = layer.transform
        val left = t.resolvedLeft(streamWidth)
        val top = t.resolvedTop(streamHeight)
        val w = t.resolvedWidth(streamWidth)
        val h = t.resolvedHeight(streamWidth, streamHeight, layer.sourceAspect)

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

        if (captureProgram != 0) GLES20.glDeleteProgram(captureProgram)
        if (overlayProgram != 0) GLES20.glDeleteProgram(overlayProgram)
        captureProgram = 0
        overlayProgram = 0

        captureSurface?.release()
        captureSurface = null
        captureSurfaceTexture?.release()
        captureSurfaceTexture = null

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
    }
}