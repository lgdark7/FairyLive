package com.fairylive.app.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.projection.MediaProjectionManager
import android.os.Binder
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.fairylive.app.FairyLiveApplication
import com.fairylive.app.MainActivity
import com.fairylive.app.R
import com.fairylive.app.core.capture.CaptureStateNotifier
import com.fairylive.app.core.capture.ScreenCaptureEngine
import com.fairylive.app.core.capture.model.CaptureState
import com.fairylive.app.core.lifecycle.StreamState
import com.fairylive.app.core.lifecycle.StreamStateNotifier
import com.fairylive.app.core.video.HardwareVideoEncoder
import com.fairylive.app.core.video.VideoEncoderCapabilityManager
import com.fairylive.app.core.video.model.VideoEncoderConfig

/**
 * Foreground service hosting the capture engine and hardware video encoder.
 * Implements the direct Zero-Copy Surface-to-Surface pipeline for low-end Android efficiency.
 */
class FairyStreamService : Service() {

    private val binder = LocalBinder()
    private var startTimeMs: Long = 0L

    val captureEngine = ScreenCaptureEngine()
    val videoEncoder = HardwareVideoEncoder()

    inner class LocalBinder : Binder() {
        fun getService(): FairyStreamService = this@FairyStreamService
    }

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = intent?.action ?: ACTION_START_SERVICE

        when (action) {
            ACTION_START_SERVICE -> {
                startStreamForeground("Fairy Live Engine Active")
            }
            ACTION_START_CAPTURE -> {
                if (intent != null) {
                    handleStartCapture(intent)
                }
            }
            ACTION_PAUSE_CAPTURE -> {
                captureEngine.pause()
                videoEncoder.pause()
            }
            ACTION_RESUME_CAPTURE -> {
                captureEngine.resume()
                videoEncoder.resume()
            }
            ACTION_STOP_CAPTURE -> {
                captureEngine.stop()
                videoEncoder.stop()
                updateNotificationContent("Screen capture & encoder stopped")
            }
            ACTION_REQUEST_KEYFRAME -> {
                videoEncoder.requestKeyFrame()
            }
            ACTION_ADJUST_BITRATE -> {
                val newBitrate = intent?.getIntExtra(EXTRA_BITRATE_KBPS, 1200) ?: 1200
                videoEncoder.adjustBitrate(newBitrate)
            }
            ACTION_UPDATE_RESOLUTION -> {
                val w = intent?.getIntExtra(EXTRA_WIDTH, 0) ?: 0
                val h = intent?.getIntExtra(EXTRA_HEIGHT, 0) ?: 0
                val d = intent?.getIntExtra(EXTRA_DPI, 0) ?: 0
                if (w > 0 && h > 0 && d > 0) {
                    captureEngine.updateResolution(w, h, d)
                }
            }
            ACTION_STOP_SERVICE -> {
                stopStreamService()
                return START_NOT_STICKY
            }
        }

        return START_STICKY
    }

    private fun handleStartCapture(intent: Intent) {
        val resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, 0)
        val resultData = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent.getParcelableExtra(EXTRA_RESULT_DATA, Intent::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent.getParcelableExtra(EXTRA_RESULT_DATA)
        }

        if (resultCode == 0 || resultData == null) {
            CaptureStateNotifier.updateState(CaptureState.ERROR)
            return
        }

        val rawW = intent.getIntExtra(EXTRA_WIDTH, 1080)
        val rawH = intent.getIntExtra(EXTRA_HEIGHT, 1920)
        val width = (rawW / 2) * 2
        val height = (rawH / 2) * 2
        val dpi = intent.getIntExtra(EXTRA_DPI, 420)
        val enableEncoding = intent.getBooleanExtra(EXTRA_ENABLE_ENCODING, true)
        val bitrateKbps = intent.getIntExtra(EXTRA_BITRATE_KBPS, 1200)
        val fps = intent.getIntExtra(EXTRA_FPS, 24)

        // Ensure foreground service is running with MediaProjection type before creating VirtualDisplay
        val statusMsg = if (enableEncoding) "Live H.264 Encoding (${width}x${height})" else "Screen Capture (${width}x${height})"
        startStreamForeground(statusMsg, withMediaProjection = true)

        val projectionManager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as? MediaProjectionManager
        val projection = projectionManager?.getMediaProjection(resultCode, resultData)

        if (projection == null) {
            CaptureStateNotifier.updateState(CaptureState.ERROR)
            return
        }

        if (enableEncoding) {
            val selection = VideoEncoderCapabilityManager.findOptimalEncoder()
            val app = applicationContext as? FairyLiveApplication
            val profile = app?.settingsRepository?.loadSettings(app.deviceCapability.recommendedProfile)?.profile
                ?: com.fairylive.app.core.capability.model.StreamProfile.LOW
            val (encW, encH) = VideoEncoderCapabilityManager.resolveAdaptiveDimensions(width, height, profile, selection)

            val encoderConfig = VideoEncoderConfig(
                width = encW,
                height = encH,
                bitrateKbps = bitrateKbps,
                fps = fps
            )
            val encoderSurface = videoEncoder.initialize(encoderConfig)
            videoEncoder.start()

            // CPU-copy-free Surface pipeline: VirtualDisplay feeds directly into MediaCodec input surface!
            captureEngine.start(projection, encW, encH, dpi, customSurface = encoderSurface)
        } else {
            captureEngine.start(projection, width, height, dpi)
        }

        StreamStateNotifier.updateState(StreamState.STREAMING)
    }

    private fun startStreamForeground(statusText: String, withMediaProjection: Boolean = false) {
        startTimeMs = System.currentTimeMillis()

        try {
            val notification = buildForegroundNotification(statusText)

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                var serviceType = ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
                if (withMediaProjection) {
                    serviceType = serviceType or ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
                }
                startForeground(NOTIFICATION_ID, notification, serviceType)
            } else {
                startForeground(NOTIFICATION_ID, notification)
            }

            StreamStateNotifier.updateState(StreamState.READY)
        } catch (e: Exception) {
            StreamStateNotifier.updateState(StreamState.ERROR)
            stopSelf()
        }
    }

    private fun updateNotificationContent(statusText: String) {
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
        manager?.notify(NOTIFICATION_ID, buildForegroundNotification(statusText))
    }

    private fun stopStreamService() {
        captureEngine.stop()
        videoEncoder.stop()
        StreamStateNotifier.updateState(StreamState.IDLE)
        CaptureStateNotifier.updateState(CaptureState.IDLE)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            stopForeground(STOP_FOREGROUND_REMOVE)
        } else {
            @Suppress("DEPRECATION")
            stopForeground(true)
        }
        stopSelf()
    }

    override fun onDestroy() {
        super.onDestroy()
        captureEngine.stop()
        videoEncoder.stop()
        StreamStateNotifier.updateState(StreamState.IDLE)
        CaptureStateNotifier.updateState(CaptureState.IDLE)
    }

    fun getUptimeMillis(): Long {
        return if (startTimeMs > 0L) System.currentTimeMillis() - startTimeMs else 0L
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                getString(R.string.notification_channel_name),
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = getString(R.string.notification_channel_desc)
                setShowBadge(false)
            }

            val manager = getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
            manager?.createNotificationChannel(channel)
        }
    }

    private fun buildForegroundNotification(contentMessage: String): Notification {
        val launchIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val contentPendingIntent = PendingIntent.getActivity(
            this,
            0,
            launchIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val stopIntent = Intent(this, FairyStreamService::class.java).apply {
            action = ACTION_STOP_SERVICE
        }
        val stopPendingIntent = PendingIntent.getService(
            this,
            1,
            stopIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.notification_title))
            .setContentText(contentMessage)
            .setSmallIcon(R.drawable.ic_dashboard)
            .setContentIntent(contentPendingIntent)
            .addAction(
                R.drawable.ic_stop,
                getString(R.string.notification_action_stop),
                stopPendingIntent
            )
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    companion object {
        const val CHANNEL_ID = "fairy_stream_channel"
        const val NOTIFICATION_ID = 2026
        const val ACTION_START_SERVICE = "com.fairylive.app.action.START_SERVICE"
        const val ACTION_STOP_SERVICE = "com.fairylive.app.action.STOP_SERVICE"
        const val ACTION_START_CAPTURE = "com.fairylive.app.action.START_CAPTURE"
        const val ACTION_PAUSE_CAPTURE = "com.fairylive.app.action.PAUSE_CAPTURE"
        const val ACTION_RESUME_CAPTURE = "com.fairylive.app.action.RESUME_CAPTURE"
        const val ACTION_STOP_CAPTURE = "com.fairylive.app.action.STOP_CAPTURE"
        const val ACTION_UPDATE_RESOLUTION = "com.fairylive.app.action.UPDATE_RESOLUTION"
        const val ACTION_REQUEST_KEYFRAME = "com.fairylive.app.action.REQUEST_KEYFRAME"
        const val ACTION_ADJUST_BITRATE = "com.fairylive.app.action.ADJUST_BITRATE"

        const val EXTRA_RESULT_CODE = "extra_result_code"
        const val EXTRA_RESULT_DATA = "extra_result_data"
        const val EXTRA_WIDTH = "extra_width"
        const val EXTRA_HEIGHT = "extra_height"
        const val EXTRA_DPI = "extra_dpi"
        const val EXTRA_ENABLE_ENCODING = "extra_enable_encoding"
        const val EXTRA_BITRATE_KBPS = "extra_bitrate_kbps"
        const val EXTRA_FPS = "extra_fps"

        fun start(context: Context) {
            val intent = Intent(context, FairyStreamService::class.java).apply {
                action = ACTION_START_SERVICE
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun updateResolution(context: Context, width: Int, height: Int, dpi: Int) {
            val intent = Intent(context, FairyStreamService::class.java).apply {
                action = ACTION_UPDATE_RESOLUTION
                putExtra(EXTRA_WIDTH, width)
                putExtra(EXTRA_HEIGHT, height)
                putExtra(EXTRA_DPI, dpi)
            }
            context.startService(intent)
        }

        fun startCapture(
            context: Context,
            resultCode: Int,
            data: Intent,
            width: Int,
            height: Int,
            dpi: Int,
            enableEncoding: Boolean = true,
            bitrateKbps: Int = 1200,
            fps: Int = 24
        ) {
            val intent = Intent(context, FairyStreamService::class.java).apply {
                action = ACTION_START_CAPTURE
                putExtra(EXTRA_RESULT_CODE, resultCode)
                putExtra(EXTRA_RESULT_DATA, data)
                putExtra(EXTRA_WIDTH, width)
                putExtra(EXTRA_HEIGHT, height)
                putExtra(EXTRA_DPI, dpi)
                putExtra(EXTRA_ENABLE_ENCODING, enableEncoding)
                putExtra(EXTRA_BITRATE_KBPS, bitrateKbps)
                putExtra(EXTRA_FPS, fps)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun requestKeyFrame(context: Context) {
            val intent = Intent(context, FairyStreamService::class.java).apply {
                action = ACTION_REQUEST_KEYFRAME
            }
            context.startService(intent)
        }

        fun adjustBitrate(context: Context, newBitrateKbps: Int) {
            val intent = Intent(context, FairyStreamService::class.java).apply {
                action = ACTION_ADJUST_BITRATE
                putExtra(EXTRA_BITRATE_KBPS, newBitrateKbps)
            }
            context.startService(intent)
        }

        fun pauseCapture(context: Context) {
            val intent = Intent(context, FairyStreamService::class.java).apply {
                action = ACTION_PAUSE_CAPTURE
            }
            context.startService(intent)
        }

        fun resumeCapture(context: Context) {
            val intent = Intent(context, FairyStreamService::class.java).apply {
                action = ACTION_RESUME_CAPTURE
            }
            context.startService(intent)
        }

        fun stopCapture(context: Context) {
            val intent = Intent(context, FairyStreamService::class.java).apply {
                action = ACTION_STOP_CAPTURE
            }
            context.startService(intent)
        }

        fun stop(context: Context) {
            val intent = Intent(context, FairyStreamService::class.java).apply {
                action = ACTION_STOP_SERVICE
            }
            context.startService(intent)
        }
    }
}
