package com.fairylive.app.ui.dashboard

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.res.ColorStateList
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
import com.fairylive.app.MainActivity
import com.fairylive.app.R
import com.fairylive.app.core.audio.AudioStateNotifier
import com.fairylive.app.core.audio.model.AudioCaptureState
import com.fairylive.app.core.camera.CameraState
import com.fairylive.app.core.camera.CameraStateNotifier
import com.fairylive.app.core.capture.CaptureStateNotifier
import com.fairylive.app.core.capture.model.CaptureState
import com.fairylive.app.core.common.CreatorError
import com.fairylive.app.core.common.CreatorErrorNotifier
import com.fairylive.app.core.lifecycle.StreamState
import com.fairylive.app.core.lifecycle.StreamStateNotifier
import com.fairylive.app.core.permission.PermissionManager
import com.fairylive.app.core.scene.SceneStateNotifier
import com.fairylive.app.core.scene.model.BroadcastScene
import com.fairylive.app.core.settings.StreamConfigurationValidator
import com.fairylive.app.core.stream.StreamTelemetryNotifier
import com.fairylive.app.core.stream.model.RtmpConnectionState
import com.fairylive.app.core.stream.model.StreamTelemetry
import com.fairylive.app.databinding.FragmentDashboardBinding
import com.fairylive.app.service.FairyStreamService

class DashboardFragment : Fragment(),
    StreamStateNotifier.Listener,
    CaptureStateNotifier.StateListener,
    AudioStateNotifier.StateListener,
    StreamTelemetryNotifier.StateListener,
    StreamTelemetryNotifier.TelemetryListener,
    CreatorErrorNotifier.ErrorListener {

    private var _binding: FragmentDashboardBinding? = null
    private val binding get() = _binding!!

    private var isCameraActive: Boolean = false
    private var isMicMuted: Boolean = false

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { _ ->
        // Continue if permissions granted
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
                enableRtmp = settings.serverUrl.isNotEmpty(),
                enableCompositor = true,
                singleOverlayOnly = false,
                rtmpUrl = settings.serverUrl,
                streamKey = settings.streamKey,
                bitrateKbps = settings.videoBitrateKbps,
                fps = settings.fps
            )
        } else {
            Toast.makeText(requireContext(), "Screen capture permission denied", Toast.LENGTH_SHORT).show()
            CreatorErrorNotifier.postError(
                title = "Screen Recording Permission Denied",
                message = "You did not grant screen recording permission, so the broadcast was not started.",
                resolution = "Tap GO LIVE again and select 'Start' (or 'Entire screen') on the permission dialog."
            )
            CaptureStateNotifier.updateState(CaptureState.IDLE)
        }
    }

    private val cameraStateListener: (CameraState, CameraState) -> Unit = { newState, _ ->
        activity?.runOnUiThread {
            if (_binding != null) {
                isCameraActive = (newState == CameraState.STREAMING)
                updateCameraControls()
            }
        }
    }

    private val sceneStateListener: (BroadcastScene) -> Unit = { scene ->
        activity?.runOnUiThread {
            if (_binding != null) {
                updateSceneUI(scene)
            }
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
        AudioStateNotifier.addStateListener(this)
        StreamTelemetryNotifier.addStateListener(this)
        StreamTelemetryNotifier.addTelemetryListener(this)
        CreatorErrorNotifier.addListener(this)
        CameraStateNotifier.addStateListener(cameraStateListener)
        SceneStateNotifier.addListener(sceneStateListener)

        bindActions()
        updateSceneUI(SceneStateNotifier.currentScene)
        updateCameraControls()
        updateServiceState(StreamStateNotifier.currentState)
    }

    override fun onDestroyView() {
        super.onDestroyView()
        StreamStateNotifier.removeListener(this)
        CaptureStateNotifier.removeStateListener(this)
        AudioStateNotifier.removeStateListener(this)
        StreamTelemetryNotifier.removeStateListener(this)
        StreamTelemetryNotifier.removeTelemetryListener(this)
        CreatorErrorNotifier.removeListener(this)
        CameraStateNotifier.removeStateListener(cameraStateListener)
        SceneStateNotifier.removeListener(sceneStateListener)
        _binding = null
    }

    private fun bindActions() {
        // Go Live / Stop broadcast
        binding.btnGoLive.setOnClickListener {
            val ctx = requireContext()
            if (StreamStateNotifier.currentState.isActive || CaptureStateNotifier.currentState.isActive) {
                FairyStreamService.stopCapture(ctx)
                return@setOnClickListener
            }

            val missing = PermissionManager.getMissingPermissions(ctx)
            if (missing.isNotEmpty()) {
                CreatorErrorNotifier.postError(
                    title = "Permissions Required",
                    message = "Fairy Live requires microphone and notification permissions to broadcast live.",
                    resolution = "Grant the missing permissions to start streaming."
                )
                permissionLauncher.launch(missing.toTypedArray())
                return@setOnClickListener
            }

            val app = ctx.applicationContext as FairyLiveApplication
            val settings = app.settingsRepository.loadSettings(app.deviceCapability.recommendedProfile)

            if (settings.serverUrl.isNotEmpty()) {
                val validation = StreamConfigurationValidator.validate(settings.serverUrl, settings.streamKey)
                if (!validation.isValid) {
                    val err = validation as StreamConfigurationValidator.ValidationResult.Error
                    CreatorErrorNotifier.postError(
                        title = "Invalid Stream Destination",
                        message = err.userMessage,
                        resolution = "Tap 'Configure Destination' below to configure a valid RTMP URL."
                    )
                    return@setOnClickListener
                }
            }

            CaptureStateNotifier.updateState(CaptureState.AUTHORIZING)
            val projectionManager = ctx.getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            val captureIntent = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                val config = MediaProjectionConfig.createConfigForDefaultDisplay()
                projectionManager.createScreenCaptureIntent(config)
            } else {
                @Suppress("DEPRECATION")
                projectionManager.createScreenCaptureIntent()
            }
            projectionLauncher.launch(captureIntent)
        }

        // Camera Quick Toggle
        binding.btnToggleCamera.setOnClickListener {
            val ctx = requireContext()
            if (!PermissionManager.hasCameraPermission(ctx)) {
                permissionLauncher.launch(arrayOf(android.Manifest.permission.CAMERA))
                return@setOnClickListener
            }
            try {
                FairyStreamService.toggleFacecam(ctx)
            } catch (e: Exception) {
                Toast.makeText(ctx, "Start broadcast first to toggle Camera", Toast.LENGTH_SHORT).show()
            }
        }

        // Mic Quick Toggle
        binding.btnToggleMic.setOnClickListener {
            val ctx = requireContext()
            if (!PermissionManager.hasAudioPermission(ctx)) {
                permissionLauncher.launch(arrayOf(android.Manifest.permission.RECORD_AUDIO))
                return@setOnClickListener
            }
            try {
                if (isMicMuted) {
                    FairyStreamService.unmuteAudio(ctx)
                } else {
                    FairyStreamService.muteAudio(ctx)
                }
            } catch (e: Exception) {
                Toast.makeText(ctx, "Start broadcast first to toggle Mic", Toast.LENGTH_SHORT).show()
            }
        }

        // Shortcuts
        binding.cardStreamPreview.setOnClickListener {
            (activity as? MainActivity)?.navigateToStudio()
        }

        binding.rowShortcutDestination.setOnClickListener {
            (activity as? MainActivity)?.navigateToSettings()
        }

        binding.rowShortcutStudio.setOnClickListener {
            (activity as? MainActivity)?.navigateToStudio()
        }

        binding.rowShortcutSettings.setOnClickListener {
            (activity as? MainActivity)?.navigateToSettings()
        }

        binding.btnDismissError.setOnClickListener {
            binding.cardCreatorError.visibility = View.GONE
            CreatorErrorNotifier.clear()
        }
    }

    private fun updateSceneUI(scene: BroadcastScene) {
        if (_binding == null) return
        binding.tvPreviewSceneName.text = "Scene: ${scene.name}"
        binding.tvCardSceneBadge.text = scene.name
        val count = scene.overlayElements.size
        binding.tvPreviewOverlaysSummary.text = if (count == 0) {
            "No overlays active • Tap to customize"
        } else {
            "$count overlay layers active • Tap to edit"
        }

        val app = requireContext().applicationContext as FairyLiveApplication
        val settings = app.settingsRepository.loadSettings(app.deviceCapability.recommendedProfile)
        binding.tvShortcutDestinationSubtext.text = if (settings.serverUrl.isNotEmpty()) {
            settings.serverUrl
        } else {
            "Not configured • Tap to set RTMP endpoint"
        }
    }

    private fun updateCameraControls() {
        if (_binding == null) return
        val ctx = context ?: return
        if (isCameraActive) {
            binding.btnToggleCamera.setTextColor(ContextCompat.getColor(ctx, R.color.accent_primary))
            binding.btnToggleCamera.strokeColor = ColorStateList.valueOf(ContextCompat.getColor(ctx, R.color.accent_primary))
            binding.btnToggleCamera.iconTint = ColorStateList.valueOf(ContextCompat.getColor(ctx, R.color.accent_primary))
            binding.btnToggleCamera.text = "Camera: ON"
        } else {
            binding.btnToggleCamera.setTextColor(ContextCompat.getColor(ctx, R.color.text_primary))
            binding.btnToggleCamera.strokeColor = ColorStateList.valueOf(ContextCompat.getColor(ctx, R.color.bg_card_stroke))
            binding.btnToggleCamera.iconTint = ColorStateList.valueOf(ContextCompat.getColor(ctx, R.color.text_primary))
            binding.btnToggleCamera.text = "Camera"
        }
    }

    private fun updateAudioControls() {
        if (_binding == null) return
        val ctx = context ?: return
        if (isMicMuted) {
            binding.btnToggleMic.setTextColor(ContextCompat.getColor(ctx, R.color.status_amber))
            binding.btnToggleMic.strokeColor = ColorStateList.valueOf(ContextCompat.getColor(ctx, R.color.status_amber))
            binding.btnToggleMic.iconTint = ColorStateList.valueOf(ContextCompat.getColor(ctx, R.color.status_amber))
            binding.btnToggleMic.text = "Mic: Muted"
        } else {
            binding.btnToggleMic.setTextColor(ContextCompat.getColor(ctx, R.color.text_primary))
            binding.btnToggleMic.strokeColor = ColorStateList.valueOf(ContextCompat.getColor(ctx, R.color.bg_card_stroke))
            binding.btnToggleMic.iconTint = ColorStateList.valueOf(ContextCompat.getColor(ctx, R.color.text_primary))
            binding.btnToggleMic.text = "Microphone"
        }
    }

    private fun updateServiceState(state: StreamState) {
        val ctx = context ?: return
        when (state) {
            StreamState.ERROR -> {
                binding.tvStreamStatusHeadline.text = "Stream Stopped"
                binding.tvStreamStatusSubtext.text = "An error occurred during broadcasting"
                binding.tvDashboardStatusPill.text = "ERROR"
                binding.tvDashboardStatusPill.setTextColor(ContextCompat.getColor(ctx, R.color.status_red))

                binding.viewStatusDot.backgroundTintList = ColorStateList.valueOf(ContextCompat.getColor(ctx, R.color.status_red))
                binding.tvReadinessLabel.text = "Stream interrupted"

                binding.btnGoLive.text = "● Retry Broadcast"
                binding.btnGoLive.backgroundTintList = ColorStateList.valueOf(ContextCompat.getColor(ctx, R.color.status_red))
                binding.btnGoLive.isEnabled = true

                binding.tvDashboardTimer.visibility = View.GONE
            }
            StreamState.STREAMING -> {
                binding.tvStreamStatusHeadline.text = "Live On Air 🔴"
                binding.tvStreamStatusSubtext.text = "Broadcasting to RTMP destination"
                binding.tvDashboardStatusPill.text = "● LIVE"
                binding.tvDashboardStatusPill.setTextColor(ContextCompat.getColor(ctx, R.color.status_green))

                binding.viewStatusDot.backgroundTintList = ColorStateList.valueOf(ContextCompat.getColor(ctx, R.color.status_green))
                binding.tvReadinessLabel.text = "Broadcasting live"

                binding.btnGoLive.text = "■ Stop Broadcast"
                binding.btnGoLive.backgroundTintList = ColorStateList.valueOf(ContextCompat.getColor(ctx, R.color.status_red))
                binding.btnGoLive.isEnabled = true

                binding.tvDashboardTimer.visibility = View.VISIBLE
            }
            StreamState.STARTING -> {
                binding.tvStreamStatusHeadline.text = "Starting Stream..."
                binding.tvStreamStatusSubtext.text = "Initializing hardware encoder & network handshake"
                binding.tvDashboardStatusPill.text = "CONNECTING"
                binding.tvDashboardStatusPill.setTextColor(ContextCompat.getColor(ctx, R.color.accent_primary))

                binding.viewStatusDot.backgroundTintList = ColorStateList.valueOf(ContextCompat.getColor(ctx, R.color.accent_primary))
                binding.tvReadinessLabel.text = "Connecting to RTMP ingest..."

                binding.btnGoLive.text = "Connecting..."
                binding.btnGoLive.backgroundTintList = ColorStateList.valueOf(ContextCompat.getColor(ctx, R.color.accent_primary))
                binding.btnGoLive.isEnabled = false

                binding.tvDashboardTimer.visibility = View.GONE
            }
            StreamState.STOPPING -> {
                binding.tvStreamStatusHeadline.text = "Stopping Stream..."
                binding.tvStreamStatusSubtext.text = "Flushing muxer and tearing down encoders"
                binding.tvDashboardStatusPill.text = "STOPPING"
                binding.tvDashboardStatusPill.setTextColor(ContextCompat.getColor(ctx, R.color.status_amber))

                binding.viewStatusDot.backgroundTintList = ColorStateList.valueOf(ContextCompat.getColor(ctx, R.color.status_amber))
                binding.tvReadinessLabel.text = "Wrapping up broadcast..."

                binding.btnGoLive.text = "Stopping..."
                binding.btnGoLive.backgroundTintList = ColorStateList.valueOf(ContextCompat.getColor(ctx, R.color.status_amber))
                binding.btnGoLive.isEnabled = false

                binding.tvDashboardTimer.visibility = View.GONE
            }
            else -> {
                binding.tvStreamStatusHeadline.text = "Good evening 👋"
                binding.tvStreamStatusSubtext.text = "Balanced HD • 720p 30fps • Low Latency"
                binding.tvDashboardStatusPill.text = "Ready"
                binding.tvDashboardStatusPill.setTextColor(ContextCompat.getColor(ctx, R.color.accent_primary))

                binding.viewStatusDot.backgroundTintList = ColorStateList.valueOf(ContextCompat.getColor(ctx, R.color.status_green))
                binding.tvReadinessLabel.text = "Ready to go live"

                binding.btnGoLive.text = "Start Live"
                binding.btnGoLive.backgroundTintList = ColorStateList.valueOf(ContextCompat.getColor(ctx, R.color.accent_primary))
                binding.btnGoLive.isEnabled = true

                binding.tvDashboardTimer.visibility = View.GONE
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
            if (_binding != null && newState == CaptureState.IDLE && StreamStateNotifier.currentState.isIdle) {
                updateServiceState(StreamState.IDLE)
            }
        }
    }

    override fun onAudioStateChanged(newState: AudioCaptureState, previousState: AudioCaptureState) {
        activity?.runOnUiThread {
            if (_binding != null) {
                isMicMuted = (newState == AudioCaptureState.MUTED)
                updateAudioControls()
            }
        }
    }

    override fun onConnectionStateChanged(newState: RtmpConnectionState, previousState: RtmpConnectionState) {
        activity?.runOnUiThread {
            if (_binding != null && newState == RtmpConnectionState.PUBLISHING) {
                updateServiceState(StreamState.STREAMING)
            }
        }
    }

    override fun onTelemetryUpdated(telemetry: StreamTelemetry) {
        activity?.runOnUiThread {
            if (_binding != null && StreamStateNotifier.currentState == StreamState.STREAMING) {
                val seconds = (telemetry.durationMs / 1000) % 60
                val minutes = (telemetry.durationMs / (1000 * 60)) % 60
                val hours = (telemetry.durationMs / (1000 * 60 * 60))
                binding.tvDashboardTimer.text = String.format(java.util.Locale.US, "%02d:%02d:%02d", hours, minutes, seconds)
            }
        }
    }

    override fun onError(error: CreatorError) {
        activity?.runOnUiThread {
            if (_binding != null) {
                binding.cardCreatorError.visibility = View.VISIBLE
                binding.tvErrorTitle.text = error.title
                binding.tvErrorMessage.text = error.message
                binding.tvErrorResolution.text = error.resolution
            }
        }
    }
}
