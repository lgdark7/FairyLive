package com.fairylive.app.core.audio.dsp

import android.util.Log
import kotlin.math.abs
import kotlin.math.log10
import kotlin.math.sign
import kotlin.math.sqrt
import kotlin.math.tanh

/**
 * High-performance, zero-allocation native DSP Audio Mixer for FAIRY LIVE.
 *
 * Implements 4-channel independent volume mixing with real-time DSP:
 * 1. Device Microphone (with independent gain & mute) - Universal Device Mic
 * 2. Game / System Audio (with independent gain & mute)
 * 3. Media / BGM Audio (with independent gain & mute)
 * 4. Master Output Volume
 *
 * DSP Processing Filters:
 * - Studio-Grade Noise Gate & Rumble Cut:
 *   - 80Hz 1st-order high-pass rumble filter (cuts sub-bass AC hum and phone handling noise)
 *   - Dual-time-constant envelope detector (5ms attack, 150ms release)
 *   - Dual-threshold hysteresis gate (-38 dBFS open / -44 dBFS close)
 *   - Exponential smooth downward expander (guarantees dead silence when streamer is silent)
 * - Auto Gain Control (AGC) (speech-aware level stabilization without noise pumping)
 * - Soft-Knee Dynamic Compressor (prevents shouting distortion)
 * - Brickwall Soft Saturation Limiter (guarantees zero digital clipping)
 *
 * Real-time VU Meter dB telemetry (-60 dB to 0 dB).
 */
class AudioMixerProcessor {

    companion object {
        private const val TAG = "AudioMixerProcessor"

        // DSP Thresholds
        private const val NOISE_GATE_OPEN_THRESHOLD = 0.010f  // ~ -40 dBFS (speech onset)
        private const val NOISE_GATE_CLOSE_THRESHOLD = 0.004f // ~ -48 dBFS (silence onset)
        private const val AGC_TARGET_LEVEL = 0.45f             // ~ -7 dBFS
        private const val COMPRESSOR_THRESHOLD = 0.20f         // ~ -14 dBFS
        private const val COMPRESSOR_RATIO = 3.5f
        private const val LIMITER_CEILING = 0.95f
        private const val HP_ALPHA = 0.988f                    // ~80Hz high-pass at 44.1kHz
    }

    enum class AudioPreset {
        GAMING,
        VOICE_FOCUS,
        MUSIC,
        BALANCED,
        LOW_END
    }

    // Channels
    @Volatile var micVolume: Float = 0.9f
    @Volatile var isMicMuted: Boolean = false

    @Volatile var gameVolume: Float = 0.8f
    @Volatile var isGameMuted: Boolean = false

    @Volatile var mediaVolume: Float = 0.5f
    @Volatile var isMediaMuted: Boolean = false

    @Volatile var masterVolume: Float = 1.0f

    // DSP Toggles
    @Volatile var isNoiseSuppressionEnabled: Boolean = true
    @Volatile var isAgcEnabled: Boolean = false
    @Volatile var isCompressorEnabled: Boolean = true
    @Volatile var isLimiterEnabled: Boolean = true

    // Current Preset
    @Volatile var activePreset: AudioPreset = AudioPreset.GAMING

    // Real-time VU Meter levels (in dBFS: -60.0f to 0.0f)
    @Volatile var micDb: Float = -60.0f
        private set
    @Volatile var gameDb: Float = -60.0f
        private set
    @Volatile var masterDb: Float = -60.0f
        private set

    // Internal DSP State
    private var micEnvelope: Float = 0.0f
    private var isGateOpen: Boolean = false
    private var gateHoldCounter: Int = 0
    private var currentGateGain: Float = 0.0f
    private var agcGain: Float = 1.0f

    // High-pass filter state
    private var hpPrevIn: Float = 0.0f
    private var hpPrevOut: Float = 0.0f

    fun resetDspState() {
        micEnvelope = 0.0f
        isGateOpen = false
        gateHoldCounter = 0
        currentGateGain = 0.0f
        agcGain = 1.0f
        hpPrevIn = 0.0f
        hpPrevOut = 0.0f
        Log.i(TAG, "DSP internal state reset (noise gate & AGC cleared)")
    }

    fun applyPreset(preset: AudioPreset) {
        activePreset = preset
        when (preset) {
            AudioPreset.GAMING -> {
                gameVolume = 0.8f
                micVolume = 0.9f
                mediaVolume = 0.5f
                masterVolume = 1.0f
                isNoiseSuppressionEnabled = true
                isCompressorEnabled = true
                isLimiterEnabled = true
                isAgcEnabled = false
            }
            AudioPreset.VOICE_FOCUS -> {
                gameVolume = 0.4f
                micVolume = 1.0f
                mediaVolume = 0.3f
                masterVolume = 1.0f
                isNoiseSuppressionEnabled = true
                isCompressorEnabled = true
                isLimiterEnabled = true
                isAgcEnabled = true
            }
            AudioPreset.MUSIC -> {
                gameVolume = 0.6f
                micVolume = 0.7f
                mediaVolume = 0.8f
                masterVolume = 1.0f
                isNoiseSuppressionEnabled = false
                isCompressorEnabled = false
                isLimiterEnabled = true
                isAgcEnabled = false
            }
            AudioPreset.BALANCED -> {
                gameVolume = 0.7f
                micVolume = 0.8f
                mediaVolume = 0.6f
                masterVolume = 1.0f
                isNoiseSuppressionEnabled = true
                isCompressorEnabled = true
                isLimiterEnabled = true
                isAgcEnabled = false
            }
            AudioPreset.LOW_END -> {
                gameVolume = 0.8f
                micVolume = 0.8f
                mediaVolume = 0.5f
                masterVolume = 1.0f
                isNoiseSuppressionEnabled = false
                isCompressorEnabled = false
                isLimiterEnabled = false
                isAgcEnabled = false
            }
        }
        resetDspState()
        Log.i(TAG, "Applied audio preset: $preset")
    }

    /**
     * Mixes mic PCM and game/playback PCM buffers with full studio-grade DSP into outMixedBuffer.
     * All operations are zero-allocation and run in the high-priority capture thread.
     */
    fun processAndMix(
        micBuffer: ByteArray?,
        gameBuffer: ByteArray?,
        outMixedBuffer: ByteArray,
        bytesToProcess: Int,
        isPaused: Boolean,
        mediaBuffer: ByteArray? = null
    ) {
        val effectiveMicVol = if (isMicMuted || isPaused) 0.0f else micVolume.coerceIn(0.0f, 2.0f)
        val effectiveGameVol = if (isGameMuted || isPaused) 0.0f else gameVolume.coerceIn(0.0f, 2.0f)
        val effectiveMediaVol = if (isMediaMuted || isPaused) 0.0f else mediaVolume.coerceIn(0.0f, 2.0f)
        val effectiveMasterVol = if (isPaused) 0.0f else masterVolume.coerceIn(0.0f, 1.0f)

        var micSumSq = 0.0
        var gameSumSq = 0.0
        var masterSumSq = 0.0
        val sampleCount = bytesToProcess / 2

        for (i in 0 until bytesToProcess - 1 step 2) {
            // 1. Extract 16-bit little-endian samples as normalized floats [-1.0 .. 1.0]
            var rawMic = 0.0f
            if (micBuffer != null && effectiveMicVol > 0.0f) {
                val micSample = ((micBuffer[i + 1].toInt() shl 8) or (micBuffer[i].toInt() and 0xFF)).toShort()
                rawMic = micSample / 32768.0f
            }

            var rawGame = 0.0f
            if (gameBuffer != null && effectiveGameVol > 0.0f) {
                val gameSample = ((gameBuffer[i + 1].toInt() shl 8) or (gameBuffer[i].toInt() and 0xFF)).toShort()
                rawGame = (gameSample / 32768.0f) * effectiveGameVol
            }

            var rawMedia = 0.0f
            if (mediaBuffer != null && effectiveMediaVol > 0.0f) {
                val mediaSample = ((mediaBuffer[i + 1].toInt() shl 8) or (mediaBuffer[i].toInt() and 0xFF)).toShort()
                rawMedia = (mediaSample / 32768.0f) * effectiveMediaVol
            }

            // Track input energy for VU meters
            micSumSq += (rawMic * rawMic)
            gameSumSq += (rawGame * rawGame)

            // 2. Microphone DSP Pipeline
            var processedMic = 0.0f

            if (effectiveMicVol > 0.0f) {
                // A. High-pass filter (cuts sub-80Hz rumble / fan noise / phone handling)
                val hpOut = rawMic - hpPrevIn + HP_ALPHA * hpPrevOut
                hpPrevIn = rawMic
                hpPrevOut = hpOut

                val micSignal = if (isNoiseSuppressionEnabled) hpOut else rawMic
                val absSignal = abs(micSignal)

                // B. Dual-time-constant envelope detector (5ms attack, 150ms release)
                if (absSignal > micEnvelope) {
                    micEnvelope += 0.005f * (absSignal - micEnvelope)
                } else {
                    micEnvelope += 0.00015f * (absSignal - micEnvelope)
                }

                // C. Downward Noise Gate with Hysteresis & Hold
                if (isNoiseSuppressionEnabled) {
                    if (micEnvelope >= NOISE_GATE_OPEN_THRESHOLD) {
                        isGateOpen = true
                        gateHoldCounter = 2000 // ~45ms hold to preserve word endings
                    } else if (micEnvelope < NOISE_GATE_CLOSE_THRESHOLD) {
                        if (gateHoldCounter > 0) {
                            gateHoldCounter--
                        } else {
                            isGateOpen = false
                        }
                    }

                    val targetGateGain = if (isGateOpen) 1.0f else 0.0f
                    // Smooth gain interpolation to avoid clicks (ramps in ~15ms)
                    currentGateGain += 0.002f * (targetGateGain - currentGateGain)
                    if (!isGateOpen && currentGateGain < 0.005f) {
                        currentGateGain = 0.0f // Absolute silence below threshold
                    }

                    processedMic = micSignal * effectiveMicVol * currentGateGain
                } else {
                    currentGateGain = 1.0f
                    processedMic = micSignal * effectiveMicVol
                }

                // D. Speech-Aware AGC (Only adjusts when gate is open, never boosts noise)
                if (isAgcEnabled) {
                    if (isGateOpen && currentGateGain > 0.8f && micEnvelope > 0.015f) {
                        val error = AGC_TARGET_LEVEL - micEnvelope
                        agcGain += error * 0.0002f
                        agcGain = agcGain.coerceIn(0.6f, 1.8f) // max +5dB boost
                    }
                    processedMic *= agcGain
                }

                // E. Soft-Knee Dynamic Compressor (prevents shouting clipping)
                if (isCompressorEnabled) {
                    val mag = abs(processedMic)
                    if (mag > COMPRESSOR_THRESHOLD) {
                        val excess = mag - COMPRESSOR_THRESHOLD
                        val compressedMag = COMPRESSOR_THRESHOLD + (excess / COMPRESSOR_RATIO)
                        processedMic = sign(processedMic) * compressedMag
                    }
                }
            } else {
                currentGateGain = 0.0f
            }

            // 3. 4-Channel Summing Bus (Mic + Game + Media) scaled by Master Volume
            var mixed = (processedMic + rawGame + rawMedia) * effectiveMasterVol

            // 5. Master Brickwall Soft Limiter
            if (isLimiterEnabled) {
                val absMixed = abs(mixed)
                if (absMixed > LIMITER_CEILING) {
                    mixed = tanh(mixed.toDouble()).toFloat() * 0.98f
                }
            }

            masterSumSq += (mixed * mixed)

            // 6. Convert back to 16-bit PCM little endian
            val finalSample = (mixed * 32767.0f).toInt().coerceIn(-32768, 32767).toShort()
            outMixedBuffer[i] = (finalSample.toInt() and 0xFF).toByte()
            outMixedBuffer[i + 1] = ((finalSample.toInt() shr 8) and 0xFF).toByte()
        }

        // 7. Update VU meter dB values
        if (sampleCount > 0) {
            val micRms = sqrt(micSumSq / sampleCount).toFloat()
            val gameRms = sqrt(gameSumSq / sampleCount).toFloat()
            val masterRms = sqrt(masterSumSq / sampleCount).toFloat()

            micDb = if (micRms > 0.0001f) (20.0f * log10(micRms)).coerceIn(-60.0f, 0.0f) else -60.0f
            gameDb = if (gameRms > 0.0001f) (20.0f * log10(gameRms)).coerceIn(-60.0f, 0.0f) else -60.0f
            masterDb = if (masterRms > 0.0001f) (20.0f * log10(masterRms)).coerceIn(-60.0f, 0.0f) else -60.0f
        }
    }
}
