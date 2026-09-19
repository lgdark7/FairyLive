package com.fairylive.app.core.compositor.player

import android.content.Context
import android.media.MediaPlayer
import android.util.Log
import android.view.Surface
import java.io.File
import java.io.FileOutputStream

class StreamPausePlayer(private val context: Context) {
    private var mediaPlayer: MediaPlayer? = null
    @Volatile
    private var isPlaying = false

    @Synchronized
    fun start(surface: Surface) {
        stop()
        try {
            val cacheFile = File(context.cacheDir, "stream_paused.mp4")
            if (!cacheFile.exists() || cacheFile.length() < 1024L) {
                context.assets.open("stream_paused.mp4").use { input ->
                    FileOutputStream(cacheFile).use { output ->
                        input.copyTo(output)
                    }
                }
            }

            val player = MediaPlayer().apply {
                setDataSource(cacheFile.absolutePath)
                setSurface(surface)
                isLooping = true
                setVolume(0f, 0f)
                setOnPreparedListener { mp ->
                    try {
                        mp.start()
                        this@StreamPausePlayer.isPlaying = true
                        Log.i(TAG, "StreamPausePlayer started successfully (looping=true)")
                    } catch (e: Exception) {
                        Log.e(TAG, "Failed to start pause video playback", e)
                    }
                }
                setOnErrorListener { _, what, extra ->
                    Log.w(TAG, "StreamPausePlayer media error: what=$what extra=$extra")
                    true
                }
                prepareAsync()
            }
            mediaPlayer = player
        } catch (e: Exception) {
            Log.e(TAG, "Failed to initialize StreamPausePlayer", e)
        }
    }

    @Synchronized
    fun stop() {
        try {
            mediaPlayer?.let { player ->
                if (isPlaying) {
                    try {
                        player.stop()
                    } catch (_: Exception) {}
                }
                player.reset()
                player.release()
            }
        } catch (e: Exception) {
            Log.w(TAG, "Error releasing StreamPausePlayer", e)
        } finally {
            mediaPlayer = null
            isPlaying = false
        }
    }

    companion object {
        private const val TAG = "StreamPausePlayer"
    }
}
