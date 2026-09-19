package com.fairylive.app.core.audio

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.media.MediaFormat
import android.os.Build
import android.os.SystemClock
import android.util.Log
import com.fairylive.app.core.audio.model.AudioCaptureConfig
import com.fairylive.app.core.audio.model.EncodedAudioFrame
import com.fairylive.app.core.capability.model.EncoderType
import java.nio.ByteBuffer
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.LongAdder

data class AudioCodecSelection(
    val codecName: String,
    val encoderType: EncoderType,
    val maxBitrateBps: Int
) {
    val isHardwareAccelerated: Boolean
        get() = encoderType == EncoderType.HARDWARE

    val isSoftwareOnly: Boolean
        get() = encoderType == EncoderType.SOFTWARE
}

/**
 * AAC audio encoder using native Android MediaCodec.
 * Supports hardware AAC encoders if exposed by device SoC, with transparent
 * fallback to platform software Codec2/OMX codecs (e.g. c2.android.aac.encoder).
 */
class AacMediaCodecEncoder {

    /** Count of PCM buffers dropped because dequeueInputBuffer timed out (diagnostic only). */
    val inputTimeoutCount = LongAdder()

    fun interface EncodedAudioFrameListener {
        fun onAudioFrameEncoded(frame: EncodedAudioFrame)
    }

    private var mediaCodec: MediaCodec? = null
    private var config: AudioCaptureConfig? = null
    private var frameListener: EncodedAudioFrameListener? = null
    var onAudioSpecificConfigAvailable: ((ByteArray) -> Unit)? = null
    var codecSelection: AudioCodecSelection? = null
        private set

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
        val selection = findOptimalAudioEncoder()
        this.codecSelection = selection

        val format = config.toMediaFormat()
        val codec = MediaCodec.createByCodecName(selection.codecName)
        codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)

        this.mediaCodec = codec
        this.totalFramesEncoded = 0L
        this.totalBytesEncoded = 0L
        this.lastMetricsCalcTime = SystemClock.elapsedRealtime()
        this.lastBytesCount = 0L

        Log.i(TAG, "AAC Audio encoder initialized: ${selection.codecName} [${selection.encoderType}] (${config.sampleRate}Hz, ${config.channelCount}ch, ${config.bitrateKbps}kbps)")

        AudioStateNotifier.updateMetrics(
            AudioStateNotifier.currentMetrics.copy(
                sampleRate = config.sampleRate,
                channels = config.channelCount,
                bitrateKbps = config.bitrateKbps.toLong(),
                encoderName = selection.codecName,
                encoderType = selection.encoderType
            )
        )
    }

    private fun findOptimalAudioEncoder(): AudioCodecSelection {
        val candidates = mutableListOf<AudioCodecSelection>()
        try {
            val codecList = MediaCodecList(MediaCodecList.REGULAR_CODECS)
            for (info in codecList.codecInfos) {
                if (!info.isEncoder) continue
                val hasAac = info.supportedTypes.any { it.equals(MediaFormat.MIMETYPE_AUDIO_AAC, ignoreCase = true) }
                if (!hasAac) continue

                val encoderType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    when {
                        info.isHardwareAccelerated -> EncoderType.HARDWARE
                        info.isSoftwareOnly -> EncoderType.SOFTWARE
                        !info.isVendor && (info.name.startsWith("c2.android.") || info.name.startsWith("OMX.google.")) -> EncoderType.SOFTWARE
                        else -> EncoderType.UNKNOWN
                    }
                } else {
                    val lower = info.name.lowercase()
                    when {
                        lower.startsWith("omx.google.") || lower.startsWith("c2.android.") -> EncoderType.SOFTWARE
                        lower.startsWith("omx.qcom.") || lower.startsWith("c2.qti.") || lower.startsWith("omx.exynos.") -> EncoderType.HARDWARE
                        else -> EncoderType.UNKNOWN
                    }
                }

                var maxBitrate = 512000
                try {
                    val caps = info.getCapabilitiesForType(MediaFormat.MIMETYPE_AUDIO_AAC)
                    caps.audioCapabilities?.bitrateRange?.let { maxBitrate = it.upper }
                } catch (_: Exception) {}

                candidates.add(
                    AudioCodecSelection(
                        codecName = info.name,
                        encoderType = encoderType,
                        maxBitrateBps = maxBitrate
                    )
                )
            }
        } catch (_: Exception) {}

        // Priority: 1. Hardware AAC -> 2. Unknown -> 3. Software Codec2 -> 4. Software OMX -> 5. Fallback
        return candidates.firstOrNull { it.encoderType == EncoderType.HARDWARE }
            ?: candidates.firstOrNull { it.encoderType == EncoderType.UNKNOWN }
            ?: candidates.firstOrNull { it.codecName.startsWith("c2.android.") }
            ?: candidates.firstOrNull()
            ?: AudioCodecSelection("c2.android.aac.encoder", EncoderType.SOFTWARE, 510000)
    }

    @Synchronized
    fun start() {
        val codec = mediaCodec ?: throw IllegalStateException("Audio encoder not initialized")
        if (isEncoding) return

        codec.start()
        isEncoding = true
        isDraining.set(true)

        val thread = Thread({ drainLoop() }, "FairyAacDrainThread").apply {
            priority = Thread.NORM_PRIORITY
            start()
        }
        this.drainThread = thread
    }

    /**
     * Feeds raw PCM samples into the encoder input buffer with canonical presentation timestamp.
     */
    fun queuePcm(pcmData: ByteArray, size: Int, presentationTimeUs: Long): Boolean {
        if (!isEncoding) return false
        val codec = mediaCodec ?: return false

        for (attempt in 0..4) {
            try {
                val inputIndex = codec.dequeueInputBuffer(5_000L)
                if (inputIndex >= 0) {
                    val inputBuffer: ByteBuffer? = codec.getInputBuffer(inputIndex)
                    if (inputBuffer != null) {
                        inputBuffer.clear()
                        inputBuffer.put(pcmData, 0, size)
                        codec.queueInputBuffer(inputIndex, 0, size, presentationTimeUs, 0)
                        return true
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error queuing PCM buffer into audio encoder", e)
                return false
            }
        }
        inputTimeoutCount.increment()
        return false
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
                    val newFormat = codec.outputFormat
                    Log.i(TAG, "Audio encoder format changed: $newFormat")
                    try {
                        val csd0 = newFormat.getByteBuffer("csd-0")
                        if (csd0 != null) {
                            val config = ByteArray(csd0.remaining())
                            csd0.get(config)
                            Log.i(TAG, "Extracted AudioSpecificConfig (${config.size}B) from csd-0")
                            onAudioSpecificConfigAvailable?.invoke(config)
                        }
                    } catch (e: Exception) {
                        Log.w(TAG, "Failed to extract csd-0 from audio format", e)
                    }
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

            if (totalFramesEncoded == 1L) {
                com.fairylive.app.core.lifecycle.StreamLifecycleDiagnostics.firstAudioFrame(bufferInfo.presentationTimeUs)
            }

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
                        bitrateKbps = currentBitrateKbps,
                        encoderName = codecSelection?.codecName ?: "unknown",
                        encoderType = codecSelection?.encoderType ?: EncoderType.UNKNOWN
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
        private const val TAG = "AacMediaCodecEncoder"
    }
}
