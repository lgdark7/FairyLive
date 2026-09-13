package com.fairylive.app

import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.os.Build
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class CodecInspectionTest {

    @Test
    fun inspectAllDeviceAudioEncoders() {
        val codecList = MediaCodecList(MediaCodecList.ALL_CODECS)
        println("=== COMPLETE AUDIO CODEC INSPECTION ON DEVICE ===")
        for (info in codecList.codecInfos) {
            if (!info.isEncoder) continue
            val hasAac = info.supportedTypes.any { it.equals("audio/mp4a-latm", ignoreCase = true) }
            if (!hasAac) continue

            println("--------------------------------------------------")
            println("Audio Codec Name: ${info.name}")
            println("Is Encoder: ${info.isEncoder}")

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                println("isHardwareAccelerated: ${info.isHardwareAccelerated}")
                println("isSoftwareOnly: ${info.isSoftwareOnly}")
                println("isVendor: ${info.isVendor}")
            }

            try {
                val caps = info.getCapabilitiesForType("audio/mp4a-latm")
                caps.audioCapabilities?.let { ac ->
                    println("  Sample Rates: ${ac.supportedSampleRateRanges.map { "[${it.lower}, ${it.upper}]" }}")
                    println("  Max Input Channel Count: ${ac.maxInputChannelCount}")
                    ac.bitrateRange?.let { br ->
                        println("  Bitrate Range: [${br.lower} bps, ${br.upper} bps]")
                    }
                }
                val profiles = caps.profileLevels.map { "Profile:${it.profile}/Level:${it.level}" }
                println("  Profiles: $profiles")
            } catch (e: Exception) {
                println("  Error querying caps: ${e.message}")
            }
        }
        println("==================================================")
    }

    @Test
    fun inspectAllDeviceVideoEncoders() {
        val codecList = MediaCodecList(MediaCodecList.ALL_CODECS)
        println("=== COMPLETE MEDIA CODEC INSPECTION ON DEVICE ===")
        println("Build Model: ${Build.MODEL} (SDK ${Build.VERSION.SDK_INT})")

        for (info in codecList.codecInfos) {
            if (!info.isEncoder) continue
            val hasAvc = info.supportedTypes.any { it.equals("video/avc", ignoreCase = true) }
            val hasHevc = info.supportedTypes.any { it.equals("video/hevc", ignoreCase = true) }
            if (!hasAvc && !hasHevc) continue

            println("--------------------------------------------------")
            println("Codec Name: ${info.name}")
            println("Is Encoder: ${info.isEncoder}")

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                println("isHardwareAccelerated: ${info.isHardwareAccelerated}")
                println("isSoftwareOnly: ${info.isSoftwareOnly}")
                println("isVendor: ${info.isVendor}")
            }

            for (mime in info.supportedTypes) {
                if (!mime.equals("video/avc", ignoreCase = true) && !mime.equals("video/hevc", ignoreCase = true)) continue
                println("MIME: $mime")
                try {
                    val caps = info.getCapabilitiesForType(mime)
                    val colorFormats = caps.colorFormats.map { "0x" + Integer.toHexString(it) }
                    val hasSurface = caps.colorFormats.contains(MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
                    println("  Supports COLOR_FormatSurface (0x7f000789): $hasSurface")
                    println("  Supported Color Formats: $colorFormats")

                    caps.videoCapabilities?.let { vc ->
                        println("  Width Range: [${vc.supportedWidths.lower}, ${vc.supportedWidths.upper}]")
                        println("  Height Range: [${vc.supportedHeights.lower}, ${vc.supportedHeights.upper}]")
                        println("  Width Alignment: ${vc.widthAlignment}")
                        println("  Height Alignment: ${vc.heightAlignment}")
                        println("  FrameRate Range: [${vc.supportedFrameRates.lower}, ${vc.supportedFrameRates.upper}]")
                        println("  isSizeSupported(1080, 2400): ${vc.isSizeSupported(1080, 2400)}")
                        println("  isSizeSupported(1080, 1920): ${vc.isSizeSupported(1080, 1920)}")
                        println("  isSizeSupported(720, 1600): ${vc.isSizeSupported(720, 1600)}")
                        println("  isSizeSupported(480, 1056): ${vc.isSizeSupported(480, 1056)}")
                        println("  isSizeSupported(854, 480): ${vc.isSizeSupported(854, 480)}")
                        println("  isSizeSupported(1280, 720): ${vc.isSizeSupported(1280, 720)}")
                        println("  isSizeSupported(1920, 1080): ${vc.isSizeSupported(1920, 1080)}")
                        vc.bitrateRange?.let { br ->
                            println("  Bitrate Range: [${br.lower} bps, ${br.upper} bps]")
                        }
                    }

                    caps.encoderCapabilities?.let { ec ->
                        println("  Supports CBR (mode 2): ${ec.isBitrateModeSupported(MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_CBR)}")
                        println("  Supports VBR (mode 1): ${ec.isBitrateModeSupported(MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_VBR)}")
                        println("  Supports CQ (mode 0): ${ec.isBitrateModeSupported(MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_CQ)}")
                    }

                    val profiles = caps.profileLevels.map { "Profile:${it.profile}/Level:${it.level}" }
                    println("  Profile/Levels: $profiles")
                } catch (e: Exception) {
                    println("  Error querying caps for $mime: ${e.message}")
                }
            }
        }
        println("==================================================")
    }
}
