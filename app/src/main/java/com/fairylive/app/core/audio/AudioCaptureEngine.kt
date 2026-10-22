package com.fairylive.app.core.audio

import android.annotation.SuppressLint
import android.media.AudioRecord
import android.media.MediaRecorder
import android.media.audiofx.NoiseSuppressor
import android.media.projection.MediaProjection
import android.os.Build
import android.os.SystemClock
import android.util.Log
import com.fairylive.app.core.audio.model.AudioCaptureConfig
import com.fairylive.app.core.audio.model.AudioCaptureState
import com.fairylive.app.core.audio.model.AudioMetrics
import com.fairylive.app.core.audio.model.AudioSourceType
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.LongAdder
import kotlin.math.sqrt

/**
 * High-performance, zero-allocation native AudioRecord capture engine.
 * Supports dual-audio simultaneous capture:
 * 1. Microphone voice capture with independent volume slider & mute control.
 * 2. Internal device / game audio capture (Android 10+) with independent volume slider.
 * Mixes both PCM streams in real-time with sample-accurate timestamp continuity.
 */
class AudioCaptureEngine {

    /** PCM buffers not dispatched because no listener was attached (diagnostic only). */
    val pcmDroppedCount = LongAdder()

    fun interface AudioSampleListener {
        fun onAudioSample(data: ByteArray, size: Int, presentationTimeUs: Long)
    }

    private var micRecord: AudioRecord? = null
    private var playbackRecord: AudioRecord? = null
    private var config: AudioCaptureConfig = AudioCaptureConfig()
    private var sampleListener: AudioSampleListener? = null

    private var micThread: Thread? = null
    private var captureThread: Thread? = null
    private var playbackThread: Thread? = null
    private val isCapturing = AtomicBoolean(false)

    private var recordingCallback: Any? = null
    private var recordingExecutor: java.util.concurrent.ExecutorService? = null
    @Volatile private var recoveryRampFrames: Int = 0

    @Volatile
    var isMicSilencedBySystem: Boolean = false
        private set

    // Hardware Audio Effect: NoiseSuppressor attached to AudioRecord session ID (FORTEMEDIA / OEM HAL)
    private var hardwareNoiseSuppressor: NoiseSuppressor? = null
    @Volatile var isHardwareNsEnabled: Boolean = false
    val isHardwareNsAvailable: Boolean get() = NoiseSuppressor.isAvailable()

    private var micRingBuffer: PcmRingBuffer? = null
    private var playbackRingBuffer: PcmRingBuffer? = null
    private var mediaProjectionRef: MediaProjection? = null
    private var isPlaybackStereo: Boolean = false
    private var lastTelemetryLogTime: Long = 0L

    val micRingBufferDepth: Int get() = micRingBuffer?.availableBytes ?: 0
    val gameRingBufferDepth: Int get() = playbackRingBuffer?.availableBytes ?: 0
    val micOverflowCount: Long get() = micRingBuffer?.overflowCount ?: 0L
    val gameOverflowCount: Long get() = playbackRingBuffer?.overflowCount ?: 0L

    val audioMixer = com.fairylive.app.core.audio.dsp.AudioMixerProcessor()

    @Volatile
    var isMuted: Boolean = false
        private set

    val isMicMuted: Boolean
        get() = audioMixer.isMicMuted

    @Volatile
    var isStreamPaused: Boolean = false
        private set

    val micVolume: Float
        get() = audioMixer.micVolume

    val gameVolume: Float
        get() = audioMixer.gameVolume

    val mediaVolume: Float
        get() = audioMixer.mediaVolume

    val masterVolume: Float
        get() = audioMixer.masterVolume

    val isGameMuted: Boolean
        get() = audioMixer.isGameMuted

    val isMediaMuted: Boolean
        get() = audioMixer.isMediaMuted

    private var totalSamplesRead: Long = 0L
    private var totalSilenceFramesInserted: Long = 0L
    private var startTimestampUs: Long = 0L
    private var lastAmplitudeUpdateTime: Long = 0L

    fun getSilenceFramesInserted(): Long = totalSilenceFramesInserted
    fun getTotalSamplesProduced(): Long = totalSamplesRead

    fun setAudioSampleListener(listener: AudioSampleListener?) {
        this.sampleListener = listener
    }

    fun setMicVolume(volume: Float) {
        audioMixer.micVolume = volume.coerceIn(0.0f, 2.0f)
        Log.i(TAG, "Microphone volume set to ${(audioMixer.micVolume * 100).toInt()}%")
    }

    fun setGameVolume(volume: Float) {
        audioMixer.gameVolume = volume.coerceIn(0.0f, 2.0f)
        Log.i(TAG, "Game audio volume set to ${(audioMixer.gameVolume * 100).toInt()}%")
    }

    fun setMediaVolume(volume: Float) {
        audioMixer.mediaVolume = volume.coerceIn(0.0f, 2.0f)
        Log.i(TAG, "Media volume set to ${(audioMixer.mediaVolume * 100).toInt()}%")
    }

    fun setMasterVolume(volume: Float) {
        audioMixer.masterVolume = volume.coerceIn(0.0f, 1.0f)
        Log.i(TAG, "Master volume set to ${(audioMixer.masterVolume * 100).toInt()}%")
    }

    fun setMicMuted(muted: Boolean) {
        audioMixer.isMicMuted = muted
        Log.i(TAG, "User mic muted: $muted")
    }

    fun setGameMuted(muted: Boolean) {
        audioMixer.isGameMuted = muted
        Log.i(TAG, "Game audio muted: $muted")
    }

    fun setMediaMuted(muted: Boolean) {
        audioMixer.isMediaMuted = muted
        Log.i(TAG, "Media audio muted: $muted")
    }

    fun setDspFilters(
        noiseGate: Boolean,
        agc: Boolean,
        compressor: Boolean,
        limiter: Boolean,
        godMode: Boolean = true,
        noiseLevel: Int? = null,
        spectralNoise: Boolean? = null,
        hardwareNs: Boolean? = null
    ) {
        audioMixer.isNoiseSuppressionEnabled = noiseGate
        audioMixer.isGodModeNoiseEnabled = godMode
        if (noiseLevel != null) {
            audioMixer.noiseSuppressionLevel = noiseLevel.coerceIn(0, 100)
        }
        audioMixer.isAgcEnabled = agc
        audioMixer.isCompressorEnabled = compressor
        audioMixer.isLimiterEnabled = limiter
        if (spectralNoise != null) {
            audioMixer.isSpectralNoiseSuppressionEnabled = spectralNoise
        }
        if (hardwareNs != null) {
            setHardwareNoiseSuppressor(hardwareNs)
        }
    }

    fun setHardwareNoiseSuppressor(enabled: Boolean) {
        this.isHardwareNsEnabled = enabled
        try {
            hardwareNoiseSuppressor?.enabled = enabled
            Log.i(TAG, "Hardware NoiseSuppressor enabled=$enabled")
        } catch (e: Exception) {
            Log.w(TAG, "Failed to toggle hardware NoiseSuppressor: ${e.message}")
        }
    }

    fun setAutoDucking(enabled: Boolean, attenuation: Float = 0.30f) {
        audioMixer.isAutoDuckingEnabled = enabled
        audioMixer.duckingAttenuation = attenuation.coerceIn(0.05f, 1.0f)
        Log.i(TAG, "Auto-ducking set: enabled=$enabled, attenuation=$attenuation")
    }

    fun applyPreset(preset: com.fairylive.app.core.audio.dsp.AudioMixerProcessor.AudioPreset) {
        audioMixer.applyPreset(preset)
    }

    fun setStreamPaused(paused: Boolean) {
        this.isStreamPaused = paused
        Log.i(TAG, "Stream audio paused: $paused")
    }

    /**
     * Initializes and starts dual AudioRecord capture.
     * Simultaneous mic + internal game audio capture on Android 10+ (API 29+).
     */
    @SuppressLint("MissingPermission")
    @Synchronized
    fun start(
        config: AudioCaptureConfig,
        mediaProjection: MediaProjection? = null
    ) {
        if (isCapturing.get()) {
            stop()
        }

        AudioStateNotifier.updateState(AudioCaptureState.INITIALIZING)
        this.config = config

        var micRec: AudioRecord? = null
        var playRec: AudioRecord? = null

        // 1. Initialize Microphone AudioRecord with high-fidelity source fallback
        // MIC and DEFAULT provide raw uncompressed 20Hz-20kHz response with zero phone carrier filtering.
        // We strictly DO NOT attach AcousticEchoCanceler or hardware NoiseSuppressor because mobile OEM
        // HAL implementations cause aggressive phase-cancellation, robotic flutter, and breaking voice.
        val preferredSources = intArrayOf(
            MediaRecorder.AudioSource.MIC,
            MediaRecorder.AudioSource.DEFAULT,
            MediaRecorder.AudioSource.CAMCORDER,
            MediaRecorder.AudioSource.VOICE_RECOGNITION
        )
        for (source in preferredSources) {
            try {
                val bufferSize = config.minBufferSize
                val rec = AudioRecord(
                    source,
                    config.sampleRate,
                    config.channelConfig,
                    config.audioFormat,
                    bufferSize
                )
                if (rec.state == AudioRecord.STATE_INITIALIZED) {
                    micRec = rec
                    Log.i(TAG, "Initialized Microphone AudioRecord (source=$source, sampleRate=${config.sampleRate}Hz, buffer: $bufferSize, clean raw audio)")

                    // Attach hardware NoiseSuppressor if available on device HAL (e.g. Fortemedia on Pixel 6a)
                    // Note: AcousticEchoCanceler (AEC) strictly remains disabled for game streaming fidelity
                    if (NoiseSuppressor.isAvailable()) {
                        try {
                            val ns = NoiseSuppressor.create(rec.audioSessionId)
                            if (ns != null) {
                                ns.enabled = isHardwareNsEnabled
                                hardwareNoiseSuppressor = ns
                                Log.i(TAG, "Hardware NoiseSuppressor attached to session ${rec.audioSessionId} (enabled=$isHardwareNsEnabled)")
                            }
                        } catch (e: Exception) {
                            Log.w(TAG, "Hardware NoiseSuppressor initialization failed: ${e.message}")
                        }
                    }
                    break
                } else {
                    rec.release()
                    Log.w(TAG, "Microphone AudioRecord failed to initialize for source=$source")
                }
            } catch (se: SecurityException) {
                Log.e(TAG, "Microphone recording permission denied for source=$source: ${se.message}")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to instantiate Microphone AudioRecord for source=$source: ${e.message}")
            }
        }

        // 2. Initialize Internal Playback AudioRecord ONLY if explicitly requested via sourceType
        // When sourceType is MIC (default), internal playback capture is strictly disabled so NO notification
        // sounds, ringtones, or device sounds ever leak into the stream.
        var playbackStereo = false
        val shouldCapturePlayback = config.sourceType == AudioSourceType.INTERNAL_PLAYBACK &&
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q &&
                mediaProjection != null

        if (shouldCapturePlayback) {
            try {
                val result = AudioPlaybackCaptureHelper.createPlaybackCaptureRecord(mediaProjection!!, config)
                if (result != null && result.record.state == AudioRecord.STATE_INITIALIZED) {
                    playRec = result.record
                    playbackStereo = result.isStereo
                    Log.i(TAG, "Initialized AudioPlaybackCapture (Internal Game Audio, stereo=$playbackStereo, notifications excluded)")
                }
            } catch (e: Exception) {
                Log.w(TAG, "Failed to initialize AudioPlaybackCapture: ${e.message}")
            }
        } else {
            Log.i(TAG, "AudioPlaybackCapture skipped: strictly Microphone Only mode (sourceType=${config.sourceType})")
        }

        if (micRec == null && playRec == null) {
            AudioStateNotifier.updateState(AudioCaptureState.ERROR)
            Log.e(TAG, "Neither Microphone nor Playback AudioRecord could be initialized")
            return
        }

        this.mediaProjectionRef = mediaProjection
        this.isPlaybackStereo = playbackStereo
        this.micRecord = micRec
        this.playbackRecord = playRec
        this.totalSamplesRead = 0L
        this.totalSilenceFramesInserted = 0L
        val nowNs = com.fairylive.app.core.common.MediaClock.nanoTimeSource()
        this.startTimestampUs = com.fairylive.app.core.common.MediaClock.toSessionPtsUs(nowNs)
        this.lastAmplitudeUpdateTime = SystemClock.elapsedRealtime()

        val ringCapacity = kotlin.math.max(config.minBufferSize * 8, 32768)
        val mRing = if (micRec != null) PcmRingBuffer(ringCapacity) else null
        val pRing = if (playRec != null) PcmRingBuffer(ringCapacity) else null
        this.micRingBuffer = mRing
        this.playbackRingBuffer = pRing

        isCapturing.set(true)

        // Start playback recording thread if internal audio is active
        if (playRec != null && pRing != null) {
            playRec.startRecording()
            val isStereo = isPlaybackStereo
            val pThread = Thread({ playbackCaptureLoop(playRec, pRing, isStereo) }, "FairyGameAudioThread").apply {
                priority = Thread.MAX_PRIORITY
                start()
            }
            this.playbackThread = pThread
        }

        // Start dedicated microphone recording thread if mic is active
        if (micRec != null && mRing != null) {
            micRec.startRecording()
            val mThread = Thread({ micCaptureLoop(micRec, mRing) }, "FairyMicCaptureThread").apply {
                priority = Thread.MAX_PRIORITY
                start()
            }
            this.micThread = mThread
        }

        // Register system AudioRecordingCallback for automatic in-game mic / concurrent capture detection (Android 10+)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && micRec != null) {
            try {
                val executor = java.util.concurrent.Executors.newSingleThreadExecutor { r ->
                    Thread(r, "FairyAudioRecordingCallback").apply { isDaemon = true }
                }
                this.recordingExecutor = executor
                val callback = object : android.media.AudioManager.AudioRecordingCallback() {
                    override fun onRecordingConfigChanged(configs: List<android.media.AudioRecordingConfiguration>) {
                        val session = micRec.audioSessionId
                        var silenced = false
                        for (c in configs) {
                            if (c.clientAudioSessionId == session) {
                                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                                    silenced = c.isClientSilenced
                                }
                                break
                            }
                        }
                        if (isMicSilencedBySystem != silenced) {
                            val wasSilenced = isMicSilencedBySystem
                            isMicSilencedBySystem = silenced
                            Log.w(TAG, "AudioRecord client silenced state changed: $silenced (in-game or VoIP mic active)")
                            AudioStateNotifier.updateMicSilenced(silenced)

                            // When game mic turns OFF -> stream mic active:
                            // Reset DSP filter states and start smooth fade-in ramp to prevent HAL mode-switch pop/static
                            if (wasSilenced && !silenced) {
                                audioMixer.resetDspState()
                                recoveryRampFrames = (config.sampleRate * 0.15f).toInt()
                                Log.i(TAG, "Post-silence audio recovery initiated: DSP reset, 150ms ramp armed")
                            }
                        }
                    }
                }
                micRec.registerAudioRecordingCallback(executor, callback)
                this.recordingCallback = callback
                Log.i(TAG, "Registered AudioRecordingCallback for system mic silence detection (API ${Build.VERSION.SDK_INT})")
            } catch (e: Exception) {
                Log.w(TAG, "Could not register AudioRecordingCallback: ${e.message}")
            }
        }

        val mThread = Thread({ masterCaptureLoop(mRing, pRing) }, "FairyAudioMixerThread").apply {
            priority = Thread.MAX_PRIORITY
            start()
        }
        this.captureThread = mThread

        val isDeviceAudio = playRec != null
        val initialState = if (isMuted || isMicMuted) AudioCaptureState.MUTED else AudioCaptureState.RECORDING
        AudioStateNotifier.updateState(initialState)
        AudioStateNotifier.updateMetrics(
            AudioMetrics(
                sampleRate = config.sampleRate,
                channels = config.channelCount,
                isMuted = isMuted,
                isDeviceAudio = isDeviceAudio
            )
        )
    }

    private fun playbackCaptureLoop(playRec: AudioRecord, ringBuffer: PcmRingBuffer, isStereo: Boolean) {
        val currentRec = playRec
        if (isStereo) {
            val stereoBufferSize = config.minBufferSize * 2
            val stereoBuffer = ByteArray(stereoBufferSize)
            val monoBuffer = ByteArray(config.minBufferSize)

            while (isCapturing.get()) {
                val bytesRead = currentRec.read(stereoBuffer, 0, stereoBuffer.size)
                if (bytesRead > 0) {
                    // Downmix interleaved 16-bit PCM stereo (L, R) to mono ((L + R) / 2) with zero heap allocation
                    var outIdx = 0
                    val limit = bytesRead - 3
                    var i = 0
                    while (i <= limit && outIdx < monoBuffer.size - 1) {
                        val left = ((stereoBuffer[i + 1].toInt() shl 8) or (stereoBuffer[i].toInt() and 0xFF)).toShort()
                        val right = ((stereoBuffer[i + 3].toInt() shl 8) or (stereoBuffer[i + 2].toInt() and 0xFF)).toShort()
                        val mono = ((left.toInt() + right.toInt()) shr 1).toShort()
                        monoBuffer[outIdx] = (mono.toInt() and 0xFF).toByte()
                        monoBuffer[outIdx + 1] = ((mono.toInt() shr 8) and 0xFF).toByte()
                        outIdx += 2
                        i += 4
                    }
                    if (outIdx > 0) {
                        ringBuffer.write(monoBuffer, 0, outIdx)
                    }
                } else if (bytesRead < 0) {
                    Log.w(TAG, "AudioPlaybackCapture read error: $bytesRead")
                    if (isCapturing.get()) {
                        try {
                            Thread.sleep(20)
                        } catch (_: Exception) {}
                        if (currentRec.recordingState != AudioRecord.RECORDSTATE_RECORDING) {
                            try {
                                currentRec.startRecording()
                            } catch (e: Exception) {
                                Log.w(TAG, "AudioPlaybackCapture restart attempt failed: ${e.message}")
                            }
                        }
                    }
                }
            }
        } else {
            val buffer = ByteArray(config.minBufferSize)
            while (isCapturing.get()) {
                val bytesRead = currentRec.read(buffer, 0, buffer.size)
                if (bytesRead > 0) {
                    ringBuffer.write(buffer, 0, bytesRead)
                } else if (bytesRead < 0) {
                    Log.w(TAG, "AudioPlaybackCapture read error: $bytesRead")
                    if (isCapturing.get()) {
                        try {
                            Thread.sleep(20)
                        } catch (_: Exception) {}
                        if (currentRec.recordingState != AudioRecord.RECORDSTATE_RECORDING) {
                            try {
                                currentRec.startRecording()
                            } catch (e: Exception) {
                                Log.w(TAG, "AudioPlaybackCapture restart attempt failed: ${e.message}")
                            }
                        }
                    }
                }
            }
        }
    }

    private fun micCaptureLoop(micRec: AudioRecord, micRing: PcmRingBuffer) {
        val buffer = ByteArray(config.minBufferSize)
        while (isCapturing.get()) {
            val bytesRead = micRec.read(buffer, 0, buffer.size)
            if (bytesRead > 0) {
                // If OS un-silences mic or recovers, apply smooth fade-in ramp
                if (recoveryRampFrames > 0) {
                    val totalRamp = (config.sampleRate * 0.15f).toInt().coerceAtLeast(1)
                    for (i in 0 until bytesRead - 1 step 2) {
                        if (recoveryRampFrames > 0) {
                            val factor = (totalRamp - recoveryRampFrames).toFloat() / totalRamp
                            val sample = ((buffer[i + 1].toInt() shl 8) or (buffer[i].toInt() and 0xFF)).toShort()
                            val rampedSample = (sample * factor).toInt().toShort()
                            buffer[i] = (rampedSample.toInt() and 0xFF).toByte()
                            buffer[i + 1] = ((rampedSample.toInt() shr 8) and 0xFF).toByte()
                            recoveryRampFrames--
                        }
                    }
                }
                micRing.write(buffer, 0, bytesRead)
            } else if (bytesRead < 0) {
                Log.w(TAG, "Microphone AudioRecord read error: $bytesRead")
                if (isCapturing.get()) {
                    try {
                        Thread.sleep(20)
                    } catch (_: Exception) {}
                    if (micRec.recordingState != AudioRecord.RECORDSTATE_RECORDING) {
                        try {
                            micRec.startRecording()
                        } catch (e: Exception) {
                            Log.w(TAG, "Failed to re-start mic recording: ${e.message}")
                        }
                    }
                }
            }
        }
    }

    private fun masterCaptureLoop(micRing: PcmRingBuffer?, gameRing: PcmRingBuffer?) {
        val frameBytes = FRAME_SAMPLES * config.bytesPerFrame
        val micFrame = ByteArray(frameBytes)
        val gameFrame = ByteArray(frameBytes)
        val mixedFrame = ByteArray(frameBytes)

        val frameDurationNs = (FRAME_SAMPLES * 1_000_000_000L) / config.sampleRate
        var nextFrameDeadlineNs = com.fairylive.app.core.common.MediaClock.nanoTimeSource()

        while (isCapturing.get()) {
            val nowNs = com.fairylive.app.core.common.MediaClock.nanoTimeSource()

            // Check data availability in active ring buffers
            val micAvail = micRing?.availableBytes ?: 0
            val gameAvail = gameRing?.availableBytes ?: 0

            // If neither active buffer has a full frame yet, pace using frame timing
            val hasData = (micRing != null && micAvail >= frameBytes) ||
                    (gameRing != null && gameAvail >= frameBytes)

            if (!hasData) {
                val waitNs = nextFrameDeadlineNs - nowNs
                if (waitNs > 1_000_000L) {
                    try {
                        Thread.sleep((waitNs / 1_000_000L).coerceIn(1L, 25L))
                    } catch (_: InterruptedException) {
                        if (!isCapturing.get()) break
                    }
                    continue
                }
            }

            // Advance target time for next frame
            nextFrameDeadlineNs += frameDurationNs
            val curTimeNs = com.fairylive.app.core.common.MediaClock.nanoTimeSource()
            if (curTimeNs - nextFrameDeadlineNs > frameDurationNs * 4) {
                nextFrameDeadlineNs = curTimeNs + frameDurationNs
            }

            // Drain microphone frame (PcmRingBuffer.read automatically zero-fills remainder if underflow)
            if (micRing != null) {
                micRing.read(micFrame, 0, frameBytes)
            } else {
                micFrame.fill(0)
            }

            // Drain game audio frame (PcmRingBuffer.read automatically zero-fills remainder if underflow)
            if (gameRing != null) {
                gameRing.read(gameFrame, 0, frameBytes)
            } else {
                gameFrame.fill(0)
            }

            // Presentation timestamp calculation:
            // Strictly monotonic sample-count timeline matching MediaClock session origin
            if (totalSamplesRead == 0L) {
                startTimestampUs = com.fairylive.app.core.common.MediaClock.toSessionPtsUs(curTimeNs)
            }
            val currentPtsUs = startTimestampUs + (totalSamplesRead * 1_000_000L) / config.sampleRate
            totalSamplesRead += FRAME_SAMPLES

            // Sync mute & pause states to DSP mixer
            audioMixer.isMicMuted = isMuted || isMicMuted
            audioMixer.isGameMuted = isMuted || isGameMuted || isStreamPaused || (playbackRecord == null)

            // Real-time 16-bit PCM mixing & DSP audio processing
            val isCompletelySilent = audioMixer.isMicMuted &&
                    (playbackRecord == null || audioMixer.isGameMuted) &&
                    audioMixer.isMediaMuted
            audioMixer.processAndMix(
                micBuffer = if (micRecord != null) micFrame else null,
                gameBuffer = if (playbackRecord != null) gameFrame else null,
                outMixedBuffer = mixedFrame,
                bytesToProcess = frameBytes,
                isPaused = isCompletelySilent
            )

            // Peak amplitude calculation for UI meters (every 100ms)
            val now = SystemClock.elapsedRealtime()
            if (now - lastAmplitudeUpdateTime >= 100L) {
                lastAmplitudeUpdateTime = now
                val amplitudePercent = if (audioMixer.isMicMuted && audioMixer.isGameMuted) 0
                    else calculateRmsAmplitude(mixedFrame, frameBytes)
                AudioStateNotifier.updateMetrics(
                    AudioStateNotifier.currentMetrics.copy(
                        samplesRead = totalSamplesRead,
                        silenceFramesInserted = totalSilenceFramesInserted,
                        peakAmplitudePercent = amplitudePercent,
                        micDb = audioMixer.micDb,
                        gameDb = audioMixer.gameDb,
                        mediaDb = audioMixer.mediaDb,
                        masterDb = audioMixer.masterDb,
                        micPeakPercent = audioMixer.micPeakPercent,
                        gamePeakPercent = audioMixer.gamePeakPercent,
                        masterPeakPercent = audioMixer.masterPeakPercent,
                        pcmUnderruns = pcmDroppedCount.sum(),
                        ringBufferDepth = gameRing?.availableBytes ?: 0,
                        lastPresentationTimeUs = currentPtsUs,
                        rtmpAudioPacketsSent = com.fairylive.app.core.stream.StreamTelemetryNotifier.currentTelemetry.audioFramesSent,
                        isMuted = (audioMixer.isMicMuted && audioMixer.isGameMuted) || isMuted,
                        isDeviceAudio = playbackRecord != null,
                        isDeviceAudioStereo = isPlaybackStereo,
                        isMicSilencedBySystem = isMicSilencedBySystem,
                        isGameAudioActive = audioMixer.gameDb > -50.0f,
                        isGateOpen = audioMixer.isGateActive,
                        isVoiceDetected = audioMixer.isVoiceDetected,
                        noiseFloorDb = audioMixer.estimatedNoiseFloorDb,
                        isHardwareNsActive = hardwareNoiseSuppressor?.enabled == true,
                        isSpectralNsActive = audioMixer.isSpectralNoiseSuppressionEnabled && audioMixer.isNoiseSuppressionEnabled
                    )
                )
            }

            if (now - lastTelemetryLogTime >= 5000L) {
                lastTelemetryLogTime = now
                val rtmpA = com.fairylive.app.core.stream.StreamTelemetryNotifier.currentTelemetry.audioFramesSent
                val micOverflow = micRing?.overflowCount ?: 0L
                val gameOverflow = gameRing?.overflowCount ?: 0L
                Log.i(
                    TAG,
                    "[AUDIO-DIAGNOSTIC] mic=${audioMixer.micDb}dB (${audioMixer.micPeakPercent}%) micAvail=${micRing?.availableBytes ?: 0}B " +
                    "game=${audioMixer.gameDb}dB (${audioMixer.gamePeakPercent}%) gameAvail=${gameRing?.availableBytes ?: 0}B " +
                    "master=${audioMixer.masterDb}dB ovf(mic=$micOverflow, game=$gameOverflow) " +
                    "gateOpen=${audioMixer.isGateActive} vad=${audioMixer.isVoiceDetected} rtmpAudioSent=$rtmpA"
                )
            }

            val listener = sampleListener
            if (listener != null) {
                listener.onAudioSample(mixedFrame, frameBytes, currentPtsUs)
            } else {
                pcmDroppedCount.increment()
            }
        }
    }

    private fun calculateRmsAmplitude(buffer: ByteArray, bytesRead: Int): Int {
        var sum = 0.0
        val sampleCount = bytesRead / 2
        if (sampleCount <= 0) return 0

        for (i in 0 until bytesRead - 1 step 2) {
            val sample = (buffer[i].toInt() and 0xFF) or (buffer[i + 1].toInt() shl 8)
            val sampleShort = sample.toShort()
            sum += sampleShort * sampleShort
        }

        val rms = sqrt(sum / sampleCount)
        return (rms * 100 / 32767).toInt().coerceIn(0, 100)
    }

    fun mute() {
        isMuted = true
        AudioStateNotifier.updateState(AudioCaptureState.MUTED)
        AudioStateNotifier.updateMetrics(
            AudioStateNotifier.currentMetrics.copy(isMuted = true, peakAmplitudePercent = 0)
        )
    }

    fun unmute() {
        isMuted = false
        val state = if (isMicMuted) AudioCaptureState.MUTED else AudioCaptureState.RECORDING
        AudioStateNotifier.updateState(state)
        AudioStateNotifier.updateMetrics(
            AudioStateNotifier.currentMetrics.copy(isMuted = isMicMuted)
        )
    }

    @Synchronized
    fun stop() {
        if (!isCapturing.get() && micRecord == null && playbackRecord == null) return

        AudioStateNotifier.updateState(AudioCaptureState.STOPPING)
        isCapturing.set(false)

        micThread?.interrupt()
        playbackThread?.interrupt()
        captureThread?.interrupt()
        try {
            micThread?.join(400L)
            playbackThread?.join(400L)
            captureThread?.join(400L)
        } catch (_: Exception) {}
        micThread = null
        playbackThread = null
        captureThread = null
        recoveryRampFrames = 0

        try {
            micRecord?.stop()
            micRecord?.release()
        } catch (_: Exception) {}
        micRecord = null

        try {
            hardwareNoiseSuppressor?.release()
        } catch (_: Exception) {}
        hardwareNoiseSuppressor = null

        try {
            playbackRecord?.stop()
            playbackRecord?.release()
        } catch (_: Exception) {}
        playbackRecord = null

        micRingBuffer?.clear()
        micRingBuffer = null
        playbackRingBuffer?.clear()
        playbackRingBuffer = null
        mediaProjectionRef = null
        isPlaybackStereo = false

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val cb = recordingCallback as? android.media.AudioManager.AudioRecordingCallback
            if (cb != null) {
                try {
                    micRecord?.unregisterAudioRecordingCallback(cb)
                } catch (_: Exception) {}
            }
            recordingCallback = null
            recordingExecutor?.shutdown()
            recordingExecutor = null
        }
        isMicSilencedBySystem = false
        AudioStateNotifier.updateMicSilenced(false)

        AudioStateNotifier.updateState(AudioCaptureState.IDLE)
        AudioStateNotifier.updateMetrics(
            AudioStateNotifier.currentMetrics.copy(
                peakAmplitudePercent = 0
            )
        )
    }

    companion object {
        private const val TAG = "AudioCaptureEngine"
        private const val FRAME_SAMPLES = 1024
        private const val GAP_COMPENSATION_THRESHOLD_US = 500_000L
    }
}
