package com.fairylive.app.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.AudioFormat
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Binder
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.fairylive.app.FairyLiveApplication
import com.fairylive.app.MainActivity
import com.fairylive.app.R
import com.fairylive.app.core.audio.AudioPipeline
import com.fairylive.app.core.audio.AudioStateNotifier
import com.fairylive.app.core.audio.model.AudioCaptureConfig
import com.fairylive.app.core.audio.model.AudioCaptureState
import com.fairylive.app.core.audio.model.AudioSourceType
import com.fairylive.app.core.capture.CaptureStateNotifier
import com.fairylive.app.core.capture.ScreenCaptureEngine
import com.fairylive.app.core.capture.model.CaptureState
import com.fairylive.app.core.common.MediaClock
import com.fairylive.app.core.lifecycle.StreamState
import com.fairylive.app.core.lifecycle.StreamStateNotifier
import com.fairylive.app.core.permission.PermissionManager
import com.fairylive.app.core.stream.StreamPipeline
import com.fairylive.app.core.stream.StreamTelemetryNotifier
import com.fairylive.app.core.stream.model.RtmpConnectionState
import com.fairylive.app.core.stream.rtmp.RtmpEndpoint
import com.fairylive.app.core.video.HardwareVideoEncoder
import com.fairylive.app.core.video.VideoEncoderCapabilityManager
import com.fairylive.app.core.video.model.VideoEncoderConfig

/**
 * Foreground service hosting the capture engine, hardware video encoder, and audio pipeline.
 * Manages CPU-copy-free Surface pipeline, low-latency audio synchronization, and RTMP streaming transport.
 */
class FairyStreamService : Service() {

    private val binder = LocalBinder()
    private var startTimeMs: Long = 0L

    val captureEngine = ScreenCaptureEngine()
    val videoEncoder = HardwareVideoEncoder()
    val audioPipeline = AudioPipeline()
    val streamPipeline = StreamPipeline()

    private var activeMediaProjection: MediaProjection? = null

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
                audioPipeline.stop()
                streamPipeline.stop()
                videoEncoder.setEncodedFrameListener(null)
                audioPipeline.setEncodedAudioFrameListener(null)
                MediaClock.stopSession()
                updateNotificationContent("Capture, encoders, and stream stopped")
            }
            ACTION_START_AUDIO -> {
                handleStartAudio(intent)
            }
            ACTION_MUTE_AUDIO -> {
                audioPipeline.mute()
            }
            ACTION_UNMUTE_AUDIO -> {
                audioPipeline.unmute()
            }
            ACTION_STOP_AUDIO -> {
                audioPipeline.stop()
                if (!videoEncoder.isEncoding && !captureEngine.isCapturing) {
                    MediaClock.stopSession()
                }
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
        val enableAudio = intent.getBooleanExtra(EXTRA_ENABLE_AUDIO, true)
        val bitrateKbps = intent.getIntExtra(EXTRA_BITRATE_KBPS, 1200)
        val fps = intent.getIntExtra(EXTRA_FPS, 24)

        // Ensure foreground service is running with MediaProjection & Microphone types
        val statusMsg = if (enableEncoding) "Live H.264/AAC Broadcast (${width}x${height})" else "Screen Capture (${width}x${height})"
        startStreamForeground(statusMsg, withMediaProjection = true)

        val projectionManager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as? MediaProjectionManager
        val projection = projectionManager?.getMediaProjection(resultCode, resultData)

        if (projection == null) {
            CaptureStateNotifier.updateState(CaptureState.ERROR)
            return
        }
        this.activeMediaProjection = projection

        // Ensure canonical session media clock is anchored for shared A/V timestamp synchronization
        if (!MediaClock.isRunning) {
            MediaClock.startSession()
        }

        if (enableEncoding) {
            val selection = VideoEncoderCapabilityManager.findOptimalEncoder()
            val app = applicationContext as? FairyLiveApplication
            val settings = app?.let { it.settingsRepository.loadSettings(it.deviceCapability.recommendedProfile) }
            val profile = settings?.profile ?: com.fairylive.app.core.capability.model.StreamProfile.LOW
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

            // Start audio pipeline concurrently if microphone permission is granted
            if (enableAudio && PermissionManager.hasAudioPermission(this)) {
                val audioConfig = AudioCaptureConfig(
                    sampleRate = 44100,
                    channelConfig = AudioFormat.CHANNEL_IN_MONO,
                    bitrateKbps = 96,
                    sourceType = AudioSourceType.MIC
                )
                audioPipeline.start(audioConfig, projection)
            }

            // Start RTMP streaming transport if enabled
            val enableRtmp = intent?.getBooleanExtra(EXTRA_ENABLE_RTMP, false) ?: false
            val rtmpUrl = intent?.getStringExtra(EXTRA_RTMP_URL) ?: settings?.serverUrl ?: ""
            val streamKey = intent?.getStringExtra(EXTRA_STREAM_KEY) ?: settings?.streamKey ?: ""

            if (enableRtmp && rtmpUrl.isNotEmpty()) {
                val endpoint = RtmpEndpoint.parse(rtmpUrl, streamKey)
                videoEncoder.setEncodedFrameListener { frame ->
                    streamPipeline.onVideoFrame(frame)
                }
                audioPipeline.setEncodedAudioFrameListener { frame ->
                    streamPipeline.onAudioFrame(frame)
                }
                streamPipeline.start(
                    endpoint = endpoint,
                    videoWidth = encW,
                    videoHeight = encH,
                    videoFps = fps,
                    videoBitrateKbps = bitrateKbps,
                    audioSampleRate = 44100,
                    audioBitrateKbps = 96,
                    audioChannels = 1
                )
            }
        } else {
            captureEngine.start(projection, width, height, dpi)
        }

        StreamStateNotifier.updateState(StreamState.STREAMING)
    }

    private fun handleStartAudio(intent: Intent?) {
        if (!PermissionManager.hasAudioPermission(this)) {
            AudioStateNotifier.updateState(AudioCaptureState.ERROR)
            return
        }

        val sampleRate = intent?.getIntExtra(EXTRA_AUDIO_SAMPLE_RATE, 44100) ?: 44100
        val bitrate = intent?.getIntExtra(EXTRA_AUDIO_BITRATE_KBPS, 96) ?: 96
        val isInternal = intent?.getBooleanExtra(EXTRA_AUDIO_INTERNAL, false) ?: false

        val sourceType = if (isInternal && activeMediaProjection != null) {
            AudioSourceType.INTERNAL_PLAYBACK
        } else {
            AudioSourceType.MIC
        }

        val config = AudioCaptureConfig(
            sampleRate = sampleRate,
            channelConfig = AudioFormat.CHANNEL_IN_MONO,
            bitrateKbps = bitrate,
            sourceType = sourceType
        )

        if (!MediaClock.isRunning) {
            MediaClock.startSession()
        }

        audioPipeline.start(config, activeMediaProjection)
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
        audioPipeline.stop()
        streamPipeline.stop()
        videoEncoder.setEncodedFrameListener(null)
        audioPipeline.setEncodedAudioFrameListener(null)
        activeMediaProjection = null
        MediaClock.stopSession()

        StreamStateNotifier.updateState(StreamState.IDLE)
        CaptureStateNotifier.updateState(CaptureState.IDLE)
        AudioStateNotifier.updateState(AudioCaptureState.IDLE)
        StreamTelemetryNotifier.updateState(RtmpConnectionState.IDLE)

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
        audioPipeline.stop()
        streamPipeline.stop()
        videoEncoder.setEncodedFrameListener(null)
        audioPipeline.setEncodedAudioFrameListener(null)
        activeMediaProjection = null
        MediaClock.stopSession()

        StreamStateNotifier.updateState(StreamState.IDLE)
        CaptureStateNotifier.updateState(CaptureState.IDLE)
        AudioStateNotifier.updateState(AudioCaptureState.IDLE)
        StreamTelemetryNotifier.updateState(RtmpConnectionState.IDLE)
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
        const val ACTION_START_AUDIO = "com.fairylive.app.action.START_AUDIO"
        const val ACTION_MUTE_AUDIO = "com.fairylive.app.action.MUTE_AUDIO"
        const val ACTION_UNMUTE_AUDIO = "com.fairylive.app.action.UNMUTE_AUDIO"
        const val ACTION_STOP_AUDIO = "com.fairylive.app.action.STOP_AUDIO"

        const val EXTRA_RESULT_CODE = "extra_result_code"
        const val EXTRA_RESULT_DATA = "extra_result_data"
        const val EXTRA_WIDTH = "extra_width"
        const val EXTRA_HEIGHT = "extra_height"
        const val EXTRA_DPI = "extra_dpi"
        const val EXTRA_ENABLE_ENCODING = "extra_enable_encoding"
        const val EXTRA_ENABLE_AUDIO = "extra_enable_audio"
        const val EXTRA_ENABLE_RTMP = "extra_enable_rtmp"
        const val EXTRA_RTMP_URL = "extra_rtmp_url"
        const val EXTRA_STREAM_KEY = "extra_stream_key"
        const val EXTRA_BITRATE_KBPS = "extra_bitrate_kbps"
        const val EXTRA_FPS = "extra_fps"
        const val EXTRA_AUDIO_BITRATE_KBPS = "extra_audio_bitrate_kbps"
        const val EXTRA_AUDIO_SAMPLE_RATE = "extra_audio_sample_rate"
        const val EXTRA_AUDIO_INTERNAL = "extra_audio_internal"

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
            enableAudio: Boolean = true,
            enableRtmp: Boolean = false,
            rtmpUrl: String = "",
            streamKey: String = "",
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
                putExtra(EXTRA_ENABLE_AUDIO, enableAudio)
                putExtra(EXTRA_ENABLE_RTMP, enableRtmp)
                putExtra(EXTRA_RTMP_URL, rtmpUrl)
                putExtra(EXTRA_STREAM_KEY, streamKey)
                putExtra(EXTRA_BITRATE_KBPS, bitrateKbps)
                putExtra(EXTRA_FPS, fps)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun startAudio(context: Context, sampleRate: Int = 44100, bitrateKbps: Int = 96, isInternal: Boolean = false) {
            val intent = Intent(context, FairyStreamService::class.java).apply {
                action = ACTION_START_AUDIO
                putExtra(EXTRA_AUDIO_SAMPLE_RATE, sampleRate)
                putExtra(EXTRA_AUDIO_BITRATE_KBPS, bitrateKbps)
                putExtra(EXTRA_AUDIO_INTERNAL, isInternal)
            }
            context.startService(intent)
        }

        fun muteAudio(context: Context) {
            val intent = Intent(context, FairyStreamService::class.java).apply {
                action = ACTION_MUTE_AUDIO
            }
            context.startService(intent)
        }

        fun unmuteAudio(context: Context) {
            val intent = Intent(context, FairyStreamService::class.java).apply {
                action = ACTION_UNMUTE_AUDIO
            }
            context.startService(intent)
        }

        fun stopAudio(context: Context) {
            val intent = Intent(context, FairyStreamService::class.java).apply {
                action = ACTION_STOP_AUDIO
            }
            context.startService(intent)
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
