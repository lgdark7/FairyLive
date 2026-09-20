package com.fairylive.app.ui.settings

import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.text.method.HideReturnsTransformationMethod
import android.text.method.PasswordTransformationMethod
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import com.fairylive.app.FairyLiveApplication
import com.fairylive.app.MainActivity
import com.fairylive.app.R
import com.fairylive.app.core.audio.AudioStudioRepository
import com.fairylive.app.core.capability.model.StreamProfile
import com.fairylive.app.core.updater.AppUpdateManager
import com.fairylive.app.core.settings.CaptureSource
import com.fairylive.app.core.settings.StreamConfigurationValidator
import com.fairylive.app.core.settings.StreamDestination
import com.fairylive.app.core.settings.StreamSettings
import com.fairylive.app.core.settings.StreamSettingsRepository
import com.fairylive.app.databinding.FragmentSettingsBinding
import com.fairylive.app.ui.audio.AudioStudioBottomSheetDialog
import com.google.android.material.dialog.MaterialAlertDialogBuilder

class SettingsFragment : Fragment() {

    private var _binding: FragmentSettingsBinding? = null
    private val binding get() = _binding!!

    private var isStreamKeyMasked = true

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentSettingsBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        val app = requireActivity().application as FairyLiveApplication
        val repo = app.settingsRepository
        val currentSettings = repo.loadSettings(app.deviceCapability.recommendedProfile)

        // Initialize AudioStudioRepository
        AudioStudioRepository.init(requireContext())

        setupCategoryChips()
        setupStreamDestinationControls()
        setupStreamKeyControls()
        setupVideoProfileControls()
        setupAudioStudioSection()
        setupCameraAndHudControls()
        setupAdvancedAndStorageControls(app, repo)
        setupLegalComplianceControls()

        populateFields(currentSettings, repo)

        binding.btnSave.setOnClickListener {
            saveFields(showFeedback = true)
        }
        binding.btnSaveHeader.setOnClickListener {
            saveFields(showFeedback = true)
        }
    }

    override fun onResume() {
        super.onResume()
        updateAudioStudioStatus()
    }

    // =========================================================================
    // Category Chip Navigation & Two-Tier Filtering
    // =========================================================================

    private fun setupCategoryChips() {
        val categoryMap = mapOf(
            binding.chipAll to null,
            binding.chipStream to binding.cardCategoryStream,
            binding.chipCapture to binding.cardCategoryCapture,
            binding.chipVideo to binding.cardCategoryVideo,
            binding.chipAudio to binding.cardCategoryAudio,
            binding.chipCamera to binding.cardCategoryCamera,
            binding.chipAdvanced to binding.cardCategoryAdvanced,
            binding.chipLegal to binding.cardCategoryLegal
        )

        val chips = categoryMap.keys.toList()
        val allCards = listOf(
            binding.cardCategoryStream,
            binding.cardCategoryCapture,
            binding.cardCategoryVideo,
            binding.cardCategoryAudio,
            binding.cardCategoryCamera,
            binding.cardCategoryAdvanced,
            binding.cardCategoryLegal
        )

        chips.forEach { chip ->
            chip.setOnClickListener {
                chips.forEach { c ->
                    val isSelected = c == chip
                    c.setBackgroundResource(if (isSelected) R.drawable.bg_chip_selected else R.drawable.bg_chip_unselected)
                    c.setTextColor(
                        ContextCompat.getColor(
                            requireContext(),
                            if (isSelected) R.color.text_on_dark else R.color.text_secondary
                        )
                    )
                }

                val targetCard = categoryMap[chip]
                if (targetCard == null) {
                    // Show all cards in unified scroll view
                    allCards.forEach { it.visibility = View.VISIBLE }
                    binding.scrollSettings.smoothScrollTo(0, 0)
                } else {
                    // Two-tier filter: show only selected category for focused editing
                    allCards.forEach { card ->
                        card.visibility = if (card == targetCard) View.VISIBLE else View.GONE
                    }
                    binding.scrollSettings.smoothScrollTo(0, 0)
                }
            }
        }
    }

    // =========================================================================
    // Stream Destination & Credentials
    // =========================================================================

    private fun setupStreamDestinationControls() {
        binding.rgDestination.setOnCheckedChangeListener { _, checkedId ->
            when (checkedId) {
                R.id.rb_dest_youtube -> {
                    binding.etServerUrl.setText(StreamDestination.YOUTUBE.defaultUrl)
                    binding.etServerUrl.isEnabled = false
                }
                R.id.rb_dest_custom -> {
                    binding.etServerUrl.isEnabled = true
                    val currentText = binding.etServerUrl.text.toString()
                    if (currentText == StreamDestination.YOUTUBE.defaultUrl ||
                        currentText == StreamDestination.LOCAL_TEST.defaultUrl
                    ) {
                        binding.etServerUrl.setText("")
                    }
                }
                R.id.rb_dest_local_test -> {
                    binding.etServerUrl.setText(StreamDestination.LOCAL_TEST.defaultUrl)
                    binding.etServerUrl.isEnabled = true
                }
            }
        }
    }

    private fun setupStreamKeyControls() {
        binding.btnToggleKeyVisibility.setOnClickListener {
            isStreamKeyMasked = !isStreamKeyMasked
            if (isStreamKeyMasked) {
                binding.etStreamKey.transformationMethod = PasswordTransformationMethod.getInstance()
                binding.btnToggleKeyVisibility.setImageResource(R.drawable.ic_visibility)
            } else {
                binding.etStreamKey.transformationMethod = HideReturnsTransformationMethod.getInstance()
                binding.btnToggleKeyVisibility.setImageResource(R.drawable.ic_visibility_off)
            }
            binding.etStreamKey.setSelection(binding.etStreamKey.text?.length ?: 0)
        }

        binding.btnPasteStreamKey.setOnClickListener {
            val clipboard = requireContext().getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
            val clip = clipboard?.primaryClip?.getItemAt(0)?.text?.toString()?.trim()
            if (!clip.isNullOrEmpty()) {
                binding.etStreamKey.setText(clip)
                binding.etStreamKey.setSelection(clip.length)
                Toast.makeText(requireContext(), "Stream key pasted from clipboard", Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(requireContext(), "Clipboard is empty", Toast.LENGTH_SHORT).show()
            }
        }
    }

    // =========================================================================
    // Video Quality & Profile Controls
    // =========================================================================

    private fun setupVideoProfileControls() {
        binding.rgProfiles.setOnCheckedChangeListener { _, checkedId ->
            updateVideoProfileSummary(checkedId)
        }
    }

    private fun updateVideoProfileSummary(checkedId: Int) {
        val summary = when (checkedId) {
            R.id.rb_profile_low -> "Active Spec: 480p • 24 FPS • Video: 1200 kbps • Audio: 64 kbps (Low CPU)"
            R.id.rb_profile_balanced -> "Active Spec: 720p • 30 FPS • Video: 2500 kbps • Audio: 96 kbps (Recommended)"
            R.id.rb_profile_high -> "Active Spec: 1080p • 60 FPS • Video: 4500 kbps • Audio: 128 kbps (Flagship)"
            else -> "Active Spec: 720p • 30 FPS • Video: 2500 kbps"
        }
        binding.tvVideoProfileSummary.text = summary
    }

    // =========================================================================
    // Audio Studio & Sound Pipeline
    // =========================================================================

    private fun setupAudioStudioSection() {
        binding.switchHardwareNs.isChecked = AudioStudioRepository.isNoiseSuppressionEnabled
        binding.switchDspNoiseGate.isChecked = AudioStudioRepository.isNoiseSuppressionEnabled
        binding.switchGodModeNoise.isChecked = AudioStudioRepository.isGodModeNoiseEnabled

        binding.switchHardwareNs.setOnCheckedChangeListener { _, isChecked ->
            AudioStudioRepository.saveDsp(requireContext(), noiseGate = isChecked)
            updateAudioStudioStatus()
        }

        binding.switchDspNoiseGate.setOnCheckedChangeListener { _, isChecked ->
            AudioStudioRepository.saveDsp(requireContext(), noiseGate = isChecked)
            updateAudioStudioStatus()
        }

        binding.switchGodModeNoise.setOnCheckedChangeListener { _, isChecked ->
            AudioStudioRepository.saveDsp(requireContext(), godMode = isChecked)
            updateAudioStudioStatus()
        }

        binding.btnOpenAudioStudio.setOnClickListener {
            AudioStudioBottomSheetDialog().show(parentFragmentManager, AudioStudioBottomSheetDialog.TAG)
        }

        updateAudioStudioStatus()
    }

    private fun updateAudioStudioStatus() {
        if (_binding == null) return
        AudioStudioRepository.init(requireContext())
        val micPercent = (AudioStudioRepository.micVolume * 100).toInt()
        val gamePercent = (AudioStudioRepository.gameVolume * 100).toInt()
        val masterPercent = (AudioStudioRepository.masterVolume * 100).toInt()
        val preset = AudioStudioRepository.activePreset
        val nsStatus = when {
            !AudioStudioRepository.isNoiseSuppressionEnabled -> "OFF"
            AudioStudioRepository.isGodModeNoiseEnabled -> "🔥 GOD-MODE"
            else -> "ON"
        }
        binding.tvAudioStudioStatus.text = "Mic: $micPercent% • Game: $gamePercent% • Master: $masterPercent% • DSP: $nsStatus • Preset: $preset"
    }

    // =========================================================================
    // Camera & Visual Overlays
    // =========================================================================

    private fun setupCameraAndHudControls() {
        val prefs = requireContext().getSharedPreferences("fairy_hud_prefs", Context.MODE_PRIVATE)
        binding.switchHudMicAlert.isChecked = prefs.getBoolean("hud_mic_alert_enabled", true)

        binding.switchHudMicAlert.setOnCheckedChangeListener { _, isChecked ->
            prefs.edit().putBoolean("hud_mic_alert_enabled", isChecked).apply()
        }
    }

    // =========================================================================
    // Advanced, Creator Profile, Diagnostics & Storage
    // =========================================================================

    private fun setupAdvancedAndStorageControls(app: FairyLiveApplication, repo: StreamSettingsRepository) {
        binding.rowCreatorProfile.setOnClickListener {
            showEditCreatorNameDialog(repo)
        }

        binding.rowAdvancedDiagnostics.setOnClickListener {
            (activity as? MainActivity)?.openDiagnostics()
        }

        binding.rowResetDefaults.setOnClickListener {
            showResetDefaultsDialog(app, repo)
        }

        binding.rowClearCredentials.setOnClickListener {
            showClearCredentialsDialog(repo)
        }

        binding.rowCheckUpdates.setOnClickListener {
            AppUpdateManager.checkUpdateManually(requireActivity())
        }

        binding.rowAboutApp.setOnClickListener {
            showAboutDialog()
        }
    }

    private fun updateCreatorNameSummary(name: String) {
        if (_binding == null) return
        val displayName = if (name.isNotBlank()) name else "Not set"
        binding.tvCreatorNameSummary.text = "Channel: $displayName"
    }

    private fun showEditCreatorNameDialog(repo: StreamSettingsRepository) {
        val currentName = repo.getCreatorName()
        val input = EditText(requireContext()).apply {
            setText(currentName)
            hint = "e.g. ProGamer Live"
            setSingleLine()
            setPadding(48, 32, 48, 32)
        }

        MaterialAlertDialogBuilder(requireContext())
            .setTitle("Creator Channel Name")
            .setMessage("Set your streamer alias for greetings and on-screen overlays.")
            .setView(input)
            .setPositiveButton("Save") { _, _ ->
                val newName = input.text.toString().trim()
                repo.setCreatorName(newName)
                updateCreatorNameSummary(newName)
                Toast.makeText(requireContext(), "Creator alias updated!", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun showResetDefaultsDialog(app: FairyLiveApplication, repo: StreamSettingsRepository) {
        MaterialAlertDialogBuilder(requireContext())
            .setTitle("Reset Settings to Defaults")
            .setMessage("Are you sure you want to reset all broadcast settings to the recommended configuration (YouTube Live, 720p Balanced, Single-App Capture)?")
            .setPositiveButton("Reset") { _, _ ->
                val defaultProfile = app.deviceCapability.recommendedProfile
                val defaultSettings = StreamSettings.fromProfile(
                    profile = defaultProfile,
                    destination = StreamDestination.YOUTUBE,
                    captureSource = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
                        CaptureSource.SINGLE_APP
                    else
                        CaptureSource.ENTIRE_SCREEN,
                    serverUrl = StreamDestination.YOUTUBE.defaultUrl,
                    streamKey = "",
                    youtubeLiveUrl = ""
                )
                repo.saveSettings(defaultSettings)
                populateFields(defaultSettings, repo)
                Toast.makeText(requireContext(), "Settings reset to defaults ✓", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun showClearCredentialsDialog(repo: StreamSettingsRepository) {
        MaterialAlertDialogBuilder(requireContext())
            .setTitle("Clear Stored Stream Key")
            .setMessage("Are you sure you want to remove your YouTube stream key from secure local storage?")
            .setPositiveButton("Clear") { _, _ ->
                repo.clearCredentials()
                binding.etStreamKey.setText("")
                Toast.makeText(requireContext(), "Stream key cleared ✓", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun showAboutDialog() {
        MaterialAlertDialogBuilder(requireContext())
            .setTitle("About Fairy Live")
            .setMessage(
                "FAIRY LIVE v1.2.3\n\n" +
                "A lightweight, pure Kotlin Native live-streaming application built for Android creators.\n\n" +
                "• Architecture: 100% Android Hardware MediaCodec + GLES20/30\n" +
                "• Audio: 4-Channel DSP Mixer + Native Hardware Noise Suppression\n" +
                "• Zero third-party streaming SDKs (APK < 10 MB)\n" +
                "• Strict YouTube Live RTMP Ingestion Compliance\n" +
                "• Dynamic Adaptive Bitrate & Symmetrical Game Cropping\n\n" +
                "Developed with ❤️ for mobile creators."
            )
            .setPositiveButton("OK", null)
            .show()
    }

    // =========================================================================
    // Legal & Compliance Controls
    // =========================================================================

    private fun setupLegalComplianceControls() {
        binding.rowYoutubeTerms.setOnClickListener {
            openWebUrl("https://www.youtube.com/t/terms")
        }

        binding.rowGooglePrivacy.setOnClickListener {
            openWebUrl("https://policies.google.com/privacy")
        }

        binding.rowFairyPrivacy.setOnClickListener {
            showPrivacyDisclosureDialog()
        }
    }

    private fun openWebUrl(url: String) {
        try {
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url))
            startActivity(intent)
        } catch (e: Exception) {
            Toast.makeText(requireContext(), "Unable to open link: $url", Toast.LENGTH_SHORT).show()
        }
    }

    private fun showPrivacyDisclosureDialog() {
        MaterialAlertDialogBuilder(requireContext())
            .setTitle("Privacy & Data Safety Disclosures")
            .setMessage(
                "FAIRY LIVE is engineered with a strict privacy-first architecture:\n\n" +
                "1. Screen Capture (MediaProjection):\n" +
                "   • Used solely to capture and stream the selected game surface to your chosen RTMP ingest server.\n" +
                "   • Single-App mode isolates broadcast to the game task, hiding private notifications and background apps.\n" +
                "   • Zero screen recordings or frames are stored or analyzed.\n\n" +
                "2. Microphone & Audio Recording:\n" +
                "   • Captures voice commentary and internal game audio for live mixing.\n" +
                "   • Audio is encoded into AAC-LC directly in RAM; no audio files are saved.\n\n" +
                "3. Camera Access:\n" +
                "   • Used only for the optional creator facecam overlay rendered locally in real-time.\n\n" +
                "4. Stream Keys & Security:\n" +
                "   • Your YouTube Stream Key is stored strictly on your local device in encrypted storage.\n" +
                "   • Plaintext keys are never logged, transmitted to telemetry, or shared.\n\n" +
                "5. YouTube API Services Compliance:\n" +
                "   • By streaming to YouTube or using YouTube integration features, you agree to be bound by the YouTube Terms of Service (youtube.com/t/terms) and Google Privacy Policy (policies.google.com/privacy)."
            )
            .setPositiveButton("I Understand", null)
            .show()
    }

    // =========================================================================
    // Populate & Save Fields
    // =========================================================================

    private fun populateFields(settings: StreamSettings, repo: StreamSettingsRepository) {
        when (settings.destination) {
            StreamDestination.YOUTUBE -> {
                binding.rbDestYoutube.isChecked = true
                binding.etServerUrl.setText(StreamDestination.YOUTUBE.defaultUrl)
                binding.etServerUrl.isEnabled = false
            }
            StreamDestination.CUSTOM_RTMP -> {
                binding.rbDestCustom.isChecked = true
                binding.etServerUrl.setText(settings.serverUrl)
                binding.etServerUrl.isEnabled = true
            }
            StreamDestination.LOCAL_TEST -> {
                // If legacy local test was saved, migrate to YouTube Live in production app
                binding.rbDestYoutube.isChecked = true
                binding.etServerUrl.setText(StreamDestination.YOUTUBE.defaultUrl)
                binding.etServerUrl.isEnabled = false
            }
        }

        binding.etStreamKey.setText(settings.streamKey)
        binding.etYoutubeLiveChatUrl.setText(settings.youtubeLiveUrl)

        when (settings.profile) {
            StreamProfile.LOW -> binding.rbProfileLow.isChecked = true
            StreamProfile.BALANCED -> binding.rbProfileBalanced.isChecked = true
            StreamProfile.HIGH -> binding.rbProfileHigh.isChecked = true
            else -> binding.rbProfileBalanced.isChecked = true
        }
        updateVideoProfileSummary(binding.rgProfiles.checkedRadioButtonId)

        if (settings.captureSource == CaptureSource.SINGLE_APP) {
            binding.rbCaptureSingleApp.isChecked = true
        } else {
            binding.rbCaptureEntireScreen.isChecked = true
        }

        binding.switchAudioMic.isChecked = settings.micEnabled
        updateCreatorNameSummary(repo.getCreatorName())
        updateAudioStudioStatus()
    }

    private fun saveFields(showFeedback: Boolean = true) {
        val app = requireActivity().application as FairyLiveApplication
        val repo = app.settingsRepository

        val selectedDestination = when (binding.rgDestination.checkedRadioButtonId) {
            R.id.rb_dest_youtube -> StreamDestination.YOUTUBE
            R.id.rb_dest_custom -> StreamDestination.CUSTOM_RTMP
            else -> StreamDestination.YOUTUBE
        }

        val selectedProfile = when (binding.rgProfiles.checkedRadioButtonId) {
            R.id.rb_profile_low -> StreamProfile.LOW
            R.id.rb_profile_balanced -> StreamProfile.BALANCED
            R.id.rb_profile_high -> StreamProfile.HIGH
            else -> StreamProfile.BALANCED
        }

        val selectedCaptureSource = when (binding.rgCaptureSource.checkedRadioButtonId) {
            R.id.rb_capture_single_app -> {
                if (Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                    if (showFeedback) {
                        Toast.makeText(
                            requireContext(),
                            "Single-App capture requires Android 14+ (API 34). Falling back to Entire Screen.",
                            Toast.LENGTH_LONG
                        ).show()
                    }
                    CaptureSource.ENTIRE_SCREEN
                } else {
                    CaptureSource.SINGLE_APP
                }
            }
            R.id.rb_capture_entire_screen -> CaptureSource.ENTIRE_SCREEN
            else -> if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
                CaptureSource.SINGLE_APP
            else
                CaptureSource.ENTIRE_SCREEN
        }

        val rawServerUrl = binding.etServerUrl.text?.toString()?.trim() ?: ""
        val serverUrl = if (selectedDestination == StreamDestination.YOUTUBE) {
            StreamDestination.YOUTUBE.defaultUrl
        } else {
            rawServerUrl
        }

        val streamKey = binding.etStreamKey.text?.toString()?.trim() ?: ""
        val youtubeLiveUrl = binding.etYoutubeLiveChatUrl.text?.toString()?.trim() ?: ""

        val validation = StreamConfigurationValidator.validate(
            serverUrl = serverUrl,
            streamKey = streamKey,
            destination = selectedDestination
        )

        if (showFeedback) {
            if (!validation.isValid) {
                val err = validation as StreamConfigurationValidator.ValidationResult.Error
                Toast.makeText(requireContext(), err.userMessage, Toast.LENGTH_LONG).show()
            } else {
                Toast.makeText(requireContext(), "Settings saved successfully ✓", Toast.LENGTH_SHORT).show()
            }
        }

        val newSettings = StreamSettings.fromProfile(
            profile = selectedProfile,
            destination = selectedDestination,
            captureSource = selectedCaptureSource,
            serverUrl = serverUrl,
            streamKey = streamKey,
            youtubeLiveUrl = youtubeLiveUrl,
            micEnabled = binding.switchAudioMic.isChecked
        )

        repo.saveSettings(newSettings)
        repo.setYouTubeLiveUrl(youtubeLiveUrl)
    }

    override fun onPause() {
        super.onPause()
        if (_binding != null) {
            saveFields(showFeedback = false)
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
