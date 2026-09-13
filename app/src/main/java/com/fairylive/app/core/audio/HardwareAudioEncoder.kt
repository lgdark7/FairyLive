package com.fairylive.app.core.audio

import android.media.MediaCodec
import android.media.MediaFormat
import android.os.SystemClock
import android.util.Log
import com.fairylive.app.core.audio.model.AudioCaptureConfig
import com.fairylive.app.core.audio.model.EncodedAudioFrame
import java.nio.ByteBuffer
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Low-overhead AAC-LC audio encoder using native MediaCodec.
 * Converts 16-bit PCM buffers to compressed AAC streaming frames.
 */
class HardwareAudioEncoder {

    fun interface EncodedAudioFrameListener {
        fun onAudioFrameEncoded(frame: EncodedAudioFrame)
    }

    private var mediaCodec: MediaCodec? = null
    private var config: AudioCaptureConfig? = null
    private var frameListener: EncodedAudioFrameListener? = null

    private var drainThread: Thread? = null
    private val isDraining = AtomicBoolean(false)

    @Volatile
    var isEncoding: Boolean = false
        private set

    private var totalFramesEncoded: Long = 0L
    private var totalBytesEncoded: Long = 0L
    private var lastMetricsCalcTime: Long = 0L
    private var lastBytesCount: Long = 0L
    private var currentBitrateKbps: Long = 0L

    fun setEncodedAudioFrameListener(listener: EncodedAudioFrameListener?) {
        this.frameListener = listener
    }

    @Synchronized
    fun initialize(config: AudioCaptureConfig) {
        if (isEncoding) {
            stop()
        }

        this.config = config
        val format = config.toMediaFormat()

        val codec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC)
        codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)

        this.mediaCodec = codec
        this.totalFramesEncoded = 0L
        this.totalBytesEncoded = 0L
        this.lastMetricsCalcTime = SystemClock.elapsedRealtime()
        this.lastBytesCount = 0L

        Log.i(TAG, "Audio encoder initialized: ${codec.name} (${config.sampleRate}Hz, ${config.channelCount}ch, ${config.bitrateKbps}kbps)")
    }

    @Synchronized
    fun start() {
        val codec = mediaCodec ?: throw IllegalStateException("Audio encoder not initialized")
        if (isEncoding) return

        codec.start()
        isEncoding = true
        isDraining.set(true)

        val thread = Thread({ drainLoop() }, "FairyAudioDrainThread").apply {
            priority = Thread.NORM_PRIORITY
            start()
        }
        this.drainThread = thread
    }

    /**
     * Feeds raw PCM samples into the encoder input buffer.
     */
    fun queuePcm(pcmData: ByteArray, size: Int, presentationTimeUs: Long) {
        val codec = mediaCodec ?: return
        if (!isEncoding) return

        try {
            val inputIndex = codec.dequeueInputBuffer(5_000L)
            if (inputIndex >= 0) {
                val inputBuffer: ByteBuffer? = codec.getInputBuffer(inputIndex)
                if (inputBuffer != null) {
                    inputBuffer.clear()
                    inputBuffer.put(pcmData, 0, size)
                    codec.queueInputBuffer(inputIndex, 0, size, presentationTimeUs, 0)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error queuing PCM buffer into audio encoder", e)
        }
    }

    private fun drainLoop() {
        val codec = mediaCodec ?: return
        val bufferInfo = MediaCodec.BufferInfo()

        while (isDraining.get()) {
            val outputIndex = try {
                codec.dequeueOutputBuffer(bufferInfo, 10_000L)
            } catch (_: Exception) {
                break
            }

            when (outputIndex) {
                MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                    Log.i(TAG, "Audio encoder format changed: ${codec.outputFormat}")
                }
                MediaCodec.INFO_TRY_AGAIN_LATER -> {}
                else -> {
                    if (outputIndex >= 0) {
                        processOutputBuffer(codec, outputIndex, bufferInfo)
                    }
                }
            }
        }
    }

    private fun processOutputBuffer(codec: MediaCodec, index: Int, bufferInfo: MediaCodec.BufferInfo) {
        val outputBuffer: ByteBuffer? = try {
            codec.getOutputBuffer(index)
        } catch (_: Exception) {
            null
        }

        if (outputBuffer != null && bufferInfo.size > 0) {
            outputBuffer.position(bufferInfo.offset)
            outputBuffer.limit(bufferInfo.offset + bufferInfo.size)

            val data = ByteArray(bufferInfo.size)
            outputBuffer.get(data)

            val frame = EncodedAudioFrame(
                data = data,
                presentationTimeUs = bufferInfo.presentationTimeUs,
                flags = bufferInfo.flags,
                size = bufferInfo.size
            )

            totalFramesEncoded++
            totalBytesEncoded += bufferInfo.size

            val now = SystemClock.elapsedRealtime()
            val diff = now - lastMetricsCalcTime
            if (totalFramesEncoded == 1L || diff >= 1000L) {
                val bytesInWindow = totalBytesEncoded - lastBytesCount
                currentBitrateKbps = if (diff > 0) ((bytesInWindow * 8L * 1000L) / diff) / 1000L else 0L
                lastMetricsCalcTime = now
                lastBytesCount = totalBytesEncoded

                AudioStateNotifier.updateMetrics(
                    AudioStateNotifier.currentMetrics.copy(
                        encodedFrames = totalFramesEncoded,
                        bitrateKbps = currentBitrateKbps
                    )
                )
            }

            frameListener?.onAudioFrameEncoded(frame)
        }

        try {
            codec.releaseOutputBuffer(index, false)
        } catch (_: Exception) {}

        if ((bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
            isDraining.set(false)
        }
    }

    @Synchronized
    fun stop() {
        if (!isEncoding && mediaCodec == null) return

        isDraining.set(false)
        drainThread?.interrupt()
        try {
            drainThread?.join(500L)
        } catch (_: Exception) {}
        drainThread = null

        try {
            mediaCodec?.stop()
        } catch (_: Exception) {}

        try {
            mediaCodec?.release()
        } catch (_: Exception) {}
        mediaCodec = null

        isEncoding = false
    }

    companion object {
        private const val TAG = "HardwareAudioEncoder"
    }
}
