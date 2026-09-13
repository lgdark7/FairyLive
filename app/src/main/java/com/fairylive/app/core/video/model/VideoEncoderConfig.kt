package com.fairylive.app.core.video.model

import android.media.MediaCodecInfo
import android.media.MediaFormat
import com.fairylive.app.core.capability.model.StreamProfile

/**
 * Immutable configuration parameters for MediaCodec video encoding.
 * Dimensions are strictly clamped and aligned to encoder boundaries.
 */
data class VideoEncoderConfig(
    val width: Int = 854,
    val height: Int = 480,
    val bitrateKbps: Int = 1200,
    val fps: Int = 24,
    val keyframeIntervalSec: Int = 2,
    val bitrateMode: Int = MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_CBR,
    val profile: Int = MediaCodecInfo.CodecProfileLevel.AVCProfileBaseline,
    val mimeType: String = MediaFormat.MIMETYPE_VIDEO_AVC
) {
    val bitrateBps: Int
        get() = bitrateKbps * 1000

    val alignedWidth: Int
        get() = (width.coerceIn(240, 2048) / 16) * 16

    val alignedHeight: Int
        get() = (height.coerceIn(240, 2048) / 2) * 2

    fun toMediaFormat(): MediaFormat {
        val format = MediaFormat.createVideoFormat(mimeType, alignedWidth, alignedHeight)
        format.setInteger(
            MediaFormat.KEY_COLOR_FORMAT,
            MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface
        )
        format.setInteger(MediaFormat.KEY_BIT_RATE, bitrateBps)
        format.setInteger(MediaFormat.KEY_FRAME_RATE, fps)
        format.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, keyframeIntervalSec)
        format.setInteger(MediaFormat.KEY_BITRATE_MODE, bitrateMode)
        format.setInteger(MediaFormat.KEY_PROFILE, profile)
        return format
    }

    companion object {
        fun fromProfile(profile: StreamProfile): VideoEncoderConfig {
            return VideoEncoderConfig(
                width = profile.width,
                height = profile.height,
                bitrateKbps = profile.videoBitrateKbps,
                fps = profile.fps,
                keyframeIntervalSec = 2,
                bitrateMode = MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_CBR,
                profile = MediaCodecInfo.CodecProfileLevel.AVCProfileBaseline,
                mimeType = MediaFormat.MIMETYPE_VIDEO_AVC
            )
        }
    }
}
