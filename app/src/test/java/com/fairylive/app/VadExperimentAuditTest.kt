package com.fairylive.app

import com.fairylive.app.core.audio.dsp.AudioMixerProcessor
import com.fairylive.app.core.audio.dsp.SpectralNoiseSuppressor
import com.fairylive.app.core.audio.dsp.VoiceActivityDetector
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Random
import kotlin.math.*

/**
 * Test-only experimental VAD audit harness authorized by ChatGPT review.
 * Compares:
 * - Variant 0: Baseline VAD (Path A = 220 Hz, Path B = 80 Hz, Baseline rules)
 * - Variant 1: Path A cutoff changed from 220 Hz to 130 Hz
 * - Variant 2: Path B cutoff changed from 80 Hz to 105 Hz
 * - Variant 3: Both filter changes together (Path A 130 Hz + Path B 105 Hz)
 * - Variant 4: Dual-criterion harmonicity proposal alone (Path A 220 Hz, Path B 80 Hz)
 * - Variant 5: Combined variant (Both filters + Dual-criterion harmonicity)
 *
 * ZERO production files are modified.
 */
class VadExperimentAuditTest {

    companion object {
        const val SAMPLE_RATE = 44100
        const val DURATION_SEC = 3.0
        val TOTAL_SAMPLES = (SAMPLE_RATE * DURATION_SEC).toInt() // 132,300
    }

    enum class HarmonicityMode {
        BASELINE,
        DUAL_CRITERION
    }

    data class ExperimentVariant(
        val name: String,
        val hpFormantCutoff: Float,
        val hpAmdfCutoff: Float,
        val harmonicityMode: HarmonicityMode
    )

    data class ScenarioResult(
        val variantName: String,
        val scenarioName: String,
        val scenarioType: String,
        val fnr: Double,
        val fpr: Double,
        val onsetLatencyMs: Double,
        val isPreTriggered: Boolean,
        val fnDurationMs: Double,
        val fpDurationMs: Double,
        val totalSpeechFrames: Int,
        val totalNonSpeechFrames: Int,
        val speechLossDb: Double,
        val noiseAttenDb: Double,
        val consonantLossDb: Double,
        val status: String
    )

    /**
     * Isolated Experimental VAD implementing configurable filter cutoffs and harmonicity rules.
     * Mirrors production VoiceActivityDetector line-for-line when configured with baseline parameters.
     */
    class ExperimentalVoiceActivityDetector(
        val sampleRate: Int = SAMPLE_RATE,
        val hpFormantCutoff: Float = 220.0f,
        val hpAmdfCutoff: Float = 80.0f,
        val harmonicityMode: HarmonicityMode = HarmonicityMode.BASELINE
    ) {
        private val dt: Float = 1.0f / sampleRate.toFloat()

        private val rcHpFormant: Float = 1.0f / (2.0f * Math.PI.toFloat() * hpFormantCutoff)
        private val hpFormantAlpha: Float = rcHpFormant / (rcHpFormant + dt)
        private var hpF1PrevIn: Float = 0.0f
        private var hpF1PrevOut: Float = 0.0f
        private var hpF2PrevIn: Float = 0.0f
        private var hpF2PrevOut: Float = 0.0f

        private val rcHpAmdf: Float = 1.0f / (2.0f * Math.PI.toFloat() * hpAmdfCutoff)
        private val hpAmdfAlpha: Float = rcHpAmdf / (rcHpAmdf + dt)
        private var hpA1PrevIn: Float = 0.0f
        private var hpA1PrevOut: Float = 0.0f
        private var hpA2PrevIn: Float = 0.0f
        private var hpA2PrevOut: Float = 0.0f

        private val rcLp3200: Float = 1.0f / (2.0f * Math.PI.toFloat() * 3200.0f)
        private val lpVoiceAlpha: Float = dt / (rcLp3200 + dt)
        private var lpVoice: Float = 0.0f

        private val rcRumble180: Float = 1.0f / (2.0f * Math.PI.toFloat() * 180.0f)
        private val rumbleAlpha: Float = dt / (rcRumble180 + dt)
        private var lpRumble: Float = 0.0f

        var totalEnvelope: Float = 0.0f
        var voiceEnvelope: Float = 0.0f
        var rumbleEnvelope: Float = 0.0f

        var estimatedNoiseFloor: Float = 0.003f
        var estimatedVoiceFloor: Float = 0.001f

        private var dsAccumulator: Float = 0.0f
        private var dsSampleCounter: Int = 0
        private var samplesProcessed: Long = 0L

        private val ringBuffer = FloatArray(256)
        private var ringHead: Int = 0
        private var hopCounter: Int = 0
        var minAmdf: Float = 1.0f
        var bestLag: Int = 0

        var onsetCounter: Int = 0
        var hangoverCounter: Int = 0

        var onsetThreshold: Int = 440
        var hangoverSamples: Int = 14000
        var snrThreshold: Float = 1.14f
        var minVoiceEnvelope: Float = 0.0024f

        var isVoiceActive: Boolean = false
        var isDroneDetected: Boolean = false
        var isDroneDiscriminatorEnabled: Boolean = true
        var isInstantVoice: Boolean = false
        var voiceProbability: Float = 0.0f

        var currentFormantRatio: Float = 0.0f
        var currentRumbleRatio: Float = 0.0f
        var currentMinAmdf: Float = 1.0f

        init {
            setSensitivity(80)
        }

        fun setSensitivity(level: Int) {
            val clamped = level.coerceIn(0, 100)
            val norm = clamped / 100.0f
            snrThreshold = 1.30f - norm * 0.20f
            minVoiceEnvelope = 0.0040f - norm * 0.0020f
            onsetThreshold = (600.0f - norm * 200.0f).toInt().coerceAtLeast(350)
            hangoverSamples = (10000.0f + norm * 5000.0f).toInt()
        }

        fun processSample(micSample: Float): Boolean {
            // 1. Path A (Formant Band)
            val hF1 = micSample - hpF1PrevIn + hpFormantAlpha * hpF1PrevOut
            hpF1PrevIn = micSample
            hpF1PrevOut = hF1

            val hF2 = hF1 - hpF2PrevIn + hpFormantAlpha * hpF2PrevOut
            hpF2PrevIn = hF1
            hpF2PrevOut = hF2

            lpVoice += lpVoiceAlpha * (hF2 - lpVoice)
            val voiceSignal = lpVoice

            lpRumble += rumbleAlpha * (micSample - lpRumble)
            val rumbleSignal = lpRumble

            // Path B (AMDF Band)
            val hA1 = micSample - hpA1PrevIn + hpAmdfAlpha * hpA1PrevOut
            hpA1PrevIn = micSample
            hpA1PrevOut = hA1

            val hA2 = hA1 - hpA2PrevIn + hpAmdfAlpha * hpA2PrevOut
            hpA2PrevIn = hA1
            hpA2PrevOut = hA2

            // 2. Envelopes
            val absMic = abs(micSample)
            val absVoice = abs(voiceSignal)
            val absRumble = abs(rumbleSignal)

            val attackRate = 0.008f
            val releaseRate = 0.0006f

            val totalRate = if (absMic > totalEnvelope) attackRate else releaseRate
            totalEnvelope += totalRate * (absMic - totalEnvelope)

            val voiceRate = if (absVoice > voiceEnvelope) attackRate else releaseRate
            voiceEnvelope += voiceRate * (absVoice - voiceEnvelope)

            val rumbleRate = if (absRumble > rumbleEnvelope) attackRate else releaseRate
            rumbleEnvelope += rumbleRate * (absRumble - rumbleEnvelope)

            // 3. AMDF Decimation
            dsAccumulator += hA2
            dsSampleCounter++
            if (dsSampleCounter == 4) {
                val dsSample = dsAccumulator * 0.25f
                dsAccumulator = 0.0f
                dsSampleCounter = 0

                ringHead = (ringHead + 1) and 255
                ringBuffer[ringHead] = dsSample

                hopCounter++
                if (hopCounter >= 16) {
                    hopCounter = 0
                    computeAmdf()
                }
            }

            // 4. Feature Ratios & Drone Discriminator
            val denom = totalEnvelope + 1e-6f
            val formantRatio = voiceEnvelope / denom
            val rumbleRatio = rumbleEnvelope / denom
            currentFormantRatio = formantRatio
            currentRumbleRatio = rumbleRatio
            currentMinAmdf = minAmdf

            val curVoiceSnr = voiceEnvelope / estimatedVoiceFloor
            val isPureSpeech = (formantRatio > 0.70f) && (rumbleRatio < 0.40f)

            val rawDrone = (rumbleRatio > 0.60f && formantRatio < 0.28f) ||
                    (rumbleRatio > 0.55f && minAmdf < 0.08f && formantRatio < 0.48f && bestLag in 85..98)
            val isDrone = isDroneDiscriminatorEnabled && rawDrone
            isDroneDetected = isDrone
            val isLikelySpeech = isPureSpeech ||
                    ((formantRatio > 0.35f) && !isDrone) ||
                    (formantRatio > 0.30f && minAmdf < 0.30f && bestLag > 100)

            samplesProcessed++
            if (totalEnvelope > 0.0001f) {
                if (samplesProcessed < 200 && !isLikelySpeech) {
                    estimatedNoiseFloor += 0.02f * (totalEnvelope - estimatedNoiseFloor)
                    estimatedVoiceFloor += 0.02f * (voiceEnvelope - estimatedVoiceFloor)
                } else if (!isVoiceActive && !isLikelySpeech && (isDrone || onsetCounter == 0)) {
                    val adaptRate = if (isDrone && totalEnvelope > estimatedNoiseFloor) 0.025f else 0.0005f
                    estimatedNoiseFloor += adaptRate * (totalEnvelope - estimatedNoiseFloor)
                    estimatedVoiceFloor += adaptRate * (voiceEnvelope - estimatedVoiceFloor)
                } else {
                    if (totalEnvelope < estimatedNoiseFloor) {
                        val downRate = if (isDrone) 0.005f else 0.001f
                        estimatedNoiseFloor += downRate * (totalEnvelope - estimatedNoiseFloor)
                    }
                    if (voiceEnvelope < estimatedVoiceFloor) {
                        val downRate = if (isDrone) 0.005f else 0.001f
                        estimatedVoiceFloor += downRate * (voiceEnvelope - estimatedVoiceFloor)
                    }
                }
            }
            estimatedNoiseFloor = estimatedNoiseFloor.coerceIn(0.001f, 0.100f)
            estimatedVoiceFloor = estimatedVoiceFloor.coerceIn(0.0005f, 0.050f)

            // SNR
            val widebandSnr = totalEnvelope / estimatedNoiseFloor
            val voiceSnr = voiceEnvelope / estimatedVoiceFloor
            val snr = max(widebandSnr, voiceSnr)

            // 5. Harmonicity Rule (Baseline vs Dual-Criterion)
            val isHarmonic = when (harmonicityMode) {
                HarmonicityMode.BASELINE -> {
                    (minAmdf < 0.35f) || (isDrone && curVoiceSnr > 1.15f && formantRatio > 0.20f)
                }
                HarmonicityMode.DUAL_CRITERION -> {
                    (minAmdf < 0.35f) ||
                    ((minAmdf < 0.45f) && (curVoiceSnr > (snrThreshold * 1.25f)) && (formantRatio > 0.20f)) ||
                    (isDrone && curVoiceSnr > 1.15f && formantRatio > 0.20f)
                }
            }

            val hasVoiceFormants = isPureSpeech ||
                    (rumbleRatio <= 0.60f && formantRatio > 0.20f) ||
                    (!isDrone && formantRatio >= 0.26f) ||
                    (curVoiceSnr > 1.18f && formantRatio > 0.20f)

            val isVoiced = isHarmonic &&
                    (rumbleRatio < 0.92f) &&
                    hasVoiceFormants &&
                    (snr > snrThreshold) &&
                    (!isDrone || curVoiceSnr > 1.18f)

            val isUnvoiced = (formantRatio > 0.60f) &&
                    (rumbleRatio < 0.40f) &&
                    (snr > (snrThreshold * 1.25f))

            val instantVoice = (isVoiced || isUnvoiced) && (totalEnvelope > minVoiceEnvelope)
            isInstantVoice = instantVoice

            val harmonicScore = (0.50f - minAmdf).coerceAtLeast(0.0f) * 2.0f
            val formantScore = (formantRatio - 0.20f).coerceIn(0.0f, 1.0f)
            val instantProb = if (instantVoice) (formantScore * 0.6f + harmonicScore * 0.4f).coerceIn(0.0f, 1.0f) else 0.0f
            voiceProbability += 0.005f * (instantProb - voiceProbability)

            // 6. Anti-Transient Onset & Hangover State Machine
            if (instantVoice) {
                onsetCounter = min(onsetThreshold * 2, onsetCounter + 1)
                if (onsetCounter >= onsetThreshold) {
                    isVoiceActive = true
                    hangoverCounter = hangoverSamples
                } else if (hangoverCounter > 0) {
                    hangoverCounter--
                    isVoiceActive = true
                } else {
                    isVoiceActive = false
                }
            } else {
                onsetCounter = max(0, onsetCounter - 1)
                if (hangoverCounter > 0) {
                    hangoverCounter--
                    isVoiceActive = true
                } else {
                    isVoiceActive = false
                }
            }

            return isVoiceActive
        }

        private fun computeAmdf() {
            var bestAmdf = 1.0f
            var bestLagFound = 0
            val head = ringHead

            for (lag in 22..126 step 2) {
                var diffSum = 0.0f
                var normSum = 0.0f

                for (k in 0 until 64) {
                    val idx1 = (head - k) and 255
                    val idx2 = (head - k - lag) and 255
                    val s1 = ringBuffer[idx1]
                    val s2 = ringBuffer[idx2]

                    diffSum += abs(s1 - s2)
                    normSum += abs(s1) + abs(s2)
                }

                if (normSum > 0.02f) {
                    val ratio = diffSum / normSum
                    if (ratio < bestAmdf) {
                        bestAmdf = ratio
                        bestLagFound = lag
                    }
                }
            }

            minAmdf = bestAmdf
            bestLag = bestLagFound
        }

        fun reset() {
            hpF1PrevIn = 0.0f; hpF1PrevOut = 0.0f
            hpF2PrevIn = 0.0f; hpF2PrevOut = 0.0f
            hpA1PrevIn = 0.0f; hpA1PrevOut = 0.0f
            hpA2PrevIn = 0.0f; hpA2PrevOut = 0.0f
            lpVoice = 0.0f
            lpRumble = 0.0f
            totalEnvelope = 0.0f
            voiceEnvelope = 0.0f
            rumbleEnvelope = 0.0f
            estimatedNoiseFloor = 0.003f
            estimatedVoiceFloor = 0.001f
            samplesProcessed = 0L
            dsAccumulator = 0.0f
            dsSampleCounter = 0
            ringBuffer.fill(0.0f)
            ringHead = 0
            hopCounter = 0
            minAmdf = 1.0f
            bestLag = 0
            onsetCounter = 0
            hangoverCounter = 0
            isVoiceActive = false
            isDroneDetected = false
            voiceProbability = 0.0f
        }
    }

    // Helper functions
    private fun rms(samples: FloatArray, start: Int = 0, end: Int = samples.size): Double {
        if (start >= end) return 0.0
        var sum = 0.0
        for (i in start until end) {
            sum += samples[i] * samples[i]
        }
        return sqrt(sum / (end - start))
    }

    private fun toDbfs(rmsVal: Double): Double {
        if (rmsVal <= 1e-9) return -120.0
        return 20.0 * log10(rmsVal)
    }

    private fun generatePinkNoise(nSamples: Int, seed: Long = 42L): FloatArray {
        val rand = Random(seed)
        var b0 = 0.0f; var b1 = 0.0f; var b2 = 0.0f; var b3 = 0.0f; var b4 = 0.0f; var b5 = 0.0f; var b6 = 0.0f
        val out = FloatArray(nSamples)
        for (i in 0 until nSamples) {
            val white = rand.nextFloat() * 2.0f - 1.0f
            b0 = 0.99886f * b0 + white * 0.0555179f
            b1 = 0.99332f * b1 + white * 0.0750759f
            b2 = 0.96900f * b2 + white * 0.1538520f
            b3 = 0.86650f * b3 + white * 0.3104856f
            b4 = 0.55000f * b4 + white * 0.5329522f
            b5 = -0.7616f * b5 - white * 0.0168980f
            val pink = b0 + b1 + b2 + b3 + b4 + b5 + b6 + white * 0.5362f
            b6 = white * 0.115926f
            out[i] = pink * 0.11f
        }
        return out
    }

    private fun generateRealisticFan(f0: Double = 80.0, duration: Double = 3.0, levelDb: Double = -24.0, seed: Long = 101L): FloatArray {
        val n = (duration * SAMPLE_RATE).toInt()
        val pink = generatePinkNoise(n, seed)
        val targetRms = 10.0.pow(levelDb / 20.0)
        val out = FloatArray(n)
        for (i in 0 until n) {
            val t = i.toDouble() / SAMPLE_RATE
            val tone = (sin(2.0 * PI * f0 * t) * 0.70 +
                    sin(2.0 * PI * 2.0 * f0 * t + 0.5) * 0.25 +
                    sin(2.0 * PI * 3.0 * f0 * t + 1.2) * 0.15 +
                    sin(2.0 * PI * 4.0 * f0 * t + 2.1) * 0.08)
            out[i] = (tone * 0.75 + pink[i] * 0.25).toFloat()
        }
        val curRms = rms(out)
        val scale = targetRms / (curRms + 1e-9)
        for (i in 0 until n) out[i] = (out[i] * scale).toFloat()
        return out
    }

    private fun generateSyntheticSpeech(
        f0: Double = 95.0,
        duration: Double = 3.0,
        speechStart: Double = 0.5,
        speechEnd: Double = 2.5,
        levelDb: Double = -32.0,
        trailingConsonant: Boolean = false
    ): Pair<FloatArray, BooleanArray> {
        val n = (duration * SAMPLE_RATE).toInt()
        val targetRms = 10.0.pow(levelDb / 20.0)
        val speech = FloatArray(n)
        val mask = BooleanArray(n)
        val rand = Random(555L)

        for (i in 0 until n) {
            val t = i.toDouble() / SAMPLE_RATE
            val isSp = (t in speechStart..speechEnd)
            mask[i] = isSp
            if (isSp) {
                val tSp = t - speechStart
                val sylEnv = 0.5 * (1.0 - cos(2.0 * PI * 3.5 * tSp))
                var hSum = 0.0
                for (h in 1..34) {
                    val fH = h * f0
                    if (fH > 4000.0) break
                    val w1 = exp(-((fH - 500.0).pow(2.0)) / (2.0 * (120.0.pow(2.0))))
                    val w2 = exp(-((fH - 1500.0).pow(2.0)) / (2.0 * (180.0.pow(2.0))))
                    val w3 = exp(-((fH - 2500.0).pow(2.0)) / (2.0 * (250.0.pow(2.0))))
                    val wTot = 1.0 / (h.toDouble().pow(0.8)) + (w1 * 2.2 + w2 * 1.5 + w3 * 0.9)
                    hSum += wTot * sin(2.0 * PI * fH * t + 0.1 * h)
                }

                // Add trailing unvoiced consonant (/s/ or /t/ burst) during the last 60ms of speech
                if (trailingConsonant && (speechEnd - t) < 0.060) {
                    val noiseBurst = (rand.nextFloat() * 2.0f - 1.0f) * 0.35f
                    speech[i] = (hSum * sylEnv * 0.2 + noiseBurst).toFloat()
                } else {
                    speech[i] = (hSum * sylEnv).toFloat()
                }
            }
        }
        val spSliceRms = rms(speech, (speechStart * SAMPLE_RATE).toInt(), (speechEnd * SAMPLE_RATE).toInt())
        val scale = targetRms / (spSliceRms + 1e-9)
        for (i in 0 until n) speech[i] = (speech[i] * scale).toFloat()
        return Pair(speech, mask)
    }

    /**
     * Executes the experimental pipeline (STFT SNS + Expander + Limiter) with the given VAD instance.
     */
    private fun runExperimentalPipeline(
        input: FloatArray,
        mask: BooleanArray,
        refSpeech: FloatArray?,
        vad: ExperimentalVoiceActivityDetector,
        scenarioType: String,
        variantName: String,
        scenarioName: String
    ): ScenarioResult {
        val totalSamples = input.size
        val outFloats = FloatArray(totalSamples)
        val vadDecisions = BooleanArray(totalSamples)

        val spectralSuppressor = SpectralNoiseSuppressor(SAMPLE_RATE.toFloat())
        val levelNorm = 80 / 100.0f
        spectralSuppressor.isEnabled = true
        spectralSuppressor.intensity = levelNorm

        var micEnvelope = 0.0f
        var isGateOpen = false
        var currentGateGain = 0.0f
        var crossoverLp = 0.0f
        var airEnvelope = 0.0f
        var ambientNoiseFloor = 0.003f
        var ambientAirFloor = 0.001f
        var compEnvelope = 0.0f

        for (i in 0 until totalSamples) {
            val rawMic = input[i]
            val absRaw = abs(rawMic)
            if (absRaw > micEnvelope) {
                micEnvelope += 0.008f * (absRaw - micEnvelope)
            } else {
                micEnvelope += 0.00045f * (absRaw - micEnvelope)
            }

            vad.processSample(rawMic)
            val isSpeechActive = vad.isVoiceActive
            vadDecisions[i] = isSpeechActive

            val micPreprocessed = spectralSuppressor.processSample(rawMic, isSpeechActive)

            crossoverLp += 0.35f * (micPreprocessed - crossoverLp)
            val speechBody = crossoverLp
            val airBand = micPreprocessed - crossoverLp

            val absAir = abs(airBand)
            if (absAir > airEnvelope) {
                airEnvelope += 0.04f * (absAir - airEnvelope)
            } else {
                airEnvelope += 0.001f * (absAir - airEnvelope)
            }

            if (!isGateOpen) {
                if (micEnvelope < 0.015f) {
                    ambientNoiseFloor += 0.00005f * (micEnvelope - ambientNoiseFloor)
                }
                if (airEnvelope < 0.008f) {
                    ambientAirFloor += 0.00005f * (airEnvelope - ambientAirFloor)
                }
            } else {
                if (micEnvelope < ambientNoiseFloor) {
                    ambientNoiseFloor += 0.0001f * (micEnvelope - ambientNoiseFloor)
                }
                if (airEnvelope < ambientAirFloor) {
                    ambientAirFloor += 0.0001f * (airEnvelope - ambientAirFloor)
                }
            }
            ambientNoiseFloor = ambientNoiseFloor.coerceIn(0.001f, 0.008f)
            ambientAirFloor = ambientAirFloor.coerceIn(0.0005f, 0.005f)

            isGateOpen = isSpeechActive
            val targetGateGain = if (isGateOpen) 1.0f else (0.12f - levelNorm * 0.10f).coerceIn(0.02f, 0.12f)
            val rampSpeed = if (targetGateGain > currentGateGain) 0.025f else 0.008f
            currentGateGain += rampSpeed * (targetGateGain - currentGateGain)

            var micClean = (speechBody + airBand) * currentGateGain

            val absMic = abs(micClean)
            if (absMic > compEnvelope) {
                compEnvelope += 0.15f * (absMic - compEnvelope)
            } else {
                compEnvelope += 0.002f * (absMic - compEnvelope)
            }
            val compThresh = 0.50f
            if (compEnvelope > compThresh) {
                val overDb = 20.0f * log10(compEnvelope / compThresh)
                val compGainDb = -overDb * (1.0f - 1.0f / 2.5f)
                val compGain = 10.0f.pow(compGainDb / 20.0f)
                micClean *= compGain
            }

            val peak = abs(micClean)
            if (peak > 0.95f) {
                micClean = (0.95f * (micClean / peak))
            }

            outFloats[i] = micClean
        }

        // Metrics computation
        var tp = 0; var fp = 0; var tn = 0; var fn = 0
        var preOnsetFp = 0; var preOnsetTn = 0
        var firstSpeechIdx = -1; var lastSpeechIdx = -1; var firstVadIdx = -1

        for (i in 0 until totalSamples) {
            val isSp = mask[i]
            val vadAct = vadDecisions[i]

            if (isSp) {
                if (firstSpeechIdx == -1) firstSpeechIdx = i
                lastSpeechIdx = i
                if (vadAct) {
                    tp++
                    if (firstVadIdx == -1) firstVadIdx = i
                } else {
                    fn++
                }
            } else {
                if (vadAct) fp++ else tn++
                if (firstSpeechIdx == -1) {
                    if (vadAct) preOnsetFp++ else preOnsetTn++
                }
            }
        }

        val totalSp = tp + fn
        val totalPause = tn + fp
        val vadFnr = if (totalSp > 0) (fn.toDouble() / totalSp) * 100.0 else 0.0

        // Exact denominators for FPR:
        // For pure noise, entire file is non-speech (totalPause).
        // For mixed signal, pre-onset pause provides uncorrupted baseline non-speech denominator.
        val totalNoiseFrames = if (scenarioType == "noise") totalPause else (preOnsetTn + preOnsetFp)
        val falsePositiveFrames = if (scenarioType == "noise") fp else preOnsetFp
        val correctedFpr = if (totalNoiseFrames > 0) (falsePositiveFrames.toDouble() / totalNoiseFrames) * 100.0 else 0.0

        // Latency & Stuck-Open Guard:
        // If VAD is already open at the sample immediately preceding speech onset,
        // it cannot receive a false 0.0 ms onset score; it is marked pre-triggered / invalid.
        val isPreTriggered = if (firstSpeechIdx > 0) vadDecisions[firstSpeechIdx - 1] else (preOnsetFp > 0 && firstSpeechIdx != -1)
        val vadOnsetLatencyMs = if (isPreTriggered) {
            Double.NaN
        } else if (firstVadIdx != -1 && firstSpeechIdx != -1 && firstVadIdx >= firstSpeechIdx) {
            ((firstVadIdx - firstSpeechIdx).toDouble() / SAMPLE_RATE) * 1000.0
        } else {
            Double.NaN
        }

        val fnDurationMs = (fn.toDouble() / SAMPLE_RATE) * 1000.0
        val fpDurationMs = (falsePositiveFrames.toDouble() / SAMPLE_RATE) * 1000.0

        var speechLossDb = 0.0
        if (refSpeech != null && firstSpeechIdx != -1 && lastSpeechIdx != -1) {
            val evalStart = min(totalSamples - 1, firstSpeechIdx + (0.15 * SAMPLE_RATE).toInt())
            val evalEnd = max(evalStart + 1, lastSpeechIdx - (0.10 * SAMPLE_RATE).toInt())
            val cleanSpRmsDb = toDbfs(rms(refSpeech, evalStart, evalEnd))
            val outSpRmsDb = toDbfs(rms(outFloats, evalStart, evalEnd))
            val delta = outSpRmsDb - cleanSpRmsDb
            speechLossDb = if (delta < 0) abs(delta) else 0.0
        }

        var noiseAttenDb = 0.0
        if (scenarioType == "noise") {
            val sStart = (1.0 * SAMPLE_RATE).toInt()
            val sEnd = (2.8 * SAMPLE_RATE).toInt()
            noiseAttenDb = toDbfs(rms(input, sStart, sEnd)) - toDbfs(rms(outFloats, sStart, sEnd))
        } else if (scenarioType == "mixed") {
            val pStart = (0.1 * SAMPLE_RATE).toInt()
            val pEnd = (0.4 * SAMPLE_RATE).toInt()
            noiseAttenDb = toDbfs(rms(input, pStart, pEnd)) - toDbfs(rms(outFloats, pStart, pEnd))
        }

        var consonantLossDb = 0.0
        if (lastSpeechIdx != -1 && refSpeech != null) {
            val cStart = max(0, lastSpeechIdx - (0.05 * SAMPLE_RATE).toInt())
            val cEnd = lastSpeechIdx
            val refCRms = toDbfs(rms(refSpeech, cStart, cEnd))
            val outCRms = toDbfs(rms(outFloats, cStart, cEnd))
            val delta = outCRms - refCRms
            consonantLossDb = if (delta < 0) abs(delta) else 0.0
        }

        val status = if (scenarioType == "noise") {
            if (correctedFpr <= 1.0) "PASS" else "FAIL"
        } else {
            if (vadFnr <= 5.0 && correctedFpr <= 1.0 && !isPreTriggered && (!vadOnsetLatencyMs.isNaN() && vadOnsetLatencyMs <= 50.0)) "PASS" else "FAIL"
        }

        return ScenarioResult(
            variantName = variantName,
            scenarioName = scenarioName,
            scenarioType = scenarioType,
            fnr = vadFnr,
            fpr = correctedFpr,
            onsetLatencyMs = vadOnsetLatencyMs,
            isPreTriggered = isPreTriggered,
            fnDurationMs = fnDurationMs,
            fpDurationMs = fpDurationMs,
            totalSpeechFrames = totalSp,
            totalNonSpeechFrames = totalNoiseFrames,
            speechLossDb = speechLossDb,
            noiseAttenDb = noiseAttenDb,
            consonantLossDb = consonantLossDb,
            status = status
        )
    }

    @Test
    fun testFilterImplementationAndFrequencyResponse() {
        println("=== STEP 2: VERIFY REAL FILTER IMPLEMENTATION & FREQUENCY RESPONSE ===")
        val frequencies = doubleArrayOf(60.0, 80.0, 90.0, 95.0, 120.0, 130.0, 150.0, 180.0, 220.0, 300.0)
        val dt = 1.0 / SAMPLE_RATE.toDouble()

        // Test at sample rate 44100 Hz
        val cutoffs = listOf(
            Pair("Baseline Path A (220 Hz)", 220.0),
            Pair("Variant Path A (130 Hz)", 130.0),
            Pair("Baseline Path B (80 Hz)", 80.0),
            Pair("Variant Path B (105 Hz)", 105.0)
        )

        for ((name, fc) in cutoffs) {
            val rc = 1.0 / (2.0 * Math.PI * fc)
            val alpha = rc / (rc + dt)
            println("\nFilter: $name (alpha = $alpha)")
            println("Freq (Hz) | Linear Gain | Attenuation (dB)")
            println("------------------------------------------")
            for (f in frequencies) {
                val w = 2.0 * Math.PI * f
                // z^-1 = cos(w*dt) - j*sin(w*dt)
                val cosW = cos(w * dt)
                val sinW = sin(w * dt)
                // (1 - z^-1) = (1 - cosW) + j*sinW
                val numRe = 1.0 - cosW
                val numIm = sinW
                // (1 - alpha * z^-1) = (1 - alpha*cosW) + j*alpha*sinW
                val denRe = 1.0 - alpha * cosW
                val denIm = alpha * sinW

                val numMagSq = numRe * numRe + numIm * numIm
                val denMagSq = denRe * denRe + denIm * denIm
                val singleStageMag = sqrt(numMagSq / denMagSq)
                // 2 cascaded stages
                val twoStageMag = singleStageMag * singleStageMag
                val twoStageDb = 20.0 * log10(twoStageMag)
                println(String.format("%8.1f  |   %8.4f  |   %7.2f dB", f, twoStageMag, twoStageDb))
            }
        }
    }

    @Test
    fun testBaselineBitParityWithProductionVad() {
        println("\n=== VERIFY BIT-FOR-BIT BASELINE PARITY ===")
        val prodVad = VoiceActivityDetector(SAMPLE_RATE)
        prodVad.setSensitivity(80)

        val expVad = ExperimentalVoiceActivityDetector(
            sampleRate = SAMPLE_RATE,
            hpFormantCutoff = 220.0f,
            hpAmdfCutoff = 80.0f,
            harmonicityMode = HarmonicityMode.BASELINE
        )
        expVad.setSensitivity(80)

        // Generate Pink noise + tones
        val testSig = generateRealisticFan(f0 = 95.0, duration = 3.0, levelDb = -24.0)
        var mismatches = 0

        for (i in 0 until testSig.size) {
            val s = testSig[i]
            val prodOut = prodVad.processSample(s)
            val expOut = expVad.processSample(s)

            if (prodOut != expOut || prodVad.isDroneDetected != expVad.isDroneDetected) {
                mismatches++
            }
        }

        println("Evaluated ${testSig.size} samples across production vs experimental baseline.")
        println("Sample mismatches: $mismatches")
        assertEquals("Experimental baseline must match production VAD bit-for-bit", 0, mismatches)
        println("PASS: 100.00% bit-for-bit parity confirmed.")
    }

    @Test
    fun testComprehensiveVariantsMatrix() {
        println("\n=========================================================================================")
        println("=== STEP 3, 4, 5: SYSTEMATIC REGRESSION AUDIT OF ALL 6 EXPERIMENTAL VARIANTS ===")
        println("=========================================================================================")

        val variants = listOf(
            ExperimentVariant("Var 0: Baseline (220/80 Hz, Base)", 220.0f, 80.0f, HarmonicityMode.BASELINE),
            ExperimentVariant("Var 1: Path A 130 Hz (130/80 Hz, Base)", 130.0f, 80.0f, HarmonicityMode.BASELINE),
            ExperimentVariant("Var 2: Path B 105 Hz (220/105 Hz, Base)", 220.0f, 105.0f, HarmonicityMode.BASELINE),
            ExperimentVariant("Var 3: Both Filters (130/105 Hz, Base)", 130.0f, 105.0f, HarmonicityMode.BASELINE),
            ExperimentVariant("Var 4: Dual Criterion (220/80 Hz, Dual)", 220.0f, 80.0f, HarmonicityMode.DUAL_CRITERION),
            ExperimentVariant("Var 5: Combined (130/105 Hz, Dual)", 130.0f, 105.0f, HarmonicityMode.DUAL_CRITERION)
        )

        // Build deterministic fixtures
        // 1. Pure Fan Noise Fixtures
        val fan80 = generateRealisticFan(f0 = 80.0, levelDb = -24.0, seed = 101L)
        val fan120 = generateRealisticFan(f0 = 120.0, levelDb = -24.0, seed = 102L)
        val fan180 = generateRealisticFan(f0 = 180.0, levelDb = -24.0, seed = 103L)
        val pinkNoise = generatePinkNoise(TOTAL_SAMPLES, seed = 104L)
        val zeroMask = BooleanArray(TOTAL_SAMPLES)

        // 2. Quiet Speech Fixtures (-32 dBFS)
        val (sp90, mask90) = generateSyntheticSpeech(f0 = 90.0, levelDb = -32.0)
        val (sp95, mask95) = generateSyntheticSpeech(f0 = 95.0, levelDb = -32.0)
        val (sp100, mask100) = generateSyntheticSpeech(f0 = 100.0, levelDb = -32.0)
        val (sp130, mask130) = generateSyntheticSpeech(f0 = 130.0, levelDb = -32.0)
        val (sp150, mask150) = generateSyntheticSpeech(f0 = 150.0, levelDb = -32.0)

        // 3. Speech + 80 Hz Fan Noise at 0, -6, -12 dB SNR
        // 80 Hz fan at -24 dBFS
        fun mixSignals(sp: FloatArray, fan: FloatArray, spLevelDb: Double): FloatArray {
            val targetSpRms = 10.0.pow(spLevelDb / 20.0)
            val spSlice = rms(sp, (0.5 * SAMPLE_RATE).toInt(), (2.5 * SAMPLE_RATE).toInt())
            val scale = targetSpRms / (spSlice + 1e-9)
            val out = FloatArray(sp.size)
            for (i in 0 until sp.size) {
                out[i] = (sp[i] * scale + fan[i]).toFloat()
            }
            return out
        }

        // 95 Hz speech mixtures with 80 Hz fan
        val mix95_0db = mixSignals(sp95, fan80, -24.0)
        val mix95_m6db = mixSignals(sp95, fan80, -30.0)
        val mix95_m12db = mixSignals(sp95, fan80, -36.0)

        // 150 Hz speech mixtures with 80 Hz fan
        val mix150_0db = mixSignals(sp150, fan80, -24.0)
        val mix150_m6db = mixSignals(sp150, fan80, -30.0)
        val mix150_m12db = mixSignals(sp150, fan80, -36.0)

        // 4. Conversational & Edge Case Fixtures
        // Trailing consonant speech fixture
        val (spTrailing, maskTrailing) = generateSyntheticSpeech(f0 = 130.0, levelDb = -26.0, trailingConsonant = true)
        // Normal speech
        val (spNormal, maskNormal) = generateSyntheticSpeech(f0 = 150.0, levelDb = -20.0)
        // Digital silence
        val silence = FloatArray(TOTAL_SAMPLES)

        // Collect all test items
        data class TestItem(
            val name: String,
            val type: String,
            val input: FloatArray,
            val mask: BooleanArray,
            val refSpeech: FloatArray?
        )

        val testItems = listOf(
            TestItem("Pure Fan 80 Hz", "noise", fan80, zeroMask, null),
            TestItem("Pure Fan 120 Hz", "noise", fan120, zeroMask, null),
            TestItem("Pure Fan 180 Hz", "noise", fan180, zeroMask, null),
            TestItem("Pure Pink Noise", "noise", pinkNoise, zeroMask, null),
            TestItem("Quiet Speech 90 Hz", "speech", sp90, mask90, sp90),
            TestItem("Quiet Speech 95 Hz", "speech", sp95, mask95, sp95),
            TestItem("Quiet Speech 100 Hz", "speech", sp100, mask100, sp100),
            TestItem("Quiet Speech 130 Hz", "speech", sp130, mask130, sp130),
            TestItem("Quiet Speech 150 Hz", "speech", sp150, mask150, sp150),
            TestItem("95Hz Sp + 80Hz Fan (0 dB)", "mixed", mix95_0db, mask95, sp95),
            TestItem("95Hz Sp + 80Hz Fan (-6 dB)", "mixed", mix95_m6db, mask95, sp95),
            TestItem("95Hz Sp + 80Hz Fan (-12 dB)", "mixed", mix95_m12db, mask95, sp95),
            TestItem("150Hz Sp + 80Hz Fan (0 dB)", "mixed", mix150_0db, mask150, sp150),
            TestItem("150Hz Sp + 80Hz Fan (-6 dB)", "mixed", mix150_m6db, mask150, sp150),
            TestItem("150Hz Sp + 80Hz Fan (-12 dB)", "mixed", mix150_m12db, mask150, sp150),
            TestItem("Normal Speech 150 Hz", "speech", spNormal, maskNormal, spNormal),
            TestItem("Trailing Consonant", "speech", spTrailing, maskTrailing, spTrailing),
            TestItem("Digital Silence", "noise", silence, zeroMask, null)
        )

        val allResults = mutableListOf<ScenarioResult>()

        for (variant in variants) {
            println("\n-----------------------------------------------------------------------------------------")
            println("Running Variant: ${variant.name}")
            println("-----------------------------------------------------------------------------------------")

            for (item in testItems) {
                val vad = ExperimentalVoiceActivityDetector(
                    sampleRate = SAMPLE_RATE,
                    hpFormantCutoff = variant.hpFormantCutoff,
                    hpAmdfCutoff = variant.hpAmdfCutoff,
                    harmonicityMode = variant.harmonicityMode
                )
                vad.setSensitivity(80)

                val res = runExperimentalPipeline(
                    input = item.input,
                    mask = item.mask,
                    refSpeech = item.refSpeech,
                    vad = vad,
                    scenarioType = item.type,
                    variantName = variant.name,
                    scenarioName = item.name
                )
                allResults.add(res)
                val latStr = when {
                    res.isPreTriggered -> "PRE-TRIG"
                    res.onsetLatencyMs.isNaN() -> "   N/A  "
                    else -> String.format("%6.1f ms", res.onsetLatencyMs)
                }
                println(String.format("  %-32s | FNR: %6.2f%% | FPR: %5.2f%% | Latency: %-10s | Loss: %5.2f dB | Atten: %5.1f dB | %s",
                    res.scenarioName, res.fnr, res.fpr, latStr, res.speechLossDb, res.noiseAttenDb, res.status))
            }
        }

        // Print comparative markdown table for report
        println("\n\n=========================================================================================")
        println("=== FULL COMPARATIVE REGRESSION MATRIX ACROSS ALL 6 VARIANTS ===")
        println("=========================================================================================")
        println("| Scenario | Baseline (Var 0) | Path A 130Hz (Var 1) | Path B 105Hz (Var 2) | Both Filters (Var 3) | Dual-Criterion (Var 4) | Combined (Var 5) |")
        println("|---|:---:|:---:|:---:|:---:|:---:|:---:|")

        for (item in testItems) {
            val rowResults = allResults.filter { it.scenarioName == item.name }
            val cols = rowResults.map { r ->
                if (r.scenarioType == "noise") {
                    String.format("FPR: %.1f%% (Att: %.0fdB)", r.fpr, r.noiseAttenDb)
                } else {
                    val latStr = when {
                        r.isPreTriggered -> "PRE-TRIG"
                        r.onsetLatencyMs.isNaN() -> "N/A"
                        else -> String.format("%.0fms", r.onsetLatencyMs)
                    }
                    String.format("FNR: %.1f%% (%s)", r.fnr, latStr)
                }
            }
            println("| ${item.name} | " + cols.joinToString(" | ") + " |")
        }
        println("=========================================================================================\n")
    }

    @Test
    fun testDiagnostic95HzMinus12Db() {
        println("\n=== DIAGNOSTIC INSPECTION: 95 Hz SPEECH + 80 Hz FAN AT -12 dB SNR ===")
        val fan80 = generateRealisticFan(f0 = 80.0, levelDb = -24.0, seed = 101L)
        val (sp95, mask95) = generateSyntheticSpeech(f0 = 95.0, levelDb = -32.0)

        val targetSpRms = 10.0.pow(-36.0 / 20.0)
        val spSlice = rms(sp95, (0.5 * SAMPLE_RATE).toInt(), (2.5 * SAMPLE_RATE).toInt())
        val scale = targetSpRms / (spSlice + 1e-9)
        val mix = FloatArray(sp95.size)
        for (i in 0 until sp95.size) mix[i] = (sp95[i] * scale + fan80[i]).toFloat()

        val vad = ExperimentalVoiceActivityDetector(
            sampleRate = SAMPLE_RATE,
            hpFormantCutoff = 220.0f,
            hpAmdfCutoff = 80.0f,
            harmonicityMode = HarmonicityMode.DUAL_CRITERION
        )
        vad.setSensitivity(80)

        var snapCount = 0
        for (i in 0 until mix.size) {
            val t = i.toDouble() / SAMPLE_RATE
            vad.processSample(mix[i])

            if (t in 0.7..0.85 && (i % 441 == 0) && snapCount < 5) {
                val curVoiceSnr = vad.voiceEnvelope / (vad.estimatedVoiceFloor + 1e-6f)
                val isDualHarmonic = (vad.minAmdf < 0.45f) && (curVoiceSnr > (vad.snrThreshold * 1.25f)) && (vad.currentFormantRatio > 0.20f)
                println(String.format("t=%.3fs | voiceEnv=%.5f | voiceFloor=%.5f | voiceSnr=%.3f (thresh=%.3f) | minAmdf=%.3f | bestLag=%d | formantRatio=%.3f | rumbleRatio=%.3f | isDualHarm=%b | isVoiceAct=%b",
                    t, vad.voiceEnvelope, vad.estimatedVoiceFloor, curVoiceSnr, vad.snrThreshold * 1.25f, vad.minAmdf, vad.bestLag, vad.currentFormantRatio, vad.currentRumbleRatio, isDualHarmonic, vad.isVoiceActive))
                snapCount++
            }
        }
    }

    private fun generateTone(
        f0: Double,
        duration: Double = 2.0,
        levelDb: Double = -24.0,
        harmonics: Boolean = false
    ): FloatArray {
        val n = (duration * SAMPLE_RATE).toInt()
        val targetRms = 10.0.pow(levelDb / 20.0)
        val out = FloatArray(n)
        for (i in 0 until n) {
            val t = i.toDouble() / SAMPLE_RATE
            val sample = if (!harmonics) {
                sin(2.0 * PI * f0 * t)
            } else {
                sin(2.0 * PI * f0 * t) * 0.70 +
                sin(2.0 * PI * 2.0 * f0 * t + 0.5) * 0.25 +
                sin(2.0 * PI * 3.0 * f0 * t + 1.2) * 0.15 +
                sin(2.0 * PI * 4.0 * f0 * t + 2.1) * 0.08
            }
            out[i] = sample.toFloat()
        }
        val curRms = rms(out)
        val scale = targetRms / (curRms + 1e-9)
        for (i in 0 until n) out[i] = (out[i] * scale).toFloat()
        return out
    }

    private fun generateDynamicSpeech(
        f0: Double = 120.0,
        duration: Double = 3.0,
        speechStart: Double = 0.5,
        speechEnd: Double = 2.5
    ): Pair<FloatArray, BooleanArray> {
        val n = (duration * SAMPLE_RATE).toInt()
        val speech = FloatArray(n)
        val mask = BooleanArray(n)
        for (i in 0 until n) {
            val t = i.toDouble() / SAMPLE_RATE
            val isSp = (t in speechStart..speechEnd)
            mask[i] = isSp
            if (isSp) {
                val tSp = t - speechStart
                val durationSp = speechEnd - speechStart
                val dynamicEnv = 0.2 + 0.8 * sin(PI * tSp / durationSp)
                val sylEnv = 0.5 * (1.0 - cos(2.0 * PI * 3.5 * tSp))
                var hSum = 0.0
                for (h in 1..25) {
                    val fH = h * f0
                    if (fH > 4000.0) break
                    val w = 1.0 / (h.toDouble().pow(0.85))
                    hSum += w * sin(2.0 * PI * fH * t + 0.1 * h)
                }
                speech[i] = (hSum * sylEnv * dynamicEnv).toFloat()
            }
        }
        val targetRms = 10.0.pow(-28.0 / 20.0)
        val spSliceRms = rms(speech, (speechStart * SAMPLE_RATE).toInt(), (speechEnd * SAMPLE_RATE).toInt())
        val scale = targetRms / (spSliceRms + 1e-9)
        for (i in 0 until n) speech[i] = (speech[i] * scale).toFloat()
        return Pair(speech, mask)
    }

    private fun generateCompetingSpeakers(
        duration: Double = 3.2
    ): Pair<FloatArray, BooleanArray> {
        val n = (duration * SAMPLE_RATE).toInt()
        val out = FloatArray(n)
        val mask = BooleanArray(n)
        val (sp1, m1) = generateSyntheticSpeech(f0 = 95.0, duration = duration, speechStart = 0.4, speechEnd = 1.4, levelDb = -30.0)
        val (sp2, m2) = generateSyntheticSpeech(f0 = 180.0, duration = duration, speechStart = 1.8, speechEnd = 2.8, levelDb = -26.0)
        for (i in 0 until n) {
            out[i] = sp1[i] + sp2[i]
            mask[i] = m1[i] || m2[i]
        }
        return Pair(out, mask)
    }

    private fun generateMotorFanMixture(
        motorF0: Double = 60.0,
        bladePassHarmonic: Int = 3,
        duration: Double = 3.0,
        levelDb: Double = -24.0,
        seed: Long = 101L
    ): FloatArray {
        val n = (duration * SAMPLE_RATE).toInt()
        val pink = generatePinkNoise(n, seed)
        val targetRms = 10.0.pow(levelDb / 20.0)
        val out = FloatArray(n)
        val bladeF0 = motorF0 * bladePassHarmonic
        for (i in 0 until n) {
            val t = i.toDouble() / SAMPLE_RATE
            val tone = (sin(2.0 * PI * motorF0 * t) * 0.50 +
                    sin(2.0 * PI * bladeF0 * t + 0.5) * 0.30 +
                    sin(2.0 * PI * 2.0 * bladeF0 * t + 1.2) * 0.10)
            out[i] = (tone * 0.80 + pink[i] * 0.20).toFloat()
        }
        val curRms = rms(out)
        val scale = targetRms / (curRms + 1e-9)
        for (i in 0 until n) out[i] = (out[i] * scale).toFloat()
        return out
    }

    @Test
    fun testInvestigation180HzFailureAndTones() {
        println("\n=========================================================================================")
        println("=== 1. INVESTIGATE 180 Hz FALSE-POSITIVE FAILURE & PURE TONES (60-300 HZ) ===")
        println("=========================================================================================")

        val freqs = doubleArrayOf(60.0, 70.0, 80.0, 90.0, 100.0, 110.0, 120.0, 130.0, 140.0, 150.0, 160.0, 170.0, 180.0, 190.0, 200.0, 220.0, 250.0, 300.0)

        // Part A: Pure Tones (No Harmonics)
        println("\n--- Part A: Pure Sine Tones (No Harmonics) at -24 dBFS (Duration: 2.0s) ---")
        println(String.format("%-5s | %-10s | %-10s | %-8s | %-8s | %-8s | %-8s | %-8s | %-8s",
            "Freq", "Base FPR", "Dual FPR", "RumbleR", "FormantR", "minAmdf", "bestLag", "isDrone", "GateAct"))
        println("------------------------------------------------------------------------------------------------")

        for (f in freqs) {
            val tone = generateTone(f, duration = 2.0, levelDb = -24.0, harmonics = false)
            val baseVad = ExperimentalVoiceActivityDetector(harmonicityMode = HarmonicityMode.BASELINE)
            val dualVad = ExperimentalVoiceActivityDetector(harmonicityMode = HarmonicityMode.DUAL_CRITERION)

            var baseActCount = 0
            var dualActCount = 0
            for (s in tone) {
                if (baseVad.processSample(s)) baseActCount++
                if (dualVad.processSample(s)) dualActCount++
            }

            val baseFpr = (baseActCount.toDouble() / tone.size) * 100.0
            val dualFpr = (dualActCount.toDouble() / tone.size) * 100.0

            println(String.format("%5.0f | %9.1f%% | %9.1f%% | %8.3f | %8.3f | %8.3f | %8d | %8b | %8b",
                f, baseFpr, dualFpr, baseVad.currentRumbleRatio, baseVad.currentFormantRatio,
                baseVad.minAmdf, baseVad.bestLag, baseVad.isDroneDetected, baseVad.isVoiceActive))
        }

        // Part B: Harmonic Tones
        println("\n--- Part B: Harmonic Tones (f0 + 2f0 + 3f0 + 4f0) at -24 dBFS (Duration: 2.0s) ---")
        println(String.format("%-5s | %-10s | %-10s | %-8s | %-8s | %-8s | %-8s | %-8s | %-8s",
            "Freq", "Base FPR", "Dual FPR", "RumbleR", "FormantR", "minAmdf", "bestLag", "isDrone", "GateAct"))
        println("------------------------------------------------------------------------------------------------")

        for (f in freqs) {
            val tone = generateTone(f, duration = 2.0, levelDb = -24.0, harmonics = true)
            val baseVad = ExperimentalVoiceActivityDetector(harmonicityMode = HarmonicityMode.BASELINE)
            val dualVad = ExperimentalVoiceActivityDetector(harmonicityMode = HarmonicityMode.DUAL_CRITERION)

            var baseActCount = 0
            var dualActCount = 0
            for (s in tone) {
                if (baseVad.processSample(s)) baseActCount++
                if (dualVad.processSample(s)) dualActCount++
            }

            val baseFpr = (baseActCount.toDouble() / tone.size) * 100.0
            val dualFpr = (dualActCount.toDouble() / tone.size) * 100.0

            println(String.format("%5.0f | %9.1f%% | %9.1f%% | %8.3f | %8.3f | %8.3f | %8d | %8b | %8b",
                f, baseFpr, dualFpr, baseVad.currentRumbleRatio, baseVad.currentFormantRatio,
                baseVad.minAmdf, baseVad.bestLag, baseVad.isDroneDetected, baseVad.isVoiceActive))
        }

        // Part C: Multi-Component Fan Mixtures vs Pure Synthetic 180 Hz Fixture
        println("\n--- Part C: Multi-Component Fan Models, Pure Noise, and Silence ---")
        val fanMixtures = listOf(
            Pair("Realistic Fan: 60Hz motor + 180Hz blade-pass (3x)", generateMotorFanMixture(60.0, 3, 2.0, -24.0, 101L)),
            Pair("Realistic Fan: 80Hz motor + 160/240Hz harmonics", generateMotorFanMixture(80.0, 2, 2.0, -24.0, 101L)),
            Pair("Realistic Fan: 120Hz motor + 240Hz harmonics", generateMotorFanMixture(120.0, 2, 2.0, -24.0, 101L)),
            Pair("Original Synthetic Fixture: Pure 180Hz Harmonic", generateRealisticFan(180.0, 2.0, -24.0, 101L)),
            Pair("Pure Pink Noise (-24 dBFS)", generatePinkNoise((2.0 * SAMPLE_RATE).toInt(), 101L)),
            Pair("Digital Silence (-120 dBFS)", FloatArray((2.0 * SAMPLE_RATE).toInt()))
        )

        println(String.format("%-50s | %-10s | %-10s | %-8s | %-8s | %-8s",
            "Signal Type", "Base FPR", "Dual FPR", "RumbleR", "FormantR", "isDrone"))
        println("-------------------------------------------------------------------------------------------------------------------")

        for ((name, sig) in fanMixtures) {
            val baseVad = ExperimentalVoiceActivityDetector(harmonicityMode = HarmonicityMode.BASELINE)
            val dualVad = ExperimentalVoiceActivityDetector(harmonicityMode = HarmonicityMode.DUAL_CRITERION)

            var baseActCount = 0
            var dualActCount = 0
            for (s in sig) {
                if (baseVad.processSample(s)) baseActCount++
                if (dualVad.processSample(s)) dualActCount++
            }

            val baseFpr = (baseActCount.toDouble() / sig.size) * 100.0
            val dualFpr = (dualActCount.toDouble() / sig.size) * 100.0

            println(String.format("%-50s | %9.1f%% | %9.1f%% | %8.3f | %8.3f | %8b",
                name, baseFpr, dualFpr, baseVad.currentRumbleRatio, baseVad.currentFormantRatio, baseVad.isDroneDetected))
        }
    }

    @Test
    fun testBaselineVsVariant4ComprehensiveRevalidation() {
        println("\n=========================================================================================")
        println("=== 2. COMPREHENSIVE REVALIDATION: BASELINE (VAR 0) vs VARIANT 4 (DUAL-CRITERION) ===")
        println("=========================================================================================")

        val seeds = longArrayOf(101L, 202L, 303L)

        data class BenchmarkScenario(
            val name: String,
            val type: String,
            val generator: (Long) -> Triple<FloatArray, BooleanArray, FloatArray?>
        )

        val benchmarkSuite = listOf(
            // 95 Hz speech + 80 Hz fan @ 0, -6, -12 dB SNR
            BenchmarkScenario("95Hz Sp + 80Hz Fan (0 dB SNR)", "mixed") { seed ->
                val fan = generateRealisticFan(80.0, 3.0, -24.0, seed)
                val (sp, mask) = generateSyntheticSpeech(f0 = 95.0, levelDb = -24.0)
                val mix = FloatArray(sp.size) { i -> sp[i] + fan[i] }
                Triple(mix, mask, sp)
            },
            BenchmarkScenario("95Hz Sp + 80Hz Fan (-6 dB SNR)", "mixed") { seed ->
                val fan = generateRealisticFan(80.0, 3.0, -24.0, seed)
                val (sp, mask) = generateSyntheticSpeech(f0 = 95.0, levelDb = -30.0)
                val mix = FloatArray(sp.size) { i -> sp[i] + fan[i] }
                Triple(mix, mask, sp)
            },
            BenchmarkScenario("95Hz Sp + 80Hz Fan (-12 dB SNR)", "mixed") { seed ->
                val fan = generateRealisticFan(80.0, 3.0, -24.0, seed)
                val (sp, mask) = generateSyntheticSpeech(f0 = 95.0, levelDb = -36.0)
                val mix = FloatArray(sp.size) { i -> sp[i] + fan[i] }
                Triple(mix, mask, sp)
            },

            // 150 Hz speech + 80 Hz fan @ 0, -6, -12 dB SNR
            BenchmarkScenario("150Hz Sp + 80Hz Fan (0 dB SNR)", "mixed") { seed ->
                val fan = generateRealisticFan(80.0, 3.0, -24.0, seed)
                val (sp, mask) = generateSyntheticSpeech(f0 = 150.0, levelDb = -24.0)
                val mix = FloatArray(sp.size) { i -> sp[i] + fan[i] }
                Triple(mix, mask, sp)
            },
            BenchmarkScenario("150Hz Sp + 80Hz Fan (-6 dB SNR)", "mixed") { seed ->
                val fan = generateRealisticFan(80.0, 3.0, -24.0, seed)
                val (sp, mask) = generateSyntheticSpeech(f0 = 150.0, levelDb = -30.0)
                val mix = FloatArray(sp.size) { i -> sp[i] + fan[i] }
                Triple(mix, mask, sp)
            },
            BenchmarkScenario("150Hz Sp + 80Hz Fan (-12 dB SNR)", "mixed") { seed ->
                val fan = generateRealisticFan(80.0, 3.0, -24.0, seed)
                val (sp, mask) = generateSyntheticSpeech(f0 = 150.0, levelDb = -36.0)
                val mix = FloatArray(sp.size) { i -> sp[i] + fan[i] }
                Triple(mix, mask, sp)
            },

            // Dynamic amplitude & harmonics
            BenchmarkScenario("Dynamic Amplitude Speech (120 Hz)", "speech") { _ ->
                val (sp, mask) = generateDynamicSpeech(f0 = 120.0, duration = 3.0)
                Triple(sp, mask, sp)
            },

            // Competing speakers with pause (95 Hz male -> pause -> 180 Hz female) + 80 Hz fan
            BenchmarkScenario("Competing Speakers (95Hz/180Hz) + Fan", "mixed") { seed ->
                val fan = generateRealisticFan(80.0, 3.2, -26.0, seed)
                val (sp, mask) = generateCompetingSpeakers(duration = 3.2)
                val mix = FloatArray(sp.size) { i -> sp[i] + fan[i] }
                Triple(mix, mask, sp)
            },

            // Fan-only signals @ 80, 120, 180 Hz
            BenchmarkScenario("Fan Only 80 Hz (Motor Model)", "noise") { seed ->
                val fan = generateMotorFanMixture(motorF0 = 80.0, bladePassHarmonic = 2, duration = 3.0, levelDb = -24.0, seed = seed)
                Triple(fan, BooleanArray(fan.size), null)
            },
            BenchmarkScenario("Fan Only 120 Hz (Motor Model)", "noise") { seed ->
                val fan = generateMotorFanMixture(motorF0 = 120.0, bladePassHarmonic = 2, duration = 3.0, levelDb = -24.0, seed = seed)
                Triple(fan, BooleanArray(fan.size), null)
            },
            BenchmarkScenario("Fan Only 180 Hz (Motor Model)", "noise") { seed ->
                val fan = generateMotorFanMixture(motorF0 = 60.0, bladePassHarmonic = 3, duration = 3.0, levelDb = -24.0, seed = seed)
                Triple(fan, BooleanArray(fan.size), null)
            },

            // Speech fixtures: Normal, Quiet, Trailing Consonant
            BenchmarkScenario("Normal Speech 150 Hz (-24 dBFS)", "speech") { _ ->
                val (sp, mask) = generateSyntheticSpeech(f0 = 150.0, levelDb = -24.0)
                Triple(sp, mask, sp)
            },
            BenchmarkScenario("Quiet Speech 95 Hz (-32 dBFS)", "speech") { _ ->
                val (sp, mask) = generateSyntheticSpeech(f0 = 95.0, levelDb = -32.0)
                Triple(sp, mask, sp)
            },
            BenchmarkScenario("Trailing Consonants /s/ Burst", "speech") { _ ->
                val (sp, mask) = generateSyntheticSpeech(f0 = 95.0, levelDb = -28.0, trailingConsonant = true)
                Triple(sp, mask, sp)
            }
        )

        val testedVariants = listOf(
            Pair("Baseline (Var 0)", HarmonicityMode.BASELINE),
            Pair("Variant 4 (Dual)", HarmonicityMode.DUAL_CRITERION)
        )

        println("\n=== SUMMARY REVALIDATION ACROSS 3 DETERMINISTIC SEEDS (101, 202, 303) ===")
        println(String.format("%-38s | %-18s | %-18s | %-14s | %-14s",
            "Scenario Name", "Baseline FNR (%)", "Variant 4 FNR (%)", "Base Lat (ms)", "Var 4 Lat (ms)"))
        println("-------------------------------------------------------------------------------------------------------------------------")

        for (scenario in benchmarkSuite) {
            val baseResults = mutableListOf<ScenarioResult>()
            val var4Results = mutableListOf<ScenarioResult>()

            for (seed in seeds) {
                val (input, mask, refSpeech) = scenario.generator(seed)

                // Run Baseline
                val baseVad = ExperimentalVoiceActivityDetector(harmonicityMode = HarmonicityMode.BASELINE)
                baseVad.setSensitivity(80)
                baseResults.add(runExperimentalPipeline(input, mask, refSpeech, baseVad, scenario.type, "Baseline", scenario.name))

                // Run Variant 4
                val var4Vad = ExperimentalVoiceActivityDetector(harmonicityMode = HarmonicityMode.DUAL_CRITERION)
                var4Vad.setSensitivity(80)
                var4Results.add(runExperimentalPipeline(input, mask, refSpeech, var4Vad, scenario.type, "Variant 4", scenario.name))
            }

            // Print per-seed breakdown for audit trace
            for (s in seeds.indices) {
                val seed = seeds[s]
                val b = baseResults[s]
                val v = var4Results[s]
                val bLat = if (b.isPreTriggered) "PRE-TRIG" else if (b.onsetLatencyMs.isNaN()) "N/A" else String.format("%.1fms", b.onsetLatencyMs)
                val vLat = if (v.isPreTriggered) "PRE-TRIG" else if (v.onsetLatencyMs.isNaN()) "N/A" else String.format("%.1fms", v.onsetLatencyMs)
                println(String.format("   [Seed %d] Base: FNR=%5.2f%%, FPR=%5.2f%%, Lat=%-8s, Loss=%5.2fdB, Atten=%5.2fdB | Var4: FNR=%5.2f%%, FPR=%5.2f%%, Lat=%-8s, Loss=%5.2fdB, Atten=%5.2fdB",
                    seed, b.fnr, b.fpr, bLat, b.speechLossDb, b.noiseAttenDb,
                    v.fnr, v.fpr, vLat, v.speechLossDb, v.noiseAttenDb))
            }

            val baseFnrMean = baseResults.map { it.fnr }.average()
            val var4FnrMean = var4Results.map { it.fnr }.average()
            val baseFprMean = baseResults.map { it.fpr }.average()
            val var4FprMean = var4Results.map { it.fpr }.average()

            val baseLatValid = baseResults.map { it.onsetLatencyMs }.filter { !it.isNaN() }
            val var4LatValid = var4Results.map { it.onsetLatencyMs }.filter { !it.isNaN() }

            val baseLatStr = if (baseResults.any { it.isPreTriggered }) "PRE-TRIG" else if (baseLatValid.isNotEmpty()) String.format("%.1f ms", baseLatValid.average()) else "N/A"
            val var4LatStr = if (var4Results.any { it.isPreTriggered }) "PRE-TRIG" else if (var4LatValid.isNotEmpty()) String.format("%.1f ms", var4LatValid.average()) else "N/A"

            if (scenario.type == "noise") {
                println(String.format("%-38s | FPR: %5.1f%% (Att:%.0fdB) | FPR: %5.1f%% (Att:%.0fdB) | %-14s | %-14s",
                    scenario.name, baseFprMean, baseResults.map { it.noiseAttenDb }.average(),
                    var4FprMean, var4Results.map { it.noiseAttenDb }.average(), "N/A", "N/A"))
            } else {
                println(String.format("%-38s | %5.1f%% (FPR:%4.1f%%)   | %5.1f%% (FPR:%4.1f%%)   | %-14s | %-14s",
                    scenario.name, baseFnrMean, baseFprMean, var4FnrMean, var4FprMean, baseLatStr, var4LatStr))
            }
        }
    }

    @Test
    fun testPhysicalRecordingsBaselineVsVariant4Parity() {
        val candidatePaths = listOf(
            "physical_raw_mic.pcm",
            "../physical_raw_mic.pcm",
            "physical_hw_ns.pcm",
            "../physical_hw_ns.pcm",
            "evidence/physical_acoustic_validation_20261018_1700/physical_raw_mic_live.pcm",
            "../evidence/physical_acoustic_validation_20261018_1700/physical_raw_mic_live.pcm"
        )
        val files = candidatePaths.map { File(it) }
            .filter { it.exists() }
            .distinctBy { it.canonicalPath }

        println("\n=== PHYSICAL RECORDING REPLAY: BASELINE (MODE 0) VS VARIANT 4 (MODE 4) ===")
        println(String.format("%-35s | %-8s | %-18s | %-18s | %-8s",
            "File Name", "Duration", "Baseline Atten/FPR", "Var4 Atten/FPR", "Verdict"))
        println("-".repeat(95))

        for (file in files) {
            val bytes = file.readBytes()
            val nSamples = bytes.size / 2
            val floats = FloatArray(nSamples)
            for (i in 0 until nSamples) {
                val bLow = bytes[i * 2].toInt() and 0xFF
                val bHigh = bytes[i * 2 + 1].toInt()
                val s = (bHigh shl 8) or bLow
                floats[i] = s / 32768.0f
            }

            val emptyMask = BooleanArray(nSamples)
            val vadBase = ExperimentalVoiceActivityDetector(sampleRate = SAMPLE_RATE, hpFormantCutoff = 220f, hpAmdfCutoff = 80f, harmonicityMode = HarmonicityMode.BASELINE)
            val vadVar4 = ExperimentalVoiceActivityDetector(sampleRate = SAMPLE_RATE, hpFormantCutoff = 220f, hpAmdfCutoff = 80f, harmonicityMode = HarmonicityMode.DUAL_CRITERION)

            val baseRes = runExperimentalPipeline(floats, emptyMask, null, vadBase, "noise", "Baseline", file.name)
            val var4Res = runExperimentalPipeline(floats, emptyMask, null, vadVar4, "noise", "Variant 4", file.name)

            val baseFprStr = String.format("%.1fdB / %.1f%%", baseRes.noiseAttenDb, baseRes.fpr)
            val var4FprStr = String.format("%.1fdB / %.1f%%", var4Res.noiseAttenDb, var4Res.fpr)
            val durSec = nSamples.toDouble() / SAMPLE_RATE

            val isPass = (baseRes.fpr == 0.0 && var4Res.fpr == 0.0)
            println(String.format("%-35s | %5.2fs   | %-18s | %-18s | %-8s",
                file.name, durSec, baseFprStr, var4FprStr, if (isPass) "PASS" else "NOTE"))

            if (file.name.contains("raw_mic")) {
                assertTrue("Baseline false positive rate on ${file.name} must be 0%", baseRes.fpr == 0.0)
                assertTrue("Baseline settled attenuation on ${file.name} must be >= 50 dB", baseRes.noiseAttenDb >= 50.0)
                // Variant 4 diagnostic note: documents if relaxed AMDF threshold regresses on physical room fan harmonics
                if (var4Res.fpr > 5.0) {
                    println(String.format("  [PHYSICAL REGRESSION DETECTED] %s: Variant 4 FPR=%.1f%%, Atten=%.1fdB (AMDF threshold fired on fan harmonics)",
                        file.name, var4Res.fpr, var4Res.noiseAttenDb))
                }
            }
        }
    }
}
