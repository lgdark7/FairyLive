package com.fairylive.app

import android.media.MediaCodec
import android.media.MediaFormat
import com.fairylive.app.core.capability.model.EncoderType
import com.fairylive.app.core.capability.model.StreamProfile
import com.fairylive.app.core.video.CodecSelection
import com.fairylive.app.core.video.EncoderStateNotifier
import com.fairylive.app.core.video.VideoEncoderCapabilityManager
import com.fairylive.app.core.video.model.EncodedFrame
import com.fairylive.app.core.video.model.EncoderMetrics
import com.fairylive.app.core.video.model.EncoderState
import com.fairylive.app.core.video.model.VideoEncoderConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class HardwareVideoEncoderTest {

    @Before
    fun setup() {
        EncoderStateNotifier.reset()
    }

    @Test
    fun testVideoEncoderConfigAlignment() {
        val config = VideoEncoderConfig(
            width = 855,
            height = 479,
            bitrateKbps = 1200,
            fps = 24
        )

        assertEquals("Width must be 16-pixel aligned", 848, config.alignedWidth)
        assertEquals("Height must be 2-pixel aligned", 478, config.alignedHeight)
        assertEquals(1200_000, config.bitrateBps)
    }

    @Test
    fun testConfigFromStreamProfile() {
        val profile = StreamProfile.BALANCED
        val config = VideoEncoderConfig.fromProfile(profile)

        assertEquals(1280, config.alignedWidth)
        assertEquals(720, config.alignedHeight)
        assertEquals(2500, config.bitrateKbps)
        assertEquals(30, config.fps)
        assertEquals(MediaFormat.MIMETYPE_VIDEO_AVC, config.mimeType)
    }

    @Test
    fun testAdaptiveResolutionPortraitPreservesAspectRatio() {
        val dummySelection = CodecSelection(
            codecName = "c2.android.avc.encoder",
            encoderType = EncoderType.SOFTWARE,
            supportedColorFormats = listOf(0x7f000789),
            maxSupportedWidth = 2048,
            maxSupportedHeight = 2048,
            maxBitrateBps = 12000000,
            supportsSurface = true
        )

        // 1080x2400 is 9:20 aspect ratio (0.45)
        val (w, h) = VideoEncoderCapabilityManager.resolveAdaptiveDimensions(
            sourceWidth = 1080,
            sourceHeight = 2400,
            profile = StreamProfile.BALANCED,
            selection = dummySelection
        )

        assertEquals("Width should scale to 720", 720, w)
        assertEquals("Height should scale to 1600 to preserve 9:20 ratio", 1600, h)
        assertEquals("Width must be 16-pixel aligned", 0, w % 16)
        assertEquals("Height must be 16-pixel aligned", 0, h % 16)
        assertTrue("Height must fit within encoder limit of 2048", h <= dummySelection.maxSupportedHeight)
    }

    @Test
    fun testAdaptiveResolutionLandscapeUsesStandard() {
        val dummySelection = CodecSelection(
            codecName = "c2.android.avc.encoder",
            encoderType = EncoderType.SOFTWARE,
            supportedColorFormats = listOf(0x7f000789),
            maxSupportedWidth = 2048,
            maxSupportedHeight = 2048,
            maxBitrateBps = 12000000,
            supportsSurface = true
        )

        val (w, h) = VideoEncoderCapabilityManager.resolveAdaptiveDimensions(
            sourceWidth = 2400,
            sourceHeight = 1080,
            profile = StreamProfile.BALANCED,
            selection = dummySelection
        )

        assertEquals(1280, w)
        assertEquals(720, h)
    }

    @Test
    fun testEncodedFrameFlags() {
        val keyFrame = EncodedFrame(
            data = byteArrayOf(0, 0, 0, 1, 0x65),
            presentationTimeUs = 1000L,
            flags = MediaCodec.BUFFER_FLAG_KEY_FRAME,
            size = 5
        )
        assertTrue(keyFrame.isKeyFrame)
        assertFalse(keyFrame.isConfigFrame)
        assertFalse(keyFrame.isEndOfStream)

        val configFrame = EncodedFrame(
            data = byteArrayOf(0, 0, 0, 1, 0x67),
            presentationTimeUs = 0L,
            flags = MediaCodec.BUFFER_FLAG_CODEC_CONFIG,
            size = 5
        )
        assertFalse(configFrame.isKeyFrame)
        assertTrue(configFrame.isConfigFrame)

        val eosFrame = EncodedFrame(
            data = byteArrayOf(),
            presentationTimeUs = 5000L,
            flags = MediaCodec.BUFFER_FLAG_END_OF_STREAM,
            size = 0
        )
        assertTrue(eosFrame.isEndOfStream)
    }

    @Test
    fun testEncoderStateNotifierTransitions() {
        val observedStates = mutableListOf<EncoderState>()
        val listener = EncoderStateNotifier.StateListener { newState, _ ->
            observedStates.add(newState)
        }

        EncoderStateNotifier.addStateListener(listener)

        EncoderStateNotifier.updateState(EncoderState.CONFIGURING)
        EncoderStateNotifier.updateState(EncoderState.ENCODING)
        EncoderStateNotifier.updateState(EncoderState.PAUSED)
        EncoderStateNotifier.updateState(EncoderState.ENCODING)
        EncoderStateNotifier.updateState(EncoderState.STOPPING)
        EncoderStateNotifier.updateState(EncoderState.IDLE)

        assertEquals(6, observedStates.size)
        assertEquals(EncoderState.CONFIGURING, observedStates[0])
        assertEquals(EncoderState.ENCODING, observedStates[1])
        assertEquals(EncoderState.PAUSED, observedStates[2])
        assertEquals(EncoderState.ENCODING, observedStates[3])
        assertEquals(EncoderState.STOPPING, observedStates[4])
        assertEquals(EncoderState.IDLE, observedStates[5])

        EncoderStateNotifier.removeStateListener(listener)
    }

    @Test
    fun testEncoderMetricsClassification() {
        val hwMetrics = EncoderMetrics(
            framesEncoded = 60L,
            codecName = "c2.qti.avc.encoder",
            encoderType = EncoderType.HARDWARE
        )
        assertTrue(hwMetrics.isHardwareAccelerated)
        assertFalse(hwMetrics.isSoftwareOnly)

        val swMetrics = EncoderMetrics(
            framesEncoded = 60L,
            codecName = "c2.android.avc.encoder",
            encoderType = EncoderType.SOFTWARE
        )
        assertFalse(swMetrics.isHardwareAccelerated)
        assertTrue(swMetrics.isSoftwareOnly)

        val unkMetrics = EncoderMetrics(
            framesEncoded = 60L,
            codecName = "custom.encoder",
            encoderType = EncoderType.UNKNOWN
        )
        assertFalse(unkMetrics.isHardwareAccelerated)
        assertFalse(unkMetrics.isSoftwareOnly)
    }
}
