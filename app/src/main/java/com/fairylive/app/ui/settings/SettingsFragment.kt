package com.fairylive.app.ui.settings

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import com.fairylive.app.FairyLiveApplication
import com.fairylive.app.MainActivity
import com.fairylive.app.R
import com.fairylive.app.core.capability.model.StreamProfile
import com.fairylive.app.core.settings.StreamDestination
import com.fairylive.app.core.settings.StreamSettings
import com.fairylive.app.databinding.FragmentSettingsBinding
import com.google.android.material.dialog.MaterialAlertDialogBuilder

class SettingsFragment : Fragment() {

    private var _binding: FragmentSettingsBinding? = null
    private val binding get() = _binding!!

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

        populateFields(currentSettings)

        // Category Filter Chips
        binding.chipAll.setOnClickListener { selectCategory(binding.chipAll, null) }
        binding.chipStream.setOnClickListener { selectCategory(binding.chipStream, binding.cardCategoryStream) }
        binding.chipCapture.setOnClickListener { selectCategory(binding.chipCapture, binding.cardCategoryCapture) }
        binding.chipVideo.setOnClickListener { selectCategory(binding.chipVideo, binding.cardCategoryVideo) }
        binding.chipAudio.setOnClickListener { selectCategory(binding.chipAudio, binding.cardCategoryAudio) }
        binding.chipAdvanced.setOnClickListener { selectCategory(binding.chipAdvanced, binding.cardCategoryAdvanced) }
        binding.chipLegal.setOnClickListener { selectCategory(binding.chipLegal, binding.cardCategoryLegal) }

        binding.rgDestination.setOnCheckedChangeListener { _, checkedId ->
            when (checkedId) {
                R.id.rb_dest_youtube -> {
                    binding.etServerUrl.setText(StreamDestination.YOUTUBE.defaultUrl)
                    binding.etServerUrl.isEnabled = false
                }
                R.id.rb_dest_custom -> {
                    binding.etServerUrl.isEnabled = true
                    if (binding.etServerUrl.text.toString() == StreamDestination.YOUTUBE.defaultUrl ||
                        binding.etServerUrl.text.toString() == StreamDestination.LOCAL_TEST.defaultUrl
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

        binding.rowAdvancedDiagnostics.setOnClickListener {
            (activity as? MainActivity)?.openDiagnostics()
        }

        binding.rowAboutApp.setOnClickListener {
            showAboutDialog()
        }

        binding.rowYoutubeTerms.setOnClickListener {
            openWebUrl("https://www.youtube.com/t/terms")
        }

        binding.rowGooglePrivacy.setOnClickListener {
            openWebUrl("https://policies.google.com/privacy")
        }

        binding.rowFairyPrivacy.setOnClickListener {
            showPrivacyDisclosureDialog()
        }

        binding.btnSave.setOnClickListener {
            saveFields(showFeedback = true)
        }
    }

    private fun selectCategory(selectedChip: TextView, targetCard: View?) {
        val chips = listOf(
            binding.chipAll,
            binding.chipStream,
            binding.chipCapture,
            binding.chipVideo,
            binding.chipAudio,
            binding.chipAdvanced,
            binding.chipLegal
        )

        chips.forEach { chip ->
            if (chip == selectedChip) {
                chip.setBackgroundResource(R.drawable.bg_chip_selected)
                chip.setTextColor(ContextCompat.getColor(requireContext(), R.color.text_on_dark))
            } else {
                chip.setBackgroundResource(R.drawable.bg_chip_unselected)
                chip.setTextColor(ContextCompat.getColor(requireContext(), R.color.text_secondary))
            }
        }

        if (targetCard == null) {
            binding.scrollSettings.smoothScrollTo(0, 0)
        } else {
            binding.scrollSettings.post {
                binding.scrollSettings.smoothScrollTo(0, targetCard.top)
            }
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

    private fun showAboutDialog() {
        MaterialAlertDialogBuilder(requireContext())
            .setTitle("About Fairy Live")
            .setMessage(
                "FAIRY LIVE v1.2.2\n\n" +
                "A lightweight, pure Kotlin Native live-streaming application built for Android creators.\n\n" +
                "• Architecture: 100% Android Hardware MediaCodec + GLES20/30\n" +
                "• Zero third-party streaming SDKs (APK < 10 MB)\n" +
                "• Strict YouTube Live RTMP Ingestion Compliance\n" +
                "• Dynamic Adaptive Bitrate & Symmetrical Game Cropping\n\n" +
                "Developed with ❤️ for mobile creators."
            )
            .setPositiveButton("OK", null)
            .show()
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
                "   • Your YouTube Stream Key is stored strictly on your local device.\n" +
                "   • Plaintext keys are never logged, transmitted to telemetry, or shared.\n\n" +
                "5. YouTube API Services Compliance:\n" +
                "   • By streaming to YouTube or using YouTube integration features, you agree to be bound by the YouTube Terms of Service (youtube.com/t/terms) and Google Privacy Policy (policies.google.com/privacy)."
            )
            .setPositiveButton("I Understand", null)
            .show()
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

    private fun populateFields(settings: StreamSettings) {
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
                binding.rbDestLocalTest.isChecked = true
                binding.etServerUrl.setText(settings.serverUrl)
                binding.etServerUrl.isEnabled = true
            }
        }

        binding.etStreamKey.setText(settings.streamKey)
        binding.etYoutubeLiveChatUrl.setText(settings.youtubeLiveUrl)

        when (settings.profile) {
            StreamProfile.LOW -> binding.rbProfileLow.isChecked = true
            StreamProfile.BALANCED -> binding.rbProfileBalanced.isChecked = true
            StreamProfile.HIGH -> binding.rbProfileHigh.isChecked = true
            else -> binding.rbProfileLow.isChecked = true
        }

        if (settings.captureSource == com.fairylive.app.core.settings.CaptureSource.SINGLE_APP) {
            binding.rbCaptureSingleApp.isChecked = true
        } else {
            binding.rbCaptureEntireScreen.isChecked = true
        }
    }

    private fun saveFields(showFeedback: Boolean = true) {
        val app = requireActivity().application as FairyLiveApplication
        val repo = app.settingsRepository

        val selectedDestination = when (binding.rgDestination.checkedRadioButtonId) {
            R.id.rb_dest_youtube -> StreamDestination.YOUTUBE
            R.id.rb_dest_custom -> StreamDestination.CUSTOM_RTMP
            R.id.rb_dest_local_test -> StreamDestination.LOCAL_TEST
            else -> StreamDestination.YOUTUBE
        }

        val selectedProfile = when (binding.rgProfiles.checkedRadioButtonId) {
            R.id.rb_profile_low -> StreamProfile.LOW
            R.id.rb_profile_balanced -> StreamProfile.BALANCED
            R.id.rb_profile_high -> StreamProfile.HIGH
            else -> StreamProfile.LOW
        }

        val selectedCaptureSource = when (binding.rgCaptureSource.checkedRadioButtonId) {
            R.id.rb_capture_single_app -> {
                if (android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                    if (showFeedback) {
                        Toast.makeText(requireContext(), "Single-App capture requires Android 14+ (API 34). Falling back to Entire Screen.", Toast.LENGTH_LONG).show()
                    }
                    com.fairylive.app.core.settings.CaptureSource.ENTIRE_SCREEN
                } else {
                    com.fairylive.app.core.settings.CaptureSource.SINGLE_APP
                }
            }
            R.id.rb_capture_entire_screen -> com.fairylive.app.core.settings.CaptureSource.ENTIRE_SCREEN
            else -> if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.UPSIDE_DOWN_CAKE) com.fairylive.app.core.settings.CaptureSource.SINGLE_APP else com.fairylive.app.core.settings.CaptureSource.ENTIRE_SCREEN
        }

        val rawServerUrl = binding.etServerUrl.text?.toString()?.trim() ?: ""
        val serverUrl = if (selectedDestination == StreamDestination.YOUTUBE) {
            StreamDestination.YOUTUBE.defaultUrl
        } else {
            rawServerUrl
        }

        val streamKey = binding.etStreamKey.text?.toString()?.trim() ?: ""
        val youtubeLiveUrl = binding.etYoutubeLiveChatUrl.text?.toString()?.trim() ?: ""

        val validation = com.fairylive.app.core.settings.StreamConfigurationValidator.validate(
            serverUrl = serverUrl,
            streamKey = streamKey,
            destination = selectedDestination
        )

        if (showFeedback) {
            if (!validation.isValid) {
                val err = validation as com.fairylive.app.core.settings.StreamConfigurationValidator.ValidationResult.Error
                Toast.makeText(requireContext(), err.userMessage, Toast.LENGTH_LONG).show()
            } else {
                Toast.makeText(requireContext(), "Stream configuration valid & saved", Toast.LENGTH_SHORT).show()
            }
        }

        val newSettings = StreamSettings.fromProfile(
            profile = selectedProfile,
            destination = selectedDestination,
            captureSource = selectedCaptureSource,
            serverUrl = serverUrl,
            streamKey = streamKey,
            youtubeLiveUrl = youtubeLiveUrl
        )

        repo.saveSettings(newSettings)
        repo.setYouTubeLiveUrl(youtubeLiveUrl)
    }
}
