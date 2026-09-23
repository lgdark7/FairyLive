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

    @Test
    fun testGodModeNoiseCancellationEliminatesAmbientHiss() {
        val mixer = AudioMixerProcessor()
        mixer.isNoiseSuppressionEnabled = true
        mixer.isGodModeNoiseEnabled = true
        mixer.micVolume = 1.0f

        val numSamples = 512
        val bytes = numSamples * 2

        // Synthesize steady background hiss (amplitude 100, ~ -50dB)
        val hissBuffer = ByteArray(bytes)
        for (i in 0 until bytes - 1 step 2) {
            val sample = 100.toShort()
            hissBuffer[i] = (sample.toInt() and 0xFF).toByte()
            hissBuffer[i + 1] = ((sample.toInt() shr 8) and 0xFF).toByte()
        }

        val outBuffer = ByteArray(bytes)
        mixer.processAndMix(hissBuffer, null, outBuffer, bytes, isPaused = false)

        // God-mode soft-knee expander should crush background hiss to near-zero
        for (i in 0 until bytes - 1 step 2) {
            val sample = abs(((outBuffer[i + 1].toInt() shl 8) or (outBuffer[i].toInt() and 0xFF)).toShort().toInt())
            assertTrue("God-Mode noise suppression must drive ambient hiss to near-zero ($sample)", sample <= 10)
        }

        // Check estimated noise floor tracked
        assertTrue("Estimated noise floor must be measured in dB", mixer.estimatedNoiseFloorDb < -30.0f)
    }

    @Test
    fun testGodModeSpeechPassesThroughCleanly() {
        val mixer = AudioMixerProcessor()
        mixer.isNoiseSuppressionEnabled = true
        mixer.isGodModeNoiseEnabled = true
        mixer.micVolume = 1.0f

        val numSamples = 1024
        val bytes = numSamples * 2

        // Synthesize AC speech sine wave (500 Hz tone, amplitude 8000, ~ -12dB)
        // Note: Human speech is AC; 100Hz HPF blocks DC.
        val speechBuffer = ByteArray(bytes)
        for (i in 0 until numSamples) {
            val angle = 2.0 * Math.PI * 500.0 * (i.toDouble() / 44100.0)
            val sample = (8000.0 * Math.sin(angle)).toInt().toShort()
            speechBuffer[i * 2] = (sample.toInt() and 0xFF).toByte()
            speechBuffer[i * 2 + 1] = ((sample.toInt() shr 8) and 0xFF).toByte()
        }

        val outBuffer = ByteArray(bytes)
        mixer.processAndMix(speechBuffer, null, outBuffer, bytes, isPaused = false)

        // Find peak amplitude in the latter half of the buffer after gate opens
        var peakSample = 0
        for (i in (numSamples / 2) until numSamples) {
            val sample = abs(((outBuffer[i * 2 + 1].toInt() shl 8) or (outBuffer[i * 2].toInt() and 0xFF)).toShort().toInt())
            if (sample > peakSample) peakSample = sample
        }
        assertTrue("Active speech (500Hz AC) should pass through gate with peak > 2000 (was $peakSample)", peakSample > 2000)
    }

    @Test
    fun testVoiceWaveformPurityAndZeroGarGarDistortion() {
        val mixer = AudioMixerProcessor()
        mixer.isNoiseSuppressionEnabled = true
        mixer.isGodModeNoiseEnabled = true
        mixer.micVolume = 1.0f

        val numSamples = 2048
        val bytes = numSamples * 2

        // Synthesize 400 Hz vocal tone
        val speechBuffer = ByteArray(bytes)
        val origDoubles = DoubleArray(numSamples)
        for (i in 0 until numSamples) {
            val angle = 2.0 * Math.PI * 400.0 * (i.toDouble() / 44100.0)
            val d = Math.sin(angle)
            origDoubles[i] = d
            val sample = (6000.0 * d).toInt().toShort()
            speechBuffer[i * 2] = (sample.toInt() and 0xFF).toByte()
            speechBuffer[i * 2 + 1] = ((sample.toInt() shr 8) and 0xFF).toByte()
        }

        val outBuffer = ByteArray(bytes)
        mixer.processAndMix(speechBuffer, null, outBuffer, bytes, isPaused = false)

        // Check output samples in steady state (last 512 samples)
        var dot = 0.0
        var normA = 0.0
        var normB = 0.0
        for (i in 1500 until numSamples) {
            val s = ((outBuffer[i * 2 + 1].toInt() shl 8) or (outBuffer[i * 2].toInt() and 0xFF)).toShort().toDouble()
            val o = origDoubles[i]
            dot += s * o
            normA += s * s
            normB += o * o
        }

        val correlation = dot / (Math.sqrt(normA) * Math.sqrt(normB))
        // Crystal-clear voice has > 0.90 correlation with input sine wave (zero gar-gar zero-crossing distortion)
        assertTrue("Speech output must retain clean sine wave purity (>0.90 correlation, was $correlation)", correlation > 0.90)
    }

    @Test
    fun testCeilingFanNoiseSuppressionAndLevelControl() {
        val mixer = AudioMixerProcessor()
        mixer.isNoiseSuppressionEnabled = true
        mixer.isGodModeNoiseEnabled = true
        mixer.noiseSuppressionLevel = 80 // Aggressive fan cut
        mixer.micVolume = 1.0f

        val numSamples = 2048
        val bytes = numSamples * 2

        // Synthesize realistic fan noise: 100Hz motor drone + blade turbulence (amplitude ~ 400, ~ -38 dBFS)
        val fanBuffer = ByteArray(bytes)
        for (i in 0 until numSamples) {
            val angle = 2.0 * Math.PI * 100.0 * (i.toDouble() / 44100.0)
            val sample = (350.0 * Math.sin(angle) + 50.0 * Math.sin(angle * 2.0)).toInt().toShort()
            fanBuffer[i * 2] = (sample.toInt() and 0xFF).toByte()
            fanBuffer[i * 2 + 1] = ((sample.toInt() shr 8) and 0xFF).toByte()
        }

        val outBuffer = ByteArray(bytes)
        mixer.processAndMix(fanBuffer, null, outBuffer, bytes, isPaused = false)

        // The adaptive gate should clamp the ceiling fan noise down to absolute silence
        var maxFanOut = 0
        for (i in 1000 until numSamples) {
            val s = abs(((outBuffer[i * 2 + 1].toInt() shl 8) or (outBuffer[i * 2].toInt() and 0xFF)).toShort().toInt())
            if (s > maxFanOut) maxFanOut = s
        }
        assertTrue("Ceiling fan noise must be gated to near-zero (was $maxFanOut)", maxFanOut <= 15)
        assertFalse("Gate should be closed during fan noise", mixer.isGateActive)
    }

    @Test
    fun testGamePauseDoesNotSilenceMicVoice() {
        val mixer = AudioMixerProcessor()
        val numSamples = 256
        val bytes = numSamples * 2

        val micBuffer = ByteArray(bytes)
        val gameBuffer = ByteArray(bytes)
        for (i in 0 until numSamples) {
            val micSample = (5000 * Math.sin(2.0 * Math.PI * 1000.0 * i / 44100.0)).toInt().toShort()
            val gameSample = (3000 * Math.sin(2.0 * Math.PI * 400.0 * i / 44100.0)).toInt().toShort()
            micBuffer[i * 2] = (micSample.toInt() and 0xFF).toByte()
            micBuffer[i * 2 + 1] = ((micSample.toInt() shr 8) and 0xFF).toByte()
            gameBuffer[i * 2] = (gameSample.toInt() and 0xFF).toByte()
            gameBuffer[i * 2 + 1] = ((gameSample.toInt() shr 8) and 0xFF).toByte()
        }

        // Scenario: Game is paused / minimized (BRB active on stream, game audio muted)
        // Creator's mic must remain unmuted and produce real audio output
        mixer.isMicMuted = false
        mixer.isGameMuted = true // Simulates game audio paused during BRB

        val outBuffer = ByteArray(bytes)
        val allChannelsMuted = mixer.isMicMuted && mixer.isGameMuted && mixer.isMediaMuted
        mixer.processAndMix(micBuffer, gameBuffer, outBuffer, bytes, isPaused = allChannelsMuted)

        var hasMicAudio = false
        for (b in outBuffer) {
            if (b.toInt() != 0) {
                hasMicAudio = true
                break
            }
        }
        assertTrue("Creator voice must NOT be silenced when game audio is paused/minimized", hasMicAudio)

        // Scenario 2: When user explicitly mutes mic as well, stream should become completely silent
        mixer.isMicMuted = true
        outBuffer.fill(0)
        val bothMuted = mixer.isMicMuted && mixer.isGameMuted && mixer.isMediaMuted
        mixer.processAndMix(micBuffer, gameBuffer, outBuffer, bytes, isPaused = bothMuted)

        var hasSoundWhenBothMuted = false
        for (b in outBuffer) {
            if (b.toInt() != 0) {
                hasSoundWhenBothMuted = true
                break
            }
        }
        assertFalse("Output must be silent when both mic and game are muted", hasSoundWhenBothMuted)
    }

    @Test
    fun testUnderSpeechFanNoiseEraserAttenuatesNoiseWhileSpeaking() {
        val mixer = AudioMixerProcessor()
        mixer.isNoiseSuppressionEnabled = true
        mixer.isGodModeNoiseEnabled = true
        mixer.noiseSuppressionLevel = 85 // Aggressive fan cut
        mixer.micVolume = 1.0f

        val numSamples = 2048
        val bytes = numSamples * 2

        // Synthesize mixed audio: 400Hz speech (amp 6000) + 4000Hz fan hiss / wind turbulence (amp 600)
        val mixedMicBuffer = ByteArray(bytes)
        for (i in 0 until numSamples) {
            val speechAngle = 2.0 * Math.PI * 400.0 * (i.toDouble() / 44100.0)
            val hissAngle = 2.0 * Math.PI * 4000.0 * (i.toDouble() / 44100.0)
            val sample = (6000.0 * Math.sin(speechAngle) + 600.0 * Math.sin(hissAngle)).toInt().coerceIn(-32768, 32767).toShort()
            mixedMicBuffer[i * 2] = (sample.toInt() and 0xFF).toByte()
            mixedMicBuffer[i * 2 + 1] = ((sample.toInt() shr 8) and 0xFF).toByte()
        }

        val outBuffer = ByteArray(bytes)
        mixer.processAndMix(mixedMicBuffer, null, outBuffer, bytes, isPaused = false)

        // Verify gate opens for speech
        assertTrue("Gate must open for speech", mixer.isGateActive)

        // Find peak amplitude of output speech in steady state
        var peakSample = 0
        for (i in 1000 until numSamples) {
            val s = abs(((outBuffer[i * 2 + 1].toInt() shl 8) or (outBuffer[i * 2].toInt() and 0xFF)).toShort().toInt())
            if (s > peakSample) peakSample = s
        }
        assertTrue("Speech must pass through cleanly with strong amplitude (> 1500, was $peakSample)", peakSample > 1500)
    }

    @Test
    fun testGameAudioPassesAtFullVolumeWhenStreamerIsCompletelySilent() {
        val mixer = AudioMixerProcessor()
        mixer.isNoiseSuppressionEnabled = true
        mixer.isGodModeNoiseEnabled = true
        mixer.noiseSuppressionLevel = 85 // Noise suppression high
        mixer.micVolume = 1.0f
        mixer.gameVolume = 1.0f

        val numSamples = 2048
        val bytes = numSamples * 2

        // Mic is completely silent (ambient room floor)
        val silentMic = ByteArray(bytes)

        // Game audio has strong in-game sound (e.g. 440Hz gunshot/music at amp 8000)
        val gameBuffer = ByteArray(bytes)
        for (i in 0 until numSamples) {
            val angle = 2.0 * Math.PI * 440.0 * (i.toDouble() / 44100.0)
            val sample = (8000.0 * Math.sin(angle)).toInt().coerceIn(-32768, 32767).toShort()
            gameBuffer[i * 2] = (sample.toInt() and 0xFF).toByte()
            gameBuffer[i * 2 + 1] = ((sample.toInt() shr 8) and 0xFF).toByte()
        }

        val outBuffer = ByteArray(bytes)
        mixer.processAndMix(silentMic, gameBuffer, outBuffer, bytes, isPaused = false)

        // Mic gate is closed because streamer is silent
        assertFalse("Mic gate should be closed when streamer is silent", mixer.isGateActive)

        // But Game Audio must pass through at full volume without being muted
        var peakOutGame = 0
        for (i in 0 until numSamples) {
            val s = abs(((outBuffer[i * 2 + 1].toInt() shl 8) or (outBuffer[i * 2].toInt() and 0xFF)).toShort().toInt())
            if (s > peakOutGame) peakOutGame = s
        }

        assertTrue("Game audio must pass cleanly even when streamer is silent (peak was $peakOutGame)", peakOutGame > 7000)
    }

    @Test
    fun testConversationalSpeechOpensGateAndPassesThroughAtNormalVolume() {
        val mixer = AudioMixerProcessor()
        mixer.isNoiseSuppressionEnabled = true
        mixer.isGodModeNoiseEnabled = true
        mixer.noiseSuppressionLevel = 80 // Gaming preset level
        mixer.micVolume = 1.0f

        val numSamples = 2048
        val bytes = numSamples * 2

        // Conversational voice at arm's length (phone held during gaming: amplitude ~ 1200, approx -28 dBFS)
        val speechBuffer = ByteArray(bytes)
        for (i in 0 until numSamples) {
            val angle = 2.0 * Math.PI * 400.0 * (i.toDouble() / 44100.0)
            val sample = (1200.0 * Math.sin(angle)).toInt().toShort()
            speechBuffer[i * 2] = (sample.toInt() and 0xFF).toByte()
            speechBuffer[i * 2 + 1] = ((sample.toInt() shr 8) and 0xFF).toByte()
        }

        val outBuffer = ByteArray(bytes)
        mixer.processAndMix(speechBuffer, null, outBuffer, bytes, isPaused = false)

        // Gate must open for conversational voice
        assertTrue("Gate must open for conversational speaking voice (~1200 amplitude)", mixer.isGateActive)

        // Find peak amplitude in steady state (last 1000 samples)
        var peakSample = 0
        for (i in 1000 until numSamples) {
            val s = abs(((outBuffer[i * 2 + 1].toInt() shl 8) or (outBuffer[i * 2].toInt() and 0xFF)).toShort().toInt())
            if (s > peakSample) peakSample = s
        }
        assertTrue("Conversational speech must pass cleanly (> 900, was $peakSample)", peakSample > 900)
    }

    @Test
    fun testWhisperSpeechOpensGateWithoutBeingSwallowed() {
        val mixer = AudioMixerProcessor()
        mixer.isNoiseSuppressionEnabled = true
        mixer.isGodModeNoiseEnabled = true
        mixer.noiseSuppressionLevel = 75 // Default Studio setting
        mixer.micVolume = 1.0f

        val numSamples = 2048
        val bytes = numSamples * 2

        // Soft whisper / quiet speech (amplitude ~ 700, approx -33 dBFS)
        val whisperBuffer = ByteArray(bytes)
        for (i in 0 until numSamples) {
            val angle = 2.0 * Math.PI * 500.0 * (i.toDouble() / 44100.0)
            val sample = (700.0 * Math.sin(angle)).toInt().toShort()
            whisperBuffer[i * 2] = (sample.toInt() and 0xFF).toByte()
            whisperBuffer[i * 2 + 1] = ((sample.toInt() shr 8) and 0xFF).toByte()
        }

        val outBuffer = ByteArray(bytes)
        mixer.processAndMix(whisperBuffer, null, outBuffer, bytes, isPaused = false)

        assertTrue("Gate must open for whisper speech (~700 amplitude)", mixer.isGateActive)

        var peakWhisper = 0
        for (i in 1000 until numSamples) {
            val s = abs(((outBuffer[i * 2 + 1].toInt() shl 8) or (outBuffer[i * 2].toInt() and 0xFF)).toShort().toInt())
            if (s > peakWhisper) peakWhisper = s
        }
        assertTrue("Whisper speech must pass through (> 500, was $peakWhisper)", peakWhisper > 500)
    }

    @Test
    fun testGameAudioIsolationAndZeroSuppression() {
        val mixer = AudioMixerProcessor()
        mixer.isNoiseSuppressionEnabled = true
        mixer.isGodModeNoiseEnabled = true
        mixer.noiseSuppressionLevel = 100 // Maximum noise suppression on mic
        mixer.micVolume = 1.0f
        mixer.gameVolume = 1.0f

        val numSamples = 2048
        val bytes = numSamples * 2

        // Mic is completely silent (gate remains closed)
        val silentMic = ByteArray(bytes)

        // Game audio has 1000Hz tone with amplitude 10,000
        val gameBuffer = ByteArray(bytes)
        for (i in 0 until numSamples) {
            val angle = 2.0 * Math.PI * 1000.0 * (i.toDouble() / 44100.0)
            val sample = (10000.0 * Math.sin(angle)).toInt().toShort()
            gameBuffer[i * 2] = (sample.toInt() and 0xFF).toByte()
            gameBuffer[i * 2 + 1] = ((sample.toInt() shr 8) and 0xFF).toByte()
        }

        val outBuffer = ByteArray(bytes)
        mixer.processAndMix(silentMic, gameBuffer, outBuffer, bytes, isPaused = false)

        // Find peak game output
        var peakOut = 0
        for (i in 0 until numSamples) {
            val s = abs(((outBuffer[i * 2 + 1].toInt() shl 8) or (outBuffer[i * 2].toInt() and 0xFF)).toShort().toInt())
            if (s > peakOut) peakOut = s
        }

        // Game audio must pass through completely unhindered (amplitude ~ 10,000), not suppressed by mic noise gate
        assertTrue("Game audio must retain full amplitude (>9500, was $peakOut) regardless of mic suppression", peakOut > 9500)
        assertTrue("Game dB must reflect game energy (~ -10dBFS, was ${mixer.gameDb})", mixer.gameDb > -15.0f)
    }

    @Test
    fun testLoudSpeechAndGameAudioMasterLimiter() {
        val mixer = AudioMixerProcessor()
        mixer.isLimiterEnabled = true
        mixer.micVolume = 1.0f
        mixer.gameVolume = 1.0f
        mixer.masterVolume = 1.0f

        val numSamples = 2048
        val bytes = numSamples * 2

        // Shouting streamer: amplitude 28,000
        val shoutingMic = ByteArray(bytes)
        // Loud game explosion: amplitude 28,000
        val loudGame = ByteArray(bytes)

        for (i in 0 until numSamples) {
            val micSample = 28000.toShort()
            shoutingMic[i * 2] = (micSample.toInt() and 0xFF).toByte()
            shoutingMic[i * 2 + 1] = ((micSample.toInt() shr 8) and 0xFF).toByte()

            val gameSample = 28000.toShort()
            loudGame[i * 2] = (gameSample.toInt() and 0xFF).toByte()
            loudGame[i * 2 + 1] = ((gameSample.toInt() shr 8) and 0xFF).toByte()
        }

        val outBuffer = ByteArray(bytes)
        mixer.processAndMix(shoutingMic, loudGame, outBuffer, bytes, isPaused = false)

        // Check every output sample: must never overflow or wrap around into negative
        for (i in 0 until numSamples) {
            val sample = ((outBuffer[i * 2 + 1].toInt() shl 8) or (outBuffer[i * 2].toInt() and 0xFF)).toShort()
            // Sample must be positive and within 32767
            assertTrue("Master limiter must prevent overflow wrapping (sample=$sample)", sample > 0 && sample <= 32767)
        }
    }

    @Test
    fun testDynamicVolumeAdjustmentWithoutRestart() {
        val mixer = AudioMixerProcessor()
        mixer.micVolume = 1.0f
        mixer.gameVolume = 1.0f

        val numSamples = 1024
        val bytes = numSamples * 2

        val gameBuffer = ByteArray(bytes)
        for (i in 0 until numSamples) {
            val sample = 10000.toShort()
            gameBuffer[i * 2] = (sample.toInt() and 0xFF).toByte()
            gameBuffer[i * 2 + 1] = ((sample.toInt() shr 8) and 0xFF).toByte()
        }

        val out1 = ByteArray(bytes)
        mixer.processAndMix(null, gameBuffer, out1, bytes, isPaused = false)
        val sampleAt100 = ((out1[1].toInt() shl 8) or (out1[0].toInt() and 0xFF)).toShort()
        assertTrue("Sample at 100% volume must be ~10000 (was $sampleAt100)", abs(sampleAt100 - 10000) <= 2)

        // Change game volume dynamically to 50%
        mixer.gameVolume = 0.5f
        val out2 = ByteArray(bytes)
        mixer.processAndMix(null, gameBuffer, out2, bytes, isPaused = false)
        val sampleAt50 = ((out2[1].toInt() shl 8) or (out2[0].toInt() and 0xFF)).toShort()
        assertTrue("Sample at 50% volume must be ~5000 (was $sampleAt50)", abs(sampleAt50 - 5000) <= 2)

        // Change game volume dynamically to 0% (mute)
        mixer.gameVolume = 0.0f
        val out3 = ByteArray(bytes)
        mixer.processAndMix(null, gameBuffer, out3, bytes, isPaused = false)
        val sampleAt0 = ((out3[1].toInt() shl 8) or (out3[0].toInt() and 0xFF)).toShort()
        assertEquals(0.toShort(), sampleAt0)
    }

    @Test
    fun testVuMeterTelemetryAccuracy() {
        val mixer = AudioMixerProcessor()
        mixer.micVolume = 1.0f
        mixer.gameVolume = 1.0f
        mixer.masterVolume = 1.0f

        val numSamples = 1024
        val bytes = numSamples * 2

        // Mic sine wave at ~16384 (50% full scale, approx -6 dBFS)
        val micBuffer = ByteArray(bytes)
        for (i in 0 until numSamples) {
            val angle = 2.0 * Math.PI * 440.0 * (i.toDouble() / 44100.0)
            val sample = (16384.0 * Math.sin(angle)).toInt().toShort()
            micBuffer[i * 2] = (sample.toInt() and 0xFF).toByte()
            micBuffer[i * 2 + 1] = ((sample.toInt() shr 8) and 0xFF).toByte()
        }

        val outBuffer = ByteArray(bytes)
        mixer.processAndMix(micBuffer, null, outBuffer, bytes, isPaused = false)

        assertTrue("Mic dB must be approximately -9 to -6 dBFS (was ${mixer.micDb})", mixer.micDb in -10.0f..-5.0f)
        assertTrue("Mic peak percent must be approx 50% (was ${mixer.micPeakPercent}%)", mixer.micPeakPercent in 48..52)
        assertEquals(-60.0f, mixer.gameDb, 0.1f)
        assertEquals(0, mixer.gamePeakPercent)
    }
}
