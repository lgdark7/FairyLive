package com.fairylive.app

import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaFormat
import com.fairylive.app.core.audio.AudioPlaybackCaptureHelper
import com.fairylive.app.core.audio.AudioStateNotifier
import com.fairylive.app.core.audio.model.AudioCaptureConfig
import com.fairylive.app.core.audio.model.AudioCaptureState
import com.fairylive.app.core.audio.model.AudioMetrics
import com.fairylive.app.core.audio.model.AudioSourceType
import com.fairylive.app.core.audio.model.EncodedAudioFrame
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class AudioPipelineTest {

    @Before
    fun setup() {
        AudioStateNotifier.reset()
    }

    @Test
    fun testAudioCaptureConfigCalculation() {
        val monoConfig = AudioCaptureConfig(
            sampleRate = 44100,
            channelConfig = AudioFormat.CHANNEL_IN_MONO,
            bitrateKbps = 96,
            sourceType = AudioSourceType.MIC
        )

        assertEquals(1, monoConfig.channelCount)
        assertEquals(2, monoConfig.bytesPerSample)
        assertEquals(2, monoConfig.bytesPerFrame)
        assertTrue("Min buffer size must be at least 2048", monoConfig.minBufferSize >= 2048)

        val stereoConfig = monoConfig.copy(channelConfig = AudioFormat.CHANNEL_IN_STEREO)
        assertEquals(2, stereoConfig.channelCount)
        assertEquals(4, stereoConfig.bytesPerFrame)
    }

    @Test
    fun testAudioCaptureConfigProperties() {
        val config = AudioCaptureConfig(
            sampleRate = 48000,
            channelConfig = AudioFormat.CHANNEL_IN_STEREO,
            bitrateKbps = 128
        )
        assertEquals(48000, config.sampleRate)
        assertEquals(2, config.channelCount)
        assertEquals(128, config.bitrateKbps)
        assertEquals(4, config.bytesPerFrame)
        assertTrue(config.minBufferSize >= 2048)
    }

    @Test
    fun testMonotonicTimestampCalculation() {
        val sampleRate = 44100L
        val startEpochUs = 1000000L
        val bufferSamples = 1024L

        var totalSamplesRead = 0L
        val timestamps = mutableListOf<Long>()

        for (i in 0 until 50) {
            val pts = startEpochUs + (totalSamplesRead * 1000000L) / sampleRate
            timestamps.add(pts)
            totalSamplesRead += bufferSamples
        }

        assertEquals(50, timestamps.size)
        // Verify timestamps are strictly monotonically increasing
        for (i in 1 until timestamps.size) {
            assertTrue("PTS must strictly increase without drift", timestamps[i] > timestamps[i - 1])
            val deltaUs = timestamps[i] - timestamps[i - 1]
            // At 44.1kHz, 1024 samples takes ~23,220 microseconds
            assertTrue("PTS interval must match frame duration", Math.abs(deltaUs - 23220L) <= 2L)
        }
    }

    @Test
    fun testEncodedAudioFrameFlags() {
        val configFrame = EncodedAudioFrame(
            data = byteArrayOf(0x12, 0x10), // AAC audio specific config
            presentationTimeUs = 0L,
            flags = MediaCodec.BUFFER_FLAG_CODEC_CONFIG,
            size = 2
        )
        assertTrue(configFrame.isConfigFrame)
        assertFalse(configFrame.isEndOfStream)

        val normalFrame = EncodedAudioFrame(
            data = byteArrayOf(1, 2, 3, 4),
            presentationTimeUs = 23219L,
            flags = 0,
            size = 4
        )
        assertFalse(normalFrame.isConfigFrame)
        assertFalse(normalFrame.isEndOfStream)
    }

    @Test
    fun testAudioStateNotifierTransitions() {
        val observedStates = mutableListOf<AudioCaptureState>()
        val listener = AudioStateNotifier.StateListener { newState, _ ->
            observedStates.add(newState)
        }

        AudioStateNotifier.addStateListener(listener)

        AudioStateNotifier.updateState(AudioCaptureState.INITIALIZING)
        AudioStateNotifier.updateState(AudioCaptureState.RECORDING)
        AudioStateNotifier.updateState(AudioCaptureState.MUTED)
        AudioStateNotifier.updateState(AudioCaptureState.RECORDING)
        AudioStateNotifier.updateState(AudioCaptureState.STOPPING)
        AudioStateNotifier.updateState(AudioCaptureState.IDLE)

        assertEquals(6, observedStates.size)
        assertEquals(AudioCaptureState.INITIALIZING, observedStates[0])
        assertEquals(AudioCaptureState.RECORDING, observedStates[1])
        assertEquals(AudioCaptureState.MUTED, observedStates[2])
        assertEquals(AudioCaptureState.RECORDING, observedStates[3])
        assertEquals(AudioCaptureState.STOPPING, observedStates[4])
        assertEquals(AudioCaptureState.IDLE, observedStates[5])

        AudioStateNotifier.removeStateListener(listener)
    }

    @Test
    fun testAudioMetricsUpdates() {
        val metricsList = mutableListOf<AudioMetrics>()
        val listener = AudioStateNotifier.MetricsListener { metrics ->
            metricsList.add(metrics)
        }

        AudioStateNotifier.addMetricsListener(listener)

        val metrics = AudioMetrics(
            samplesRead = 88200L,
            peakAmplitudePercent = 45,
            encodedFrames = 86L,
            bitrateKbps = 96L,
            sampleRate = 44100,
            channels = 1,
            isMuted = false,
            isDeviceAudio = false
        )

        AudioStateNotifier.updateMetrics(metrics)

        assertEquals(1, metricsList.size)
        assertEquals(88200L, metricsList[0].samplesRead)
        assertEquals(45, metricsList[0].peakAmplitudePercent)
        assertEquals(86L, metricsList[0].encodedFrames)
        assertEquals(96L, metricsList[0].bitrateKbps)
        assertFalse(metricsList[0].isMuted)
    }

    @Test
    fun testDeviceAudioPlaybackSupportProbe() {
        val isSupported = AudioPlaybackCaptureHelper.isDeviceAudioSupported()
        // On JVM tests, Build.VERSION.SDK_INT is 0, so should be false
        assertFalse(isSupported)
    }
}
