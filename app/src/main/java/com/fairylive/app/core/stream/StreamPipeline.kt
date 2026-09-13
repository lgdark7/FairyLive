package com.fairylive.app.core.stream

import android.os.SystemClock
import android.util.Log
import com.fairylive.app.core.audio.model.EncodedAudioFrame
import com.fairylive.app.core.common.MediaClock
import com.fairylive.app.core.stream.flv.AacFlvPacketizer
import com.fairylive.app.core.stream.flv.FlvMetadataBuilder
import com.fairylive.app.core.stream.flv.H264FlvPacketizer
import com.fairylive.app.core.stream.model.FlvPacket
import com.fairylive.app.core.stream.model.RtmpConnectionState
import com.fairylive.app.core.stream.model.StreamTelemetry
import com.fairylive.app.core.stream.queue.BoundedStreamQueue
import com.fairylive.app.core.stream.rtmp.RtmpClient
import com.fairylive.app.core.stream.rtmp.RtmpEndpoint
import com.fairylive.app.core.video.model.EncodedFrame
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Top-level streaming pipeline orchestrator for Fairy Live.
 * Manages FLV multiplexing, bounded queue buffering with backpressure eviction,
 * asynchronous network transmission, reconnection logic, and real-time telemetry.
 */
class StreamPipeline(
    val maxQueuePackets: Int = 90,
    val maxQueueBytes: Long = 4 * 1024 * 1024L // 4 MB
) {

    private val videoPacketizer = H264FlvPacketizer()
    private var audioPacketizer = AacFlvPacketizer()
    private val packetQueue = BoundedStreamQueue(maxQueuePackets, maxQueueBytes)
    private val rtmpClient = RtmpClient()

    private var senderThread: Thread? = null
    private val isRunning = AtomicBoolean(false)

    private var activeEndpoint: RtmpEndpoint? = null
    private var startTimeMs: Long = 0L
    var totalBytesSent: Long = 0L
        private set
    var totalPacketsSent: Long = 0L
        private set
    var videoFramesSent: Long = 0L
        private set
    var audioFramesSent: Long = 0L
        private set

    // Stage-audit counters (M6 final review: per-stage FPS trace).
    // Muxer-tag counters: FLV tags produced by the packetizers (encoder output consumers).
    private var muxedVideoTags: Long = 0L
    private var muxedAudioTags: Long = 0L
    private var lastAuditLogTime: Long = 0L
    private var auditStartElapsed: Long = 0L

    private var lastBitrateCalcTime: Long = 0L
    private var lastBytesCount: Long = 0L
    private var currentBitrateBps: Long = 0L
    private var reconnectCount: Int = 0

    @Volatile
    var isStreaming: Boolean = false
        private set

    fun start(
        endpoint: RtmpEndpoint,
        videoWidth: Int,
        videoHeight: Int,
        videoFps: Int,
        videoBitrateKbps: Int,
        audioSampleRate: Int = 44100,
        audioBitrateKbps: Int = 96,
        audioChannels: Int = 1
    ) {
        if (isRunning.get()) {
            stop()
        }

        this.activeEndpoint = endpoint
        this.audioPacketizer = AacFlvPacketizer(audioSampleRate, audioChannels)
        this.videoPacketizer.reset()
        this.packetQueue.clear()
        this.reconnectCount = 0
        this.totalBytesSent = 0L
        this.totalPacketsSent = 0L
        this.videoFramesSent = 0L
        this.audioFramesSent = 0L
        this.muxedVideoTags = 0L
        this.muxedAudioTags = 0L
        this.lastAuditLogTime = 0L
        this.auditStartElapsed = 0L
        this.currentBitrateBps = 0L
        this.lastBytesCount = 0L
        this.lastBitrateCalcTime = SystemClock.elapsedRealtime()
        this.startTimeMs = System.currentTimeMillis()

        isRunning.set(true)
        isStreaming = false

        // Inject initial metadata packet (@setDataFrame onMetaData)
        val metadataPacket = FlvMetadataBuilder.buildMetadataPacket(
            width = videoWidth,
            height = videoHeight,
            videoFps = videoFps,
            videoBitrateKbps = videoBitrateKbps,
            audioSampleRate = audioSampleRate,
            audioBitrateKbps = audioBitrateKbps,
            audioChannels = audioChannels
        )
        packetQueue.offer(metadataPacket)

        val thread = Thread({ networkLoop(endpoint) }, "FairyStreamSenderThread").apply {
            priority = Thread.NORM_PRIORITY + 1
            start()
        }
        this.senderThread = thread
    }

    private fun networkLoop(endpoint: RtmpEndpoint) {
        while (isRunning.get()) {
            try {
                rtmpClient.onStateChangedListener = { state ->
                    StreamTelemetryNotifier.updateState(state)
                    if (state == RtmpConnectionState.PUBLISHING) {
                        isStreaming = true
                    }
                }

                // Connect and publish
                rtmpClient.connect(endpoint)

                // Main packet transmission loop
                while (isRunning.get() && rtmpClient.isPublishing) {
                    val packet = packetQueue.poll(100L) ?: continue

                    val writeStart = SystemClock.elapsedRealtime()
                    rtmpClient.sendPacket(packet)
                    val latency = SystemClock.elapsedRealtime() - writeStart

                    totalBytesSent += packet.payload.size
                    totalPacketsSent++
                    if (packet.isVideo) videoFramesSent++
                    if (packet.isAudio) audioFramesSent++

                    updateTelemetry(latency)
                }
            } catch (e: Exception) {
                Log.w(TAG, "Streaming connection error: ${e.message}")
                StreamTelemetryNotifier.updateState(RtmpConnectionState.ERROR)
                StreamTelemetryNotifier.updateTelemetry(
                    StreamTelemetryNotifier.currentTelemetry.copy(
                        lastError = e.message
                    )
                )

                rtmpClient.close()
                isStreaming = false

                // Reconnection logic with exponential backoff (max 3 attempts)
                if (isRunning.get() && reconnectCount < MAX_RECONNECT_ATTEMPTS) {
                    reconnectCount++
                    val backoffDelayMs = (1000L * (1 shl (reconnectCount - 1))).coerceAtMost(8000L)
                    Log.i(TAG, "Attempting reconnect #$reconnectCount in ${backoffDelayMs}ms...")
                    StreamTelemetryNotifier.updateState(RtmpConnectionState.RECONNECTING)
                    StreamTelemetryNotifier.updateTelemetry(
                        StreamTelemetryNotifier.currentTelemetry.copy(
                            reconnectAttempts = reconnectCount
                        )
                    )

                    try {
                        Thread.sleep(backoffDelayMs)
                    } catch (_: InterruptedException) {
                        break
                    }
                } else {
                    Log.e(TAG, "Reconnection limit reached or stopped. Stream halted.")
                    val creatorError = com.fairylive.app.core.common.CreatorErrorNotifier.mapNetworkException(e)
                    com.fairylive.app.core.common.CreatorErrorNotifier.postError(
                        creatorError.title,
                        creatorError.message,
                        creatorError.resolution
                    )
                    break
                }
            }
        }

        rtmpClient.close()
        isStreaming = false
        StreamTelemetryNotifier.updateState(RtmpConnectionState.IDLE)
    }

    fun flushTelemetry(networkLatencyMs: Long = 0L) {
        updateTelemetry(networkLatencyMs, force = true)
    }

    private fun updateTelemetry(networkLatencyMs: Long, force: Boolean = false) {
        val now = SystemClock.elapsedRealtime()
        val diff = now - lastBitrateCalcTime
        if (force || diff >= 1000L) {
            val bytesInWindow = totalBytesSent - lastBytesCount
            currentBitrateBps = if (diff > 0) (bytesInWindow * 8L * 1000L) / diff else 0L
            lastBitrateCalcTime = now
            lastBytesCount = totalBytesSent

            val duration = System.currentTimeMillis() - startTimeMs

            StreamTelemetryNotifier.updateTelemetry(
                StreamTelemetry(
                    connectionState = StreamTelemetryNotifier.currentState,
                    bytesSent = totalBytesSent,
                    bitrateBps = currentBitrateBps,
                    packetsSent = totalPacketsSent,
                    videoFramesSent = videoFramesSent,
                    audioFramesSent = audioFramesSent,
                    droppedFrames = packetQueue.droppedCount.get(),
                    queueDepth = packetQueue.size,
                    networkLatencyMs = networkLatencyMs,
                    durationMs = duration,
                    reconnectAttempts = reconnectCount
                )
            )
        }
    }

    /**
     * Ingests an encoded video frame from HardwareVideoEncoder.
     */
    fun onVideoFrame(frame: EncodedFrame) {
        if (!isRunning.get()) return
        // Canonical timestamp conversion: PTS in microseconds / 1000 = FLV timestamp in milliseconds
        val ptsMs = frame.presentationTimeUs / 1000L

        val packets = videoPacketizer.processAnnexBFrame(
            annexBData = frame.data,
            ptsMs = ptsMs,
            isKeyFrameHint = frame.isKeyFrame
        )
        muxedVideoTags += packets.size

        for (p in packets) {
            packetQueue.offer(p)
        }

        emitStageAudit()
    }

    /**
     * Ingests an encoded audio frame from AacMediaCodecEncoder.
     */
    fun onAudioFrame(frame: EncodedAudioFrame) {
        if (!isRunning.get()) return
        val ptsMs = frame.presentationTimeUs / 1000L

        val packets = if (frame.isConfigFrame) {
            audioPacketizer.setAudioSpecificConfig(frame.data)
            listOf(audioPacketizer.buildAacSequenceHeader(ptsMs))
        } else {
            audioPacketizer.processAacFrame(frame.data, ptsMs)
        }
        muxedAudioTags += packets.size

        for (p in packets) {
            packetQueue.offer(p)
        }

        emitStageAudit()
    }

    fun setSpsPps(sps: ByteArray, pps: ByteArray) {
        videoPacketizer.setSpsPps(sps, pps)
    }

    /**
     * Stage-audit log (M6 final review): every ~5 s, log encoder-output (muxer tag) counts vs
     * RTMP-sent counts vs dropped counts, so the frame-rate at each stage can be traced.
     * Instrumentation only — no behavior change.
     */
    private fun emitStageAudit() {
        val now = SystemClock.elapsedRealtime()
        if (lastAuditLogTime == 0L) {
            lastAuditLogTime = now
            auditStartElapsed = now
        }
        if (now - lastAuditLogTime < 5_000L) return
        lastAuditLogTime = now
        val elapsedSec = (now - auditStartElapsed) / 1000.0
        Log.i(
            TAG,
            "STAGE-AUDIT muxedV=$muxedVideoTags muxedA=$muxedAudioTags sentV=$videoFramesSent sentA=$audioFramesSent " +
                "drops=${packetQueue.droppedCount.get()} qDepth=${packetQueue.size} elapsed=${"%.1f".format(elapsedSec)}s " +
                "muxVfps=${"%.1f".format(muxedVideoTags / elapsedSec)} muxAfps=${"%.1f".format(muxedAudioTags / elapsedSec)} " +
                "sentVfps=${"%.1f".format(videoFramesSent / elapsedSec)} sentAfps=${"%.1f".format(audioFramesSent / elapsedSec)}"
        )
    }

    fun stop() {
        if (!isRunning.get()) return

        isRunning.set(false)
        isStreaming = false
        StreamTelemetryNotifier.updateState(RtmpConnectionState.STOPPING)

        senderThread?.interrupt()
        try {
            senderThread?.join(1000L)
        } catch (_: Exception) {}
        senderThread = null

        rtmpClient.close()
        packetQueue.clear()
        videoPacketizer.reset()
        audioPacketizer.reset()

        StreamTelemetryNotifier.updateState(RtmpConnectionState.IDLE)
    }

    companion object {
        private const val TAG = "StreamPipeline"
        const val MAX_RECONNECT_ATTEMPTS = 3
    }
}
