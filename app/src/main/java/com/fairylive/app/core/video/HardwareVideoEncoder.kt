package com.fairylive.app.core.video

import android.media.MediaCodec
import android.media.MediaFormat
import android.os.Bundle
import android.os.SystemClock
import android.util.Log
import android.view.Surface
import com.fairylive.app.core.video.model.EncodedFrame
import com.fairylive.app.core.video.model.EncoderMetrics
import com.fairylive.app.core.video.model.EncoderState
import com.fairylive.app.core.video.model.VideoEncoderConfig
import java.nio.ByteBuffer
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Native hardware-accelerated video encoder using MediaCodec and Surface input.
 * High-performance, zero-copy architecture designed for low-end device efficiency.
 */
class HardwareVideoEncoder {

    fun interface EncodedFrameListener {
        fun onFrameEncoded(frame: EncodedFrame)
    }

    private var mediaCodec: MediaCodec? = null
    private var inputSurface: Surface? = null
    private var currentConfig: VideoEncoderConfig? = null
    private var codecSelection: CodecSelection? = null

    private var drainThread: Thread? = null
    private val isDraining = AtomicBoolean(false)

    private var frameListener: EncodedFrameListener? = null

    @Volatile
    var isEncoding: Boolean = false
        private set

    @Volatile
    var isPaused: Boolean = false
        private set

    private var framesEncodedCount: Long = 0L
    private var keyframesEncodedCount: Long = 0L
    private var totalBytesEncoded: Long = 0L
    private var lastMetricsCalcTime: Long = 0L
    private var lastFramesCount: Long = 0L
    private var lastBytesCount: Long = 0L
    private var currentFps: Double = 0.0
    private var currentBitrateKbps: Long = 0L

    fun setEncodedFrameListener(listener: EncodedFrameListener?) {
        this.frameListener = listener
    }

    @Synchronized
    fun initialize(config: VideoEncoderConfig): Surface {
        if (isEncoding) {
            stop()
        }

        EncoderStateNotifier.updateState(EncoderState.CONFIGURING)

        val selection = VideoEncoderCapabilityManager.findOptimalEncoder(config.mimeType)
            ?: throw IllegalStateException("No compatible video encoder found for ${config.mimeType}")
        this.codecSelection = selection

        val clampedConfig = VideoEncoderCapabilityManager.clampConfig(config, selection)
        this.currentConfig = clampedConfig

        Log.i(TAG, "Configuring MediaCodec: ${selection.codecName} (${clampedConfig.alignedWidth}x${clampedConfig.alignedHeight} @ ${clampedConfig.fps}fps, ${clampedConfig.bitrateKbps}kbps)")

        val codec = MediaCodec.createByCodecName(selection.codecName)
        val format = clampedConfig.toMediaFormat()

        codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        val surface = codec.createInputSurface()

        this.mediaCodec = codec
        this.inputSurface = surface

        this.framesEncodedCount = 0L
        this.keyframesEncodedCount = 0L
        this.totalBytesEncoded = 0L
        this.lastMetricsCalcTime = SystemClock.elapsedRealtime()
        this.lastFramesCount = 0L
        this.lastBytesCount = 0L

        EncoderStateNotifier.updateMetrics(
            EncoderMetrics(
                framesEncoded = 0L,
                keyframesEncoded = 0L,
                outputFps = 0.0,
                bitrateKbps = 0L,
                codecName = selection.codecName,
                encoderType = selection.encoderType,
                width = clampedConfig.alignedWidth,
                height = clampedConfig.alignedHeight,
                isPaused = false
            )
        )

        return surface
    }

    @Synchronized
    fun start() {
        val codec = mediaCodec ?: throw IllegalStateException("Encoder not initialized")
        if (isEncoding) return

        codec.start()
        isEncoding = true
        isPaused = false
        isDraining.set(true)

        val thread = Thread({ drainLoop() }, "FairyEncoderDrainThread").apply {
            priority = Thread.MAX_PRIORITY
            start()
        }
        this.drainThread = thread

        EncoderStateNotifier.updateState(EncoderState.ENCODING)
    }

    private fun drainLoop() {
        val codec = mediaCodec ?: return
        val bufferInfo = MediaCodec.BufferInfo()

        while (isDraining.get()) {
            val outputIndex = try {
                codec.dequeueOutputBuffer(bufferInfo, 10_000L)
            } catch (e: Exception) {
                Log.e(TAG, "Error dequeuing output buffer", e)
                EncoderStateNotifier.updateState(EncoderState.ERROR)
                break
            }

            when (outputIndex) {
                MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                    val newFormat = codec.outputFormat
                    Log.i(TAG, "Encoder output format changed: $newFormat")
                }
                MediaCodec.INFO_TRY_AGAIN_LATER -> {
                    // Timeout, keep polling
                }
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

        if (outputBuffer != null && bufferInfo.size > 0 && !isPaused) {
            outputBuffer.position(bufferInfo.offset)
            outputBuffer.limit(bufferInfo.offset + bufferInfo.size)

            val data = ByteArray(bufferInfo.size)
            outputBuffer.get(data)

            val rawPtsUs = bufferInfo.presentationTimeUs
            val sessionOriginUs = com.fairylive.app.core.common.MediaClock.sessionOriginNs / 1000L
            val normalizedPtsUs = if (sessionOriginUs > 0) {
                (rawPtsUs - sessionOriginUs).coerceAtLeast(0L)
            } else {
                rawPtsUs
            }

            val frame = EncodedFrame(
                data = data,
                presentationTimeUs = normalizedPtsUs,
                flags = bufferInfo.flags,
                size = bufferInfo.size
            )

            framesEncodedCount++
            totalBytesEncoded += bufferInfo.size
            if (frame.isKeyFrame) {
                keyframesEncodedCount++
            }

            val now = SystemClock.elapsedRealtime()
            val diff = now - lastMetricsCalcTime
            if (framesEncodedCount == 1L || diff >= 1000L) {
                val framesInWindow = framesEncodedCount - lastFramesCount
                val bytesInWindow = totalBytesEncoded - lastBytesCount
                currentFps = if (diff > 0) (framesInWindow * 1000.0) / diff else 0.0
                currentBitrateKbps = if (diff > 0) ((bytesInWindow * 8L * 1000L) / diff) / 1000L else 0L

                lastMetricsCalcTime = now
                lastFramesCount = framesEncodedCount
                lastBytesCount = totalBytesEncoded

                Log.d(TAG, "Encoded Frame #$framesEncodedCount (Keys: $keyframesEncodedCount, Size: ${frame.size}B, Live: ${currentBitrateKbps}kbps @ ${currentFps}fps)")

                EncoderStateNotifier.updateMetrics(
                    EncoderMetrics(
                        framesEncoded = framesEncodedCount,
                        keyframesEncoded = keyframesEncodedCount,
                        outputFps = currentFps,
                        bitrateKbps = currentBitrateKbps,
                        codecName = codecSelection?.codecName ?: "unknown",
                        encoderType = codecSelection?.encoderType ?: com.fairylive.app.core.capability.model.EncoderType.UNKNOWN,
                        width = currentConfig?.alignedWidth ?: 0,
                        height = currentConfig?.alignedHeight ?: 0,
                        isPaused = isPaused
                    )
                )
            }

            frameListener?.onFrameEncoded(frame)
        }

        try {
            codec.releaseOutputBuffer(index, false)
        } catch (_: Exception) {}

        if ((bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
            isDraining.set(false)
        }
    }

    fun requestKeyFrame() {
        val codec = mediaCodec ?: return
        try {
            val params = Bundle().apply {
                putInt(MediaCodec.PARAMETER_KEY_REQUEST_SYNC_FRAME, 0)
            }
            codec.setParameters(params)
            Log.i(TAG, "Requested sync keyframe from hardware encoder")
        } catch (e: Exception) {
            Log.w(TAG, "Failed to request sync frame", e)
        }
    }

    fun adjustBitrate(newBitrateKbps: Int) {
        val codec = mediaCodec ?: return
        try {
            val params = Bundle().apply {
                putInt(MediaCodec.PARAMETER_KEY_VIDEO_BITRATE, newBitrateKbps * 1000)
            }
            codec.setParameters(params)
            Log.i(TAG, "Adjusted hardware encoder bitrate to ${newBitrateKbps}kbps")
        } catch (e: Exception) {
            Log.w(TAG, "Failed to adjust bitrate dynamically", e)
        }
    }

    @Synchronized
    fun pause() {
        isPaused = true
        EncoderStateNotifier.updateState(EncoderState.PAUSED)
        EncoderStateNotifier.updateMetrics(
            EncoderStateNotifier.currentMetrics.copy(isPaused = true, outputFps = 0.0)
        )
    }

    @Synchronized
    fun resume() {
        isPaused = false
        EncoderStateNotifier.updateState(EncoderState.ENCODING)
        EncoderStateNotifier.updateMetrics(
            EncoderStateNotifier.currentMetrics.copy(isPaused = false)
        )
    }

    @Synchronized
    fun stop() {
        if (!isEncoding && mediaCodec == null) return

        EncoderStateNotifier.updateState(EncoderState.STOPPING)
        isDraining.set(false)

        drainThread?.interrupt()
        try {
            drainThread?.join(1000L)
        } catch (_: Exception) {}
        drainThread = null

        try {
            mediaCodec?.stop()
        } catch (_: Exception) {}

        try {
            mediaCodec?.release()
        } catch (_: Exception) {}
        mediaCodec = null

        inputSurface?.release()
        inputSurface = null

        isEncoding = false
        isPaused = false

        EncoderStateNotifier.updateState(EncoderState.IDLE)
        EncoderStateNotifier.updateMetrics(
            EncoderStateNotifier.currentMetrics.copy(
                outputFps = 0.0,
                isPaused = false
            )
        )
    }

    companion object {
        private const val TAG = "HardwareVideoEncoder"
    }
}
