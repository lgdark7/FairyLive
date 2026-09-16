package com.fairylive.app.core.audio

import android.annotation.SuppressLint
import android.media.AudioRecord
import android.media.MediaRecorder
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
 * Supports microphone capture, internal device audio capture (Android 10+),
 * mute with timestamp continuity, and drift-free monotonic PTS generation.
 */
class AudioCaptureEngine {

    /** PCM buffers not dispatched because no listener was attached (diagnostic only). */
    val pcmDroppedCount = LongAdder()

    fun interface AudioSampleListener {
        fun onAudioSample(data: ByteArray, size: Int, presentationTimeUs: Long)
    }

    private var audioRecord: AudioRecord? = null
    private var config: AudioCaptureConfig = AudioCaptureConfig()
    private var sampleListener: AudioSampleListener? = null

    private var captureThread: Thread? = null
    private val isCapturing = AtomicBoolean(false)

    @Volatile
    var isMuted: Boolean = false
        private set

    private var totalSamplesRead: Long = 0L
    private var totalSilenceFramesInserted: Long = 0L
    private var startTimestampUs: Long = 0L
    private var lastAmplitudeUpdateTime: Long = 0L

    fun getSilenceFramesInserted(): Long = totalSilenceFramesInserted
    fun getTotalSamplesProduced(): Long = totalSamplesRead

    fun setAudioSampleListener(listener: AudioSampleListener?) {
        this.sampleListener = listener
    }

    /**
     * Initializes and starts AudioRecord capture.
     * Selects internal device playback capture if [mediaProjection] is provided on Android 10+,
     * otherwise falls back cleanly to microphone capture.
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

        var record: AudioRecord? = null
        var isDeviceAudio = false

        // Attempt internal device audio capture if supported and requested
        if (config.sourceType == AudioSourceType.INTERNAL_PLAYBACK &&
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q &&
            mediaProjection != null
        ) {
            record = AudioPlaybackCaptureHelper.createPlaybackCaptureRecord(mediaProjection, config)
            if (record != null) {
                isDeviceAudio = true
                Log.i(TAG, "Initialized AudioPlaybackCapture (Internal Device Audio)")
            }
        }

        // Fallback to microphone capture
        if (record == null) {
            try {
                val bufferSize = config.minBufferSize
                record = AudioRecord(
                    MediaRecorder.AudioSource.MIC,
                    config.sampleRate,
                    config.channelConfig,
                    config.audioFormat,
                    bufferSize
                )
                isDeviceAudio = false
                Log.i(TAG, "Initialized Standard Microphone AudioRecord (${config.sampleRate}Hz, buffer: $bufferSize)")
            } catch (se: SecurityException) {
                Log.e(TAG, "Audio recording permission denied by system: ${se.message}")
                AudioStateNotifier.updateState(AudioCaptureState.ERROR)
                return
            } catch (e: Exception) {
                Log.e(TAG, "Failed to instantiate AudioRecord: ${e.message}")
                AudioStateNotifier.updateState(AudioCaptureState.ERROR)
                return
            }
        }

        if (record.state != AudioRecord.STATE_INITIALIZED) {
            record.release()
            AudioStateNotifier.updateState(AudioCaptureState.ERROR)
            Log.e(TAG, "AudioRecord failed to initialize (State: ${record.state})")
            return
        }

        this.audioRecord = record
        this.totalSamplesRead = 0L
        this.totalSilenceFramesInserted = 0L
        val nowNs = com.fairylive.app.core.common.MediaClock.nanoTimeSource()
        this.startTimestampUs = com.fairylive.app.core.common.MediaClock.toSessionPtsUs(nowNs)
        this.lastAmplitudeUpdateTime = SystemClock.elapsedRealtime()

        record.startRecording()
        isCapturing.set(true)

        val thread = Thread({ captureLoop(record, isDeviceAudio) }, "FairyAudioRecordThread").apply {
            priority = Thread.MAX_PRIORITY
            start()
        }
        this.captureThread = thread

        val initialState = if (isMuted) AudioCaptureState.MUTED else AudioCaptureState.RECORDING
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

    private fun captureLoop(record: AudioRecord, isDeviceAudio: Boolean) {
        // Pre-allocated reusable buffer to guarantee zero allocations in the capture loop
        val buffer = ByteArray(config.minBufferSize)
        val silenceBuffer = ByteArray(config.minBufferSize) // Pre-allocated reusable zeroed buffer for gap bridging
        val bytesPerFrame = config.bytesPerFrame

        while (isCapturing.get()) {
            val bytesRead = record.read(buffer, 0, buffer.size)

            if (bytesRead > 0) {
                val framesRead = bytesRead / bytesPerFrame
                val bufferDurationUs = (framesRead * 1_000_000L) / config.sampleRate

                // Compute wall clock session PTS
                val nowNs = com.fairylive.app.core.common.MediaClock.nanoTimeSource()
                val wallClockPtsUs = (com.fairylive.app.core.common.MediaClock.toSessionPtsUs(nowNs) - bufferDurationUs).coerceAtLeast(0L)

                if (totalSamplesRead == 0L) {
                    startTimestampUs = wallClockPtsUs
                }

                // Continuous sample-count accumulation for jitter-free micro-spacing
                val accumulatedPtsUs = startTimestampUs + (totalSamplesRead * 1_000_000L) / config.sampleRate

                // Explicit Gap Handling & Loss Prevention:
                // If audio HAL stalls or delays delivering PCM chunks, the real-world wall clock
                // moves ahead while accumulatedPtsUs falls behind. Instead of jumping startTimestampUs
                // (which drops audio content and causes PTS discontinuities), we explicitly synthesize
                // timestamp-consistent silence PCM frames to bridge the gap.
                val driftUs = wallClockPtsUs - accumulatedPtsUs
                if (driftUs > GAP_COMPENSATION_THRESHOLD_US) {
                    val missingFrames = (driftUs * config.sampleRate) / 1_000_000L
                    // Bound gap filling to max 3.0 seconds per stall to prevent thread starvation
                    val cappedMissingFrames = missingFrames.coerceAtMost(config.sampleRate * 3L)
                    android.util.Log.i("AudioCaptureEngine", "Audio HAL gap detected: ${driftUs / 1000}ms. Synthesizing $cappedMissingFrames silence frames.")
                    var framesToBridge = cappedMissingFrames
                    val silenceChunkFrames = (silenceBuffer.size / bytesPerFrame).toLong()

                    while (framesToBridge > 0L && isCapturing.get()) {
                        val chunkFrames = kotlin.math.min(framesToBridge, silenceChunkFrames).toInt()
                        val chunkBytes = chunkFrames * bytesPerFrame
                        val silencePtsUs = startTimestampUs + (totalSamplesRead * 1_000_000L) / config.sampleRate
                        totalSamplesRead += chunkFrames
                        totalSilenceFramesInserted += chunkFrames
                        framesToBridge -= chunkFrames

                        val listener = sampleListener
                        if (listener != null) {
                            listener.onAudioSample(silenceBuffer, chunkBytes, silencePtsUs)
                        } else {
                            pcmDroppedCount.increment()
                        }
                    }

                    // If drift exceeded the 3-second ceiling, re-anchor the remainder to prevent runaway lag
                    val residualDriftUs = driftUs - ((cappedMissingFrames * 1_000_000L) / config.sampleRate)
                    if (residualDriftUs > GAP_COMPENSATION_THRESHOLD_US) {
                        startTimestampUs += residualDriftUs
                    }
                }

                val currentPtsUs = startTimestampUs + (totalSamplesRead * 1_000_000L) / config.sampleRate
                totalSamplesRead += framesRead

                // When muted, zero out PCM buffer but dispatch samples to maintain audio/video PTS sync
                if (isMuted) {
                    buffer.fill(0, 0, bytesRead)
                }

                // Compute peak amplitude for UI meters
                val now = SystemClock.elapsedRealtime()
                if (now - lastAmplitudeUpdateTime >= 100L) {
                    lastAmplitudeUpdateTime = now
                    val amplitudePercent = if (isMuted) 0 else calculateRmsAmplitude(buffer, bytesRead)
                    AudioStateNotifier.updateMetrics(
                        AudioStateNotifier.currentMetrics.copy(
                            samplesRead = totalSamplesRead,
                            silenceFramesInserted = totalSilenceFramesInserted,
                            peakAmplitudePercent = amplitudePercent,
                            isMuted = isMuted,
                            isDeviceAudio = isDeviceAudio
                        )
                    )
                }

                val listener = sampleListener
                if (listener != null) {
                    listener.onAudioSample(buffer, bytesRead, currentPtsUs)
                } else {
                    pcmDroppedCount.increment()
                }
            } else if (bytesRead < 0) {
                Log.w(TAG, "AudioRecord read error: $bytesRead")
                if (bytesRead == AudioRecord.ERROR_INVALID_OPERATION) {
                    break
                }
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
        AudioStateNotifier.updateState(AudioCaptureState.RECORDING)
        AudioStateNotifier.updateMetrics(
            AudioStateNotifier.currentMetrics.copy(isMuted = false)
        )
    }

    @Synchronized
    fun stop() {
        if (!isCapturing.get() && audioRecord == null) return

        AudioStateNotifier.updateState(AudioCaptureState.STOPPING)
        isCapturing.set(false)

        captureThread?.interrupt()
        try {
            captureThread?.join(500L)
        } catch (_: Exception) {}
        captureThread = null

        try {
            audioRecord?.stop()
        } catch (_: Exception) {}

        try {
            audioRecord?.release()
        } catch (_: Exception) {}
        audioRecord = null

        AudioStateNotifier.updateState(AudioCaptureState.IDLE)
        AudioStateNotifier.updateMetrics(
            AudioStateNotifier.currentMetrics.copy(
                peakAmplitudePercent = 0
            )
        )
    }

    companion object {
        private const val TAG = "AudioCaptureEngine"
        private const val GAP_COMPENSATION_THRESHOLD_US = 40_000L
    }
}
