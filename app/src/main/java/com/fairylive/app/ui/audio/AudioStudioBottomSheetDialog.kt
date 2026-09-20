package com.fairylive.app.ui.audio

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.SeekBar
import androidx.lifecycle.lifecycleScope
import com.fairylive.app.R
import com.fairylive.app.core.audio.AudioStateNotifier
import com.fairylive.app.core.audio.dsp.AudioMixerProcessor
import com.fairylive.app.databinding.DialogAudioStudioBinding
import com.fairylive.app.service.FairyStreamService
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

/**
 * Advanced Audio Studio Dialog for FAIRY LIVE.
 *
 * Provides real-time 4-channel audio mixing, DSP filtering,
 * presets, and live VU meters for all Android devices.
 * (Strictly universal: zero device-specific references).
 */
class AudioStudioBottomSheetDialog : BottomSheetDialogFragment() {

    private var _binding: DialogAudioStudioBinding? = null
    private val binding get() = _binding!!

    // Local cached channel values
    private var micVol = 0.9f
    private var gameVol = 0.8f
    private var mediaVol = 0.5f
    private var masterVol = 1.0f

    private var isMicMuted = false
    private var isGameMuted = false
    private var isMediaMuted = false

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = DialogAudioStudioBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        val ctx = requireContext()
        com.fairylive.app.core.audio.AudioStudioRepository.init(ctx)
        micVol = com.fairylive.app.core.audio.AudioStudioRepository.micVolume
        gameVol = com.fairylive.app.core.audio.AudioStudioRepository.gameVolume
        mediaVol = com.fairylive.app.core.audio.AudioStudioRepository.mediaVolume
        masterVol = com.fairylive.app.core.audio.AudioStudioRepository.masterVolume
        isMicMuted = com.fairylive.app.core.audio.AudioStudioRepository.isMicMuted
        isGameMuted = com.fairylive.app.core.audio.AudioStudioRepository.isGameMuted
        isMediaMuted = com.fairylive.app.core.audio.AudioStudioRepository.isMediaMuted

        setupSliders()
        setupMuteButtons()
        setupDspSwitches()
        setupPresets()

        binding.btnCloseAudioStudio.setOnClickListener {
            syncVolumesToService()
            syncDspToService()
            dismiss()
        }
        binding.btnApplyAudioStudio.setOnClickListener {
            syncVolumesToService()
            syncDspToService()
            dismiss()
        }
    }

    private fun setupSliders() {
        // Device Microphone (Universal Device Mic, 0 - 150%)
        binding.seekMicVolume.progress = (micVol * 100).toInt()
        binding.tvLabelMicVol.text = "🎙 Device Microphone: ${(micVol * 100).toInt()}%"
        binding.seekMicVolume.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar?, progress: Int, fromUser: Boolean) {
                if (fromUser) {
                    micVol = progress / 100f
                    binding.tvLabelMicVol.text = "🎙 Device Microphone: ${progress}%"
                    syncVolumesToService()
                }
            }
            override fun onStartTrackingTouch(sb: SeekBar?) {}
            override fun onStopTrackingTouch(sb: SeekBar?) {
                syncVolumesToService()
            }
        })

        // Game Audio (0 - 150%)
        binding.seekGameVolume.progress = (gameVol * 100).toInt()
        binding.tvLabelGameVol.text = "🎮 Game & System Audio: ${(gameVol * 100).toInt()}%"
        binding.seekGameVolume.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar?, progress: Int, fromUser: Boolean) {
                if (fromUser) {
                    gameVol = progress / 100f
                    binding.tvLabelGameVol.text = "🎮 Game & System Audio: ${progress}%"
                    syncVolumesToService()
                }
            }
            override fun onStartTrackingTouch(sb: SeekBar?) {}
            override fun onStopTrackingTouch(sb: SeekBar?) {
                syncVolumesToService()
            }
        })

        // Media Audio (0 - 100%)
        binding.seekMediaVolume.progress = (mediaVol * 100).toInt()
        binding.tvLabelMediaVol.text = "🎵 Media & BGM: ${(mediaVol * 100).toInt()}%"
        binding.seekMediaVolume.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar?, progress: Int, fromUser: Boolean) {
                if (fromUser) {
                    mediaVol = progress / 100f
                    binding.tvLabelMediaVol.text = "🎵 Media & BGM: ${progress}%"
                    syncVolumesToService()
                }
            }
            override fun onStartTrackingTouch(sb: SeekBar?) {}
            override fun onStopTrackingTouch(sb: SeekBar?) {
                syncVolumesToService()
            }
        })

        // Master Output (0 - 100%)
        binding.seekMasterVolume.progress = (masterVol * 100).toInt()
        binding.tvLabelMasterVol.text = "🎚 Master Stream Output: ${(masterVol * 100).toInt()}%"
        binding.seekMasterVolume.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar?, progress: Int, fromUser: Boolean) {
                if (fromUser) {
                    masterVol = progress / 100f
                    binding.tvLabelMasterVol.text = "🎚 Master Stream Output: ${progress}%"
                    syncVolumesToService()
                }
            }
            override fun onStartTrackingTouch(sb: SeekBar?) {}
            override fun onStopTrackingTouch(sb: SeekBar?) {
                syncVolumesToService()
            }
        })
    }

    private val metricsListener = AudioStateNotifier.MetricsListener { metrics ->
        activity?.runOnUiThread {
            val b = _binding ?: return@runOnUiThread
            val micPercent = ((metrics.micDb + 60.0f) / 60.0f * 100).toInt().coerceIn(0, 100)
            val gamePercent = ((metrics.gameDb + 60.0f) / 60.0f * 100).toInt().coerceIn(0, 100)
            val masterPercent = ((metrics.masterDb + 60.0f) / 60.0f * 100).toInt().coerceIn(0, 100)

            b.vuProgressMic.progress = micPercent
            b.tvVuMicDb.text = "${metrics.micDb.toInt()} dB"

            b.vuProgressGame.progress = gamePercent
            b.tvVuGameDb.text = "${metrics.gameDb.toInt()} dB"

            b.vuProgressMaster.progress = masterPercent
            b.tvVuMasterDb.text = "${metrics.masterDb.toInt()} dB"
        }
    }

    private fun setupMuteButtons() {
        binding.btnMuteMic.text = if (isMicMuted) "Unmute" else "Mute"
        binding.btnMuteMic.setTextColor(
            if (isMicMuted) requireContext().getColor(R.color.status_red)
            else requireContext().getColor(R.color.accent_primary)
        )
        binding.btnMuteMic.setOnClickListener {
            isMicMuted = !isMicMuted
            binding.btnMuteMic.text = if (isMicMuted) "Unmute" else "Mute"
            binding.btnMuteMic.setTextColor(
                if (isMicMuted) requireContext().getColor(R.color.status_red)
                else requireContext().getColor(R.color.accent_primary)
            )
            syncVolumesToService()
        }

        binding.btnMuteGame.text = if (isGameMuted) "Unmute" else "Mute"
        binding.btnMuteGame.setTextColor(
            if (isGameMuted) requireContext().getColor(R.color.status_red)
            else requireContext().getColor(R.color.accent_primary)
        )
        binding.btnMuteGame.setOnClickListener {
            isGameMuted = !isGameMuted
            binding.btnMuteGame.text = if (isGameMuted) "Unmute" else "Mute"
            binding.btnMuteGame.setTextColor(
                if (isGameMuted) requireContext().getColor(R.color.status_red)
                else requireContext().getColor(R.color.accent_primary)
            )
            syncVolumesToService()
        }

        binding.btnMuteMedia.text = if (isMediaMuted) "Unmute" else "Mute"
        binding.btnMuteMedia.setTextColor(
            if (isMediaMuted) requireContext().getColor(R.color.status_red)
            else requireContext().getColor(R.color.accent_primary)
        )
        binding.btnMuteMedia.setOnClickListener {
            isMediaMuted = !isMediaMuted
            binding.btnMuteMedia.text = if (isMediaMuted) "Unmute" else "Mute"
            binding.btnMuteMedia.setTextColor(
                if (isMediaMuted) requireContext().getColor(R.color.status_red)
                else requireContext().getColor(R.color.accent_primary)
            )
            syncVolumesToService()
        }
    }

    private fun setupDspSwitches() {
        val dspListener = { _: View, _: Boolean ->
            syncDspToService()
        }

        binding.switchNoiseSuppression.isChecked = com.fairylive.app.core.audio.AudioStudioRepository.isNoiseSuppressionEnabled
        binding.switchAgc.isChecked = com.fairylive.app.core.audio.AudioStudioRepository.isAgcEnabled
        binding.switchCompressor.isChecked = com.fairylive.app.core.audio.AudioStudioRepository.isCompressorEnabled
        binding.switchLimiter.isChecked = com.fairylive.app.core.audio.AudioStudioRepository.isLimiterEnabled

        binding.switchNoiseSuppression.setOnCheckedChangeListener(dspListener)
        binding.switchCompressor.setOnCheckedChangeListener(dspListener)
        binding.switchLimiter.setOnCheckedChangeListener(dspListener)
        binding.switchAgc.setOnCheckedChangeListener(dspListener)
    }

    private fun setupPresets() {
        binding.btnPresetGaming.setOnClickListener {
            applyPreset(AudioMixerProcessor.AudioPreset.GAMING)
        }
        binding.btnPresetVoiceFocus.setOnClickListener {
            applyPreset(AudioMixerProcessor.AudioPreset.VOICE_FOCUS)
        }
        binding.btnPresetMusic.setOnClickListener {
            applyPreset(AudioMixerProcessor.AudioPreset.MUSIC)
        }
        binding.btnPresetBalanced.setOnClickListener {
            applyPreset(AudioMixerProcessor.AudioPreset.BALANCED)
        }
        binding.btnPresetLowEnd.setOnClickListener {
            applyPreset(AudioMixerProcessor.AudioPreset.LOW_END)
        }
    }

    private fun applyPreset(preset: AudioMixerProcessor.AudioPreset) {
        val context = context ?: return
        com.fairylive.app.core.audio.AudioStudioRepository.savePreset(context, preset.name)
        FairyStreamService.setAudioPreset(context, preset.name)

        when (preset) {
            AudioMixerProcessor.AudioPreset.GAMING -> {
                gameVol = 0.8f; micVol = 0.9f; mediaVol = 0.5f; masterVol = 1.0f
                binding.switchNoiseSuppression.isChecked = true
                binding.switchCompressor.isChecked = true
                binding.switchLimiter.isChecked = true
                binding.switchAgc.isChecked = false
            }
            AudioMixerProcessor.AudioPreset.VOICE_FOCUS -> {
                gameVol = 0.4f; micVol = 1.0f; mediaVol = 0.3f; masterVol = 1.0f
                binding.switchNoiseSuppression.isChecked = true
                binding.switchCompressor.isChecked = true
                binding.switchLimiter.isChecked = true
                binding.switchAgc.isChecked = true
            }
            AudioMixerProcessor.AudioPreset.MUSIC -> {
                gameVol = 0.6f; micVol = 0.7f; mediaVol = 0.8f; masterVol = 1.0f
                binding.switchNoiseSuppression.isChecked = false
                binding.switchCompressor.isChecked = false
                binding.switchLimiter.isChecked = true
                binding.switchAgc.isChecked = false
            }
            AudioMixerProcessor.AudioPreset.BALANCED -> {
                gameVol = 0.7f; micVol = 0.8f; mediaVol = 0.6f; masterVol = 1.0f
                binding.switchNoiseSuppression.isChecked = true
                binding.switchCompressor.isChecked = true
                binding.switchLimiter.isChecked = true
                binding.switchAgc.isChecked = false
            }
            AudioMixerProcessor.AudioPreset.LOW_END -> {
                gameVol = 0.8f; micVol = 0.8f; mediaVol = 0.5f; masterVol = 1.0f
                binding.switchNoiseSuppression.isChecked = false
                binding.switchCompressor.isChecked = false
                binding.switchLimiter.isChecked = false
                binding.switchAgc.isChecked = false
            }
        }

        binding.seekMicVolume.progress = (micVol * 100).toInt()
        binding.seekGameVolume.progress = (gameVol * 100).toInt()
        binding.seekMediaVolume.progress = (mediaVol * 100).toInt()
        binding.seekMasterVolume.progress = (masterVol * 100).toInt()

        binding.tvLabelMicVol.text = "🎙 Device Microphone: ${(micVol * 100).toInt()}%"
        binding.tvLabelGameVol.text = "🎮 Game & System Audio: ${(gameVol * 100).toInt()}%"
        binding.tvLabelMediaVol.text = "🎵 Media & BGM: ${(mediaVol * 100).toInt()}%"
        binding.tvLabelMasterVol.text = "🎚 Master Stream Output: ${(masterVol * 100).toInt()}%"

        syncVolumesToService()
        syncDspToService()
    }

    private fun syncVolumesToService() {
        val context = context ?: return
        com.fairylive.app.core.audio.AudioStudioRepository.saveVolumes(
            context = context,
            mic = micVol,
            game = gameVol,
            media = mediaVol,
            master = masterVol,
            micMuted = isMicMuted,
            gameMuted = isGameMuted,
            mediaMuted = isMediaMuted
        )
        FairyStreamService.setAudioVolumes(
            context = context,
            micVolume = micVol,
            gameVolume = gameVol,
            mediaVolume = mediaVol,
            masterVolume = masterVol,
            micMuted = isMicMuted,
            gameMuted = isGameMuted,
            mediaMuted = isMediaMuted
        )
    }

    private fun syncDspToService() {
        val context = context ?: return
        val b = _binding ?: return
        com.fairylive.app.core.audio.AudioStudioRepository.saveDsp(
            context = context,
            noiseGate = b.switchNoiseSuppression.isChecked,
            agc = b.switchAgc.isChecked,
            compressor = b.switchCompressor.isChecked,
            limiter = b.switchLimiter.isChecked
        )
        FairyStreamService.setAudioDspFilters(
            context = context,
            noiseGate = b.switchNoiseSuppression.isChecked,
            agc = b.switchAgc.isChecked,
            compressor = b.switchCompressor.isChecked,
            limiter = b.switchLimiter.isChecked
        )
    }

    override fun onResume() {
        super.onResume()
        AudioStateNotifier.addMetricsListener(metricsListener)
    }

    override fun onPause() {
        super.onPause()
        AudioStateNotifier.removeMetricsListener(metricsListener)
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    companion object {
        const val TAG = "AudioStudioDialog"
    }
}
