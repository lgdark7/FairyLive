package com.fairylive.app.core.video

import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.media.MediaFormat
import android.os.Build
import com.fairylive.app.core.video.model.VideoEncoderConfig

data class CodecSelection(
    val codecName: String,
    val isHardwareAccelerated: Boolean,
    val supportedColorFormats: List<Int>,
    val maxSupportedWidth: Int,
    val maxSupportedHeight: Int,
    val maxBitrateBps: Int
)

/**
 * Manages video encoder discovery and capability analysis.
 * Prioritizes hardware acceleration while ensuring safe fallback for constrained/emulated environments.
 */
object VideoEncoderCapabilityManager {

    fun findOptimalEncoder(mimeType: String = MediaFormat.MIMETYPE_VIDEO_AVC): CodecSelection? {
        val candidates = findAllEncoders(mimeType)
        if (candidates.isEmpty()) return null

        // 1. Prefer hardware accelerated encoder supporting surface input
        val hwSurface = candidates.firstOrNull { it.isHardwareAccelerated && it.supportsSurface }
        if (hwSurface != null) return hwSurface.toCodecSelection()

        // 2. Any hardware encoder
        val anyHw = candidates.firstOrNull { it.isHardwareAccelerated }
        if (anyHw != null) return anyHw.toCodecSelection()

        // 3. Software surface encoder (Codec2 preferred over legacy OMX)
        val c2Software = candidates.firstOrNull { it.name.startsWith("c2.android.") && it.supportsSurface }
        if (c2Software != null) return c2Software.toCodecSelection()

        // 4. Any surface encoder
        val anySurface = candidates.firstOrNull { it.supportsSurface }
        if (anySurface != null) return anySurface.toCodecSelection()

        // 5. First available fallback
        return candidates.firstOrNull()?.toCodecSelection()
    }

    private data class Candidate(
        val name: String,
        val isHardwareAccelerated: Boolean,
        val colorFormats: List<Int>,
        val maxWidth: Int,
        val maxHeight: Int,
        val maxBitrate: Int
    ) {
        val supportsSurface: Boolean
            get() = colorFormats.contains(MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)

        fun toCodecSelection() = CodecSelection(
            codecName = name,
            isHardwareAccelerated = isHardwareAccelerated,
            supportedColorFormats = colorFormats,
            maxSupportedWidth = maxWidth,
            maxSupportedHeight = maxHeight,
            maxBitrateBps = maxBitrate
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

                val isHw = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    info.isHardwareAccelerated
                } else {
                    val lower = info.name.lowercase()
                    !lower.startsWith("omx.google.") &&
                            !lower.startsWith("c2.android.") &&
                            !lower.contains(".sw.") &&
                            !lower.contains("software")
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
                        isHardwareAccelerated = isHw,
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
