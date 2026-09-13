package com.fairylive.app.ui.dashboard

import android.app.Activity
import android.content.Context
import android.content.res.Configuration
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
    AudioStateNotifier.MetricsListener {

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

            FairyStreamService.startCapture(
                context = ctx,
                resultCode = result.resultCode,
                data = result.data!!,
                width = safeWidth,
                height = safeHeight,
                dpi = dpi,
                enableEncoding = true,
                enableAudio = true,
                bitrateKbps = settings.videoBitrateKbps,
                fps = settings.fps
            )
        } else {
            Toast.makeText(requireContext(), "Screen capture permission denied", Toast.LENGTH_SHORT).show()
            CaptureStateNotifier.updateState(CaptureState.IDLE)
        }
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

        bindDeviceCapability()
        bindCaptureControls()
        bindEncoderControls()
        bindAudioControls()
        bindServiceController()
        bindPermissionControls()
        updatePermissionIndicators()
        updateServiceState(StreamStateNotifier.currentState)
        updateCaptureUI(CaptureStateNotifier.currentState, CaptureStateNotifier.currentMetrics)
        updateEncoderUI(EncoderStateNotifier.currentState, EncoderStateNotifier.currentMetrics)
        updateAudioUI(AudioStateNotifier.currentState, AudioStateNotifier.currentMetrics)
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
            val missing = PermissionManager.getMissingPermissions(ctx)
            if (missing.isNotEmpty()) {
                Toast.makeText(ctx, "Grant required permissions first", Toast.LENGTH_SHORT).show()
                permissionLauncher.launch(missing.toTypedArray())
                return@setOnClickListener
            }

            CaptureStateNotifier.updateState(CaptureState.AUTHORIZING)
            val projectionManager = ctx.getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            projectionLauncher.launch(projectionManager.createScreenCaptureIntent())
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
                binding.tvAudioStatusBadge.text = "RECORDING (LIVE)"
                binding.tvAudioStatusBadge.setTextColor(ContextCompat.getColor(ctx, R.color.status_green))
                binding.btnStartAudio.visibility = View.GONE
                binding.layoutAudioControls.visibility = View.VISIBLE
                binding.btnMuteAudio.text = "Mute"
                binding.btnMuteAudio.setTextColor(ContextCompat.getColor(ctx, R.color.status_amber))

                binding.pbAudioLevel.progress = metrics.peakAmplitudePercent
                binding.tvAudioLevelLabel.text = "Input Level: ${metrics.peakAmplitudePercent}%"

                val sourceText = if (metrics.isDeviceAudio) "Device Audio" else "Microphone"
                binding.tvAudioTelemetry.text = "Enc: ${metrics.encodedFrames} frames | ${metrics.bitrateKbps} kbps | ${metrics.sampleRate}Hz ${metrics.channels}ch | $sourceText | PTS Sync: OK"
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
                binding.tvAudioTelemetry.text = "Enc: ${metrics.encodedFrames} frames | MUTED (Silence Gen) | PTS Sync: Preserved"
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
}
