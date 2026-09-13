package com.fairylive.app.core.video

import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.media.MediaFormat
import android.os.Build
import com.fairylive.app.core.capability.model.EncoderType
import com.fairylive.app.core.capability.model.StreamProfile
import com.fairylive.app.core.video.model.VideoEncoderConfig

data class CodecSelection(
    val codecName: String,
    val encoderType: EncoderType,
    val supportedColorFormats: List<Int>,
    val maxSupportedWidth: Int,
    val maxSupportedHeight: Int,
    val maxBitrateBps: Int,
    val supportsSurface: Boolean
) {
    val isHardwareAccelerated: Boolean
        get() = encoderType == EncoderType.HARDWARE

    val isSoftwareOnly: Boolean
        get() = encoderType == EncoderType.SOFTWARE
}

/**
 * Manages video encoder discovery, capability analysis, and adaptive resolution scaling.
 * Prioritizes hardware acceleration while ensuring safe fallback for constrained/emulated environments.
 */
object VideoEncoderCapabilityManager {

    /**
     * Finds the optimal encoder adhering to strict hierarchy:
     * 1. Hardware H.264 encoder supporting surface input
     * 2. Any hardware encoder supporting surface input
     * 3. Unknown encoder supporting surface input
     * 4. Software H.264 encoder (c2.android preferred over OMX.google)
     * 5. First available fallback
     */
    fun findOptimalEncoder(mimeType: String = MediaFormat.MIMETYPE_VIDEO_AVC): CodecSelection? {
        val candidates = findAllEncoders(mimeType)
        if (candidates.isEmpty()) return null

        // 1. Hardware H.264 encoder supporting surface input
        val hwSurface = candidates.firstOrNull { it.encoderType == EncoderType.HARDWARE && it.supportsSurface }
        if (hwSurface != null) return hwSurface.toCodecSelection()

        // 2. Any hardware encoder
        val anyHw = candidates.firstOrNull { it.encoderType == EncoderType.HARDWARE }
        if (anyHw != null) return anyHw.toCodecSelection()

        // 3. Unknown encoder supporting surface input
        val unknownSurface = candidates.firstOrNull { it.encoderType == EncoderType.UNKNOWN && it.supportsSurface }
        if (unknownSurface != null) return unknownSurface.toCodecSelection()

        // 4. Software surface encoder (Codec2 preferred over legacy OMX)
        val c2Software = candidates.firstOrNull { it.name.startsWith("c2.android.") && it.supportsSurface }
        if (c2Software != null) return c2Software.toCodecSelection()

        val anySoftware = candidates.firstOrNull { it.encoderType == EncoderType.SOFTWARE && it.supportsSurface }
        if (anySoftware != null) return anySoftware.toCodecSelection()

        // 5. Fallback to first candidate
        return candidates.firstOrNull()?.toCodecSelection()
    }

    private data class Candidate(
        val name: String,
        val encoderType: EncoderType,
        val colorFormats: List<Int>,
        val maxWidth: Int,
        val maxHeight: Int,
        val maxBitrate: Int
    ) {
        val supportsSurface: Boolean
            get() = colorFormats.contains(MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)

        fun toCodecSelection() = CodecSelection(
            codecName = name,
            encoderType = encoderType,
            supportedColorFormats = colorFormats,
            maxSupportedWidth = maxWidth,
            maxSupportedHeight = maxHeight,
            maxBitrateBps = maxBitrate,
            supportsSurface = supportsSurface
        )
    }

    private fun findAllEncoders(mimeType: String): List<Candidate> {
        val list = mutableListOf<Candidate>()
        try {
            val codecList = MediaCodecList(MediaCodecList.REGULAR_CODECS)
            for (info in codecList.codecInfos) {
                if (!info.isEncoder) continue

                val hasMime = info.supportedTypes.any { it.equals(mimeType, ignoreCase = true) }
                if (!hasMime) continue

                val encoderType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    when {
                        info.isHardwareAccelerated -> EncoderType.HARDWARE
                        info.isSoftwareOnly -> EncoderType.SOFTWARE
                        !info.isVendor && (info.name.startsWith("c2.android.") || info.name.startsWith("OMX.google.")) -> EncoderType.SOFTWARE
                        else -> EncoderType.UNKNOWN
                    }
                } else {
                    val lower = info.name.lowercase()
                    when {
                        lower.startsWith("omx.google.") || lower.startsWith("c2.android.") || lower.contains(".sw.") || lower.contains("software") -> EncoderType.SOFTWARE
                        lower.startsWith("omx.qcom.") || lower.startsWith("c2.qti.") || lower.startsWith("omx.exynos.") || lower.startsWith("c2.exynos.") || lower.startsWith("omx.mtk.") || lower.startsWith("c2.mtk.") -> EncoderType.HARDWARE
                        else -> EncoderType.UNKNOWN
                    }
                }

                var colorFormats = emptyList<Int>()
                var maxWidth = 1920
                var maxHeight = 1080
                var maxBitrate = 10_000_000

                try {
                    val caps = info.getCapabilitiesForType(mimeType)
                    colorFormats = caps.colorFormats.toList()
                    caps.videoCapabilities?.let { vc ->
                        maxWidth = vc.supportedWidths.upper
                        maxHeight = vc.supportedHeights.upper
                        vc.bitrateRange?.let { br -> maxBitrate = br.upper }
                    }
                } catch (_: Exception) {}

                list.add(
                    Candidate(
                        name = info.name,
                        encoderType = encoderType,
                        colorFormats = colorFormats,
                        maxWidth = maxWidth,
                        maxHeight = maxHeight,
                        maxBitrate = maxBitrate
                    )
                )
            }
        } catch (_: Exception) {}
        return list
    }

    /**
     * Resolves encoding dimensions that preserve the source aspect ratio, fit within
     * the encoder's max supported bounds, and adhere to 16-pixel width / height alignment.
     */
    fun resolveAdaptiveDimensions(
        sourceWidth: Int,
        sourceHeight: Int,
        profile: StreamProfile,
        selection: CodecSelection?
    ): Pair<Int, Int> {
        val maxW = selection?.maxSupportedWidth ?: 2048
        val maxH = selection?.maxSupportedHeight ?: 2048

        val isPortrait = sourceHeight > sourceWidth

        if (isPortrait) {
            val aspectRatio = sourceWidth.toDouble() / sourceHeight.toDouble()
            val targetWidth = when (profile) {
                StreamProfile.LOW -> 480
                StreamProfile.BALANCED -> 720
                StreamProfile.HIGH, StreamProfile.CUSTOM -> 1080
            }

            var calculatedHeight = (targetWidth / aspectRatio).toInt()
            var calculatedWidth = targetWidth

            // If calculated height exceeds encoder maximum (e.g. 2400 > 2048), scale down proportionally
            if (calculatedHeight > maxH) {
                calculatedHeight = maxH
                calculatedWidth = (calculatedHeight * aspectRatio).toInt()
            }
            if (calculatedWidth > maxW) {
                calculatedWidth = maxW
                calculatedHeight = (calculatedWidth / aspectRatio).toInt()
            }

            // Align width and height to 16-pixel macroblocks for encoder stability
            val alignedW = (calculatedWidth / 16) * 16
            val alignedH = (calculatedHeight / 16) * 16

            return Pair(alignedW, alignedH)
        } else {
            // Landscape orientation: standard 16:9 or source aspect ratio
            val targetW = profile.width.coerceAtMost(maxW)
            val targetH = profile.height.coerceAtMost(maxH)

            val alignedW = (targetW / 16) * 16
            val alignedH = (targetH / 2) * 2

            return Pair(alignedW, alignedH)
        }
    }

    fun clampConfig(config: VideoEncoderConfig, selection: CodecSelection?): VideoEncoderConfig {
        if (selection == null) return config

        val safeWidth = config.alignedWidth.coerceAtMost(selection.maxSupportedWidth)
        val safeHeight = config.alignedHeight.coerceAtMost(selection.maxSupportedHeight)
        val safeBitrate = config.bitrateBps.coerceAtMost(selection.maxBitrateBps) / 1000

        return config.copy(
            width = safeWidth,
            height = safeHeight,
            bitrateKbps = safeBitrate
        )
    }
}
