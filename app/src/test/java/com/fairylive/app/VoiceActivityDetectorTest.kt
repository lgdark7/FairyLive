package com.fairylive.app

import com.fairylive.app.core.audio.dsp.VoiceActivityDetector
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import kotlin.math.sin

class VoiceActivityDetectorTest {

    private lateinit var vad: VoiceActivityDetector

    @Before
    fun setUp() {
        vad = VoiceActivityDetector(sampleRate = 44100)
    }

    @Test
    fun testVoiceActivityDetectorDetectsVoicedSpeechTone() {
        // Synthesize 440 Hz vocal tone with harmonics (simulating human vowel)
        val sampleRate = 44100
        val numSamples = 2048 // ~46 ms
        var voiceTriggered = false

        for (i in 0 until numSamples) {
            val angle = 2.0 * Math.PI * 440.0 * (i.toDouble() / sampleRate.toDouble())
            val sample = (0.35 * sin(angle) + 0.15 * sin(angle * 2.0)).toFloat()
            val active = vad.processSample(sample)
            if (active) voiceTriggered = true
        }

        assertTrue("VAD must detect sustained human voiced harmonic tone", voiceTriggered)
        assertTrue("VAD isVoiceActive must remain true due to speech hangover", vad.isVoiceActive)
    }

    @Test
    fun testVoiceActivityDetectorRejectsCeilingFanDroneAndHiss() {
        // Synthesize low-frequency fan drone (90 Hz motor rumble) + random air turbulence
        val sampleRate = 44100
        val numSamples = 4096 // ~92 ms
        var pseudoRandom = 123456789L

        for (i in 0 until numSamples) {
            val angle = 2.0 * Math.PI * 90.0 * (i.toDouble() / sampleRate.toDouble())
            // Simple LCG PRNG for reproducible test noise
            pseudoRandom = (pseudoRandom * 6364136223846793005L + 1442695040888963407L)
            val noise = ((pseudoRandom ushr 32).toInt().toShort() / 32768.0f) * 0.04f
            val sample = (0.06f * sin(angle).toFloat()) + noise

            vad.processSample(sample)
        }

        assertFalse("VAD must NOT trigger on stationary fan motor drone and hiss", vad.isVoiceActive)
    }

    @Test
    fun testVoiceActivityDetectorRejectsScreenTapTransient() {
        // Synthesize sharp screen tap impulse (single 4ms click followed by silence)
        val numSamples = 2048
        var pseudoRandom = 987654321L

        for (i in 0 until numSamples) {
            val sample = if (i in 100..200) {
                pseudoRandom = (pseudoRandom * 6364136223846793005L + 1442695040888963407L)
                val noise = ((pseudoRandom ushr 32).toInt().toShort() / 32768.0f)
                0.40f * (1.0f - (i - 100) / 100.0f) * noise
            } else {
                0.0f
            }

            vad.processSample(sample)
        }

        assertFalse("VAD must reject brief screen tap transients via onset thresholding", vad.isVoiceActive)
    }

    @Test
    fun testVoiceActivityDetectorSpeechHangoverHoldsAcrossPauses() {
        val sampleRate = 44100
        val speechSamples = 2048

        // Step 1: Feed active speech to trigger VAD
        for (i in 0 until speechSamples) {
            val angle = 2.0 * Math.PI * 300.0 * (i.toDouble() / sampleRate.toDouble())
            vad.processSample((0.30f * sin(angle).toFloat()))
        }
        assertTrue("VAD must be active during sustained speech", vad.isVoiceActive)

        // Step 2: Feed brief pause (5000 samples ≈ 113 ms, which is within the ~270 ms hangover)
        for (i in 0 until 5000) {
            vad.processSample(0.0f)
        }
        assertTrue("VAD must hold active state across brief conversational pauses", vad.isVoiceActive)

        // Step 3: Feed long silence (20,000 samples ≈ 453 ms, which exceeds the hangover)
        for (i in 0 until 20000) {
            vad.processSample(0.0f)
        }
        assertFalse("VAD must smoothly transition to silence after hangover counter expires", vad.isVoiceActive)
    }

    @Test
    fun testVoiceActivityDetectorSensitivityAndReset() {
        vad.setSensitivity(80)
        vad.reset()

        assertFalse(vad.isVoiceActive)
        assertEquals(0.0f, vad.voiceProbability, 0.001f)
    }
}
