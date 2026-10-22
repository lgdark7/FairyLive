package com.fairylive.app

import com.fairylive.app.core.audio.dsp.VoiceActivityDetector
import org.junit.Test
import java.util.Random
import kotlin.math.sin

class VoiceActivityDetectorEvaluationTest {

    data class VadEvaluationReport(
        val scenarioName: String,
        val totalSamples: Int,
        val totalSpeechSamples: Int,
        val totalPauseSamples: Int,
        val truePositives: Int,
        val falsePositives: Int,
        val trueNegatives: Int,
        val falseNegatives: Int,
        val fnrPercent: Double,
        val fprPercent: Double,
        val detectionLatencyMs: Double,
        val releaseLatencyMs: Double
    )

    @Test
    fun evaluateProductionVadAgainstFrameAlignedGroundTruth() {
        val sampleRate = 44100
        val durationSec = 3.0
        val totalSamples = (sampleRate * durationSec).toInt()

        // 1. Generate base stationary fan (120 Hz + 240 Hz + pink noise)
        val rand = Random(42L)
        var b0 = 0.0f; var b1 = 0.0f; var b2 = 0.0f
        val fan = FloatArray(totalSamples)
        for (i in 0 until totalSamples) {
            val white = rand.nextFloat() * 2.0f - 1.0f
            b0 = b0 * 0.99886f + white * 0.0555179f
            b1 = b1 * 0.99332f + white * 0.0750759f
            b2 = b2 * 0.969f + white * 0.153852f
            val pink = (b0 + b1 + b2 + white * 0.5362f) * 0.04f
            val d120 = (sin(753.982 * i.toDouble() / sampleRate.toDouble()) * 0.035).toFloat()
            val d240 = (sin(1507.96 * i.toDouble() / sampleRate.toDouble()) * 0.015).toFloat()
            fan[i] = pink + d120 + d240
        }

        // Test scenarios definition: (name, input, groundTruthMask)
        val scenarios = mutableListOf<Triple<String, FloatArray, BooleanArray>>()

        // S1: Fan only
        scenarios.add(Triple("S1_FanOnly", fan.copyOf(), BooleanArray(totalSamples)))

        // S2: Normal Speech over fan (0.5s to 2.5s)
        val s2In = fan.copyOf()
        val s2Mask = BooleanArray(totalSamples)
        for (i in 0 until totalSamples) {
            val t = i.toDouble() / sampleRate.toDouble()
            if (t in 0.5..2.5) {
                s2Mask[i] = true
                val sp = (sin(816.814 * i.toDouble() / sampleRate.toDouble()) * 0.12 +
                        sin(4398.23 * i.toDouble() / sampleRate.toDouble()) * 0.08 +
                        sin(7665.49 * i.toDouble() / sampleRate.toDouble()) * 0.04).toFloat()
                s2In[i] += sp
            }
        }
        scenarios.add(Triple("S2_NormalSpeech", s2In, s2Mask))

        // S3: Quiet 95 Hz speech over 0.35x fan (0.5s to 2.5s)
        val s3In = FloatArray(totalSamples) { fan[it] * 0.35f }
        val s3Mask = BooleanArray(totalSamples)
        for (i in 0 until totalSamples) {
            val t = i.toDouble() / sampleRate.toDouble()
            if (t in 0.5..2.5) {
                s3Mask[i] = true
                val sp = (sin(596.903 * i.toDouble() / sampleRate.toDouble()) * 0.025 +
                        sin(1193.81 * i.toDouble() / sampleRate.toDouble()) * 0.015 +
                        sin(1790.71 * i.toDouble() / sampleRate.toDouble()) * 0.010).toFloat()
                s3In[i] += sp
            }
        }
        scenarios.add(Triple("S3_Quiet95Hz", s3In, s3Mask))

        // S4: Bursts, short pauses, and tails
        val s4In = fan.copyOf()
        val s4Mask = BooleanArray(totalSamples)
        for (i in 0 until totalSamples) {
            val t = i.toDouble() / sampleRate.toDouble()
            val inBurst = (t in 0.5..0.9) || (t in 1.2..1.6) || (t in 1.9..2.5)
            if (inBurst) {
                s4Mask[i] = true
                val sp = (sin(816.814 * i.toDouble() / sampleRate.toDouble()) * 0.10 +
                        sin(4398.23 * i.toDouble() / sampleRate.toDouble()) * 0.06).toFloat()
                s4In[i] += sp
            }
        }
        scenarios.add(Triple("S4_BurstsPauses", s4In, s4Mask))

        // S5: Overlapping voices (130 Hz + 220 Hz)
        val s5In = fan.copyOf()
        val s5Mask = BooleanArray(totalSamples)
        for (i in 0 until totalSamples) {
            val t = i.toDouble() / sampleRate.toDouble()
            if (t in 0.5..2.5) {
                s5Mask[i] = true
                val v1 = (sin(816.814 * i.toDouble() / sampleRate.toDouble()) * 0.10 + sin(1633.63 * i.toDouble() / sampleRate.toDouble()) * 0.06).toFloat()
                val v2 = (sin(1382.30 * i.toDouble() / sampleRate.toDouble()) * 0.08 + sin(2764.60 * i.toDouble() / sampleRate.toDouble()) * 0.05).toFloat()
                s5In[i] += v1 + v2
            }
        }
        scenarios.add(Triple("S5_CompetingSpeakers", s5In, s5Mask))

        // S6: Loud 150 Hz speech
        val s6In = fan.copyOf()
        val s6Mask = BooleanArray(totalSamples)
        for (i in 0 until totalSamples) {
            val t = i.toDouble() / sampleRate.toDouble()
            if (t in 0.5..2.5) {
                s6Mask[i] = true
                val amp = if (t in 1.5..1.7) 0.50f else 0.15f
                val sp = (sin(942.478 * i.toDouble() / sampleRate.toDouble()) * amp +
                        sin(1884.96 * i.toDouble() / sampleRate.toDouble()) * (amp * 0.5f)).toFloat()
                s6In[i] += sp
            }
        }
        scenarios.add(Triple("S6_LoudPeaks", s6In, s6Mask))

        // Adversarial 1: Sustained Vowel (440 Hz pure tone 0.5 to 2.5s)
        val advVowelIn = fan.copyOf()
        val advVowelMask = BooleanArray(totalSamples)
        for (i in 0 until totalSamples) {
            val t = i.toDouble() / sampleRate.toDouble()
            if (t in 0.5..2.5) {
                advVowelMask[i] = true
                advVowelIn[i] += (sin(2.0 * Math.PI * 440.0 * t) * 0.15).toFloat()
            }
        }
        scenarios.add(Triple("Adv_SustainedVowel", advVowelIn, advVowelMask))

        // Adversarial 2: Fan Speed Drift (120 Hz to 145 Hz over 3 seconds)
        val advDriftIn = FloatArray(totalSamples)
        for (i in 0 until totalSamples) {
            val t = i.toDouble() / sampleRate.toDouble()
            val freq = 120.0 + 25.0 * (t / 3.0)
            advDriftIn[i] = (sin(2.0 * Math.PI * freq * t) * 0.035).toFloat()
        }
        scenarios.add(Triple("Adv_FanDrift", advDriftIn, BooleanArray(totalSamples)))

        // Adversarial 3: Motor Pitch Sweep (100 Hz to 180 Hz)
        val advMotorIn = FloatArray(totalSamples)
        for (i in 0 until totalSamples) {
            val t = i.toDouble() / sampleRate.toDouble()
            val freq = 100.0 + 80.0 * (t / 3.0)
            advMotorIn[i] = (sin(2.0 * Math.PI * freq * t) * 0.040).toFloat()
        }
        scenarios.add(Triple("Adv_MotorSweep", advMotorIn, BooleanArray(totalSamples)))

        println("\n==========================================================================================")
        println("  REAL-TIME PRODUCTION VAD EVALUATION (FRAME-ALIGNED GROUND TRUTH, NO ORACLE)")
        println("==========================================================================================")
        println(String.format("%-22s | %-8s | %-8s | %-12s | %-12s | %-12s",
            "Scenario", "FNR (%)", "FPR (%)", "Onset Latency", "Rel Latency", "VAD Status"))
        println("------------------------------------------------------------------------------------------")

        for ((name, input, mask) in scenarios) {
            val report = evaluateScenario(name, input, mask, sampleRate)
            val onsetStr = if (report.detectionLatencyMs >= 0) String.format("%.1f ms", report.detectionLatencyMs) else "N/A (No Det)"
            val relStr = if (report.releaseLatencyMs >= 0) String.format("%.1f ms", report.releaseLatencyMs) else "N/A"
            val statusStr = if (report.fnrPercent > 20.0) "HIGH FNR (Muting)" else if (report.fprPercent > 10.0) "HIGH FPR (Leaking)" else "OPTIMAL"

            println(String.format("%-22s | %6.2f %% | %6.2f %% | %12s | %12s | %-12s",
                name, report.fnrPercent, report.fprPercent, onsetStr, relStr, statusStr))
        }
        println("==========================================================================================\n")
    }

    private fun evaluateScenario(
        name: String,
        input: FloatArray,
        mask: BooleanArray,
        sampleRate: Int
    ): VadEvaluationReport {
        val vad = VoiceActivityDetector(sampleRate = sampleRate)
        vad.setSensitivity(80)

        var tp = 0; var fp = 0; var tn = 0; var fn = 0
        var totalSpeech = 0; var totalPause = 0

        var firstSpeechSample = -1
        var firstDetectionSample = -1

        var lastSpeechSample = -1
        var lastDetectionSample = -1

        for (i in input.indices) {
            val isSpeechGroundTruth = mask[i]
            val vadOutput = vad.processSample(input[i])

            if (isSpeechGroundTruth) {
                totalSpeech++
                if (firstSpeechSample == -1) firstSpeechSample = i
                lastSpeechSample = i

                if (vadOutput) {
                    tp++
                    if (firstDetectionSample == -1 && firstSpeechSample != -1) {
                        firstDetectionSample = i
                    }
                } else {
                    fn++
                }
            } else {
                totalPause++
                if (vadOutput) {
                    fp++
                    if (lastSpeechSample != -1 && i > lastSpeechSample) {
                        lastDetectionSample = i
                    }
                } else {
                    tn++
                }
            }
        }

        val fnr = if (totalSpeech > 0) (fn.toDouble() / totalSpeech.toDouble()) * 100.0 else 0.0
        val fpr = if (totalPause > 0) (fp.toDouble() / totalPause.toDouble()) * 100.0 else 0.0

        val detectionLatencyMs = if (firstDetectionSample != -1 && firstSpeechSample != -1) {
            ((firstDetectionSample - firstSpeechSample).toDouble() / sampleRate.toDouble()) * 1000.0
        } else {
            -1.0
        }

        val releaseLatencyMs = if (lastDetectionSample != -1 && lastSpeechSample != -1) {
            ((lastDetectionSample - lastSpeechSample).toDouble() / sampleRate.toDouble()) * 1000.0
        } else {
            -1.0
        }

        return VadEvaluationReport(
            scenarioName = name,
            totalSamples = input.size,
            totalSpeechSamples = totalSpeech,
            totalPauseSamples = totalPause,
            truePositives = tp,
            falsePositives = fp,
            trueNegatives = tn,
            falseNegatives = fn,
            fnrPercent = fnr,
            fprPercent = fpr,
            detectionLatencyMs = detectionLatencyMs,
            releaseLatencyMs = releaseLatencyMs
        )
    }
}
