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
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
import android.view.Surface
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
import com.fairylive.app.core.common.CreatorErrorNotifier
import com.fairylive.app.core.common.MediaClock
import com.fairylive.app.core.lifecycle.StreamState
import com.fairylive.app.core.lifecycle.StreamStateNotifier
import com.fairylive.app.core.permission.PermissionManager
import com.fairylive.app.core.scene.model.SceneTransition
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
    val overlayCompositor = com.fairylive.app.core.compositor.OverlayCompositor(this)
    val cameraEngine = com.fairylive.app.core.camera.CameraCaptureEngine(this)

    lateinit var thermalManager: com.fairylive.app.core.optimization.ThermalMitigationManager
    val memoryGuard = com.fairylive.app.core.optimization.MemoryPressureGuard()
    var deviceProfile: com.fairylive.app.core.optimization.model.DeviceProfile? = null

    private var activeMediaProjection: MediaProjection? = null
    private val mainHandler = Handler(Looper.getMainLooper())
    private val isStopping = java.util.concurrent.atomic.AtomicBoolean(false)
    private var floatingHudManager: com.fairylive.app.ui.hud.StreamerFloatingHudManager? = null

    @Volatile
    var isStreamPausedState: Boolean = false
        private set

    @Volatile
    var isUserMicMutedState: Boolean = false
        private set

    @Synchronized
    private fun syncAudioMuteState() {
        audioPipeline.setMicMuted(isUserMicMutedState)
        audioPipeline.setStreamPaused(isStreamPausedState)
        // Decouple microphone from video BRB:
        // Do NOT call audioPipeline.mute() on stream pause. Only user mic mute button controls mic.
        // Unmute the master pipeline so creator's voice continues streaming cleanly even when game is paused/minimized.
        if (isUserMicMutedState && (isStreamPausedState || com.fairylive.app.core.audio.AudioStudioRepository.isGameMuted)) {
            audioPipeline.setMicMuted(true)
        }
        audioPipeline.unmute()
        Log.i("FairyStreamService", "syncAudioMuteState: gamePaused=$isStreamPausedState, userMicMuted=$isUserMicMutedState")
    }

    inner class LocalBinder : Binder() {
        fun getService(): FairyStreamService = this@FairyStreamService
    }

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onCreate() {
        super.onCreate()
        activeService = this
        createNotificationChannel()

        deviceProfile = com.fairylive.app.core.optimization.DeviceProfileDetector.detect(this)
        Log.i("FairyStreamService", "Detected DeviceProfile: tier=${deviceProfile?.tier}, ram=${deviceProfile?.totalRamMb}MB, cores=${deviceProfile?.availableCores}")

        thermalManager = com.fairylive.app.core.optimization.ThermalMitigationManager(this).apply {
            addListener(object : com.fairylive.app.core.optimization.ThermalMitigationManager.ThermalListener {
                override fun onThermalLevelChanged(
                    level: com.fairylive.app.core.optimization.model.ThermalThrottlingLevel,
                    rawStatus: Int
                ) {
                    overlayCompositor.fpsThrottler.updateThermalLevel(level)
                    val ceilingKbps = when (level) {
                        com.fairylive.app.core.optimization.model.ThermalThrottlingLevel.NOMINAL -> 1200
                        com.fairylive.app.core.optimization.model.ThermalThrottlingLevel.MODERATE -> 800
                        com.fairylive.app.core.optimization.model.ThermalThrottlingLevel.SEVERE -> 500
                        com.fairylive.app.core.optimization.model.ThermalThrottlingLevel.CRITICAL -> 300
                    }
                    val decision = streamPipeline.abrController.setThermalBitrateCeiling(ceilingKbps)
                    if (decision != null) {
                        videoEncoder.adjustBitrate(decision.newBitrateKbps)
                        if (decision.requestKeyFrame) {
                            videoEncoder.requestKeyFrame()
                        }
                    }
                }
            })
            start()
        }

        memoryGuard.addListener(object : com.fairylive.app.core.optimization.MemoryPressureGuard.MemoryTrimListener {
            override fun onMemoryTrim(level: Int) {
                overlayCompositor.trimMemory(level)
            }
        })
        registerComponentCallbacks(memoryGuard)
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
                isStreamPausedState = true
                captureEngine.pause()
                overlayCompositor.setStreamPaused(true)
                syncAudioMuteState()
                Log.i("FairyStreamService", "Stream paused: Screen capture paused, pause video active, audio muted")
            }
            ACTION_RESUME_CAPTURE -> {
                isStreamPausedState = false
                overlayCompositor.setStreamPaused(false)
                captureEngine.resume()
                syncAudioMuteState()
                Log.i("FairyStreamService", "Stream resumed: Normal broadcast restored")
            }
            ACTION_MUTE_MIC -> {
                isUserMicMutedState = true
                syncAudioMuteState()
            }
            ACTION_UNMUTE_MIC -> {
                isUserMicMutedState = false
                syncAudioMuteState()
            }
            ACTION_TOGGLE_MIC -> {
                isUserMicMutedState = !isUserMicMutedState
                syncAudioMuteState()
            }
            ACTION_STOP_CAPTURE -> {
                stopStream(
                    com.fairylive.app.core.lifecycle.StreamStopReason.STOP_REASON_USER,
                    "User stopped stream from UI"
                )
            }
            ACTION_TOGGLE_FACECAM -> {
                toggleFacecam()
            }
            ACTION_SWITCH_FACECAM -> {
                switchFacecam()
            }
            ACTION_UPDATE_FACECAM -> {
                val x = intent?.getFloatExtra(EXTRA_FACECAM_X, -1f) ?: -1f
                val y = intent?.getFloatExtra(EXTRA_FACECAM_Y, -1f) ?: -1f
                val scale = intent?.getFloatExtra(EXTRA_FACECAM_SCALE, -1f) ?: -1f
                var cur = overlayCompositor.facecamConfig
                if (x >= 0f) cur = cur.copy(normalizedX = x)
                if (y >= 0f) cur = cur.copy(normalizedY = y)
                if (scale > 0f) cur = cur.copy(scale = scale)
                overlayCompositor.updateFacecamConfig(cur)
            }
            ACTION_SWITCH_SCENE -> {
                val sceneId = intent?.getStringExtra(EXTRA_SCENE_ID) ?: ""
                val transitionTypeStr = intent?.getStringExtra(EXTRA_TRANSITION) ?: "CUT"
                val transition = when (transitionTypeStr.uppercase()) {
                    "CROSSFADE" -> SceneTransition.CROSSFADE
                    else -> SceneTransition.CUT
                }
                val switched = overlayCompositor.switchScene(sceneId, transition)
                val tele = StreamTelemetryNotifier.currentTelemetry
                val connState = StreamTelemetryNotifier.currentState
                val videoCodecActive = videoEncoder.isEncoding
                val audioCodecActive = audioPipeline.audioEncoder.isEncoding
                Log.i(
                    "FairyStreamService",
                    "[M11_CONTINUITY_AUDIT] sceneId=$sceneId transition=$transitionTypeStr switched=$switched " +
                    "connState=$connState isPublishing=${connState.isStreaming} reconnects=${tele.reconnectAttempts} " +
                    "videoCodecActive=$videoCodecActive audioCodecActive=$audioCodecActive " +
                    "vSent=${tele.videoFramesSent} aSent=${tele.audioFramesSent} drops=${tele.droppedFrames} " +
                    "bytesSent=${tele.bytesSent} timeMs=${System.currentTimeMillis()}"
                )
            }
            ACTION_START_AUDIO -> {
                handleStartAudio(intent)
            }
            ACTION_MUTE_AUDIO -> {
                isUserMicMutedState = true
                syncAudioMuteState()
            }
            ACTION_UNMUTE_AUDIO -> {
                isUserMicMutedState = false
                syncAudioMuteState()
            }
            ACTION_SET_AUDIO_VOLUMES -> {
                val micVol = intent?.getFloatExtra(EXTRA_MIC_VOLUME, -1f) ?: -1f
                val gameVol = intent?.getFloatExtra(EXTRA_GAME_VOLUME, -1f) ?: -1f
                val mediaVol = intent?.getFloatExtra(EXTRA_MEDIA_VOLUME, -1f) ?: -1f
                val masterVol = intent?.getFloatExtra(EXTRA_MASTER_VOLUME, -1f) ?: -1f

                if (micVol >= 0f) audioPipeline.setMicVolume(micVol)
                if (gameVol >= 0f) audioPipeline.setGameVolume(gameVol)
                if (mediaVol >= 0f) audioPipeline.setMediaVolume(mediaVol)
                if (masterVol >= 0f) audioPipeline.setMasterVolume(masterVol)

                val micMutedVal = if (intent != null && intent.hasExtra(EXTRA_MIC_MUTED)) intent.getBooleanExtra(EXTRA_MIC_MUTED, false) else null
                val gameMutedVal = if (intent != null && intent.hasExtra(EXTRA_GAME_MUTED)) intent.getBooleanExtra(EXTRA_GAME_MUTED, false) else null
                val mediaMutedVal = if (intent != null && intent.hasExtra(EXTRA_MEDIA_MUTED)) intent.getBooleanExtra(EXTRA_MEDIA_MUTED, false) else null

                micMutedVal?.let { audioPipeline.setMicMuted(it) }
                gameMutedVal?.let { audioPipeline.setGameMuted(it) }
                mediaMutedVal?.let { audioPipeline.setMediaMuted(it) }

                com.fairylive.app.core.audio.AudioStudioRepository.saveVolumes(
                    context = this,
                    mic = if (micVol >= 0f) micVol else null,
                    game = if (gameVol >= 0f) gameVol else null,
                    media = if (mediaVol >= 0f) mediaVol else null,
                    master = if (masterVol >= 0f) masterVol else null,
                    micMuted = micMutedVal,
                    gameMuted = gameMutedVal,
                    mediaMuted = mediaMutedVal
                )

                if (intent != null && intent.hasExtra(EXTRA_DSP_NOISE_GATE)) {
                    val noise = intent.getBooleanExtra(EXTRA_DSP_NOISE_GATE, true)
                    val godMode = intent.getBooleanExtra(EXTRA_DSP_GOD_MODE_NOISE, com.fairylive.app.core.audio.AudioStudioRepository.isGodModeNoiseEnabled)
                    val noiseLevel = if (intent.hasExtra(EXTRA_DSP_NOISE_LEVEL)) intent.getIntExtra(EXTRA_DSP_NOISE_LEVEL, 75) else null
                    val agc = intent.getBooleanExtra(EXTRA_DSP_AGC, false)
                    val comp = intent.getBooleanExtra(EXTRA_DSP_COMPRESSOR, true)
                    val lim = intent.getBooleanExtra(EXTRA_DSP_LIMITER, true)
                    audioPipeline.setDspFilters(noise, agc, comp, lim, godMode, noiseLevel)
                    com.fairylive.app.core.audio.AudioStudioRepository.saveDsp(this, noise, godMode, agc, comp, lim, noiseLevel)
                }

                val presetName = intent?.getStringExtra(EXTRA_AUDIO_PRESET)
                if (presetName != null) {
                    try {
                        val preset = com.fairylive.app.core.audio.dsp.AudioMixerProcessor.AudioPreset.valueOf(presetName)
                        audioPipeline.applyPreset(preset)
                        com.fairylive.app.core.audio.AudioStudioRepository.savePreset(this, presetName)
                    } catch (_: Exception) {}
                }
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
            ACTION_TRIGGER_ALERT -> {
                overlayCompositor.triggerAlert(
                    headline = intent?.getStringExtra(EXTRA_ALERT_HEADLINE) ?: "NEW FOLLOWER!",
                    subtext = intent?.getStringExtra(EXTRA_ALERT_SUBTEXT) ?: "Alex Live joined the stream!",
                    durationMs = intent?.getLongExtra(EXTRA_ALERT_DURATION, 4000L) ?: 4000L,
                    soundAsset = "asset://alert_chime.wav"
                )
            }
            ACTION_SET_SCALE_MODE -> {
                val modeName = intent?.getStringExtra(EXTRA_SCALE_MODE) ?: com.fairylive.app.core.capture.model.ScaleMode.FIT.name
                val mode = try {
                    com.fairylive.app.core.capture.model.ScaleMode.valueOf(modeName)
                } catch (_: Exception) {
                    com.fairylive.app.core.capture.model.ScaleMode.FIT
                }
                overlayCompositor.setScaleMode(mode)
                Log.i("FairyStreamService", "Scale mode updated to $mode")
            }
        }

        return START_STICKY
    }

    private fun handleStopCapture() {
        stopStream(
            com.fairylive.app.core.lifecycle.StreamStopReason.STOP_REASON_USER,
            "handleStopCapture called"
        )
    }

    fun stopStream(
        reason: com.fairylive.app.core.lifecycle.StreamStopReason,
        details: String = ""
    ) {
        if (isStopping.getAndSet(true)) {
            return
        }
        com.fairylive.app.core.lifecycle.StreamLifecycleDiagnostics.streamStopRequested(reason, "FairyStreamService")
        com.fairylive.app.core.lifecycle.StreamLifecycleDiagnostics.streamStopReason(reason, details)

        cameraEngine.stop()
        overlayCompositor.updateFacecamConfig(overlayCompositor.facecamConfig.copy(enabled = false))
        captureEngine.stop()
        overlayCompositor.stop()
        videoEncoder.stop()
        audioPipeline.stop()
        streamPipeline.stop()
        com.fairylive.app.core.youtube.YouTubeLiveIntegrationService.stopLiveChatPolling()
        videoEncoder.setEncodedFrameListener(null)
        videoEncoder.onSpsPpsAvailable = null
        audioPipeline.setEncodedAudioFrameListener(null)
        audioPipeline.onAudioSpecificConfigAvailable = null
        MediaClock.stopSession()
        try {
            activeMediaProjection?.stop()
        } catch (_: Exception) {}
        activeMediaProjection = null

        mainHandler.post {
            floatingHudManager?.dismiss()
            floatingHudManager = null
        }

        com.fairylive.app.core.lifecycle.StreamLifecycleDiagnostics.projectionStopped()
        com.fairylive.app.core.lifecycle.StreamLifecycleDiagnostics.encoderStopped()
        com.fairylive.app.core.lifecycle.StreamLifecycleDiagnostics.rtmpDisconnected()

        if (reason.isUserInitiated) {
            StreamStateNotifier.updateState(StreamState.IDLE)
            CaptureStateNotifier.updateState(CaptureState.IDLE)
            AudioStateNotifier.updateState(AudioCaptureState.IDLE)
            StreamTelemetryNotifier.updateState(RtmpConnectionState.IDLE)
            updateNotificationContent("Capture and stream stopped")
        } else {
            StreamStateNotifier.updateState(StreamState.ERROR)
            CaptureStateNotifier.updateState(CaptureState.ERROR)
            AudioStateNotifier.updateState(AudioCaptureState.ERROR)
            StreamTelemetryNotifier.updateState(RtmpConnectionState.FAILED)
            updateNotificationContent("Stream ended: $details")
            com.fairylive.app.core.common.CreatorErrorNotifier.postError(
                title = "Broadcast Stopped",
                message = "The live broadcast stopped: $details ($reason)",
                resolution = "Verify stream settings and tap GO LIVE to resume."
            )
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            stopForeground(STOP_FOREGROUND_REMOVE)
        } else {
            @Suppress("DEPRECATION")
            stopForeground(true)
        }
        stopSelf()
    }

    private fun handleStartCapture(intent: Intent) {
        isStopping.set(false)
        // Guard against duplicate start bursts or re-entrancy
        if (captureEngine.isCapturing || videoEncoder.isEncoding) {
            Log.w("FairyStreamService", "Capture session already active during start request — recycling previous session")
            handleStopCapture()
        }

        val resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, 0)
        val resultData = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent.getParcelableExtra(EXTRA_RESULT_DATA, Intent::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent.getParcelableExtra(EXTRA_RESULT_DATA)
        }

        if (resultCode == 0 || resultData == null) {
            CaptureStateNotifier.updateState(CaptureState.ERROR)
            com.fairylive.app.core.common.CreatorErrorNotifier.postError(
                title = "Screen Capture Not Available",
                message = "The screen capture session could not be established (no projection token).",
                resolution = "Tap GO LIVE again and grant screen recording permission."
            )
            return
        }

        val rawW = intent.getIntExtra(EXTRA_WIDTH, 1080).coerceIn(320, 3840)
        val rawH = intent.getIntExtra(EXTRA_HEIGHT, 1920).coerceIn(240, 3840)
        val width = (rawW / 2) * 2
        val height = (rawH / 2) * 2
        val dpi = intent.getIntExtra(EXTRA_DPI, 420).coerceIn(120, 640)
        val enableEncoding = intent.getBooleanExtra(EXTRA_ENABLE_ENCODING, true)
        val enableAudio = intent.getBooleanExtra(EXTRA_ENABLE_AUDIO, true)
        val enableCompositor = intent.getBooleanExtra(EXTRA_ENABLE_COMPOSITOR, false)
        val bitrateKbps = intent.getIntExtra(EXTRA_BITRATE_KBPS, 2500).coerceIn(200, 8000)
        val fps = intent.getIntExtra(EXTRA_FPS, 24).coerceIn(10, 60)

        // Ensure foreground service is running with MediaProjection & Microphone types
        val statusMsg = if (enableEncoding) "Live H.264/AAC Broadcast (${width}x${height})" else "Screen Capture (${width}x${height})"
        startStreamForeground(statusMsg, withMediaProjection = true)

        // Consume projection token from foreground service context (required on API 34+)
        val projectionManager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as? MediaProjectionManager
        val projection = projectionManager?.getMediaProjection(resultCode, resultData)

        if (projection == null) {
            CaptureStateNotifier.updateState(CaptureState.ERROR)
            com.fairylive.app.core.common.CreatorErrorNotifier.postError(
                title = "Screen Capture Not Available",
                message = "The screen capture session could not be established (null MediaProjection).",
                resolution = "Tap GO LIVE again and grant screen recording permission."
            )
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

            // Register projection lifecycle callback BEFORE creating the virtual display.
            // Android 15 app-selector single-app recording stops the projection if no callback
            // is registered — this is what causes the null VirtualDisplay on API 35.
            projection.registerCallback(object : android.media.projection.MediaProjection.Callback() {
                override fun onStop() {
                    Log.i("FairyStreamService", "MediaProjection stopped (game closed/swiped from recents) -> Showing Be Right Back banner; Mic remains continuously connected (Discord VC mode)")
                    mainHandler.post {
                        isStreamPausedState = true
                        overlayCompositor.setStreamPaused(true)
                        syncAudioMuteState()
                        // Ensure foreground service transitions to persistent MICROPHONE service so Android 14/15 keeps audio alive indefinitely
                        if (Build.VERSION.SDK_INT >= 34) {
                            try {
                                val notification = buildForegroundNotification("Broadcasting • Mic Live (Game Paused)")
                                startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
                            } catch (e: Exception) {
                                Log.w("FairyStreamService", "Failed to update foreground service type to MICROPHONE: ${e.message}")
                            }
                        }
                        CaptureStateNotifier.updateState(CaptureState.PAUSED)
                        floatingHudManager?.displayEphemeralMessage(
                            "FairyLive",
                            "Game closed — Be Right Back banner is active on stream, Mic stays live",
                            isSystem = true
                        )
                    }
                }
                override fun onCapturedContentResize(width: Int, height: Int) {
                    Log.i("FairyStreamService", "[CAPTURE-CHAIN] Projection resized callback: ${width}x${height}")
                }
                override fun onCapturedContentVisibilityChanged(visible: Boolean) {
                    mainHandler.post {
                        handleVisibilityChange(visible, "MediaProjection.Callback")
                    }
                }
            }, mainHandler)

            // CPU-copy-free Surface pipeline:
            //   compositor ENABLED:  VirtualDisplay -> compositor SurfaceTexture -> GLES -> encoder input Surface
            //   compositor DISABLED: VirtualDisplay -> encoder input Surface directly (M6 zero-copy passthrough)
            val captureSurface: Surface
            if (enableCompositor) {
                try {
                    captureSurface = overlayCompositor.start(encoderSurface, encW, encH, fps)
                    overlayCompositor.onAutoResumeRequested = {
                        mainHandler.post {
                            if (isStreamPausedState) {
                                Log.i("FairyStreamService", "Self-healing: Game frame arrived on SurfaceTexture -> Auto-resuming from BRB pause")
                                isStreamPausedState = false
                                overlayCompositor.setStreamPaused(false)
                                syncAudioMuteState()
                            }
                        }
                    }
                    overlayCompositor.updateDimensions(width, height)
                    val activeScene = app?.sceneManager?.activeScene
                    if (activeScene != null) {
                        overlayCompositor.applyScene(activeScene)
                        Log.i("FairyStreamService", "Loaded active creator scene: ${activeScene.name} (${activeScene.overlayElements.size} overlays)")
                    } else {
                        overlayCompositor.clearOverlays()
                    }
                } catch (e: Exception) {
                    Log.e("FairyStreamService", "Compositor start failed: ${e.message}")
                    com.fairylive.app.core.common.CreatorErrorNotifier.postError(
                        title = "Overlay Compositor Failed",
                        message = "The broadcast compositor could not initialize (${e.message}).",
                        resolution = "Disable overlays in the Dashboard and retry."
                    )
                    stopStreamService()
                    return
                }
                Log.i("FairyStreamService", "Compositor ENABLED — capture SurfaceTexture → GLES → encoder")
                val enableFacecam = intent.getBooleanExtra(EXTRA_ENABLE_FACECAM, false)
                if (enableFacecam) {
                    setFacecamEnabled(true)
                }
            } else {
                captureSurface = encoderSurface
            }

            captureEngine.onResolutionChanged = { newW, newH ->
                overlayCompositor.updateDimensions(newW, newH)
            }
            captureEngine.onVisibilityChanged = { visible ->
                overlayCompositor.onVisibilityChanged(visible)
                mainHandler.post {
                    handleVisibilityChange(visible, "captureEngine")
                }
            }

            captureEngine.start(
                projection,
                width,
                height,
                dpi,
                customSurface = captureSurface,
                captureSource = settings?.captureSource ?: com.fairylive.app.core.settings.CaptureSource.SINGLE_APP,
                context = this
            )

            if (!captureEngine.isCapturing || CaptureStateNotifier.currentState == CaptureState.ERROR) {
                // VirtualDisplay creation failed — do not publish a video-less stream.
                Log.e("FairyStreamService", "Capture engine failed to start; aborting broadcast")
                val isSingleApp = (settings?.captureSource ?: com.fairylive.app.core.settings.CaptureSource.SINGLE_APP) == com.fairylive.app.core.settings.CaptureSource.SINGLE_APP
                com.fairylive.app.core.common.CreatorErrorNotifier.postError(
                    title = if (isSingleApp) "Single-App Capture Unavailable" else "Capture Failed to Start",
                    message = if (isSingleApp)
                        "Your device could not bind to the selected app window. Please select 'Entire Screen' in Settings for 100% compatible streaming."
                    else
                        "Android could not create the virtual display for screen capture.",
                    resolution = if (isSingleApp)
                        "Open Settings -> Change Capture Source to 'Entire Screen' -> Tap GO LIVE."
                    else
                        "Tap GO LIVE again to retry. If this persists, force-stop and reopen the app."
                )
                stopStreamService()
                return
            }

            val audioBitrate = settings?.audioBitrateKbps ?: 128
            // Start audio pipeline concurrently if microphone permission is granted
            if (enableAudio && PermissionManager.hasAudioPermission(this)) {
                com.fairylive.app.core.audio.AudioStudioRepository.init(this)
                val audioConfig = AudioCaptureConfig(
                    sampleRate = 44100,
                    channelConfig = AudioFormat.CHANNEL_IN_MONO,
                    bitrateKbps = audioBitrate,
                    sourceType = AudioSourceType.MIC
                )
                audioPipeline.start(audioConfig, projection)
                audioPipeline.setMicVolume(com.fairylive.app.core.audio.AudioStudioRepository.micVolume)
                audioPipeline.setGameVolume(com.fairylive.app.core.audio.AudioStudioRepository.gameVolume)
                audioPipeline.setMediaVolume(com.fairylive.app.core.audio.AudioStudioRepository.mediaVolume)
                audioPipeline.setMasterVolume(com.fairylive.app.core.audio.AudioStudioRepository.masterVolume)
                audioPipeline.setDspFilters(
                    noiseGate = com.fairylive.app.core.audio.AudioStudioRepository.isNoiseSuppressionEnabled,
                    agc = com.fairylive.app.core.audio.AudioStudioRepository.isAgcEnabled,
                    compressor = com.fairylive.app.core.audio.AudioStudioRepository.isCompressorEnabled,
                    limiter = com.fairylive.app.core.audio.AudioStudioRepository.isLimiterEnabled,
                    godMode = com.fairylive.app.core.audio.AudioStudioRepository.isGodModeNoiseEnabled,
                    noiseLevel = com.fairylive.app.core.audio.AudioStudioRepository.noiseSuppressionLevel
                )
                audioPipeline.setAutoDucking(com.fairylive.app.core.audio.AudioStudioRepository.isAutoDuckingEnabled)
                syncAudioMuteState()
            }

            // Start RTMP streaming transport if enabled
            val enableRtmp = intent.getBooleanExtra(EXTRA_ENABLE_RTMP, false)
            val rtmpUrl = intent.getStringExtra(EXTRA_RTMP_URL) ?: settings?.serverUrl ?: ""
            val streamKey = intent.getStringExtra(EXTRA_STREAM_KEY) ?: settings?.streamKey ?: ""

            if (enableRtmp) {
                val destination = settings?.destination ?: com.fairylive.app.core.settings.StreamDestination.YOUTUBE
                val validation = com.fairylive.app.core.settings.StreamConfigurationValidator.validate(rtmpUrl, streamKey, destination)
                if (!validation.isValid) {
                    val err = validation as com.fairylive.app.core.settings.StreamConfigurationValidator.ValidationResult.Error
                    val parsedHost = try { java.net.URI(rtmpUrl.trim()).host } catch (_: Exception) { null }
                    val isDestError = err.technicalReason.contains("Localhost", ignoreCase = true)
                    val errType = when {
                        streamKey.isNullOrBlank() -> com.fairylive.app.core.stream.model.StreamErrorType.EMPTY_STREAM_KEY
                        err.technicalReason.contains("protocol", ignoreCase = true) -> com.fairylive.app.core.stream.model.StreamErrorType.INVALID_URL_SCHEME
                        isDestError -> com.fairylive.app.core.stream.model.StreamErrorType.MALFORMED_URL
                        else -> com.fairylive.app.core.stream.model.StreamErrorType.MALFORMED_URL
                    }
                    val diag = com.fairylive.app.core.stream.model.StreamDiagnosticInfo.create(
                        errorType = errType,
                        host = parsedHost,
                        streamKey = streamKey
                    )
                    val title = if (isDestError) "Invalid Streaming Destination" else "Invalid Stream Configuration"
                    val resolution = if (isDestError) "Open Settings and ensure YouTube Live destination is selected." else "Configure a valid RTMP URL and stream key in Settings."
                    com.fairylive.app.core.common.CreatorErrorNotifier.postError(
                        title = title,
                        message = err.userMessage,
                        resolution = resolution,
                        diagnosticInfo = diag
                    )
                    stopStreamService()
                    return
                }

                StreamStateNotifier.updateState(StreamState.STARTING)

                val endpoint = RtmpEndpoint.parse(rtmpUrl, streamKey)
                videoEncoder.setEncodedFrameListener { frame ->
                    streamPipeline.onVideoFrame(frame)
                }
                videoEncoder.onSpsPpsAvailable = { sps, pps ->
                    streamPipeline.setSpsPps(sps, pps)
                }
                audioPipeline.setEncodedAudioFrameListener { frame ->
                    streamPipeline.onAudioFrame(frame)
                }
                audioPipeline.onAudioSpecificConfigAvailable = { config ->
                    streamPipeline.setAudioSpecificConfig(config)
                }

                // Wire ABR dynamic adjustments to the hardware video encoder
                streamPipeline.onAdjustBitrate = { newKbps ->
                    videoEncoder.adjustBitrate(newKbps)
                }
                streamPipeline.onRequestKeyFrame = {
                    videoEncoder.requestKeyFrame()
                }

                // Register network quality monitor with Android ConnectivityManager
                streamPipeline.networkMonitor.register(applicationContext)

                // Listen to transport state to reflect genuine LIVE status
                StreamTelemetryNotifier.addStateListener(object : com.fairylive.app.core.stream.StreamTelemetryNotifier.StateListener {
                    override fun onConnectionStateChanged(
                        newState: RtmpConnectionState,
                        previousState: RtmpConnectionState
                    ) {
                        when (newState) {
                            RtmpConnectionState.PUBLISHING -> {
                                StreamStateNotifier.updateState(StreamState.STREAMING)
                                com.fairylive.app.core.lifecycle.StreamLifecycleDiagnostics.streamRunning()
                                val ytUrl = intent.getStringExtra(EXTRA_YOUTUBE_CHAT_URL) ?: settings?.youtubeLiveUrl ?: ""
                                if (ytUrl.isNotBlank()) {
                                    Log.i("FairyStreamService", "Auto-starting YouTube Live Chat for: $ytUrl")
                                    com.fairylive.app.core.youtube.YouTubeLiveIntegrationService.startLiveChatPolling(ytUrl)
                                }
                                mainHandler.post {
                                    if (floatingHudManager == null && android.provider.Settings.canDrawOverlays(this@FairyStreamService)) {
                                        floatingHudManager = com.fairylive.app.ui.hud.StreamerFloatingHudManager(this@FairyStreamService)
                                        floatingHudManager?.show()
                                    }
                                }
                            }
                            RtmpConnectionState.ERROR,
                            RtmpConnectionState.FAILED -> {
                                stopStream(
                                    com.fairylive.app.core.lifecycle.StreamStopReason.STOP_REASON_RTMP_ERROR,
                                    "RTMP connection failed or rejected by server"
                                )
                            }
                            RtmpConnectionState.RECONNECTING -> StreamStateNotifier.updateState(StreamState.STARTING)
                            RtmpConnectionState.DISCONNECTED -> StreamStateNotifier.updateState(StreamState.STARTING)
                            RtmpConnectionState.STOPPING -> StreamStateNotifier.updateState(StreamState.STOPPING)
                            RtmpConnectionState.IDLE -> {
                                if (!videoEncoder.isEncoding && !captureEngine.isCapturing) {
                                    StreamStateNotifier.updateState(StreamState.IDLE)
                                }
                            }
                            else -> {}
                        }
                    }
                })

                streamPipeline.start(
                    endpoint = endpoint,
                    videoWidth = encW,
                    videoHeight = encH,
                    videoFps = fps,
                    videoBitrateKbps = bitrateKbps,
                    audioSampleRate = 44100,
                    audioBitrateKbps = audioBitrate,
                    audioChannels = 1
                )
            } else {
                StreamStateNotifier.updateState(StreamState.STREAMING)
            }
        } else {
            captureEngine.start(projection, width, height, dpi)
            StreamStateNotifier.updateState(StreamState.STREAMING)
        }
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

        // Apply persisted Audio Studio settings upon audio startup
        com.fairylive.app.core.audio.AudioStudioRepository.init(this)
        audioPipeline.setMicVolume(com.fairylive.app.core.audio.AudioStudioRepository.micVolume)
        audioPipeline.setGameVolume(com.fairylive.app.core.audio.AudioStudioRepository.gameVolume)
        audioPipeline.setMediaVolume(com.fairylive.app.core.audio.AudioStudioRepository.mediaVolume)
        audioPipeline.setMasterVolume(com.fairylive.app.core.audio.AudioStudioRepository.masterVolume)
        audioPipeline.setMicMuted(com.fairylive.app.core.audio.AudioStudioRepository.isMicMuted)
        audioPipeline.setGameMuted(com.fairylive.app.core.audio.AudioStudioRepository.isGameMuted)
        audioPipeline.setMediaMuted(com.fairylive.app.core.audio.AudioStudioRepository.isMediaMuted)
        audioPipeline.setDspFilters(
            noiseGate = com.fairylive.app.core.audio.AudioStudioRepository.isNoiseSuppressionEnabled,
            agc = com.fairylive.app.core.audio.AudioStudioRepository.isAgcEnabled,
            compressor = com.fairylive.app.core.audio.AudioStudioRepository.isCompressorEnabled,
            limiter = com.fairylive.app.core.audio.AudioStudioRepository.isLimiterEnabled,
            godMode = com.fairylive.app.core.audio.AudioStudioRepository.isGodModeNoiseEnabled
        )
        audioPipeline.setAutoDucking(com.fairylive.app.core.audio.AudioStudioRepository.isAutoDuckingEnabled)
    }

    private fun startStreamForeground(statusText: String, withMediaProjection: Boolean = false) {
        startTimeMs = System.currentTimeMillis()

        try {
            val notification = buildForegroundNotification(statusText)

            if (Build.VERSION.SDK_INT >= 34) { // Android 14+ (UPSIDE_DOWN_CAKE)
                val hasAudio = PermissionManager.hasAudioPermission(this)
                val serviceType = if (withMediaProjection) {
                    if (hasAudio) {
                        ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION or ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
                    } else {
                        ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
                    }
                } else {
                    if (hasAudio) {
                        ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
                    } else {
                        ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
                    }
                }
                try {
                    startForeground(NOTIFICATION_ID, notification, serviceType)
                } catch (se: SecurityException) {
                    Log.w("FairyStreamService", "startForeground with requested type ($serviceType) failed: ${se.message}")
                    // Fallback to MICROPHONE or basic foreground service
                    if (hasAudio) {
                        startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
                    } else {
                        startForeground(NOTIFICATION_ID, notification)
                    }
                }
            } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
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
            Log.e("FairyStreamService", "startForeground failed with exception!", e)
            StreamStateNotifier.updateState(StreamState.ERROR)
            stopSelf()
        }
    }

    private var pendingVisibilityPauseRunnable: Runnable? = null

    private fun handleVisibilityChange(visible: Boolean, source: String) {
        Log.i("FairyStreamService", "[CAPTURE-CHAIN] handleVisibilityChange(visible=$visible, source=$source)")
        pendingVisibilityPauseRunnable?.let { mainHandler.removeCallbacks(it) }
        pendingVisibilityPauseRunnable = null

        if (visible) {
            Log.i("FairyStreamService", "Game visible -> Resuming broadcast from $source")
            isStreamPausedState = false
            overlayCompositor.setStreamPaused(false)
            syncAudioMuteState()
        } else {
            // Debounce pause: On OEM devices (Xiaomi Game Turbo, Realme Game Space, Samsung Game Booster, Floating HUD),
            // a floating overlay triggers a bogus onCapturedContentVisibilityChanged(false).
            // Check if capture frames are still actively arriving before showing Be Right Back!
            val initialFrames = maxOf(overlayCompositor.totalCaptureFrames, captureEngine.totalFramesCaptured)
            val runnable = Runnable {
                val currentFrames = maxOf(overlayCompositor.totalCaptureFrames, captureEngine.totalFramesCaptured)
                if (currentFrames > initialFrames + 2) {
                    Log.i("FairyStreamService", "Suppressed false-alarm visibility=false: Game frames actively arriving ($initialFrames -> $currentFrames)")
                    isStreamPausedState = false
                    overlayCompositor.setStreamPaused(false)
                } else {
                    Log.i("FairyStreamService", "Game truly hidden/minimized (no new frames: $initialFrames -> $currentFrames) -> Showing Be Right Back banner")
                    isStreamPausedState = true
                    overlayCompositor.setStreamPaused(true)
                    syncAudioMuteState()
                }
            }
            pendingVisibilityPauseRunnable = runnable
            mainHandler.postDelayed(runnable, 1200L)
        }
    }

    private fun updateNotificationContent(statusText: String) {
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
        manager?.notify(NOTIFICATION_ID, buildForegroundNotification(statusText))
    }

    private fun stopStreamService() {
        pendingVisibilityPauseRunnable?.let { mainHandler.removeCallbacks(it) }
        pendingVisibilityPauseRunnable = null
        cameraEngine.stop()
        overlayCompositor.updateFacecamConfig(overlayCompositor.facecamConfig.copy(enabled = false))
        captureEngine.stop()
        overlayCompositor.stop()
        videoEncoder.stop()
        audioPipeline.stop()
        streamPipeline.stop()
        videoEncoder.setEncodedFrameListener(null)
        videoEncoder.onSpsPpsAvailable = null
        audioPipeline.setEncodedAudioFrameListener(null)
        audioPipeline.onAudioSpecificConfigAvailable = null
        try {
            activeMediaProjection?.stop()
        } catch (_: Exception) {}
        activeMediaProjection = null
        MediaClock.stopSession()

        // Session-end diagnostic: audio-path drop counters (M8 revalidation).
        Log.i(
            "FairyStreamService",
            "AUDIO-DIAG capture.pcmDropped=${audioPipeline.captureEngine.pcmDroppedCount.sum()} " +
                "encoder.inputTimeout=${audioPipeline.audioEncoder.inputTimeoutCount.sum()} " +
                "queueDropped=${streamPipeline.packetQueueDropped} " +
                "muxedV=${streamPipeline.muxedVideoTagCount} muxedA=${streamPipeline.muxedAudioTagCount} " +
                "sentV=${streamPipeline.videoFramesSent} sentA=${streamPipeline.audioFramesSent}"
        )

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
        if (::thermalManager.isInitialized) {
            thermalManager.stop()
        }
        unregisterComponentCallbacks(memoryGuard)

        cameraEngine.stop()
        cameraEngine.release()
        captureEngine.stop()
        overlayCompositor.stop()
        videoEncoder.stop()
        audioPipeline.stop()
        streamPipeline.stop()
        videoEncoder.setEncodedFrameListener(null)
        videoEncoder.onSpsPpsAvailable = null
        audioPipeline.setEncodedAudioFrameListener(null)
        audioPipeline.onAudioSpecificConfigAvailable = null
        try {
            activeMediaProjection?.stop()
        } catch (_: Exception) {}
        activeMediaProjection = null
        MediaClock.stopSession()

        StreamStateNotifier.updateState(StreamState.IDLE)
        CaptureStateNotifier.updateState(CaptureState.IDLE)
        AudioStateNotifier.updateState(AudioCaptureState.IDLE)
        StreamTelemetryNotifier.updateState(RtmpConnectionState.IDLE)
        mainHandler.post {
            floatingHudManager?.dismiss()
            floatingHudManager = null
            com.fairylive.app.ui.hud.StreamerFloatingHudManager.dismissAll()
        }
        if (activeService === this) {
            activeService = null
        }
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        super.onTaskRemoved(rootIntent)
        Log.i("FairyStreamService", "onTaskRemoved: FairyLive application removed from recents by user -> Stopping stream completely")
        mainHandler.post {
            floatingHudManager?.dismiss()
            floatingHudManager = null
            com.fairylive.app.ui.hud.StreamerFloatingHudManager.dismissAll()
            stopStream(
                com.fairylive.app.core.lifecycle.StreamStopReason.STOP_REASON_USER,
                "FairyLive application removed from recents"
            )
            stopStreamService()
        }
    }

    fun toggleFacecam(): Boolean {
        val newEnabled = !overlayCompositor.facecamConfig.enabled
        setFacecamEnabled(newEnabled)
        return newEnabled
    }

    fun setFacecamEnabled(enabled: Boolean) {
        if (enabled) {
            val surface = overlayCompositor.getCameraSurface(640, 480)
            if (surface != null) {
                overlayCompositor.updateFacecamConfig(
                    overlayCompositor.facecamConfig.copy(enabled = true)
                )
                cameraEngine.start(
                    surface = surface,
                    preferredFacing = overlayCompositor.facecamConfig.facing,
                    targetWidth = 640,
                    targetHeight = 480
                )
                Log.i("FairyStreamService", "Facecam ENABLED on broadcast compositor")
            } else {
                Log.e("FairyStreamService", "Failed to obtain camera preview surface from compositor")
            }
        } else {
            cameraEngine.stop()
            overlayCompositor.updateFacecamConfig(
                overlayCompositor.facecamConfig.copy(enabled = false)
            )
            Log.i("FairyStreamService", "Facecam DISABLED on broadcast compositor")
        }
    }

    fun switchFacecam() {
        cameraEngine.switchCamera()
        val nextFacing = if (overlayCompositor.facecamConfig.facing == com.fairylive.app.core.camera.model.CameraFacing.FRONT)
            com.fairylive.app.core.camera.model.CameraFacing.BACK else com.fairylive.app.core.camera.model.CameraFacing.FRONT
        overlayCompositor.updateFacecamConfig(
            overlayCompositor.facecamConfig.copy(
                facing = nextFacing,
                mirrorHorizontal = (nextFacing == com.fairylive.app.core.camera.model.CameraFacing.FRONT)
            )
        )
        Log.i("FairyStreamService", "Facecam switched to $nextFacing")
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
        private const val TAG = "FairyStreamService"
        @Volatile
        var activeService: FairyStreamService? = null
            private set

        const val CHANNEL_ID = "fairy_stream_channel"
        const val NOTIFICATION_ID = 2026
        const val ACTION_START_SERVICE = "com.fairylive.app.action.START_SERVICE"
        const val ACTION_STOP_SERVICE = "com.fairylive.app.action.STOP_SERVICE"
        const val ACTION_START_CAPTURE = "com.fairylive.app.action.START_CAPTURE"
        const val ACTION_PAUSE_CAPTURE = "com.fairylive.app.action.PAUSE_CAPTURE"
        const val ACTION_RESUME_CAPTURE = "com.fairylive.app.action.RESUME_CAPTURE"
        const val ACTION_MUTE_MIC = "com.fairylive.app.action.MUTE_MIC"
        const val ACTION_UNMUTE_MIC = "com.fairylive.app.action.UNMUTE_MIC"
        const val ACTION_TOGGLE_MIC = "com.fairylive.app.action.TOGGLE_MIC"
        const val ACTION_STOP_CAPTURE = "com.fairylive.app.action.STOP_CAPTURE"
        const val ACTION_UPDATE_RESOLUTION = "com.fairylive.app.action.UPDATE_RESOLUTION"
        const val ACTION_REQUEST_KEYFRAME = "com.fairylive.app.action.REQUEST_KEYFRAME"
        const val ACTION_ADJUST_BITRATE = "com.fairylive.app.action.ADJUST_BITRATE"
        const val ACTION_START_AUDIO = "com.fairylive.app.action.START_AUDIO"
        const val ACTION_MUTE_AUDIO = "com.fairylive.app.action.MUTE_AUDIO"
        const val ACTION_UNMUTE_AUDIO = "com.fairylive.app.action.UNMUTE_AUDIO"
        const val ACTION_STOP_AUDIO = "com.fairylive.app.action.STOP_AUDIO"
        const val ACTION_TRIGGER_ALERT = "com.fairylive.app.action.TRIGGER_ALERT"
        const val ACTION_TOGGLE_FACECAM = "com.fairylive.app.action.TOGGLE_FACECAM"
        const val ACTION_SWITCH_FACECAM = "com.fairylive.app.action.SWITCH_FACECAM"
        const val ACTION_UPDATE_FACECAM = "com.fairylive.app.action.UPDATE_FACECAM"
        const val ACTION_SWITCH_SCENE = "com.fairylive.app.action.SWITCH_SCENE"
        const val ACTION_SET_SCALE_MODE = "com.fairylive.app.action.SET_SCALE_MODE"
        const val ACTION_SET_AUDIO_VOLUMES = "com.fairylive.app.action.SET_AUDIO_VOLUMES"

        const val EXTRA_SCENE_ID = "extra_scene_id"
        const val EXTRA_TRANSITION = "extra_transition"
        const val EXTRA_SCALE_MODE = "extra_scale_mode"
        const val EXTRA_MIC_VOLUME = "extra_mic_volume"
        const val EXTRA_GAME_VOLUME = "extra_game_volume"
        const val EXTRA_MEDIA_VOLUME = "extra_media_volume"
        const val EXTRA_MASTER_VOLUME = "extra_master_volume"
        const val EXTRA_MIC_MUTED = "extra_mic_muted"
        const val EXTRA_GAME_MUTED = "extra_game_muted"
        const val EXTRA_MEDIA_MUTED = "extra_media_muted"
        const val EXTRA_DSP_NOISE_GATE = "extra_dsp_noise_gate"
        const val EXTRA_DSP_GOD_MODE_NOISE = "extra_dsp_god_mode_noise"
        const val EXTRA_DSP_NOISE_LEVEL = "extra_dsp_noise_level"
        const val EXTRA_DSP_AGC = "extra_dsp_agc"
        const val EXTRA_DSP_COMPRESSOR = "extra_dsp_compressor"
        const val EXTRA_DSP_LIMITER = "extra_dsp_limiter"
        const val EXTRA_AUDIO_PRESET = "extra_audio_preset"
        const val EXTRA_YOUTUBE_CHAT_URL = "extra_youtube_chat_url"

        const val EXTRA_ENABLE_FACECAM = "extra_enable_facecam"
        const val EXTRA_FACECAM_X = "extra_facecam_x"
        const val EXTRA_FACECAM_Y = "extra_facecam_y"
        const val EXTRA_FACECAM_SCALE = "extra_facecam_scale"

        const val EXTRA_ALERT_HEADLINE = "extra_alert_headline"
        const val EXTRA_ALERT_SUBTEXT = "extra_alert_subtext"
        const val EXTRA_ALERT_DURATION = "extra_alert_duration"

        const val EXTRA_RESULT_CODE = "extra_result_code"
        const val EXTRA_RESULT_DATA = "extra_result_data"
        const val EXTRA_WIDTH = "extra_width"
        const val EXTRA_HEIGHT = "extra_height"
        const val EXTRA_DPI = "extra_dpi"
        const val EXTRA_ENABLE_ENCODING = "extra_enable_encoding"
        const val EXTRA_ENABLE_AUDIO = "extra_enable_audio"
        const val EXTRA_ENABLE_RTMP = "extra_enable_rtmp"
        const val EXTRA_ENABLE_COMPOSITOR = "extra_enable_compositor"
        const val EXTRA_SINGLE_OVERLAY = "extra_single_overlay"
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
            enableCompositor: Boolean = false,
            singleOverlayOnly: Boolean = false,
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
                putExtra(EXTRA_ENABLE_COMPOSITOR, enableCompositor)
                putExtra(EXTRA_SINGLE_OVERLAY, singleOverlayOnly)
                putExtra(EXTRA_RTMP_URL, rtmpUrl)
                putExtra(EXTRA_STREAM_KEY, streamKey)
                putExtra(EXTRA_BITRATE_KBPS, bitrateKbps)
                putExtra(EXTRA_FPS, fps)
            }
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(intent)
                } else {
                    context.startService(intent)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to start capture foreground service", e)
                CreatorErrorNotifier.postError(
                    title = "Broadcast Startup Error",
                    message = "Could not start stream service: ${e.message}",
                    resolution = "Return Fairy Live to the foreground and tap Start Live again."
                )
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

        fun muteMicrophone(context: Context) {
            val intent = Intent(context, FairyStreamService::class.java).apply {
                action = ACTION_MUTE_MIC
            }
            context.startService(intent)
        }

        fun unmuteMicrophone(context: Context) {
            val intent = Intent(context, FairyStreamService::class.java).apply {
                action = ACTION_UNMUTE_MIC
            }
            context.startService(intent)
        }

        fun toggleMicrophone(context: Context) {
            val intent = Intent(context, FairyStreamService::class.java).apply {
                action = ACTION_TOGGLE_MIC
            }
            context.startService(intent)
        }

        fun setAudioVolumes(
            context: Context,
            micVolume: Float,
            gameVolume: Float,
            mediaVolume: Float = -1f,
            masterVolume: Float = -1f,
            micMuted: Boolean? = null,
            gameMuted: Boolean? = null,
            mediaMuted: Boolean? = null
        ) {
            val intent = Intent(context, FairyStreamService::class.java).apply {
                action = ACTION_SET_AUDIO_VOLUMES
                putExtra(EXTRA_MIC_VOLUME, micVolume)
                putExtra(EXTRA_GAME_VOLUME, gameVolume)
                if (mediaVolume >= 0f) putExtra(EXTRA_MEDIA_VOLUME, mediaVolume)
                if (masterVolume >= 0f) putExtra(EXTRA_MASTER_VOLUME, masterVolume)
                if (micMuted != null) putExtra(EXTRA_MIC_MUTED, micMuted)
                if (gameMuted != null) putExtra(EXTRA_GAME_MUTED, gameMuted)
                if (mediaMuted != null) putExtra(EXTRA_MEDIA_MUTED, mediaMuted)
            }
            context.startService(intent)
        }

        fun setAudioDspFilters(
            context: Context,
            noiseGate: Boolean,
            agc: Boolean,
            compressor: Boolean,
            limiter: Boolean,
            godMode: Boolean = true,
            noiseLevel: Int? = null
        ) {
            val intent = Intent(context, FairyStreamService::class.java).apply {
                action = ACTION_SET_AUDIO_VOLUMES
                putExtra(EXTRA_DSP_NOISE_GATE, noiseGate)
                putExtra(EXTRA_DSP_GOD_MODE_NOISE, godMode)
                if (noiseLevel != null) putExtra(EXTRA_DSP_NOISE_LEVEL, noiseLevel)
                putExtra(EXTRA_DSP_AGC, agc)
                putExtra(EXTRA_DSP_COMPRESSOR, compressor)
                putExtra(EXTRA_DSP_LIMITER, limiter)
            }
            context.startService(intent)
        }

        fun setAudioPreset(context: Context, presetName: String) {
            val intent = Intent(context, FairyStreamService::class.java).apply {
                action = ACTION_SET_AUDIO_VOLUMES
                putExtra(EXTRA_AUDIO_PRESET, presetName)
            }
            context.startService(intent)
        }

        fun stopCapture(context: Context) {
            val intent = Intent(context, FairyStreamService::class.java).apply {
                action = ACTION_STOP_CAPTURE
            }
            context.startService(intent)
        }

        fun toggleFacecam(context: Context) {
            val intent = Intent(context, FairyStreamService::class.java).apply {
                action = ACTION_TOGGLE_FACECAM
            }
            context.startService(intent)
        }

        fun switchFacecam(context: Context) {
            val intent = Intent(context, FairyStreamService::class.java).apply {
                action = ACTION_SWITCH_FACECAM
            }
            context.startService(intent)
        }

        fun switchScene(context: Context, sceneId: String, transitionType: String = "CUT") {
            val intent = Intent(context, FairyStreamService::class.java).apply {
                action = ACTION_SWITCH_SCENE
                putExtra(EXTRA_SCENE_ID, sceneId)
                putExtra(EXTRA_TRANSITION, transitionType)
            }
            context.startService(intent)
        }

        fun setScaleMode(context: Context, mode: com.fairylive.app.core.capture.model.ScaleMode) {
            val intent = Intent(context, FairyStreamService::class.java).apply {
                action = ACTION_SET_SCALE_MODE
                putExtra(EXTRA_SCALE_MODE, mode.name)
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
