package com.fairylive.app

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.util.Log
import android.view.View
import android.widget.TextView
import android.widget.VideoView
import androidx.appcompat.app.AppCompatActivity
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.atomic.AtomicBoolean

/**
 * SplashActivity presents the seamless 9-second branded logo-drawing animation
 * for Fairy Live on a pure black backdrop.
 *
 * Tapping anywhere or letting the intro finish transitions smoothly to MainActivity.
 */
class SplashActivity : AppCompatActivity() {

    private var videoView: VideoView? = null
    private val isNavigating = AtomicBoolean(false)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_splash)

        videoView = findViewById(R.id.splashVideoView)
        val btnSkip = findViewById<TextView>(R.id.btnSkipSplash)
        val root = findViewById<View>(R.id.splashRoot)

        root.setOnClickListener {
            navigateToMain()
        }

        btnSkip?.setOnClickListener {
            navigateToMain()
        }

        setupAndPlaySplashVideo()
    }

    private fun setupAndPlaySplashVideo() {
        try {
            val videoFile = getAssetFile("fairy_splash_intro.mp4")
            if (videoFile != null && videoFile.exists()) {
                val uri = Uri.fromFile(videoFile)
                videoView?.setVideoURI(uri)
                videoView?.setOnPreparedListener { mp ->
                    mp.isLooping = false
                    mp.start()
                    Log.i(TAG, "Splash video started playing (duration: ${mp.duration}ms)")
                }
                videoView?.setOnCompletionListener {
                    Log.i(TAG, "Splash video completed 9s sequence -> navigating to MainActivity")
                    navigateToMain()
                }
                videoView?.setOnErrorListener { _, what, extra ->
                    Log.w(TAG, "Splash video playback error: what=$what extra=$extra")
                    navigateToMain()
                    true
                }
            } else {
                Log.w(TAG, "Splash video asset not found, proceeding directly")
                navigateToMain()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error initializing splash video", e)
            navigateToMain()
        }
    }

    private fun getAssetFile(assetName: String): File? {
        return try {
            val file = File(cacheDir, assetName)
            if (!file.exists() || file.length() == 0L) {
                assets.open(assetName).use { input ->
                    FileOutputStream(file).use { output ->
                        input.copyTo(output)
                    }
                }
            }
            file
        } catch (e: Exception) {
            Log.e(TAG, "Failed to copy asset $assetName to cache", e)
            null
        }
    }

    private fun navigateToMain() {
        if (!isNavigating.compareAndSet(false, true)) {
            return
        }

        try {
            videoView?.stopPlayback()
        } catch (_: Exception) {}

        val intent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_NEW_TASK
        }
        startActivity(intent)
        finish()
        overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out)
    }

    override fun onDestroy() {
        super.onDestroy()
        try {
            videoView?.stopPlayback()
        } catch (_: Exception) {}
        videoView = null
    }

    companion object {
        private const val TAG = "SplashActivity"
    }
}
