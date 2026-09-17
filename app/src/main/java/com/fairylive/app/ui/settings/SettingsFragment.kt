package com.fairylive.app.ui.settings

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.fragment.app.Fragment
import com.fairylive.app.FairyLiveApplication
import com.fairylive.app.MainActivity
import com.fairylive.app.R
import com.fairylive.app.core.capability.model.StreamProfile
import com.fairylive.app.core.settings.StreamDestination
import com.fairylive.app.core.settings.StreamSettings
import com.fairylive.app.databinding.FragmentSettingsBinding

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

        binding.btnSave.setOnClickListener {
            saveFields(showFeedback = true)
        }
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

        when (settings.profile) {
            StreamProfile.LOW -> binding.rbProfileLow.isChecked = true
            StreamProfile.BALANCED -> binding.rbProfileBalanced.isChecked = true
            StreamProfile.HIGH -> binding.rbProfileHigh.isChecked = true
            else -> binding.rbProfileLow.isChecked = true
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

        val rawServerUrl = binding.etServerUrl.text?.toString()?.trim() ?: ""
        val serverUrl = if (selectedDestination == StreamDestination.YOUTUBE) {
            StreamDestination.YOUTUBE.defaultUrl
        } else {
            rawServerUrl
        }

        val streamKey = binding.etStreamKey.text?.toString()?.trim() ?: ""

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
            serverUrl = serverUrl,
            streamKey = streamKey
        )

        repo.saveSettings(newSettings)
    }
}
