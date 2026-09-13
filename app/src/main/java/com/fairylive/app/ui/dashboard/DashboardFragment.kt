package com.fairylive.app.ui.dashboard

import android.app.Activity
import android.content.Context
import android.content.res.Configuration
import android.media.projection.MediaProjectionConfig
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import com.fairylive.app.FairyLiveApplication
import com.fairylive.app.R
import com.fairylive.app.MainActivity
import com.fairylive.app.core.audio.AudioStateNotifier
import com.fairylive.app.core.audio.model.AudioCaptureState
import com.fairylive.app.core.audio.model.AudioMetrics
import com.fairylive.app.core.capture.CaptureStateNotifier
import com.fairylive.app.core.capture.model.CaptureMetrics
import com.fairylive.app.core.capture.model.CaptureState
import com.fairylive.app.core.lifecycle.StreamState
import com.fairylive.app.core.lifecycle.StreamStateNotifier
import com.fairylive.app.core.permission.PermissionManager
import com.fairylive.app.core.video.EncoderStateNotifier
import com.fairylive.app.core.video.model.EncoderMetrics
import com.fairylive.app.core.video.model.EncoderState
import com.fairylive.app.databinding.FragmentDashboardBinding
import com.fairylive.app.service.FairyStreamService

class DashboardFragment : Fragment(),
    StreamStateNotifier.Listener,
    CaptureStateNotifier.StateListener,
    CaptureStateNotifier.MetricsListener,
    EncoderStateNotifier.StateListener,
    EncoderStateNotifier.MetricsListener,
    AudioStateNotifier.StateListener,
    AudioStateNotifier.MetricsListener,
    com.fairylive.app.core.stream.StreamTelemetryNotifier.StateListener,
    com.fairylive.app.core.stream.StreamTelemetryNotifier.TelemetryListener,
    com.fairylive.app.core.common.CreatorErrorNotifier.ErrorListener {

    private var _binding: FragmentDashboardBinding? = null
    private val binding get() = _binding!!

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { _ ->
        updatePermissionIndicators()
    }

    private val projectionLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK && result.data != null) {
            val ctx = requireContext()
            val app = ctx.applicationContext as FairyLiveApplication
            val settings = app.settingsRepository.loadSettings(app.deviceCapability.recommendedProfile)

            val windowMetrics = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                requireActivity().windowManager.currentWindowMetrics
            } else {
                null
            }
            val bounds = windowMetrics?.bounds
            val rawW = bounds?.width() ?: resources.displayMetrics.widthPixels
            val rawH = bounds?.height() ?: resources.displayMetrics.heightPixels
            val safeWidth = (rawW / 2) * 2
            val safeHeight = (rawH / 2) * 2
            val dpi = resources.configuration.densityDpi

            val isRtmpEnabled = binding.switchEnableRtmp.isChecked

            FairyStreamService.startCapture(
                context = ctx,
                resultCode = result.resultCode,
                data = result.data!!,
                width = safeWidth,
                height = safeHeight,
                dpi = dpi,
                enableEncoding = true,
                enableAudio = true,
                enableRtmp = isRtmpEnabled,
                enableCompositor = binding.switchEnableCompositor.isChecked,
                rtmpUrl = settings.serverUrl,
                streamKey = settings.streamKey,
                bitrateKbps = settings.videoBitrateKbps,
                fps = settings.fps
            )
        } else {
            Toast.makeText(requireContext(), "Screen capture permission denied", Toast.LENGTH_SHORT).show()
            com.fairylive.app.core.common.CreatorErrorNotifier.postError(
                title = "Screen Recording Permission Denied",
                message = "You did not grant screen recording permission, so the broadcast was not started.",
                resolution = "Tap GO LIVE again and select 'Start' (or 'Entire screen') on the permission dialog."
            )
            CaptureStateNotifier.updateState(CaptureState.IDLE)
        }
    }

    private val compositorStateListener: () -> Unit = {
        updateCompositorUI()
    }

    private val compositorMetricsListener: () -> Unit = {
        updateCompositorUI()
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentDashboardBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        StreamStateNotifier.addListener(this)
        CaptureStateNotifier.addStateListener(this)
        CaptureStateNotifier.addMetricsListener(this)
        EncoderStateNotifier.addStateListener(this)
        EncoderStateNotifier.addMetricsListener(this)
        AudioStateNotifier.addStateListener(this)
        AudioStateNotifier.addMetricsListener(this)
        com.fairylive.app.core.common.CreatorErrorNotifier.addListener(this)
        com.fairylive.app.core.compositor.CompositorStateNotifier.addStateListener(compositorStateListener)
        com.fairylive.app.core.compositor.CompositorStateNotifier.addMetricsListener(compositorMetricsListener)
        updateCompositorUI()

        bindDeviceCapability()
        bindCaptureControls()
        bindEncoderControls()
        bindAudioControls()
        bindServiceController()
        bindPermissionControls()
        bindStreamControls()
        bindErrorControls()
        updatePermissionIndicators()
        updateServiceState(StreamStateNotifier.currentState)
        updateCaptureUI(CaptureStateNotifier.currentState, CaptureStateNotifier.currentMetrics)
        updateEncoderUI(EncoderStateNotifier.currentState, EncoderStateNotifier.currentMetrics)
        updateAudioUI(AudioStateNotifier.currentState, AudioStateNotifier.currentMetrics)
        updateStreamUI(
            com.fairylive.app.core.stream.StreamTelemetryNotifier.currentState,
            com.fairylive.app.core.stream.StreamTelemetryNotifier.currentTelemetry
        )
    }

    override fun onDestroyView() {
        super.onDestroyView()
        StreamStateNotifier.removeListener(this)
        CaptureStateNotifier.removeStateListener(this)
        CaptureStateNotifier.removeMetricsListener(this)
        EncoderStateNotifier.removeStateListener(this)
        EncoderStateNotifier.removeMetricsListener(this)
        AudioStateNotifier.removeStateListener(this)
        AudioStateNotifier.removeMetricsListener(this)
        com.fairylive.app.core.stream.StreamTelemetryNotifier.removeStateListener(this)
        com.fairylive.app.core.stream.StreamTelemetryNotifier.removeTelemetryListener(this)
        com.fairylive.app.core.common.CreatorErrorNotifier.removeListener(this)
        com.fairylive.app.core.compositor.CompositorStateNotifier.removeStateListener(compositorStateListener)
        com.fairylive.app.core.compositor.CompositorStateNotifier.removeMetricsListener(compositorMetricsListener)
        _binding = null
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        if (CaptureStateNotifier.currentState.isActive) {
            val metrics = resources.displayMetrics
            FairyStreamService.updateResolution(
                requireContext(),
                metrics.widthPixels,
                metrics.heightPixels,
                metrics.densityDpi
            )
        }
    }

    private fun bindDeviceCapability() {
        val app = requireActivity().application as FairyLiveApplication
        val cap = app.deviceCapability

        binding.tvRecommendedProfileTag.text = cap.recommendedProfile.title.uppercase()
        binding.tvDeviceInfo.text = "Device: ${cap.manufacturer} ${cap.model} (Android SDK ${cap.sdkInt})"
        binding.tvRamInfo.text = "RAM: ${cap.ram.totalMemMb}MB total, ${cap.ram.availMemMb}MB available (Low-RAM Flag: ${cap.ram.isLowRamDevice})"
        binding.tvCpuInfo.text = "CPU: ${cap.cpu.coreCount} cores (${cap.cpu.primaryAbi})"

        val hwEncoders = cap.videoEncoders.filter { it.isHardwareAccelerated }
        val encoderText = if (hwEncoders.isNotEmpty()) {
            "HW Encoders: " + hwEncoders.joinToString(", ") { it.codecName }
        } else {
            "No HW H.264 Encoder detected (Software Fallback)"
        }
        binding.tvEncoderInfo.text = encoderText
    }

    private fun bindCaptureControls() {
        binding.btnStartCapture.setOnClickListener {
            val ctx = requireContext()

            if (StreamStateNotifier.currentState.isActive || CaptureStateNotifier.currentState.isActive) {
                // Deterministic stop workflow
                FairyStreamService.stopCapture(ctx)
                return@setOnClickListener
            }

            val missing = PermissionManager.getMissingPermissions(ctx)
            if (missing.isNotEmpty()) {
                com.fairylive.app.core.common.CreatorErrorNotifier.postError(
                    title = "Permissions Required",
                    message = "Fairy Live requires microphone and notification permissions to broadcast live.",
                    resolution = "Grant the missing permissions to start streaming."
                )
                permissionLauncher.launch(missing.toTypedArray())
                return@setOnClickListener
            }

            val app = ctx.applicationContext as FairyLiveApplication
            val settings = app.settingsRepository.loadSettings(app.deviceCapability.recommendedProfile)

            // Validate stream configuration before requesting MediaProjection consent
            if (binding.switchEnableRtmp.isChecked) {
                val validation = com.fairylive.app.core.settings.StreamConfigurationValidator.validate(settings.serverUrl, settings.streamKey)
                if (!validation.isValid) {
                    val err = validation as com.fairylive.app.core.settings.StreamConfigurationValidator.ValidationResult.Error
                    com.fairylive.app.core.common.CreatorErrorNotifier.postError(
                        title = "Invalid Stream Destination",
                        message = err.userMessage,
                        resolution = "Tap 'Open Settings' below to configure a valid RTMP URL and stream key."
                    )
                    return@setOnClickListener
                }
            }

            CaptureStateNotifier.updateState(CaptureState.AUTHORIZING)
            val projectionManager = ctx.getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            // Android 15's plain createScreenCaptureIntent() routes to the single-app selector,
            // which fails to bind RECORD_CONTENT_TASK on API 35 emulators (createVirtualDisplay
            // returns null → 0 video frames). Full-display capture is also the correct semantic
            // for a screen-streaming app. Use MediaProjectionConfig for default display on 34+.
            val captureIntent = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                val config = MediaProjectionConfig.createConfigForDefaultDisplay()
                projectionManager.createScreenCaptureIntent(config)
            } else {
                @Suppress("DEPRECATION")
                projectionManager.createScreenCaptureIntent()
            }
            projectionLauncher.launch(captureIntent)
        }

        binding.btnPauseCapture.setOnClickListener {
            val ctx = requireContext()
            if (CaptureStateNotifier.currentState == CaptureState.PAUSED) {
                FairyStreamService.resumeCapture(ctx)
            } else {
                FairyStreamService.pauseCapture(ctx)
            }
        }

        binding.btnStopCapture.setOnClickListener {
            FairyStreamService.stopCapture(requireContext())
        }
    }

    private fun bindEncoderControls() {
        binding.btnRequestKeyframe.setOnClickListener {
            FairyStreamService.requestKeyFrame(requireContext())
            Toast.makeText(requireContext(), "Sync Keyframe (IDR) Requested", Toast.LENGTH_SHORT).show()
        }
    }

    private fun bindAudioControls() {
        binding.btnStartAudio.setOnClickListener {
            val ctx = requireContext()
            if (!PermissionManager.hasAudioPermission(ctx)) {
                Toast.makeText(ctx, "Grant microphone permission first", Toast.LENGTH_SHORT).show()
                permissionLauncher.launch(arrayOf(android.Manifest.permission.RECORD_AUDIO))
                return@setOnClickListener
            }
            FairyStreamService.startAudio(ctx)
        }

        binding.btnMuteAudio.setOnClickListener {
            val ctx = requireContext()
            if (AudioStateNotifier.currentState == AudioCaptureState.MUTED) {
                FairyStreamService.unmuteAudio(ctx)
            } else {
                FairyStreamService.muteAudio(ctx)
            }
        }

        binding.btnStopAudio.setOnClickListener {
            FairyStreamService.stopAudio(requireContext())
        }
    }

    private fun bindServiceController() {
        binding.btnToggleService.setOnClickListener {
            val ctx = requireContext()
            if (StreamStateNotifier.currentState.isIdle) {
                val missing = PermissionManager.getMissingPermissions(ctx)
                if (missing.isNotEmpty()) {
                    Toast.makeText(ctx, "Permissions required before starting service", Toast.LENGTH_SHORT).show()
                    permissionLauncher.launch(missing.toTypedArray())
                    return@setOnClickListener
                }
                FairyStreamService.start(ctx)
            } else {
                FairyStreamService.stop(ctx)
            }
        }
    }

    private fun bindPermissionControls() {
        binding.btnRequestPermissions.setOnClickListener {
            val missing = PermissionManager.getMissingPermissions(requireContext())
            if (missing.isNotEmpty()) {
                permissionLauncher.launch(missing.toTypedArray())
            }
        }
    }

    private fun updatePermissionIndicators() {
        val ctx = context ?: return

        val audioGranted = PermissionManager.hasAudioPermission(ctx)
        val notifyGranted = PermissionManager.hasNotificationPermission(ctx)
        val cameraGranted = PermissionManager.hasCameraPermission(ctx)

        setIndicator(binding.ivAudioPermIcon, binding.tvAudioPermLabel, audioGranted, "Audio Record (Microphone)")
        setIndicator(binding.ivNotifyPermIcon, binding.tvNotifyPermLabel, notifyGranted, "Post Notifications (Foreground Service)")
        setIndicator(binding.ivCameraPermIcon, binding.tvCameraPermLabel, cameraGranted, "Camera (Face Cam)")

        val allGranted = audioGranted && notifyGranted && cameraGranted
        binding.btnRequestPermissions.visibility = if (allGranted) View.GONE else View.VISIBLE
    }

    private fun setIndicator(iv: android.widget.ImageView, tv: android.widget.TextView, granted: Boolean, baseText: String) {
        val ctx = iv.context
        if (granted) {
            iv.setImageResource(R.drawable.ic_check_circle)
            tv.text = "$baseText: GRANTED"
            tv.setTextColor(ContextCompat.getColor(ctx, R.color.status_green))
        } else {
            iv.setImageResource(R.drawable.ic_warning)
            tv.text = "$baseText: NOT GRANTED"
            tv.setTextColor(ContextCompat.getColor(ctx, R.color.status_amber))
        }
    }

    private fun updateServiceState(state: StreamState) {
        val ctx = context ?: return
        if (state.isIdle) {
            binding.tvServiceStatus.text = getString(R.string.status_service_stopped)
            binding.tvServiceStatus.setTextColor(ContextCompat.getColor(ctx, R.color.status_amber))
            binding.btnToggleService.text = getString(R.string.btn_start_service)
            binding.btnToggleService.setBackgroundColor(ContextCompat.getColor(ctx, R.color.fairy_purple))
        } else {
            binding.tvServiceStatus.text = getString(R.string.status_service_running)
            binding.tvServiceStatus.setTextColor(ContextCompat.getColor(ctx, R.color.status_green))
            binding.btnToggleService.text = getString(R.string.btn_stop_service)
            binding.btnToggleService.setBackgroundColor(ContextCompat.getColor(ctx, R.color.status_red))
        }

        // Synchronize main broadcast button state
        when (state) {
            StreamState.STREAMING -> {
                binding.btnStartCapture.text = "STOP LIVE BROADCAST"
                binding.btnStartCapture.setBackgroundColor(ContextCompat.getColor(ctx, R.color.status_red))
                binding.btnStartCapture.isEnabled = true
                binding.tvCaptureStatusBadge.text = "● LIVE"
                binding.tvCaptureStatusBadge.setTextColor(ContextCompat.getColor(ctx, R.color.status_green))
            }
            StreamState.STARTING -> {
                binding.btnStartCapture.text = "CONNECTING TO SERVER..."
                binding.btnStartCapture.setBackgroundColor(ContextCompat.getColor(ctx, R.color.fairy_cyan))
                binding.btnStartCapture.isEnabled = false
                binding.tvCaptureStatusBadge.text = "CONNECTING..."
                binding.tvCaptureStatusBadge.setTextColor(ContextCompat.getColor(ctx, R.color.fairy_cyan))
            }
            StreamState.STOPPING -> {
                binding.btnStartCapture.text = "STOPPING..."
                binding.btnStartCapture.setBackgroundColor(ContextCompat.getColor(ctx, R.color.status_amber))
                binding.btnStartCapture.isEnabled = false
                binding.tvCaptureStatusBadge.text = "STOPPING..."
                binding.tvCaptureStatusBadge.setTextColor(ContextCompat.getColor(ctx, R.color.status_amber))
            }
            StreamState.ERROR -> {
                binding.btnStartCapture.text = "RETRY BROADCAST"
                binding.btnStartCapture.setBackgroundColor(ContextCompat.getColor(ctx, R.color.status_red))
                binding.btnStartCapture.isEnabled = true
                binding.tvCaptureStatusBadge.text = "STREAM ERROR"
                binding.tvCaptureStatusBadge.setTextColor(ContextCompat.getColor(ctx, R.color.status_red))
            }
            else -> {
                binding.btnStartCapture.text = "GO LIVE (START BROADCAST)"
                binding.btnStartCapture.setBackgroundColor(ContextCompat.getColor(ctx, R.color.fairy_purple))
                binding.btnStartCapture.isEnabled = true
                binding.tvCaptureStatusBadge.text = "OFFLINE"
                binding.tvCaptureStatusBadge.setTextColor(ContextCompat.getColor(ctx, R.color.status_amber))
            }
        }
    }

    private fun updateCaptureUI(state: CaptureState, metrics: CaptureMetrics) {
        val ctx = context ?: return

        when (state) {
            CaptureState.IDLE -> {
                binding.tvCaptureStatusBadge.text = "CAPTURE IDLE"
                binding.tvCaptureStatusBadge.setTextColor(ContextCompat.getColor(ctx, R.color.status_amber))
                binding.btnStartCapture.visibility = View.VISIBLE
                binding.layoutCaptureActiveControls.visibility = View.GONE
                binding.tvCaptureTelemetry.text = "Frames: 0 | FPS: 0.0 | Standby"
            }
            CaptureState.AUTHORIZING, CaptureState.STARTING -> {
                binding.tvCaptureStatusBadge.text = "INITIALIZING"
                binding.tvCaptureStatusBadge.setTextColor(ContextCompat.getColor(ctx, R.color.status_amber))
                binding.btnStartCapture.visibility = View.GONE
                binding.layoutCaptureActiveControls.visibility = View.GONE
            }
            CaptureState.CAPTURING -> {
                binding.tvCaptureStatusBadge.text = "CAPTURING"
                binding.tvCaptureStatusBadge.setTextColor(ContextCompat.getColor(ctx, R.color.status_green))
                binding.btnStartCapture.visibility = View.GONE
                binding.layoutCaptureActiveControls.visibility = View.VISIBLE
                binding.btnPauseCapture.text = "Pause"
                binding.btnPauseCapture.setTextColor(ContextCompat.getColor(ctx, R.color.status_amber))

                val fpsFormatted = String.format("%.1f", metrics.currentFps)
                binding.tvCaptureTelemetry.text = "Frames: ${metrics.framesCaptured} | FPS: $fpsFormatted | ${metrics.width}x${metrics.height} (${metrics.dpi}dpi)"
            }
            CaptureState.PAUSED -> {
                binding.tvCaptureStatusBadge.text = "PAUSED"
                binding.tvCaptureStatusBadge.setTextColor(ContextCompat.getColor(ctx, R.color.status_amber))
                binding.btnStartCapture.visibility = View.GONE
                binding.layoutCaptureActiveControls.visibility = View.VISIBLE
                binding.btnPauseCapture.text = "Resume"
                binding.btnPauseCapture.setTextColor(ContextCompat.getColor(ctx, R.color.status_green))

                binding.tvCaptureTelemetry.text = "Frames: ${metrics.framesCaptured} | PAUSED | ${metrics.width}x${metrics.height}"
            }
            CaptureState.STOPPING -> {
                binding.tvCaptureStatusBadge.text = "STOPPING"
                binding.tvCaptureStatusBadge.setTextColor(ContextCompat.getColor(ctx, R.color.status_amber))
            }
            CaptureState.ERROR -> {
                binding.tvCaptureStatusBadge.text = "CAPTURE ERROR"
                binding.tvCaptureStatusBadge.setTextColor(ContextCompat.getColor(ctx, R.color.status_red))
                binding.btnStartCapture.visibility = View.VISIBLE
                binding.layoutCaptureActiveControls.visibility = View.GONE
            }
        }
    }

    private fun updateEncoderUI(state: EncoderState, metrics: EncoderMetrics) {
        val ctx = context ?: return

        when (state) {
            EncoderState.IDLE -> {
                binding.tvEncoderStatusBadge.text = "ENCODER IDLE"
                binding.tvEncoderStatusBadge.setTextColor(ContextCompat.getColor(ctx, R.color.status_amber))
                binding.btnRequestKeyframe.visibility = View.GONE
                binding.tvEncoderTelemetry.text = "Enc: 0 (Keys: 0) | 0 kbps | 0.0 FPS | Standby"
            }
            EncoderState.CONFIGURING -> {
                binding.tvEncoderStatusBadge.text = "CONFIGURING"
                binding.tvEncoderStatusBadge.setTextColor(ContextCompat.getColor(ctx, R.color.status_amber))
                binding.btnRequestKeyframe.visibility = View.GONE
            }
            EncoderState.ENCODING -> {
                if (metrics.isHardwareAccelerated) {
                    binding.tvEncoderStatusBadge.text = "HARDWARE ENCODING"
                    binding.tvEncoderStatusBadge.setTextColor(ContextCompat.getColor(ctx, R.color.status_green))
                } else {
                    binding.tvEncoderStatusBadge.text = "SOFTWARE ENCODER (FALLBACK)"
                    binding.tvEncoderStatusBadge.setTextColor(ContextCompat.getColor(ctx, R.color.status_amber))
                }
                binding.btnRequestKeyframe.visibility = View.VISIBLE

                val typeLabel = when (metrics.encoderType) {
                    com.fairylive.app.core.capability.model.EncoderType.HARDWARE -> "[HW]"
                    com.fairylive.app.core.capability.model.EncoderType.SOFTWARE -> "[SW]"
                    com.fairylive.app.core.capability.model.EncoderType.UNKNOWN -> "[UNK]"
                }
                val fpsFormatted = String.format("%.1f", metrics.outputFps)
                binding.tvEncoderTelemetry.text = "Enc: ${metrics.framesEncoded} (Keys: ${metrics.keyframesEncoded}) | ${metrics.bitrateKbps} kbps | $fpsFormatted FPS | ${metrics.width}x${metrics.height} $typeLabel ${metrics.codecName}"
            }
            EncoderState.PAUSED -> {
                binding.tvEncoderStatusBadge.text = "PAUSED"
                binding.tvEncoderStatusBadge.setTextColor(ContextCompat.getColor(ctx, R.color.status_amber))
                binding.btnRequestKeyframe.visibility = View.GONE
                binding.tvEncoderTelemetry.text = "Enc: ${metrics.framesEncoded} (Keys: ${metrics.keyframesEncoded}) | PAUSED | ${metrics.codecName}"
            }
            EncoderState.STOPPING -> {
                binding.tvEncoderStatusBadge.text = "STOPPING"
                binding.tvEncoderStatusBadge.setTextColor(ContextCompat.getColor(ctx, R.color.status_amber))
                binding.btnRequestKeyframe.visibility = View.GONE
            }
            EncoderState.ERROR -> {
                binding.tvEncoderStatusBadge.text = "ENCODER ERROR"
                binding.tvEncoderStatusBadge.setTextColor(ContextCompat.getColor(ctx, R.color.status_red))
                binding.btnRequestKeyframe.visibility = View.GONE
            }
        }
    }

    private fun updateAudioUI(state: AudioCaptureState, metrics: AudioMetrics) {
        val ctx = context ?: return

        when (state) {
            AudioCaptureState.IDLE -> {
                binding.tvAudioStatusBadge.text = "AUDIO IDLE"
                binding.tvAudioStatusBadge.setTextColor(ContextCompat.getColor(ctx, R.color.status_amber))
                binding.btnStartAudio.visibility = View.VISIBLE
                binding.layoutAudioControls.visibility = View.GONE
                binding.pbAudioLevel.progress = 0
                binding.tvAudioLevelLabel.text = "Input Level: 0%"
                binding.tvAudioTelemetry.text = "Enc: 0 | 0 kbps | 44.1kHz Mono PCM | PTS Sync: Ready"
            }
            AudioCaptureState.INITIALIZING -> {
                binding.tvAudioStatusBadge.text = "INITIALIZING"
                binding.tvAudioStatusBadge.setTextColor(ContextCompat.getColor(ctx, R.color.status_amber))
            }
            AudioCaptureState.RECORDING -> {
                if (metrics.isHardwareAccelerated) {
                    binding.tvAudioStatusBadge.text = "HARDWARE AUDIO ENCODING"
                    binding.tvAudioStatusBadge.setTextColor(ContextCompat.getColor(ctx, R.color.status_green))
                } else {
                    binding.tvAudioStatusBadge.text = "SOFTWARE AUDIO ENCODER (FALLBACK)"
                    binding.tvAudioStatusBadge.setTextColor(ContextCompat.getColor(ctx, R.color.status_amber))
                }
                binding.btnStartAudio.visibility = View.GONE
                binding.layoutAudioControls.visibility = View.VISIBLE
                binding.btnMuteAudio.text = "Mute"
                binding.btnMuteAudio.setTextColor(ContextCompat.getColor(ctx, R.color.status_amber))

                binding.pbAudioLevel.progress = metrics.peakAmplitudePercent
                binding.tvAudioLevelLabel.text = "Input Level: ${metrics.peakAmplitudePercent}%"

                val sourceText = if (metrics.isDeviceAudio) "Device Audio" else "Microphone"
                val typeLabel = when (metrics.encoderType) {
                    com.fairylive.app.core.capability.model.EncoderType.HARDWARE -> "[HW]"
                    com.fairylive.app.core.capability.model.EncoderType.SOFTWARE -> "[SW]"
                    com.fairylive.app.core.capability.model.EncoderType.UNKNOWN -> "[UNK]"
                }
                val codecDisplay = if (metrics.encoderName.isNotEmpty()) "$typeLabel ${metrics.encoderName}" else "AAC"
                binding.tvAudioTelemetry.text = "Enc: ${metrics.encodedFrames} frames | ${metrics.bitrateKbps} kbps | ${metrics.sampleRate}Hz ${metrics.channels}ch | $sourceText | $codecDisplay | A/V Monotonic: Synced"
            }
            AudioCaptureState.MUTED -> {
                binding.tvAudioStatusBadge.text = "MUTED"
                binding.tvAudioStatusBadge.setTextColor(ContextCompat.getColor(ctx, R.color.status_amber))
                binding.btnStartAudio.visibility = View.GONE
                binding.layoutAudioControls.visibility = View.VISIBLE
                binding.btnMuteAudio.text = "Unmute"
                binding.btnMuteAudio.setTextColor(ContextCompat.getColor(ctx, R.color.status_green))

                binding.pbAudioLevel.progress = 0
                binding.tvAudioLevelLabel.text = "Input Level: MUTED"
                binding.tvAudioTelemetry.text = "Enc: ${metrics.encodedFrames} frames | MUTED (Silence Gen) | A/V Monotonic: Preserved"
            }
            AudioCaptureState.PAUSED, AudioCaptureState.STOPPING -> {
                binding.tvAudioStatusBadge.text = state.name
                binding.tvAudioStatusBadge.setTextColor(ContextCompat.getColor(ctx, R.color.status_amber))
            }
            AudioCaptureState.ERROR -> {
                binding.tvAudioStatusBadge.text = "AUDIO ERROR"
                binding.tvAudioStatusBadge.setTextColor(ContextCompat.getColor(ctx, R.color.status_red))
                binding.btnStartAudio.visibility = View.VISIBLE
                binding.layoutAudioControls.visibility = View.GONE
            }
        }
    }

    override fun onStateChanged(newState: StreamState, previousState: StreamState) {
        activity?.runOnUiThread {
            if (_binding != null) {
                updateServiceState(newState)
            }
        }
    }

    override fun onCaptureStateChanged(newState: CaptureState, previousState: CaptureState) {
        activity?.runOnUiThread {
            if (_binding != null) {
                updateCaptureUI(newState, CaptureStateNotifier.currentMetrics)
            }
        }
    }

    override fun onCaptureMetricsUpdated(metrics: CaptureMetrics) {
        activity?.runOnUiThread {
            if (_binding != null) {
                updateCaptureUI(CaptureStateNotifier.currentState, metrics)
            }
        }
    }

    override fun onEncoderStateChanged(newState: EncoderState, previousState: EncoderState) {
        activity?.runOnUiThread {
            if (_binding != null) {
                updateEncoderUI(newState, EncoderStateNotifier.currentMetrics)
            }
        }
    }

    override fun onEncoderMetricsUpdated(metrics: EncoderMetrics) {
        activity?.runOnUiThread {
            if (_binding != null) {
                updateEncoderUI(EncoderStateNotifier.currentState, metrics)
            }
        }
    }

    override fun onAudioStateChanged(newState: AudioCaptureState, previousState: AudioCaptureState) {
        activity?.runOnUiThread {
            if (_binding != null) {
                updateAudioUI(newState, AudioStateNotifier.currentMetrics)
            }
        }
    }

    override fun onAudioMetricsUpdated(metrics: AudioMetrics) {
        activity?.runOnUiThread {
            if (_binding != null) {
                updateAudioUI(AudioStateNotifier.currentState, metrics)
            }
        }
    }

    override fun onConnectionStateChanged(
        newState: com.fairylive.app.core.stream.model.RtmpConnectionState,
        previousState: com.fairylive.app.core.stream.model.RtmpConnectionState
    ) {
        activity?.runOnUiThread {
            if (_binding != null) {
                updateStreamUI(newState, com.fairylive.app.core.stream.StreamTelemetryNotifier.currentTelemetry)
            }
        }
    }

    override fun onTelemetryUpdated(telemetry: com.fairylive.app.core.stream.model.StreamTelemetry) {
        activity?.runOnUiThread {
            if (_binding != null) {
                updateStreamUI(telemetry.connectionState, telemetry)
            }
        }
    }

    private fun bindStreamControls() {
        val app = requireContext().applicationContext as FairyLiveApplication
        val settings = app.settingsRepository.loadSettings(app.deviceCapability.recommendedProfile)

        binding.switchEnableRtmp.isChecked = settings.serverUrl.isNotEmpty()
        updateEndpointDisplay(settings.serverUrl, settings.streamKey)

        binding.switchEnableRtmp.setOnCheckedChangeListener { _, isChecked ->
            if (isChecked && settings.serverUrl.isEmpty()) {
                binding.tvStreamEndpointInfo.text = "Warning: Configure Server URL in Settings first!"
            } else {
                updateEndpointDisplay(settings.serverUrl, settings.streamKey)
            }
        }

        binding.tvCompositorStatus.text = if (binding.switchEnableCompositor.isChecked) {
            "Compositor: ON (GLES overlay active)"
        } else {
            "Compositor: OFF (passthrough)"
        }
        binding.switchEnableCompositor.setOnCheckedChangeListener { _, isChecked ->
            binding.tvCompositorStatus.text = if (isChecked) {
                "Compositor: ON (GLES overlay active)"
            } else {
                "Compositor: OFF (passthrough)"
            }
        }
    }

    private fun updateCompositorUI() {
        val state = com.fairylive.app.core.compositor.CompositorStateNotifier.currentState
        val metrics = com.fairylive.app.core.compositor.CompositorStateNotifier.currentMetrics

        val prefix = when (state) {
            com.fairylive.app.core.compositor.CompositorState.RUNNING -> "ON"
            com.fairylive.app.core.compositor.CompositorState.STARTING -> "STARTING"
            com.fairylive.app.core.compositor.CompositorState.PAUSED -> "PAUSED"
            com.fairylive.app.core.compositor.CompositorState.ERROR -> "ERROR"
            com.fairylive.app.core.compositor.CompositorState.STOPPING -> "STOPPING"
            com.fairylive.app.core.compositor.CompositorState.IDLE -> "OFF"
        }
        val fps = String.format("%.1f", metrics.outputFps)
        val srcFps = String.format("%.1f", metrics.sourceFps)
        val overlayInfo = if (metrics.overlayCount > 0) " | ${metrics.overlayCount} overlay(s)" else ""
        binding.tvCompositorStatus.text =
            if (state == com.fairylive.app.core.compositor.CompositorState.IDLE) {
                "Compositor: OFF (passthrough)"
            } else {
                "Compositor: $prefix | $fps fps (src: $srcFps) | ${metrics.framesComposited} frames$overlayInfo"
            }
    }

    private fun updateEndpointDisplay(url: String, key: String) {
        if (url.isEmpty()) {
            binding.tvStreamEndpointInfo.text = "Endpoint: Standby (Not configured in Settings)"
        } else {
            val maskedKey = if (key.length > 4) {
                key.take(2) + "•".repeat(key.length - 4) + key.takeLast(2)
            } else if (key.isNotEmpty()) {
                "••••"
            } else {
                "none"
            }
            binding.tvStreamEndpointInfo.text = "Target: $url | Key: $maskedKey"
        }
    }

    private fun updateStreamUI(
        state: com.fairylive.app.core.stream.model.RtmpConnectionState,
        telemetry: com.fairylive.app.core.stream.model.StreamTelemetry
    ) {
        val ctx = context ?: return

        when (state) {
            com.fairylive.app.core.stream.model.RtmpConnectionState.IDLE -> {
                binding.tvStreamStatusBadge.text = "DISCONNECTED"
                binding.tvStreamStatusBadge.setTextColor(ContextCompat.getColor(ctx, R.color.status_amber))
            }
            com.fairylive.app.core.stream.model.RtmpConnectionState.CONNECTING -> {
                binding.tvStreamStatusBadge.text = "CONNECTING..."
                binding.tvStreamStatusBadge.setTextColor(ContextCompat.getColor(ctx, R.color.fairy_cyan))
            }
            com.fairylive.app.core.stream.model.RtmpConnectionState.HANDSHAKING -> {
                binding.tvStreamStatusBadge.text = "HANDSHAKING..."
                binding.tvStreamStatusBadge.setTextColor(ContextCompat.getColor(ctx, R.color.fairy_cyan))
            }
            com.fairylive.app.core.stream.model.RtmpConnectionState.CONNECTED,
            com.fairylive.app.core.stream.model.RtmpConnectionState.CREATING_STREAM -> {
                binding.tvStreamStatusBadge.text = "ESTABLISHING..."
                binding.tvStreamStatusBadge.setTextColor(ContextCompat.getColor(ctx, R.color.fairy_cyan))
            }
            com.fairylive.app.core.stream.model.RtmpConnectionState.PUBLISHING -> {
                binding.tvStreamStatusBadge.text = "● LIVE"
                binding.tvStreamStatusBadge.setTextColor(ContextCompat.getColor(ctx, R.color.status_green))
            }
            com.fairylive.app.core.stream.model.RtmpConnectionState.RECONNECTING -> {
                binding.tvStreamStatusBadge.text = "RECONNECTING (${telemetry.reconnectAttempts}/3)"
                binding.tvStreamStatusBadge.setTextColor(ContextCompat.getColor(ctx, R.color.status_amber))
            }
            com.fairylive.app.core.stream.model.RtmpConnectionState.STOPPING -> {
                binding.tvStreamStatusBadge.text = "STOPPING..."
                binding.tvStreamStatusBadge.setTextColor(ContextCompat.getColor(ctx, R.color.status_amber))
            }
            com.fairylive.app.core.stream.model.RtmpConnectionState.ERROR -> {
                binding.tvStreamStatusBadge.text = "STREAM ERROR"
                binding.tvStreamStatusBadge.setTextColor(ContextCompat.getColor(ctx, R.color.status_red))
            }
        }

        val kbSent = telemetry.bytesSent / 1024L
        val mbSent = kbSent / 1024.0
        val sentDisplay = if (mbSent >= 1.0) String.format(java.util.Locale.US, "%.1f MB", mbSent) else "$kbSent KB"

        val seconds = (telemetry.durationMs / 1000) % 60
        val minutes = (telemetry.durationMs / (1000 * 60)) % 60
        val hours = (telemetry.durationMs / (1000 * 60 * 60))
        val durationStr = String.format(java.util.Locale.US, "%02d:%02d:%02d", hours, minutes, seconds)

        binding.tvStreamTelemetry.text =
            "Sent: $sentDisplay | ${telemetry.bitrateKbps} kbps | Q: ${telemetry.queueDepth} (Drops: ${telemetry.droppedFrames}) | RTT: ${telemetry.networkLatencyMs}ms | $durationStr"

        val videoKbps = EncoderStateNotifier.currentMetrics.bitrateKbps
        val audioKbps = AudioStateNotifier.currentMetrics.bitrateKbps
        binding.tvStreamBreakdown.text = "Video: $videoKbps kbps | Audio: $audioKbps kbps | Protocol: <0.05%"
    }

    override fun onError(error: com.fairylive.app.core.common.CreatorError) {
        activity?.runOnUiThread {
            if (_binding != null) {
                binding.cardCreatorError.visibility = View.VISIBLE
                binding.tvErrorTitle.text = error.title
                binding.tvErrorMessage.text = error.message
                binding.tvErrorResolution.text = error.resolution
            }
        }
    }

    private fun bindErrorControls() {
        binding.btnDismissError.setOnClickListener {
            binding.cardCreatorError.visibility = View.GONE
            com.fairylive.app.core.common.CreatorErrorNotifier.clear()
        }
        binding.btnErrorAction.setOnClickListener {
            binding.cardCreatorError.visibility = View.GONE
            (activity as? MainActivity)?.navigateToSettings()
        }
        binding.btnConfigureDestination.setOnClickListener {
            (activity as? MainActivity)?.navigateToSettings()
        }
    }
}
