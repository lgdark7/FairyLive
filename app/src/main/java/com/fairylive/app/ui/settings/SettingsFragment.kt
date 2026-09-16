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

        binding.rowAdvancedDiagnostics.setOnClickListener {
            (activity as? MainActivity)?.openDiagnostics()
        }

        binding.btnSave.setOnClickListener {
            saveFields()
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    private fun populateFields(settings: StreamSettings) {
        binding.etServerUrl.setText(settings.serverUrl)
        binding.etStreamKey.setText(settings.streamKey)

        when (settings.profile) {
            StreamProfile.LOW -> binding.rbProfileLow.isChecked = true
            StreamProfile.BALANCED -> binding.rbProfileBalanced.isChecked = true
            StreamProfile.HIGH -> binding.rbProfileHigh.isChecked = true
            else -> binding.rbProfileLow.isChecked = true
        }
    }

    private fun saveFields() {
        val app = requireActivity().application as FairyLiveApplication
        val repo = app.settingsRepository

        val selectedProfile = when (binding.rgProfiles.checkedRadioButtonId) {
            R.id.rb_profile_low -> StreamProfile.LOW
            R.id.rb_profile_balanced -> StreamProfile.BALANCED
            R.id.rb_profile_high -> StreamProfile.HIGH
            else -> StreamProfile.LOW
        }

        val serverUrl = binding.etServerUrl.text?.toString()?.trim() ?: ""
        val streamKey = binding.etStreamKey.text?.toString()?.trim() ?: ""

        val validation = com.fairylive.app.core.settings.StreamConfigurationValidator.validate(serverUrl, streamKey)
        if (!validation.isValid) {
            val err = validation as com.fairylive.app.core.settings.StreamConfigurationValidator.ValidationResult.Error
            Toast.makeText(requireContext(), err.userMessage, Toast.LENGTH_LONG).show()
        } else {
            Toast.makeText(requireContext(), "Stream configuration valid & saved", Toast.LENGTH_SHORT).show()
        }

        val newSettings = StreamSettings.fromProfile(
            profile = selectedProfile,
            serverUrl = serverUrl,
            streamKey = streamKey
        )

        repo.saveSettings(newSettings)
    }
}
