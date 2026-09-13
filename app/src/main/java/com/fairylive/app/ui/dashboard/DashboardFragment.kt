package com.fairylive.app.ui.dashboard

import android.content.Context
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import com.fairylive.app.FairyLiveApplication
import com.fairylive.app.R
import com.fairylive.app.core.lifecycle.StreamState
import com.fairylive.app.core.lifecycle.StreamStateNotifier
import com.fairylive.app.core.permission.PermissionManager
import com.fairylive.app.databinding.FragmentDashboardBinding
import com.fairylive.app.service.FairyStreamService

class DashboardFragment : Fragment(), StreamStateNotifier.Listener {

    private var _binding: FragmentDashboardBinding? = null
    private val binding get() = _binding!!

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { _ ->
        updatePermissionIndicators()
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

        bindDeviceCapability()
        bindServiceController()
        bindPermissionControls()
        updatePermissionIndicators()
        updateServiceState(StreamStateNotifier.currentState)
    }

    override fun onDestroyView() {
        super.onDestroyView()
        StreamStateNotifier.removeListener(this)
        _binding = null
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

    private fun bindServiceController() {
        binding.btnToggleService.setOnClickListener {
            val ctx = requireContext()
            if (StreamStateNotifier.currentState.isIdle) {
                val missing = PermissionManager.getMissingPermissions(ctx)
                if (missing.isNotEmpty()) {
                    android.widget.Toast.makeText(
                        ctx,
                        "Permissions required before starting service",
                        android.widget.Toast.LENGTH_SHORT
                    ).show()
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
        if (allGranted) {
            binding.btnRequestPermissions.visibility = View.GONE
        } else {
            binding.btnRequestPermissions.visibility = View.VISIBLE
        }
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

    override fun onStateChanged(newState: StreamState, previousState: StreamState) {
        activity?.runOnUiThread {
            if (_binding != null) {
                updateServiceState(newState)
            }
        }
    }
}
