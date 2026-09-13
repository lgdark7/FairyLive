package com.fairylive.app.core.capability

import android.app.ActivityManager
import android.content.Context
import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.os.Build
import com.fairylive.app.core.capability.model.CpuMetrics
import com.fairylive.app.core.capability.model.DeviceCapability
import com.fairylive.app.core.capability.model.EncoderCapability
import com.fairylive.app.core.capability.model.EncoderType
import com.fairylive.app.core.capability.model.RamMetrics
import com.fairylive.app.core.capability.model.StreamProfile

/**
 * Discovers and profiles hardware capabilities.
 * Determines whether the device falls into the low-end performance tier
 * and recommends safe default encoding parameters.
 */
class DeviceCapabilityDetector(private val context: Context? = null) {

    fun detectCapabilities(): DeviceCapability {
        val ctx = requireNotNull(context) { "Context is required for system hardware detection" }
        val ramMetrics = detectRamMetrics(ctx)
        val cpuMetrics = detectCpuMetrics()
        val videoEncoders = detectVideoEncoders()

        val hasHwH264 = videoEncoders.any { it.isHardwareAccelerated && it.mimeType == "video/avc" }
        val isLowEnd = ramMetrics.isConstrained || cpuMetrics.isConstrained || !hasHwH264

        val recommendedProfile = calculateRecommendedProfile(ramMetrics, cpuMetrics, hasHwH264)

        return DeviceCapability(
            sdkInt = Build.VERSION.SDK_INT,
            model = Build.MODEL,
            manufacturer = Build.MANUFACTURER,
            ram = ramMetrics,
            cpu = cpuMetrics,
            videoEncoders = videoEncoders,
            isLowEndDevice = isLowEnd,
            recommendedProfile = recommendedProfile
        )
    }

    private fun detectRamMetrics(ctx: Context): RamMetrics {
        val am = ctx.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
        val memInfo = ActivityManager.MemoryInfo()
        am?.getMemoryInfo(memInfo)

        val totalMb = memInfo.totalMem / (1024 * 1024)
        val availMb = memInfo.availMem / (1024 * 1024)
        val isLowRam = am?.isLowRamDevice ?: false
        val memClass = am?.memoryClass ?: 128

        return RamMetrics(
            totalMemMb = totalMb,
            availMemMb = availMb,
            isLowRamDevice = isLowRam,
            memoryClassMb = memClass
        )
    }

    private fun detectCpuMetrics(): CpuMetrics {
        val cores = Runtime.getRuntime().availableProcessors()
        val abi = Build.SUPPORTED_ABIS.firstOrNull() ?: "unknown"
        return CpuMetrics(
            coreCount = cores,
            primaryAbi = abi
        )
    }

    private fun detectVideoEncoders(): List<EncoderCapability> {
        val list = mutableListOf<EncoderCapability>()
        try {
            val codecList = MediaCodecList(MediaCodecList.REGULAR_CODECS)
            for (info in codecList.codecInfos) {
                if (!info.isEncoder) continue

                for (type in info.supportedTypes) {
                    if (type.equals("video/avc", ignoreCase = true) || type.equals("video/hevc", ignoreCase = true)) {
                        val encoderType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                            when {
                                info.isHardwareAccelerated -> EncoderType.HARDWARE
                                info.isSoftwareOnly -> EncoderType.SOFTWARE
                                !info.isVendor && (info.name.startsWith("c2.android.") || info.name.startsWith("OMX.google.")) -> EncoderType.SOFTWARE
                                else -> EncoderType.UNKNOWN
                            }
                        } else {
                            classifyLegacyCodec(info.name)
                        }

                        var maxWidth = 1920
                        var maxHeight = 1080
                        var hasSurface = false
                        try {
                            val caps = info.getCapabilitiesForType(type)
                            hasSurface = caps.colorFormats.contains(MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
                            val videoCaps = caps.videoCapabilities
                            if (videoCaps != null) {
                                maxWidth = videoCaps.supportedWidths.upper
                                maxHeight = videoCaps.supportedHeights.upper
                            }
                        } catch (_: Exception) {
                            // Non-fatal, use default 1080p upper bound
                        }

                        list.add(
                            EncoderCapability(
                                codecName = info.name,
                                mimeType = type,
                                encoderType = encoderType,
                                maxSupportedWidth = maxWidth,
                                maxSupportedHeight = maxHeight,
                                supportsSurfaceInput = hasSurface
                            )
                        )
                    }
                }
            }
        } catch (_: Exception) {
            // Guard against device-specific MediaCodecList exceptions
        }
        return list
    }

    private fun classifyLegacyCodec(codecName: String): EncoderType {
        val lower = codecName.lowercase()
        return when {
            lower.startsWith("omx.google.") || lower.startsWith("c2.android.") || lower.contains(".sw.") || lower.contains("software") -> EncoderType.SOFTWARE
            lower.startsWith("omx.qcom.") || lower.startsWith("c2.qti.") || lower.startsWith("omx.exynos.") || lower.startsWith("c2.exynos.") || lower.startsWith("omx.mtk.") || lower.startsWith("c2.mtk.") -> EncoderType.HARDWARE
            else -> EncoderType.UNKNOWN
        }
    }

    internal fun calculateRecommendedProfile(
        ram: RamMetrics,
        cpu: CpuMetrics,
        hasHwH264: Boolean
    ): StreamProfile {
        return when {
            // Low-end first: if RAM is constrained, CPU is <= 4 cores, or no HW encoder, enforce LOW
            ram.isConstrained || cpu.isConstrained || !hasHwH264 -> StreamProfile.LOW
            // Mid-tier: 3GB - 6GB RAM with hardware encoder
            ram.totalMemMb in 3072L..5999L -> StreamProfile.BALANCED
            // High-tier: >= 6GB RAM, >= 8 CPU cores, hardware encoder
            ram.totalMemMb >= 6000L && cpu.coreCount >= 8 && hasHwH264 -> StreamProfile.HIGH
            else -> StreamProfile.BALANCED
        }
    }
}
