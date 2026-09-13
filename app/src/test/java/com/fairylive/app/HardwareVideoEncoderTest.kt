package com.fairylive.app

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import com.fairylive.app.core.capability.model.StreamProfile
import com.fairylive.app.core.video.EncoderStateNotifier
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
            width = 855, // Unaligned width
            height = 479, // Odd height
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
        assertEquals(2200, config.bitrateKbps)
        assertEquals(30, config.fps)
        assertEquals(MediaFormat.MIMETYPE_VIDEO_AVC, config.mimeType)
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
    fun testEncoderMetricsUpdates() {
        val metricsList = mutableListOf<EncoderMetrics>()
        val listener = EncoderStateNotifier.MetricsListener { metrics ->
            metricsList.add(metrics)
        }

        EncoderStateNotifier.addMetricsListener(listener)

        val metrics = EncoderMetrics(
            framesEncoded = 120L,
            keyframesEncoded = 4L,
            outputFps = 29.8,
            bitrateKbps = 2150L,
            lastLatencyMs = 12L,
            codecName = "c2.android.avc.encoder",
            isHardwareAccelerated = false,
            width = 1280,
            height = 720,
            isPaused = false
        )

        EncoderStateNotifier.updateMetrics(metrics)

        assertEquals(1, metricsList.size)
        assertEquals(120L, metricsList[0].framesEncoded)
        assertEquals(4L, metricsList[0].keyframesEncoded)
        assertEquals(29.8, metricsList[0].outputFps, 0.01)
        assertEquals(2150L, metricsList[0].bitrateKbps)
        assertEquals("c2.android.avc.encoder", metricsList[0].codecName)
    }
}
