package com.fairylive.app.ui.diagnostics

import android.os.Build
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import com.fairylive.app.FairyLiveApplication
import com.fairylive.app.core.lifecycle.StreamStateNotifier
import com.fairylive.app.core.permission.PermissionManager
import com.fairylive.app.databinding.FragmentDiagnosticsBinding

class DiagnosticsFragment : Fragment() {

    private var _binding: FragmentDiagnosticsBinding? = null
    private val binding get() = _binding!!

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentDiagnosticsBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        binding.btnBackFromDiagnostics.setOnClickListener {
            parentFragmentManager.popBackStack()
        }
        renderDiagnostics()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    private fun renderDiagnostics() {
        val app = requireActivity().application as FairyLiveApplication
        val cap = app.deviceCapability
        val ctx = requireContext()

        val sb = StringBuilder()
        sb.appendLine("=== FAIRY LIVE ENGINE DIAGNOSTICS ===")
        sb.appendLine("App ID: ${ctx.packageName}")
        sb.appendLine("Engine State: ${StreamStateNotifier.currentState}")
        sb.appendLine()
        sb.appendLine("--- HARDWARE PLATFORM ---")
        sb.appendLine("Model: ${Build.MANUFACTURER} ${Build.MODEL}")
        sb.appendLine("Hardware: ${Build.HARDWARE} / Board: ${Build.BOARD}")
        sb.appendLine("Android OS: ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")
        sb.appendLine("Fingerprint: ${Build.FINGERPRINT}")
        sb.appendLine()
        sb.appendLine("--- MEMORY PROFILE ---")
        sb.appendLine("Total RAM: ${cap.ram.totalMemMb} MB")
        sb.appendLine("Available RAM: ${cap.ram.availMemMb} MB")
        sb.appendLine("isLowRamDevice: ${cap.ram.isLowRamDevice}")
        sb.appendLine("Memory Class: ${cap.ram.memoryClassMb} MB")
        sb.appendLine("Constrained Memory Tier: ${cap.ram.isConstrained}")
        sb.appendLine()
        sb.appendLine("--- CPU PROFILE ---")
        sb.appendLine("CPU Cores: ${cap.cpu.coreCount}")
        sb.appendLine("Primary ABI: ${cap.cpu.primaryAbi}")
        sb.appendLine("Supported ABIs: ${Build.SUPPORTED_ABIS.joinToString(", ")}")
        sb.appendLine("Constrained CPU Tier: ${cap.cpu.isConstrained}")
        sb.appendLine()
        sb.appendLine("--- VIDEO ENCODERS DETECTED ---")
        if (cap.videoEncoders.isEmpty()) {
            sb.appendLine("None detected.")
        } else {
            cap.videoEncoders.forEachIndexed { idx, enc ->
                val hwLabel = if (enc.isHardwareAccelerated) "[HARDWARE]" else "[SOFTWARE]"
                sb.appendLine("${idx + 1}. ${enc.codecName} $hwLabel")
                sb.appendLine("   MIME: ${enc.mimeType}")
                sb.appendLine("   Max Resolution: ${enc.maxSupportedWidth}x${enc.maxSupportedHeight}")
            }
        }
        sb.appendLine()
        sb.appendLine("--- PERMISSION STATUS ---")
        sb.appendLine("Audio (RECORD_AUDIO): ${PermissionManager.hasAudioPermission(ctx)}")
        sb.appendLine("Notifications (POST_NOTIFICATIONS): ${PermissionManager.hasNotificationPermission(ctx)}")
        sb.appendLine("Camera (CAMERA): ${PermissionManager.hasCameraPermission(ctx)}")
        sb.appendLine()
        sb.appendLine("--- RECOMMENDED PROFILE ---")
        sb.appendLine("Target: ${cap.recommendedProfile.title}")
        sb.appendLine("Spec: ${cap.recommendedProfile.width}x${cap.recommendedProfile.height} @ ${cap.recommendedProfile.fps}fps")
        sb.appendLine("Bitrate: ${cap.recommendedProfile.videoBitrateKbps} kbps (Audio: ${cap.recommendedProfile.audioBitrateKbps} kbps)")

        binding.tvDiagnosticsRaw.text = sb.toString()
    }
}
