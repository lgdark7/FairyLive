package com.fairylive.app.core.stream

import android.os.SystemClock
import android.util.Log
import com.fairylive.app.core.audio.model.EncodedAudioFrame
import com.fairylive.app.core.common.MediaClock
import com.fairylive.app.core.network.AdaptiveBitrateController
import com.fairylive.app.core.network.BitrateChangeReason
import com.fairylive.app.core.network.NetworkHealth
import com.fairylive.app.core.network.NetworkQualityMonitor
import com.fairylive.app.core.network.StreamReconnectionManager
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
 * Top-level streaming pipeline orchestrator for Fairy Live (Milestone 9).
 * Manages FLV multiplexing, bounded queue buffering with backpressure eviction,
 * real-time network quality monitoring, adaptive bitrate (ABR) control,
 * bounded exponential-backoff reconnection, and non-blocking RTMP transmission.
 */
class StreamPipeline(
    val maxQueuePackets: Int = 90,
    val maxQueueBytes: Long = 4 * 1024 * 1024L // 4 MB memory ceiling
) {
    private val videoPacketizer = H264FlvPacketizer()
    private var audioPacketizer = AacFlvPacketizer()
    private val packetQueue = BoundedStreamQueue(maxQueuePackets, maxQueueBytes)
    private val rtmpClient = RtmpClient()

    // Network Resilience Engines
    val networkMonitor = NetworkQualityMonitor()
    val abrController = AdaptiveBitrateController()
    val reconnectionManager = StreamReconnectionManager()

    // Dynamic encoder callbacks
    var onAdjustBitrate: ((newBitrateKbps: Int) -> Unit)? = null
    var onRequestKeyFrame: (() -> Unit)? = null

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

    // Stream metadata cache for clean reconnection recovery
    private var streamWidth: Int = 480
    private var streamHeight: Int = 1056
    private var streamFps: Int = 24
    private var streamVideoBitrateKbps: Int = 1200
    private var streamAudioSampleRate: Int = 44100
    private var streamAudioBitrateKbps: Int = 96
    private var streamAudioChannels: Int = 1

    /** Diagnostic accessors (M8/M9 verification audit). */
    val packetQueueDropped: Long get() = packetQueue.droppedCount.get()
    val muxedVideoTagCount: Long get() = muxedVideoTags
    val muxedAudioTagCount: Long get() = muxedAudioTags

    // Stage-audit counters
    private var muxedVideoTags: Long = 0L
    private var muxedAudioTags: Long = 0L
    private var lastAuditLogTime: Long = 0L
    private var auditStartElapsed: Long = 0L

    private var lastBitrateCalcTime: Long = 0L
    private var lastBytesCount: Long = 0L
    private var currentBitrateBps: Long = 0L
    private var lastAbrEvalTime: Long = 0L

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
        this.streamWidth = videoWidth
        this.streamHeight = videoHeight
        this.streamFps = videoFps
        this.streamVideoBitrateKbps = videoBitrateKbps
        this.streamAudioSampleRate = audioSampleRate
        this.streamAudioBitrateKbps = audioBitrateKbps
        this.streamAudioChannels = audioChannels

        this.audioPacketizer = AacFlvPacketizer(audioSampleRate, audioChannels)
        this.videoPacketizer.reset()
        this.packetQueue.clear()
        this.networkMonitor.reset()
        this.reconnectionManager.reset()

        // Initialize ABR controller
        this.abrController.initialize(videoBitrateKbps)
        this.abrController.onBitrateAdjustmentListener = { decision ->
            Log.i(TAG, "ABR Decision: ${decision.oldBitrateKbps} -> ${decision.newBitrateKbps} kbps (reason=${decision.reason})")
            onAdjustBitrate?.invoke(decision.newBitrateKbps)
            if (decision.requestKeyFrame) {
                onRequestKeyFrame?.invoke()
            }
        }

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
        this.lastAbrEvalTime = SystemClock.elapsedRealtime()
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
                        networkMonitor.setRtmpConnected(true)
                    }
                }

                // Connect and publish
                rtmpClient.connect(endpoint)
                isStreaming = true
                networkMonitor.setRtmpConnected(true)
                reconnectionManager.onConnectionRestored()

                // If this was a reconnection, queue fresh metadata and sequence headers
                if (reconnectionManager.attemptCount > 0) {
                    val metadataPacket = FlvMetadataBuilder.buildMetadataPacket(
                        width = streamWidth,
                        height = streamHeight,
                        videoFps = streamFps,
                        videoBitrateKbps = abrController.currentBitrateKbps,
                        audioSampleRate = streamAudioSampleRate,
                        audioBitrateKbps = streamAudioBitrateKbps,
                        audioChannels = streamAudioChannels
                    )
                    packetQueue.offer(metadataPacket)
                    packetQueue.offer(audioPacketizer.buildAacSequenceHeader(MediaClock.currentSessionTimeUs() / 1000L))
                    onRequestKeyFrame?.invoke()
                    Log.i(TAG, "Reconnected: re-injected metadata and requested sync keyframe")
                }

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

                    // Zero-allocation hot path telemetry recording
                    networkMonitor.recordPacketWrite(
                        bytesWritten = packet.payload.size,
                        writeDurationMs = latency,
                        queueDepth = packetQueue.size,
                        queueMaxCapacity = maxQueuePackets,
                        droppedCount = packetQueue.droppedCount.get()
                    )

                    // Periodic ABR evaluation and telemetry update (every ~1000ms)
                    val now = SystemClock.elapsedRealtime()
                    if (now - lastAbrEvalTime >= 1000L) {
                        lastAbrEvalTime = now
                        val decision = abrController.evaluate(networkMonitor.currentTelemetry, now)
                        if (decision != null && decision.newBitrateKbps != decision.oldBitrateKbps) {
                            onAdjustBitrate?.invoke(decision.newBitrateKbps)
                            if (decision.requestKeyFrame) {
                                onRequestKeyFrame?.invoke()
                            }
                        }
                        reconnectionManager.checkStability(now)
                        updateTelemetry(latency)
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "Streaming connection interrupted: ${e.message}")
                networkMonitor.recordWriteFailure(e)
                networkMonitor.setRtmpConnected(false)
                rtmpClient.close()
                isStreaming = false

                // Strictly prevent false LIVE reporting
                StreamTelemetryNotifier.updateState(RtmpConnectionState.DISCONNECTED)
                StreamTelemetryNotifier.updateTelemetry(
                    StreamTelemetryNotifier.currentTelemetry.copy(
                        connectionState = RtmpConnectionState.DISCONNECTED,
                        lastError = e.message
                    )
                )

                // Schedule bounded exponential backoff reconnection
                val delayMs = reconnectionManager.scheduleNextAttempt(e.message ?: "Socket disconnect")
                if (isRunning.get() && delayMs != null) {
                    Log.i(TAG, "Attempting reconnect #${reconnectionManager.attemptCount}/${reconnectionManager.maxAttempts} in ${delayMs}ms...")
                    StreamTelemetryNotifier.updateState(RtmpConnectionState.RECONNECTING)
                    StreamTelemetryNotifier.updateTelemetry(
                        StreamTelemetryNotifier.currentTelemetry.copy(
                            connectionState = RtmpConnectionState.RECONNECTING,
                            reconnectAttempts = reconnectionManager.attemptCount
                        )
                    )

                    try {
                        Thread.sleep(delayMs)
                    } catch (_: InterruptedException) {
                        break
                    }
                    reconnectionManager.onAttemptStarted()
                } else {
                    Log.e(TAG, "Reconnection limit reached or stopped. Halting stream.")
                    StreamTelemetryNotifier.updateState(RtmpConnectionState.FAILED)
                    StreamTelemetryNotifier.updateTelemetry(
                        StreamTelemetryNotifier.currentTelemetry.copy(
                            connectionState = RtmpConnectionState.FAILED,
                            lastError = "Reconnection exhausted: ${e.message}"
                        )
                    )

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
        networkMonitor.setRtmpConnected(false)
        if (StreamTelemetryNotifier.currentState != RtmpConnectionState.FAILED) {
            StreamTelemetryNotifier.updateState(RtmpConnectionState.IDLE)
        }
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
                    reconnectAttempts = reconnectionManager.attemptCount,
                    activeBitrateKbps = abrController.currentBitrateKbps,
                    networkHealth = networkMonitor.currentHealth.name
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
     * Stage-audit log: traces encoder output vs RTMP sent counts vs queue drops.
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
                "sentVfps=${"%.1f".format(videoFramesSent / elapsedSec)} sentAfps=${"%.1f".format(audioFramesSent / elapsedSec)} " +
                "abr=${abrController.currentBitrateKbps}kbps health=${networkMonitor.currentHealth}"
        )
    }

    fun stop() {
        if (!isRunning.get()) return

        isRunning.set(false)
        isStreaming = false
        networkMonitor.setRtmpConnected(false)
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
        networkMonitor.unregister()
        networkMonitor.reset()
        reconnectionManager.reset()

        StreamTelemetryNotifier.updateState(RtmpConnectionState.IDLE)
    }

    companion object {
        private const val TAG = "StreamPipeline"
    }
}
