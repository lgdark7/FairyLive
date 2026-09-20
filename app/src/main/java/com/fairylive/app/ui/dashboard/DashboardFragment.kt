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
import com.fairylive.app.core.settings.StreamSettingsRepository
import com.fairylive.app.core.stream.StreamTelemetryNotifier
import com.fairylive.app.core.stream.model.RtmpConnectionState
import com.fairylive.app.core.stream.model.StreamTelemetry
import com.fairylive.app.databinding.FragmentDashboardBinding
import com.fairylive.app.service.FairyStreamService
import com.google.android.material.dialog.MaterialAlertDialogBuilder

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
            val isLandscapeStream = settings.width > settings.height || settings.profile.width > settings.profile.height
            val safeWidth = if (isLandscapeStream) (maxOf(rawW, rawH) / 2) * 2 else (rawW / 2) * 2
            val safeHeight = if (isLandscapeStream) (minOf(rawW, rawH) / 2) * 2 else (rawH / 2) * 2
            val dpi = resources.configuration.densityDpi

            com.fairylive.app.core.lifecycle.StreamLifecycleDiagnostics.capturePermissionGranted()
            com.fairylive.app.core.lifecycle.StreamLifecycleDiagnostics.captureSourceSelected(settings.captureSource.name)

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
        updateCreatorHeaderUI()
        updateRecentActivityUI()
        updateServiceState(StreamStateNotifier.currentState)

        // First-run check: Prompt creator for YouTube Channel Name if not set
        val app = requireActivity().application as FairyLiveApplication
        if (app.settingsRepository.getCreatorName().isBlank()) {
            view.post {
                showCreatorNameDialog(isFirstRun = true)
            }
        }
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

            val validation = StreamConfigurationValidator.validate(
                settings.serverUrl,
                settings.streamKey,
                settings.destination
            )
            if (!validation.isValid) {
                val err = validation as StreamConfigurationValidator.ValidationResult.Error
                val title = if (err.technicalReason.contains("Localhost", ignoreCase = true)) {
                    "Invalid Streaming Destination"
                } else {
                    "Stream Setup Required"
                }
                CreatorErrorNotifier.postError(
                    title = title,
                    message = err.userMessage,
                    resolution = "Tap 'Broadcast Target' below or open Settings to configure."
                )
                androidx.appcompat.app.AlertDialog.Builder(ctx)
                    .setTitle(title)
                    .setMessage(err.userMessage)
                    .setPositiveButton("Open Settings") { _, _ ->
                        val bottomNav = requireActivity().findViewById<com.google.android.material.bottomnavigation.BottomNavigationView>(R.id.bottom_navigation)
                        bottomNav?.selectedItemId = R.id.nav_settings
                    }
                    .setNegativeButton("Dismiss", null)
                    .show()
                return@setOnClickListener
            }

            if (settings.captureSource == com.fairylive.app.core.settings.CaptureSource.SINGLE_APP && Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                com.fairylive.app.core.common.CreatorErrorNotifier.postError(
                    title = "Single-App Capture Unsupported",
                    message = "Single-App capture requires Android 14+ (API 34). Your device is running Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT}).",
                    resolution = "Switch Capture Source to 'Entire Screen' in Settings."
                )
                return@setOnClickListener
            }

            CaptureStateNotifier.updateState(CaptureState.AUTHORIZING)
            val projectionManager = ctx.getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            val captureIntent = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                val config = if (settings.captureSource == com.fairylive.app.core.settings.CaptureSource.SINGLE_APP) {
                    MediaProjectionConfig.createConfigForUserChoice()
                } else {
                    MediaProjectionConfig.createConfigForDefaultDisplay()
                }
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

        // Shortcuts & Cards
        binding.cardStreamPreview.setOnClickListener {
            binding.btnGoLive.performClick()
        }

        binding.cardGridLiveChat.setOnClickListener {
            showLiveChatDialog()
        }

        binding.rowShortcutStudio.setOnClickListener {
            (activity as? MainActivity)?.navigateToStudio()
        }

        binding.cardGridAnalytics.setOnClickListener {
            (activity as? MainActivity)?.openDiagnostics()
        }

        binding.rowShortcutSettings.setOnClickListener {
            (activity as? MainActivity)?.navigateToSettings()
        }

        binding.rowShortcutDestination.setOnClickListener {
            (activity as? MainActivity)?.navigateToSettings()
        }

        binding.btnHeaderNotification.setOnClickListener {
            val isLive = StreamStateNotifier.currentState.isActive
            val msg = if (isLive) "Broadcasting live to YouTube!" else "All systems ready. Tap Start Streaming to go live."
            Toast.makeText(requireContext(), msg, Toast.LENGTH_SHORT).show()
        }

        binding.btnCreatorAvatar.setOnClickListener {
            showCreatorNameDialog(isFirstRun = false)
        }

        binding.layoutCreatorHeader.setOnClickListener {
            showCreatorNameDialog(isFirstRun = false)
        }

        binding.cardRecentActivity.setOnClickListener {
            handleRecentActivityClick()
        }

        binding.btnRecentActivityPlay.setOnClickListener {
            handleRecentActivityClick()
        }

        binding.btnDismissError.setOnClickListener {
            binding.cardCreatorError.visibility = View.GONE
            CreatorErrorNotifier.clear()
        }

        binding.btnErrorActionDismiss.setOnClickListener {
            binding.cardCreatorError.visibility = View.GONE
            CreatorErrorNotifier.clear()
        }

        binding.btnErrorActionSettings.setOnClickListener {
            binding.cardCreatorError.visibility = View.GONE
            (activity as? MainActivity)?.navigateToSettings()
        }
    }

    private var activeStreamStartTimeMs: Long = 0L

    private fun showLiveChatDialog() {
        val app = requireActivity().application as FairyLiveApplication
        val settings = app.settingsRepository.loadSettings(app.deviceCapability.recommendedProfile)
        val currentUrl = settings.youtubeLiveUrl

        val input = android.widget.EditText(requireContext()).apply {
            hint = "e.g. https://youtube.com/live/xxxx or @Channel"
            setText(currentUrl)
            setSingleLine(true)
            setTextColor(ContextCompat.getColor(context, R.color.text_primary))
            setHintTextColor(ContextCompat.getColor(context, R.color.text_muted))
            setBackgroundResource(R.drawable.bg_input_field)
            val pad = (14 * resources.displayMetrics.density).toInt()
            setPadding(pad, pad, pad, pad)
        }

        val container = android.widget.FrameLayout(requireContext()).apply {
            val padH = (20 * resources.displayMetrics.density).toInt()
            val padV = (10 * resources.displayMetrics.density).toInt()
            setPadding(padH, padV, padH, padV)
            addView(input)
        }

        MaterialAlertDialogBuilder(requireContext())
            .setTitle("YouTube Live Chat")
            .setMessage("Enter your YouTube Live Stream URL, Video ID, or Channel Handle to enable the real-time vanishing chat overlay:")
            .setView(container)
            .setPositiveButton("Connect Chat") { _, _ ->
                val newUrl = input.text.toString().trim()
                app.settingsRepository.setYouTubeLiveUrl(newUrl)
                if (newUrl.isNotEmpty()) {
                    com.fairylive.app.core.youtube.YouTubeLiveIntegrationService.startLiveChatPolling(newUrl)
                    Toast.makeText(requireContext(), "Connected to YouTube Live Chat!", Toast.LENGTH_SHORT).show()
                } else {
                    com.fairylive.app.core.youtube.YouTubeLiveIntegrationService.stopLiveChatPolling()
                    Toast.makeText(requireContext(), "Live chat disconnected", Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun getDynamicGreeting(): String {
        val hour = java.util.Calendar.getInstance().get(java.util.Calendar.HOUR_OF_DAY)
        return when (hour) {
            in 5..11 -> "Good Morning"
            in 12..16 -> "Good Afternoon"
            in 17..21 -> "Good Evening"
            else -> "Good Night"
        }
    }

    private fun updateCreatorHeaderUI() {
        if (_binding == null) return
        val app = (context?.applicationContext as? FairyLiveApplication) ?: return
        val name = app.settingsRepository.getCreatorName().ifBlank { "Creator" }
        binding.tvCreatorName.text = name
        binding.tvAvatarInitial.text = name.first().uppercase()
        binding.tvStreamStatusHeadline.text = getDynamicGreeting()
    }

    private fun showCreatorNameDialog(isFirstRun: Boolean) {
        val ctx = context ?: return
        val app = ctx.applicationContext as FairyLiveApplication
        val currentName = app.settingsRepository.getCreatorName()

        val input = android.widget.EditText(ctx).apply {
            hint = "e.g. Dark Gaming, ProStreamer"
            setText(currentName)
            setSingleLine(true)
            setTextColor(ContextCompat.getColor(context, R.color.text_primary))
            setHintTextColor(ContextCompat.getColor(context, R.color.text_muted))
            setBackgroundResource(R.drawable.bg_input_field)
            val pad = (14 * resources.displayMetrics.density).toInt()
            setPadding(pad, pad, pad, pad)
        }

        val container = android.widget.FrameLayout(ctx).apply {
            val padH = (20 * resources.displayMetrics.density).toInt()
            val padV = (10 * resources.displayMetrics.density).toInt()
            setPadding(padH, padV, padH, padV)
            addView(input)
        }

        val title = if (isFirstRun) "Welcome to FAIRY LIVE! 🎮" else "Edit Channel Name"
        val message = if (isFirstRun)
            "Enter your YouTube Channel Name or Streamer Handle to personalize your dashboard:"
        else
            "Update your YouTube Channel Name or Streamer Handle:"

        MaterialAlertDialogBuilder(ctx)
            .setTitle(title)
            .setMessage(message)
            .setView(container)
            .setCancelable(!isFirstRun)
            .setPositiveButton(if (isFirstRun) "Let's Go!" else "Save") { _, _ ->
                val newName = input.text.toString().trim()
                if (newName.isNotEmpty()) {
                    app.settingsRepository.setCreatorName(newName)
                    updateCreatorHeaderUI()
                    Toast.makeText(ctx, "Welcome, $newName! 👑", Toast.LENGTH_SHORT).show()
                }
            }
            .apply {
                if (!isFirstRun) {
                    setNegativeButton("Cancel", null)
                }
            }
            .show()
    }

    private fun isPackageInstalled(pkg: String): Boolean {
        return try {
            requireContext().packageManager.getPackageInfo(pkg, 0)
            true
        } catch (_: Exception) {
            false
        }
    }

    private fun updateRecentActivityUI() {
        if (_binding == null) return
        val app = (context?.applicationContext as? FairyLiveApplication) ?: return
        val recent = app.settingsRepository.getRecentStreamSession()

        if (recent != null) {
            val diffMs = System.currentTimeMillis() - recent.timestampMs
            val relativeTime = when {
                diffMs < 60_000 -> "Just now"
                diffMs < 3600_000 -> "${diffMs / 60_000}m ago"
                diffMs < 86400_000 -> "${diffMs / 3600_000}h ago"
                else -> "${diffMs / 86400_000}d ago"
            }
            val durMin = recent.durationSeconds / 60
            val durSec = recent.durationSeconds % 60
            val durStr = if (durMin > 0) "${durMin}m ${durSec}s" else "${durSec}s"

            binding.tvRecentActivityTitle.text = recent.gameTitle
            binding.tvRecentActivitySubtitle.text = "${recent.quality} • ${durStr} • ${relativeTime}"
        } else {
            val isFfInstalled = isPackageInstalled("com.dts.freefiremax") || isPackageInstalled("com.dts.freefireth")
            if (isFfInstalled) {
                binding.tvRecentActivityTitle.text = "Free Fire MAX"
                binding.tvRecentActivitySubtitle.text = "Installed • Tap to launch & stream"
            } else {
                binding.tvRecentActivityTitle.text = "No recent streams"
                binding.tvRecentActivitySubtitle.text = "Start your first stream above"
            }
        }
    }

    private fun handleRecentActivityClick() {
        val ctx = context ?: return
        val pm = ctx.packageManager
        val launchIntent = pm.getLaunchIntentForPackage("com.dts.freefiremax")
            ?: pm.getLaunchIntentForPackage("com.dts.freefireth")

        if (launchIntent != null) {
            Toast.makeText(ctx, "Launching Free Fire MAX...", Toast.LENGTH_SHORT).show()
            startActivity(launchIntent)
            if (!StreamStateNotifier.currentState.isActive) {
                binding.btnGoLive.performClick()
            }
        } else {
            binding.btnGoLive.performClick()
        }
    }

    private fun updateSceneUI(scene: BroadcastScene) {
        if (_binding == null) return
        binding.tvCardSceneBadge.text = scene.name
        val count = scene.overlayElements.size
        val isLive = StreamStateNotifier.currentState.isActive
        if (!isLive) {
            binding.tvPreviewSceneName.text = "Start Streaming"
            binding.tvPreviewOverlaysSummary.text = "Turn your gameplay into a story."
        }
        binding.tvShortcutDestinationSubtext.text = "1080p 60fps • 2h ago"
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
                if (activeStreamStartTimeMs > 0L) {
                    val durationSec = (System.currentTimeMillis() - activeStreamStartTimeMs) / 1000
                    if (durationSec >= 3) {
                        val app = ctx.applicationContext as FairyLiveApplication
                        val settings = app.settingsRepository.loadSettings(app.deviceCapability.recommendedProfile)
                        app.settingsRepository.saveRecentStreamSession(
                            StreamSettingsRepository.RecentStreamSession(
                                gameTitle = "Free Fire MAX",
                                packageName = "com.dts.freefiremax",
                                quality = "${settings.height}p ${settings.fps}fps",
                                durationSeconds = durationSec,
                                timestampMs = System.currentTimeMillis()
                            )
                        )
                        updateRecentActivityUI()
                    }
                    activeStreamStartTimeMs = 0L
                }

                binding.tvStreamStatusHeadline.text = "Stream Stopped"
                binding.tvStreamStatusSubtext.text = "An error occurred during broadcasting"
                binding.tvDashboardStatusPill.text = "ERROR"
                binding.tvDashboardStatusPill.setTextColor(ContextCompat.getColor(ctx, R.color.status_red))

                binding.viewStatusDot.backgroundTintList = ColorStateList.valueOf(ContextCompat.getColor(ctx, R.color.status_red))
                binding.tvReadinessLabel.text = "Stream interrupted"

                binding.btnGoLive.setIconResource(R.drawable.ic_warning)
                binding.btnGoLive.text = ""
                binding.btnGoLive.backgroundTintList = ColorStateList.valueOf(ContextCompat.getColor(ctx, R.color.status_red))
                binding.btnGoLive.isEnabled = true

                binding.tvPreviewSceneName.text = "Stream Stopped"
                binding.tvPreviewOverlaysSummary.text = "Tap to retry broadcast"
                binding.tvDashboardTimer.visibility = View.GONE
            }
            StreamState.STREAMING -> {
                if (activeStreamStartTimeMs == 0L) {
                    activeStreamStartTimeMs = System.currentTimeMillis()
                }

                binding.tvStreamStatusHeadline.text = "Live On Air 🔴"
                binding.tvStreamStatusSubtext.text = "Broadcasting to RTMP destination"
                binding.tvDashboardStatusPill.text = "● LIVE"
                binding.tvDashboardStatusPill.setTextColor(ContextCompat.getColor(ctx, R.color.status_green))

                binding.viewStatusDot.backgroundTintList = ColorStateList.valueOf(ContextCompat.getColor(ctx, R.color.status_green))
                binding.tvReadinessLabel.text = "Broadcasting live"

                binding.btnGoLive.setIconResource(R.drawable.ic_stop)
                binding.btnGoLive.text = ""
                binding.btnGoLive.backgroundTintList = ColorStateList.valueOf(ContextCompat.getColor(ctx, R.color.status_red))
                binding.btnGoLive.isEnabled = true

                binding.tvPreviewSceneName.text = "Broadcasting Live"
                binding.tvPreviewOverlaysSummary.text = "Streaming live to YouTube"
                binding.tvDashboardTimer.visibility = View.VISIBLE
            }
            StreamState.STARTING -> {
                binding.tvStreamStatusHeadline.text = "Starting Stream..."
                binding.tvStreamStatusSubtext.text = "Initializing hardware encoder & network handshake"
                binding.tvDashboardStatusPill.text = "CONNECTING"
                binding.tvDashboardStatusPill.setTextColor(ContextCompat.getColor(ctx, R.color.accent_primary))

                binding.viewStatusDot.backgroundTintList = ColorStateList.valueOf(ContextCompat.getColor(ctx, R.color.accent_primary))
                binding.tvReadinessLabel.text = "Connecting..."

                binding.btnGoLive.setIconResource(R.drawable.ic_stream)
                binding.btnGoLive.text = ""
                binding.btnGoLive.backgroundTintList = ColorStateList.valueOf(ContextCompat.getColor(ctx, R.color.accent_primary))
                binding.btnGoLive.isEnabled = false

                binding.tvPreviewSceneName.text = "Connecting..."
                binding.tvPreviewOverlaysSummary.text = "Establishing RTMP handshake"
                binding.tvDashboardTimer.visibility = View.GONE
            }
            StreamState.STOPPING -> {
                binding.tvStreamStatusHeadline.text = "Stopping Stream..."
                binding.tvStreamStatusSubtext.text = "Flushing muxer and tearing down encoders"
                binding.tvDashboardStatusPill.text = "STOPPING"
                binding.tvDashboardStatusPill.setTextColor(ContextCompat.getColor(ctx, R.color.status_amber))

                binding.viewStatusDot.backgroundTintList = ColorStateList.valueOf(ContextCompat.getColor(ctx, R.color.status_amber))
                binding.tvReadinessLabel.text = "Wrapping up..."

                binding.btnGoLive.setIconResource(R.drawable.ic_stream)
                binding.btnGoLive.text = ""
                binding.btnGoLive.backgroundTintList = ColorStateList.valueOf(ContextCompat.getColor(ctx, R.color.status_amber))
                binding.btnGoLive.isEnabled = false

                binding.tvPreviewSceneName.text = "Stopping..."
                binding.tvPreviewOverlaysSummary.text = "Wrapping up broadcast"
                binding.tvDashboardTimer.visibility = View.GONE
            }
            else -> {
                if (activeStreamStartTimeMs > 0L) {
                    val durationSec = (System.currentTimeMillis() - activeStreamStartTimeMs) / 1000
                    if (durationSec >= 3) {
                        val app = ctx.applicationContext as FairyLiveApplication
                        val settings = app.settingsRepository.loadSettings(app.deviceCapability.recommendedProfile)
                        app.settingsRepository.saveRecentStreamSession(
                            StreamSettingsRepository.RecentStreamSession(
                                gameTitle = "Free Fire MAX",
                                packageName = "com.dts.freefiremax",
                                quality = "${settings.height}p ${settings.fps}fps",
                                durationSeconds = durationSec,
                                timestampMs = System.currentTimeMillis()
                            )
                        )
                        updateRecentActivityUI()
                    }
                    activeStreamStartTimeMs = 0L
                }

                binding.tvStreamStatusHeadline.text = getDynamicGreeting()
                binding.tvStreamStatusSubtext.text = "Ready to stream?"
                binding.tvDashboardStatusPill.text = "Ready"
                binding.tvDashboardStatusPill.setTextColor(ContextCompat.getColor(ctx, R.color.accent_primary))

                binding.viewStatusDot.backgroundTintList = ColorStateList.valueOf(ContextCompat.getColor(ctx, R.color.status_green))
                binding.tvReadinessLabel.text = "Ready to go live"

                binding.btnGoLive.setIconResource(R.drawable.ic_arrow_forward)
                binding.btnGoLive.text = ""
                binding.btnGoLive.backgroundTintList = ColorStateList.valueOf(ContextCompat.getColor(ctx, R.color.accent_primary))
                binding.btnGoLive.isEnabled = true

                binding.tvPreviewSceneName.text = "Start Streaming"
                binding.tvPreviewOverlaysSummary.text = "Turn your gameplay into a story."
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

                val diag = error.diagnosticInfo
                if (diag != null) {
                    binding.tvErrorDiagnostic.visibility = View.VISIBLE
                    binding.tvErrorDiagnostic.text = diag.toSafeString()
                } else {
                    binding.tvErrorDiagnostic.visibility = View.GONE
                }
            }
        }
    }
}
