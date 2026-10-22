package com.fairylive.app

import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaFormat
import com.fairylive.app.core.audio.AudioPipeline
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

    @Test
    fun testMediaClockSessionLifecycle() {
        com.fairylive.app.core.common.MediaClock.stopSession()
        assertFalse(com.fairylive.app.core.common.MediaClock.isRunning)
        assertEquals(0L, com.fairylive.app.core.common.MediaClock.sessionOriginNs)

        val t0 = com.fairylive.app.core.common.MediaClock.startSession()
        assertTrue(com.fairylive.app.core.common.MediaClock.isRunning)
        assertTrue(t0 > 0L)

        // 100 milliseconds later (100,000,000 ns)
        val t1 = t0 + 100_000_000L
        val ptsUs = com.fairylive.app.core.common.MediaClock.toSessionPtsUs(t1)
        assertEquals(100_000L, ptsUs)

        com.fairylive.app.core.common.MediaClock.stopSession()
        assertFalse(com.fairylive.app.core.common.MediaClock.isRunning)
    }

    @Test
    fun testSharedAvClockDeterministicAlignment() {
        com.fairylive.app.core.common.MediaClock.stopSession()
        val originNs = com.fairylive.app.core.common.MediaClock.startSession()
        val originUs = originNs / 1000L

        // Video frame captured 40ms into session
        val rawVideoPtsUs = originUs + 40_000L
        val normalizedVideoPtsUs = (rawVideoPtsUs - originUs).coerceAtMost(Long.MAX_VALUE)
        assertEquals(40_000L, normalizedVideoPtsUs)

        // Audio sample recording starts 10ms into session, reads 1024 samples at 44.1kHz (~23.2ms)
        val audioStartRawNs = originNs + 10_000_000L
        val audioStartPtsUs = com.fairylive.app.core.common.MediaClock.toSessionPtsUs(audioStartRawNs)
        assertEquals(10_000L, audioStartPtsUs)

        val sampleRate = 44100L
        val samplesRead = 1024L
        val nextAudioPtsUs = audioStartPtsUs + (samplesRead * 1_000_000L) / sampleRate
        assertEquals(33219L, nextAudioPtsUs)

        // Relative offset between Video PTS and Audio PTS is deterministic and derived from same t0
        val relativeOffsetUs = normalizedVideoPtsUs - nextAudioPtsUs
        assertEquals(6781L, relativeOffsetUs)

        com.fairylive.app.core.common.MediaClock.stopSession()
    }

    @Test
    fun testAudioEncoderClassification() {
        val swMetrics = AudioMetrics(
            encodedFrames = 50L,
            encoderName = "c2.android.aac.encoder",
            encoderType = com.fairylive.app.core.capability.model.EncoderType.SOFTWARE
        )
        assertFalse(swMetrics.isHardwareAccelerated)
        assertTrue(swMetrics.isSoftwareOnly)

        val hwMetrics = AudioMetrics(
            encodedFrames = 50L,
            encoderName = "c2.qti.aac.encoder",
            encoderType = com.fairylive.app.core.capability.model.EncoderType.HARDWARE
        )
        assertTrue(hwMetrics.isHardwareAccelerated)
        assertFalse(hwMetrics.isSoftwareOnly)
    }

    @Test
    fun testAudioGapBridgingCalculations() {
        // Simulates the exact gap-filling math in AudioCaptureEngine
        val sampleRate = 44100
        val bytesPerFrame = 2
        val minBufferSize = 4096
        val silenceFramesPerChunk = (minBufferSize / bytesPerFrame).toLong()

        val stallDurationUs = 200_000L // 200ms stall (e.g. emulator HAL delay)
        val expectedMissingFrames = (stallDurationUs * sampleRate) / 1_000_000L
        assertEquals(8820L, expectedMissingFrames)

        var framesToBridge = expectedMissingFrames
        var totalDispatchedSilence = 0L
        val dispatchedPts = mutableListOf<Long>()
        val startTimestampUs = 100_000L
        var accumulatedSamples = 0L

        while (framesToBridge > 0L) {
            val chunkFrames = kotlin.math.min(framesToBridge, silenceFramesPerChunk).toInt()
            val chunkBytes = chunkFrames * bytesPerFrame
            assertTrue("Chunk bytes must fit within buffer", chunkBytes <= minBufferSize)

            val silencePtsUs = startTimestampUs + (accumulatedSamples * 1_000_000L) / sampleRate
            dispatchedPts.add(silencePtsUs)
            accumulatedSamples += chunkFrames
            totalDispatchedSilence += chunkFrames
            framesToBridge -= chunkFrames
        }

        // All missing frames were cleanly synthesized as silence
        assertEquals(8820L, totalDispatchedSilence)
        assertEquals(5, dispatchedPts.size) // 2048, 2048, 2048, 2048, 628

        // Verify strictly monotonic PTS progression with zero jumps
        for (i in 1 until dispatchedPts.size) {
            assertTrue("PTS must strictly increase", dispatchedPts[i] > dispatchedPts[i - 1])
        }

        // Next real frame continues with exact continuous sample clock
        val nextRealPtsUs = startTimestampUs + (accumulatedSamples * 1_000_000L) / sampleRate
        val totalElapsedUs = nextRealPtsUs - startTimestampUs
        assertEquals(200_000L, totalElapsedUs)
    }

    @Test
    fun testAudioMetricsSilenceTracking() {
        val metrics = AudioMetrics(
            samplesRead = 88200L,
            silenceFramesInserted = 8820L,
            peakAmplitudePercent = 45,
            encodedFrames = 86L,
            bitrateKbps = 96L
        )
        assertEquals(88200L, metrics.samplesRead)
        assertEquals(8820L, metrics.silenceFramesInserted)
        assertFalse(metrics.isMicSilencedBySystem)
    }

    @Test
    fun testAudioSilencedDetectionObserver() {
        var observedSilenced = false
        val listener = AudioStateNotifier.SilencedStateListener { isSilenced ->
            observedSilenced = isSilenced
        }
        AudioStateNotifier.addSilencedListener(listener)

        // Simulate game mic turning on -> system silences stream mic
        AudioStateNotifier.updateMicSilenced(true)
        assertTrue(AudioStateNotifier.isMicSilencedBySystem)
        assertTrue(observedSilenced)
        assertTrue(AudioStateNotifier.currentMetrics.isMicSilencedBySystem)

        // Simulate game mic turning off -> system restores stream mic
        AudioStateNotifier.updateMicSilenced(false)
        assertFalse(AudioStateNotifier.isMicSilencedBySystem)
        assertFalse(observedSilenced)
        assertFalse(AudioStateNotifier.currentMetrics.isMicSilencedBySystem)

        AudioStateNotifier.removeSilencedListener(listener)
    }

    @Test
    fun testAudioPipelineFourChannelVolumeControl() {
        val pipeline = AudioPipeline()
        pipeline.setMicVolume(0.75f)
        pipeline.setGameVolume(0.85f)
        pipeline.setMediaVolume(0.40f)
        pipeline.setMasterVolume(0.95f)

        assertEquals(0.75f, pipeline.micVolume, 0.001f)
        assertEquals(0.85f, pipeline.gameVolume, 0.001f)
        assertEquals(0.40f, pipeline.mediaVolume, 0.001f)
        assertEquals(0.95f, pipeline.masterVolume, 0.001f)

        pipeline.setMicMuted(true)
        pipeline.setGameMuted(true)
        pipeline.setMediaMuted(true)
        assertTrue(pipeline.isMicMuted)
        assertTrue(pipeline.isGameMuted)
        assertTrue(pipeline.isMediaMuted)
    }

    @Test
    fun testAudioCaptureStateHardwareAndSpectralFlags() {
        val metrics = AudioMetrics(
            isHardwareNsActive = true,
            isSpectralNsActive = true
        )
        assertTrue(metrics.isHardwareNsActive)
        assertTrue(metrics.isSpectralNsActive)

        val defaultMetrics = AudioMetrics()
        assertFalse(defaultMetrics.isHardwareNsActive)
        assertFalse(defaultMetrics.isSpectralNsActive)
    }

    @Test
    fun testAudioPipelineDspFiltersSpectralAndHardwareFlags() {
        val pipeline = AudioPipeline()
        pipeline.setDspFilters(
            noiseGate = true,
            agc = true,
            compressor = true,
            limiter = true,
            godMode = true,
            noiseLevel = 80,
            spectralNoise = true,
            hardwareNs = false
        )
        // Verify captureEngine received call without throwing and state remains coherent
        assertFalse(pipeline.isMicMuted)
    }
}
