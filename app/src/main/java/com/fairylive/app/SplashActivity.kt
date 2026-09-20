package com.fairylive.app

import android.content.Intent
import android.content.res.AssetFileDescriptor
import android.graphics.Color
import android.graphics.SurfaceTexture
import android.graphics.drawable.ColorDrawable
import android.media.MediaPlayer
import android.os.Bundle
import android.util.Log
import android.view.Surface
import android.view.TextureView
import android.view.View
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowCompat
import java.util.concurrent.atomic.AtomicBoolean

/**
 * SplashActivity presents the seamless 60 FPS optical-flow animated logo-drawing intro
 * on a pitch black (#000000) canvas that 100% matches the logo background.
 *
 * Driven by double-buffered TextureView + MediaPlayer, eliminating all SurfaceView
 * hole-punch flicker, black flashes, and frame drops.
 */
class SplashActivity : AppCompatActivity(), TextureView.SurfaceTextureListener {

    private var textureView: TextureView? = null
    private var mediaPlayer: MediaPlayer? = null
    private var surface: Surface? = null
    private val isNavigating = AtomicBoolean(false)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Ensure status bar, nav bar, and window background are pitch black matching the logo
        window.setBackgroundDrawable(ColorDrawable(Color.BLACK))
        window.statusBarColor = Color.BLACK
        window.navigationBarColor = Color.BLACK
        WindowCompat.setDecorFitsSystemWindows(window, false)

        setContentView(R.layout.activity_splash)

        textureView = findViewById(R.id.splashTextureView)
        textureView?.surfaceTextureListener = this
        textureView?.alpha = 0f // Keep hidden until first frame is decoded (zero flicker)

        val btnSkip = findViewById<TextView>(R.id.btnSkipSplash)
        val root = findViewById<View>(R.id.splashRoot)

        root.setOnClickListener {
            navigateToMain()
        }

        btnSkip?.setOnClickListener {
            navigateToMain()
        }
    }

    override fun onSurfaceTextureAvailable(surfaceTexture: SurfaceTexture, width: Int, height: Int) {
        surface = Surface(surfaceTexture)
        startVideoPlayback()
    }

    override fun onSurfaceTextureSizeChanged(surfaceTexture: SurfaceTexture, width: Int, height: Int) {}

    override fun onSurfaceTextureDestroyed(surfaceTexture: SurfaceTexture): Boolean {
        releasePlayer()
        surface?.release()
        surface = null
        return true
    }

    override fun onSurfaceTextureUpdated(surfaceTexture: SurfaceTexture) {}

    private fun startVideoPlayback() {
        try {
            val surf = surface ?: return
            val afd: AssetFileDescriptor = assets.openFd("fairy_splash_intro.mp4")

            mediaPlayer = MediaPlayer().apply {
                setSurface(surf)
                setDataSource(afd.fileDescriptor, afd.startOffset, afd.length)
                afd.close()

                isLooping = false

                setOnPreparedListener { mp ->
                    mp.start()
                    Log.i(TAG, "Splash 60 FPS video started playing (duration: ${mp.duration}ms)")
                }

                setOnInfoListener { _, what, _ ->
                    if (what == MediaPlayer.MEDIA_INFO_VIDEO_RENDERING_START) {
                        // First frame ready: smoothly fade in texture view with zero black flash
                        textureView?.animate()?.alpha(1f)?.setDuration(120)?.start()
                    }
                    false
                }

                setOnCompletionListener {
                    Log.i(TAG, "Splash 9s sequence completed -> smoothly transitioning to MainActivity")
                    navigateToMain()
                }

                setOnErrorListener { _, what, extra ->
                    Log.w(TAG, "Splash video playback error: what=$what extra=$extra")
                    navigateToMain()
                    true
                }

                prepareAsync()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error starting splash video playback", e)
            navigateToMain()
        }
    }

    private fun navigateToMain() {
        if (!isNavigating.compareAndSet(false, true)) {
            return
        }

        releasePlayer()

        val intent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_NEW_TASK
        }
        startActivity(intent)
        finish()
        overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out)
    }

    private fun releasePlayer() {
        try {
            mediaPlayer?.stop()
            mediaPlayer?.release()
        } catch (_: Exception) {}
        mediaPlayer = null
    }

    override fun onPause() {
        super.onPause()
        try {
            mediaPlayer?.pause()
        } catch (_: Exception) {}
    }

    override fun onResume() {
        super.onResume()
        try {
            mediaPlayer?.start()
        } catch (_: Exception) {}
    }

    override fun onDestroy() {
        super.onDestroy()
        releasePlayer()
        surface?.release()
        surface = null
    }

    companion object {
        private const val TAG = "SplashActivity"
    }
}
