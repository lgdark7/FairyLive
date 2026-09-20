package com.fairylive.app

import com.fairylive.app.core.audio.dsp.AudioMixerProcessor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class AudioMixerProcessorTest {

    @Test
    fun testDefaultSettings() {
        val mixer = AudioMixerProcessor()
        assertEquals(0.9f, mixer.micVolume, 0.001f)
        assertEquals(0.8f, mixer.gameVolume, 0.001f)
        assertEquals(0.5f, mixer.mediaVolume, 0.001f)
        assertEquals(1.0f, mixer.masterVolume, 0.001f)
        assertFalse(mixer.isMicMuted)
        assertFalse(mixer.isGameMuted)
        assertTrue(mixer.isNoiseSuppressionEnabled)
        assertTrue(mixer.isCompressorEnabled)
        assertTrue(mixer.isLimiterEnabled)
    }

    @Test
    fun testPresetApplication() {
        val mixer = AudioMixerProcessor()

        mixer.applyPreset(AudioMixerProcessor.AudioPreset.VOICE_FOCUS)
        assertEquals(1.0f, mixer.micVolume, 0.001f)
        assertEquals(0.4f, mixer.gameVolume, 0.001f)
        assertTrue(mixer.isAgcEnabled)
        assertTrue(mixer.isCompressorEnabled)

        mixer.applyPreset(AudioMixerProcessor.AudioPreset.MUSIC)
        assertEquals(0.7f, mixer.micVolume, 0.001f)
        assertEquals(0.6f, mixer.gameVolume, 0.001f)
        assertEquals(0.8f, mixer.mediaVolume, 0.001f)
        assertFalse(mixer.isNoiseSuppressionEnabled)

        mixer.applyPreset(AudioMixerProcessor.AudioPreset.LOW_END)
        assertFalse(mixer.isNoiseSuppressionEnabled)
        assertFalse(mixer.isCompressorEnabled)
        assertFalse(mixer.isLimiterEnabled)
        assertFalse(mixer.isAgcEnabled)
    }

    @Test
    fun testMuteEliminatesChannel() {
        val mixer = AudioMixerProcessor()
        val numSamples = 256
        val bytes = numSamples * 2

        // Synthesize non-zero mic buffer (half-amplitude 16-bit sine: 16000)
        val micBuffer = ByteArray(bytes)
        for (i in 0 until bytes - 1 step 2) {
            val sample = 16000.toShort()
            micBuffer[i] = (sample.toInt() and 0xFF).toByte()
            micBuffer[i + 1] = ((sample.toInt() shr 8) and 0xFF).toByte()
        }

        val gameBuffer = ByteArray(bytes) // zero
        val outBuffer = ByteArray(bytes)

        // 1. Unmuted -> output should be non-zero
        mixer.isMicMuted = false
        mixer.processAndMix(micBuffer, gameBuffer, outBuffer, bytes, isPaused = false)
        var hasNonZero = false
        for (b in outBuffer) {
            if (b.toInt() != 0) {
                hasNonZero = true
                break
            }
        }
        assertTrue("Output should contain audio when mic is unmuted", hasNonZero)

        // 2. Muted -> output should be completely silent
        mixer.isMicMuted = true
        outBuffer.fill(0)
        mixer.processAndMix(micBuffer, gameBuffer, outBuffer, bytes, isPaused = false)
        var mutedNonZero = false
        for (b in outBuffer) {
            if (b.toInt() != 0) {
                mutedNonZero = true
                break
            }
        }
        assertFalse("Output should be complete silence when mic is muted", mutedNonZero)
    }

    @Test
    fun testBrickwallLimiterPreventsClipping() {
        val mixer = AudioMixerProcessor()
        mixer.micVolume = 1.5f
        mixer.gameVolume = 1.5f
        mixer.isLimiterEnabled = true

        val numSamples = 256
        val bytes = numSamples * 2

        // Synthesize near-maximum signals on BOTH channels
        val micBuffer = ByteArray(bytes)
        val gameBuffer = ByteArray(bytes)
        for (i in 0 until bytes - 1 step 2) {
            val sample = 32000.toShort()
            micBuffer[i] = (sample.toInt() and 0xFF).toByte()
            micBuffer[i + 1] = ((sample.toInt() shr 8) and 0xFF).toByte()
            gameBuffer[i] = (sample.toInt() and 0xFF).toByte()
            gameBuffer[i + 1] = ((sample.toInt() shr 8) and 0xFF).toByte()
        }

        val outBuffer = ByteArray(bytes)
        mixer.processAndMix(micBuffer, gameBuffer, outBuffer, bytes, isPaused = false)

        // Check that all 16-bit signed PCM samples stay within range and no wrapping occurred
        for (i in 0 until bytes - 1 step 2) {
            val sample = ((outBuffer[i + 1].toInt() shl 8) or (outBuffer[i].toInt() and 0xFF)).toShort()
            assertTrue("Sample must not exceed 16-bit PCM bounds", sample >= -32768 && sample <= 32767)
            // Soft limiter ensures output is positive and bounded
            assertTrue("Sample must remain positive for positive inputs", sample > 0)
        }
    }

    @Test
    fun testVuMeterCalculations() {
        val mixer = AudioMixerProcessor()
        val numSamples = 256
        val bytes = numSamples * 2

        val micBuffer = ByteArray(bytes)
        for (i in 0 until bytes - 1 step 2) {
            val sample = 16384.toShort() // ~ -6 dB
            micBuffer[i] = (sample.toInt() and 0xFF).toByte()
            micBuffer[i + 1] = ((sample.toInt() shr 8) and 0xFF).toByte()
        }

        val outBuffer = ByteArray(bytes)
        mixer.processAndMix(micBuffer, null, outBuffer, bytes, isPaused = false)

        // Mic dB should be roughly around -6 to -8 dB
        assertTrue("Mic dB must be measured", mixer.micDb > -20.0f && mixer.micDb <= 0.0f)
        // Game dB should be silence (-60 dB)
        assertEquals(-60.0f, mixer.gameDb, 0.1f)
    }

    @Test
    fun testNoiseGateAttenuatesFloorNoise() {
        val mixer = AudioMixerProcessor()
        mixer.isNoiseSuppressionEnabled = true
        mixer.micVolume = 1.0f

        val numSamples = 512
        val bytes = numSamples * 2

        // Synthesize low-amplitude background hiss (amplitude 100, which is ~ -50dB)
        val noiseMicBuffer = ByteArray(bytes)
        for (i in 0 until bytes - 1 step 2) {
            val sample = 100.toShort()
            noiseMicBuffer[i] = (sample.toInt() and 0xFF).toByte()
            noiseMicBuffer[i + 1] = ((sample.toInt() shr 8) and 0xFF).toByte()
        }

        val outBuffer = ByteArray(bytes)
        mixer.processAndMix(noiseMicBuffer, null, outBuffer, bytes, isPaused = false)

        // The noise gate should attenuate the signal down significantly (to <= 15)
        for (i in 0 until bytes - 1 step 2) {
            val sample = abs(((outBuffer[i + 1].toInt() shl 8) or (outBuffer[i].toInt() and 0xFF)).toShort().toInt())
            assertTrue("Low amplitude hiss ($sample) should be gated by dynamic spectral expander", sample <= 15)
        }
    }

    @Test
    fun testFourChannelVolumeScaling() {
        val mixer = AudioMixerProcessor()
        mixer.micVolume = 0.5f
        mixer.gameVolume = 0.5f
        mixer.mediaVolume = 0.5f
        mixer.masterVolume = 1.0f
        mixer.isNoiseSuppressionEnabled = false // Raw linear check

        val numSamples = 64
        val bytes = numSamples * 2

        val mic = ByteArray(bytes)
        val game = ByteArray(bytes)
        val media = ByteArray(bytes)
        for (i in 0 until bytes - 1 step 2) {
            val s = 1000.toShort()
            mic[i] = (s.toInt() and 0xFF).toByte(); mic[i + 1] = ((s.toInt() shr 8) and 0xFF).toByte()
            game[i] = (s.toInt() and 0xFF).toByte(); game[i + 1] = ((s.toInt() shr 8) and 0xFF).toByte()
            media[i] = (s.toInt() and 0xFF).toByte(); media[i + 1] = ((s.toInt() shr 8) and 0xFF).toByte()
        }

        val out = ByteArray(bytes)
        mixer.processAndMix(mic, game, out, bytes, isPaused = false, mediaBuffer = media)

        // 1000*0.5 + 1000*0.5 + 1000*0.5 = 1500
        val firstSample = ((out[1].toInt() shl 8) or (out[0].toInt() and 0xFF)).toShort()
        assertTrue("Output ($firstSample) should approximate 1500 within limiter/filter tolerance", abs(firstSample - 1500) < 50)
    }
}
