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

    @Test
    fun testInvestigateFanVsSpeech() {
        val sampleRate = 44100
        val totalSamples = 132300

        // 1. Generate baseFan
        val baseFan = FloatArray(totalSamples)
        val rand = java.util.Random(42L)
        var b0 = 0.0f; var b1 = 0.0f; var b2 = 0.0f
        for (i in 0 until totalSamples) {
            val white = rand.nextFloat() * 2.0f - 1.0f
            b0 = b0 * 0.99886f + white * 0.0555179f
            b1 = b1 * 0.99332f + white * 0.0750759f
            b2 = b2 * 0.969f + white * 0.153852f
            val pink = (b0 + b1 + b2 + white * 0.5362f) * 0.04f
            val drone120 = (sin(753.982 * i.toDouble() / sampleRate.toDouble()) * 0.035).toFloat()
            val drone240 = (sin(1507.96 * i.toDouble() / sampleRate.toDouble()) * 0.015).toFloat()
            baseFan[i] = pink + drone120 + drone240
        }

        // 2. Generate quiet low-pitch speech on top of fan
        val s3 = FloatArray(totalSamples)
        for (i in 0 until totalSamples) {
            val t = i.toDouble() / sampleRate.toDouble()
            val isSpeech = t in 0.5..2.5
            val speech = if (isSpeech) {
                (sin(2.0 * Math.PI * 115.0 * t) * 0.040 +
                 sin(2.0 * Math.PI * 230.0 * t) * 0.025 +
                 sin(2.0 * Math.PI * 345.0 * t) * 0.015).toFloat()
            } else 0.0f
            s3[i] = baseFan[i] + speech
        }

        vad.setSensitivity(80)
        var fanActive = 0
        var voicedCount = 0
        var unvoicedCount = 0
        var harmonicCount = 0
        var instantVoiceCount = 0
        var avgAmdf = 0.0; var avgFormant = 0.0; var avgRumble = 0.0; var avgSnr = 0.0; var avgNoiseFloor = 0.0
        var count = 0
        for (i in 0 until totalSamples) {
            val micSample = baseFan[i]
            val active = vad.processSample(micSample)
            if (active) fanActive++
            val formantRatio = vad.voiceEnvelope / (vad.totalEnvelope + 1e-6f)
            val rumbleRatio = vad.rumbleEnvelope / (vad.totalEnvelope + 1e-6f)
            val snr = vad.totalEnvelope / (vad.estimatedNoiseFloor + 1e-6f)
            val isHarmonic = vad.minAmdf < 0.35f
            if (isHarmonic) harmonicCount++
            val isVoiced = isHarmonic && (rumbleRatio < 0.90f) && (formantRatio > 0.20f) && (snr > 1.18f)
            if (isVoiced) voicedCount++
            val isUnvoiced = (formantRatio > 0.60f) && (rumbleRatio < 0.40f) && (snr > (1.18f * 1.25f))
            if (isUnvoiced) unvoicedCount++
            if ((isVoiced || isUnvoiced) && (vad.totalEnvelope > 0.0024f)) instantVoiceCount++

            if (i > 22050) { // steady state
                avgAmdf += vad.minAmdf
                avgFormant += formantRatio.toDouble()
                avgRumble += rumbleRatio.toDouble()
                avgSnr += snr.toDouble()
                avgNoiseFloor += vad.estimatedNoiseFloor.toDouble()
                count++
            }
        }
        println("[Diagnostic] S1 BaseFan active: $fanActive / $totalSamples (${fanActive * 100.0 / totalSamples}%)")
        println("[Diagnostic] InstantVoice=$instantVoiceCount, Voiced=$voicedCount, Unvoiced=$unvoicedCount, Harmonic=$harmonicCount")
        println("[Diagnostic] S1 steady stats: AMDF=${avgAmdf/count}, FormantRatio=${avgFormant/count}, RumbleRatio=${avgRumble/count}, SNR=${avgSnr/count}, NoiseFloor=${avgNoiseFloor/count}")

        vad.reset()
        vad.setSensitivity(80)
        var s3SpeechActive = 0
        var s3PauseActive = 0
        var s3SpeechVoiced = 0; var s3PauseVoiced = 0
        var s3AvgFormantSpeech = 0.0; var s3AvgRumbleSpeech = 0.0; var s3AvgAmdfSpeech = 0.0
        var s3SpeechCount = 0
        for (i in 0 until totalSamples) {
            val micSample = s3[i]
            val active = vad.processSample(micSample)
            val t = i.toDouble() / sampleRate.toDouble()
            val formantRatio = vad.voiceEnvelope / (vad.totalEnvelope + 1e-6f)
            val rumbleRatio = vad.rumbleEnvelope / (vad.totalEnvelope + 1e-6f)
            val isHarmonic = vad.minAmdf < 0.35f
            val isVoiced = isHarmonic && (rumbleRatio < 0.90f) && (formantRatio > 0.20f) && (vad.totalEnvelope / (vad.estimatedNoiseFloor + 1e-6f) > 1.18f)

            if (t in 0.6..2.4) {
                if (active) s3SpeechActive++
                if (isVoiced) s3SpeechVoiced++
                s3AvgFormantSpeech += formantRatio.toDouble()
                s3AvgRumbleSpeech += rumbleRatio.toDouble()
                s3AvgAmdfSpeech += vad.minAmdf.toDouble()
                s3SpeechCount++
            } else if (t < 0.4 || t > 2.8) {
                if (active) s3PauseActive++
                if (isVoiced) s3PauseVoiced++
            }
        }
        val s5 = FloatArray(totalSamples)
        val s5Mask = BooleanArray(totalSamples)
        for (i in 0 until totalSamples) {
            s5[i] = baseFan[i] * 0.5f
            val t = i.toDouble() / sampleRate.toDouble()
            if (t >= 0.5 && t < 2.5) {
                s5Mask[i] = true
                val primarySpeech = (sin(816.814 * i.toDouble() / sampleRate.toDouble()) * 0.12).toFloat()
                s5[i] += primarySpeech
            }
            if (t >= 0.7 && t < 2.0) {
                val competingSpeech = (sin(1382.3 * i.toDouble() / sampleRate.toDouble()) * 0.035).toFloat()
                s5[i] += competingSpeech
            }
        }

        val s6 = FloatArray(totalSamples)
        val s6Mask = BooleanArray(totalSamples)
        for (i in 0 until totalSamples) {
            s6[i] = baseFan[i] * 0.2f
            val t = i.toDouble() / sampleRate.toDouble()
            if (t >= 0.5 && t < 2.5) {
                s6Mask[i] = true
                var speech = (sin(942.478 * i.toDouble() / sampleRate.toDouble()) * 0.10).toFloat()
                if (t >= 1.5 && t < 1.7) {
                    speech *= 5.0f
                }
                s6[i] += speech
            }
        }

        val s5Active = runTestOnScenario(s5, s5Mask)
        val s6Active = runTestOnScenario(s6, s6Mask)
        println("[Diagnostic] S5 Active in speech: ${s5Active.first} / ${s5Active.second}, S6 Active in speech: ${s6Active.first} / ${s6Active.second}")
    }

    private fun runTestOnScenario(input: FloatArray, mask: BooleanArray): Pair<Int, Int> {
        val testVad = VoiceActivityDetector()
        testVad.setSensitivity(80)
        var speechActive = 0
        var totalSpeech = 0
        var avgAmdf = 0.0; var avgFormant = 0.0; var avgRumble = 0.0; var avgSnr = 0.0; var count = 0
        for (i in input.indices) {
            val active = testVad.processSample(input[i])
            if (mask[i]) {
                totalSpeech++
                if (active) speechActive++
                if (i > 44100) { // after 1.0s
                    val formantRatio = testVad.voiceEnvelope / (testVad.totalEnvelope + 1e-6f)
                    val rumbleRatio = testVad.rumbleEnvelope / (testVad.totalEnvelope + 1e-6f)
                    val snr = testVad.totalEnvelope / (testVad.estimatedNoiseFloor + 1e-6f)
                    avgAmdf += testVad.minAmdf
                    avgFormant += formantRatio
                    avgRumble += rumbleRatio
                    avgSnr += snr
                    count++
                }
            }
        }
        if (count > 0) {
            println("    [Scenario Stats] AMDF=${avgAmdf/count}, Formant=${avgFormant/count}, Rumble=${avgRumble/count}, SNR=${avgSnr/count}")
        }
        return Pair(speechActive, totalSpeech)
    }
}
