package com.fairylive.app

import com.fairylive.app.core.audio.dsp.SpectralNoiseSuppressor
import org.junit.Assert.assertEquals
import org.junit.Test
import kotlin.math.PI
import kotlin.math.sin
import kotlin.math.sqrt

class SpectralSuppressorPassThroughTest {

    @Test
    fun testPassThroughGain() {
        val sns = SpectralNoiseSuppressor(sampleRate = 44100.0f)
        sns.isEnabled = true
        sns.intensity = 0.0f // minimum suppression

        val sampleRate = 44100
        val freq = 1000.0
        val numSamples = 44100 // 1 second

        var sumSqIn = 0.0
        var sumSqOut = 0.0
        val startSample = 4410 // measure after 100ms startup

        for (i in 0 until numSamples) {
            val angle = 2.0 * PI * freq * (i.toDouble() / sampleRate.toDouble())
            val inSample = (0.5 * sin(angle)).toFloat()
            // In pass-through test, speech is active
            val outSample = sns.processSample(inSample, isSpeechActive = true)

            if (i >= startSample) {
                sumSqIn += inSample * inSample
                sumSqOut += outSample * outSample
            }
        }

        val rmsIn = sqrt(sumSqIn / (numSamples - startSample))
        val rmsOut = sqrt(sumSqOut / (numSamples - startSample))
        val gainDb = 20.0 * kotlin.math.log10(rmsOut / rmsIn)

        println("Pass-through RMS in: $rmsIn, out: $rmsOut, Gain: $gainDb dB")
    }
}
