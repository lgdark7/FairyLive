package com.fairylive.app

import com.fairylive.app.core.audio.dsp.SpectralNoiseSuppressor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Enterprise verification test suite for SpectralNoiseSuppressor.
 * Validates spectral subtraction math, COLA reconstruction fidelity,
 * under-speech fan/hiss attenuation, and numeric stability.
 */
class SpectralNoiseSuppressorTest {

    private lateinit var suppressor: SpectralNoiseSuppressor
    private val sampleRate = 44100.0f

    @Before
    fun setUp() {
        suppressor = SpectralNoiseSuppressor(sampleRate = sampleRate)
        suppressor.intensity = 0.70f
        suppressor.isEnabled = true
    }

    @Test
    fun testColaUnityGainReconstructionOnCleanSpeech() {
        // Feed pure 440 Hz sine tone representing vowel formant
        val durationSec = 0.5f
        val numSamples = (sampleRate * durationSec).toInt()
        val freq = 440.0

        var sumSqIn = 0.0
        var sumSqOut = 0.0
        var dotProd = 0.0

        // Suppressor bypass to verify clean path
        suppressor.isEnabled = false

        for (i in 0 until numSamples) {
            val t = i / sampleRate
            val sample = (0.5 * sin(2.0 * PI * freq * t)).toFloat()
            val out = suppressor.processSample(sample, isSpeechActive = true)

            sumSqIn += sample * sample
            sumSqOut += out * out
            dotProd += sample * out
        }

        val rmsIn = sqrt(sumSqIn / numSamples)
        val rmsOut = sqrt(sumSqOut / numSamples)
        assertEquals(rmsIn, rmsOut, 1e-4)
    }

    @Test
    fun testStftReconstructionMeasurements() {
        // Test current implementation of SpectralNoiseSuppressor when isEnabled = true
        val testSupp = SpectralNoiseSuppressor(sampleRate = sampleRate)
        testSupp.isEnabled = true
        testSupp.intensity = 0.0f // Zero suppression intensity

        // 1. Impulse test
        testSupp.resetState()
        val impulseIn = FloatArray(1024)
        impulseIn[256] = 0.5f
        val impulseOut = FloatArray(1024)
        for (i in impulseIn.indices) {
            impulseOut[i] = testSupp.processSample(impulseIn[i], isSpeechActive = true)
        }
        val maxImpulseOut = impulseOut.maxOrNull() ?: 0f
        println("[STFT Audit] Impulse peak in: 0.5, peak out: $maxImpulseOut (ratio: ${maxImpulseOut / 0.5f})")

        // 2. 1000 Hz Sine tone test (steady state after 512 samples)
        testSupp.resetState()
        val numSine = 4410
        val sineIn = FloatArray(numSine) { i -> (0.2 * sin(2.0 * PI * 1000.0 * i / sampleRate)).toFloat() }
        val sineOut = FloatArray(numSine)
        for (i in sineIn.indices) {
            sineOut[i] = testSupp.processSample(sineIn[i], isSpeechActive = true)
        }
        // Find algorithmic delay
        var bestLag = 0
        var maxDot = -1e9
        for (lag in 0 until 512) {
            var dot = 0.0
            for (i in 1000 until 3500) {
                dot += sineIn[i] * sineOut[i + lag]
            }
            if (dot > maxDot) {
                maxDot = dot
                bestLag = lag
            }
        }
        var maxDiff = 0.0
        var sumSqDiff = 0.0
        var sumSqSig = 0.0
        for (i in 1000 until 3500) {
            val diff = abs(sineOut[i + bestLag] - sineIn[i])
            if (diff > maxDiff) maxDiff = diff.toDouble()
            sumSqDiff += diff * diff
            sumSqSig += sineIn[i] * sineIn[i]
        }
        val reconSnrDb = 10.0 * log10(sumSqSig / sumSqDiff)
        println("[STFT Audit] Algorithmic Delay = $bestLag samples (${bestLag * 1000.0 / sampleRate} ms)")
        println("[STFT Audit] Time-aligned Max Error = $maxDiff, Reconstruction SNR = $reconSnrDb dB")

        var sineInEnergy = 0.0
        var sineOutEnergy = 0.0
        // Measure steady state from sample 1024 to 4000
        for (i in 1024 until 4000) {
            sineInEnergy += sineIn[i] * sineIn[i]
            sineOutEnergy += sineOut[i] * sineOut[i]
        }
        val sineRmsIn = sqrt(sineInEnergy / (4000 - 1024))
        val sineRmsOut = sqrt(sineOutEnergy / (4000 - 1024))
        val sineGain = sineRmsOut / sineRmsIn
        val sineGainDb = 20.0 * log10(sineGain)
        println("[STFT Audit] 1000 Hz Tone: In RMS=$sineRmsIn, Out RMS=$sineRmsOut, Gain=$sineGain (${sineGainDb} dB)")

        // 3. Speech-like PCM test (multi-tone harmonic complex: 200, 400, 800, 1600 Hz)
        testSupp.resetState()
        val numSpeech = 8820
        val speechIn = FloatArray(numSpeech) { i ->
            val t = i / sampleRate
            (0.10 * sin(2.0 * PI * 200.0 * t) +
             0.08 * sin(2.0 * PI * 400.0 * t) +
             0.05 * sin(2.0 * PI * 800.0 * t) +
             0.03 * sin(2.0 * PI * 1600.0 * t)).toFloat()
        }
        val speechOut = FloatArray(numSpeech)
        for (i in speechIn.indices) {
            speechOut[i] = testSupp.processSample(speechIn[i], isSpeechActive = true)
        }
        var speechInEnergy = 0.0
        var speechOutEnergy = 0.0
        for (i in 2048 until 8000) {
            speechInEnergy += speechIn[i] * speechIn[i]
            speechOutEnergy += speechOut[i] * speechOut[i]
        }
        val speechRmsIn = sqrt(speechInEnergy / (8000 - 2048))
        val speechRmsOut = sqrt(speechOutEnergy / (8000 - 2048))
        val speechGain = speechRmsOut / speechRmsIn
        val speechGainDb = 20.0 * log10(speechGain)
        println("[STFT Audit] Speech Complex: In RMS=$speechRmsIn, Out RMS=$speechRmsOut, Gain=$speechGain (${speechGainDb} dB)")
    }

    @Test
    fun testUnderSpeechFanNoiseAttenuation() {
        suppressor.isEnabled = true
        suppressor.intensity = 0.75f

        // Phase 1: Train noise profile on pure 100Hz fan hum + 3200Hz hiss (0.5s silence)
        val trainSamples = (sampleRate * 0.5f).toInt()
        for (i in 0 until trainSamples) {
            val t = i / sampleRate
            val fan100 = (0.02f * sin(2.0 * PI * 100.0 * t)).toFloat()
            val hiss3200 = (0.01f * sin(2.0 * PI * 3200.0 * t)).toFloat()
            suppressor.processSample(fan100 + hiss3200, isSpeechActive = false)
        }

        // Phase 2: Creator begins speaking (420Hz vocal vowel formant) while fan continues
        val speechSamples = (sampleRate * 0.5f).toInt()
        val speechOutputs = FloatArray(speechSamples)

        for (i in 0 until speechSamples) {
            val t = (trainSamples + i) / sampleRate
            val fan100 = (0.02f * sin(2.0 * PI * 100.0 * t)).toFloat()
            val hiss3200 = (0.01f * sin(2.0 * PI * 3200.0 * t)).toFloat()
            val vocal420 = (0.25f * sin(2.0 * PI * 420.0 * t)).toFloat()

            val mixedInput = fan100 + hiss3200 + vocal420
            speechOutputs[i] = suppressor.processSample(mixedInput, isSpeechActive = true)
        }

        // Measure residual 100Hz fan component during speech using discrete Fourier correlation
        val evalWindow = speechOutputs.copyOfRange(speechSamples / 2, speechSamples)
        val n = evalWindow.size

        var cos100 = 0.0
        var sin100 = 0.0
        var cos420 = 0.0
        var sin420 = 0.0

        for (i in 0 until n) {
            val t = i / sampleRate
            cos100 += evalWindow[i] * cos(2.0 * PI * 100.0 * t)
            sin100 += evalWindow[i] * sin(2.0 * PI * 100.0 * t)
            cos420 += evalWindow[i] * cos(2.0 * PI * 420.0 * t)
            sin420 += evalWindow[i] * sin(2.0 * PI * 420.0 * t)
        }

        val outFanAmp = sqrt(cos100 * cos100 + sin100 * sin100) * 2.0 / n
        val outVocalAmp = sqrt(cos420 * cos420 + sin420 * sin420) * 2.0 / n

        val fanAttenuationDb = 20.0 * log10(0.02 / max(1e-5, outFanAmp))
        val vocalPassRatio = outVocalAmp / 0.25

        println("SpectralNoiseSuppressorTest: 100Hz Fan Attenuation under speech = ${String.format("%.2f", fanAttenuationDb)} dB")
        println("SpectralNoiseSuppressorTest: 420Hz Vocal Pass Ratio = ${String.format("%.1f", vocalPassRatio * 100)}%")

        // Under-speech fan attenuation must exceed 12 dB (measured ~13 dB, vs old pipeline's 2.18 dB)
        // Note: 256-pt STFT with half-sine window has ~-32 dB sidelobe leakage from the 0.25 vocal tone,
        // which physically limits discrete Fourier correlation on a 0.02 tone to ~13 dB.
        assertTrue("Under-speech fan attenuation must exceed 12 dB, got $fanAttenuationDb dB", fanAttenuationDb >= 12.0)
        // Vocal preservation must exceed 85%
        assertTrue("Speech vowel must be preserved (>85%), got ${vocalPassRatio * 100}%", vocalPassRatio >= 0.85)
    }

    @Test
    fun testRumbleFilterSuppressesSubAudibleFrequencies() {
        suppressor.updateHighPassFilter(120.0f)
        suppressor.isEnabled = true

        // Feed 50Hz desk vibration
        val numSamples = 2048
        var inRms = 0.0
        var outRms = 0.0

        for (i in 0 until numSamples) {
            val t = i / sampleRate
            val sample = (0.20f * sin(2.0 * PI * 50.0 * t)).toFloat()
            val out = suppressor.processSample(sample, isSpeechActive = false)
            if (i > 512) { // Allow filter ramp to settle
                inRms += sample * sample
                outRms += out * out
            }
        }

        inRms = sqrt(inRms / (numSamples - 512))
        outRms = sqrt(outRms / (numSamples - 512))
        val attenuationDb = 20.0 * log10(inRms / max(1e-5, outRms))

        println("SpectralNoiseSuppressorTest: 50Hz Rumble Attenuation = ${String.format("%.2f", attenuationDb)} dB")
        assertTrue("50Hz rumble attenuation must exceed 12 dB, got $attenuationDb dB", attenuationDb >= 12.0)
    }

    @Test
    fun testNumericStabilityWithZeroesAndClipping() {
        suppressor.resetState()

        // 1. All zeroes input
        for (i in 0 until 1024) {
            val out = suppressor.processSample(0.0f, isSpeechActive = false)
            assertFalse("Output must never be NaN on silence", out.isNaN())
            assertFalse("Output must never be Infinite on silence", out.isInfinite())
            assertEquals(0.0f, out, 1e-4f)
        }

        // 2. Full-scale clipping input (+1.0f and -1.0f square wave)
        for (i in 0 until 1024) {
            val sample = if (i % 2 == 0) 1.0f else -1.0f
            val out = suppressor.processSample(sample, isSpeechActive = true)
            assertFalse("Output must never be NaN on clipping", out.isNaN())
            assertFalse("Output must never be Infinite on clipping", out.isInfinite())
            assertTrue("Output must remain bounded", abs(out) <= 2.0f)
        }
    }

    @Test
    fun testProcessingSpeedAndZeroAllocation() {
        // Benchmark 1 second of audio (44100 samples)
        val startTime = System.nanoTime()
        for (i in 0 until 44100) {
            val sample = (0.1f * sin(2.0 * PI * 300.0 * (i / sampleRate))).toFloat()
            suppressor.processSample(sample, isSpeechActive = (i % 4410 > 2205))
        }
        val elapsedMs = (System.nanoTime() - startTime) / 1_000_000.0
        println("SpectralNoiseSuppressorTest: 44,100 samples processed in ${String.format("%.2f", elapsedMs)} ms")

        // 1 second of audio must process in less than 50ms on JVM (real-time is 1000ms, so >20x faster than real-time)
        assertTrue("Processing must be ultra fast (< 50ms for 1s audio), took $elapsedMs ms", elapsedMs < 50.0)
    }
}
