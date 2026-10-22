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
    internal val packetQueueRef: BoundedStreamQueue get() = packetQueue
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

    // High-resolution sliding window byte rate trackers
    private class ByteRateWindow(val windowMs: Long) {
        private val samples = ArrayDeque<Pair<Long, Int>>()
        private var totalBytes: Long = 0L

        fun add(bytes: Int, nowMs: Long) {
            samples.addLast(Pair(nowMs, bytes))
            totalBytes += bytes
            while (samples.isNotEmpty() && nowMs - samples.first().first > windowMs) {
                totalBytes -= samples.removeFirst().second
            }
        }

        fun getRateBps(nowMs: Long): Long {
            while (samples.isNotEmpty() && nowMs - samples.first().first > windowMs) {
                totalBytes -= samples.removeFirst().second
            }
            return if (samples.isEmpty()) 0L else (totalBytes * 8L * 1000L) / windowMs
        }

        fun reset() {
            samples.clear()
            totalBytes = 0L
        }
    }

    private val window5s = ByteRateWindow(5_000L)
    private val window10s = ByteRateWindow(10_000L)
    private val window30s = ByteRateWindow(30_000L)
    private var lastKeyframeTimeMs: Long = 0L
    private var socketWriteStalls: Long = 0L

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
        val existingSps = this.videoPacketizer.cachedSps
        val existingPps = this.videoPacketizer.cachedPps
        this.videoPacketizer.reset()
        if (existingSps != null && existingPps != null) {
            this.videoPacketizer.setSpsPps(existingSps, existingPps)
        }
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
        // Inject initial AAC sequence header (timestamp 0)
        packetQueue.offer(audioPacketizer.buildAacSequenceHeader(0L))

        // Inject initial AVC sequence header (timestamp 0) if SPS/PPS are available
        val sps = videoPacketizer.cachedSps
        val pps = videoPacketizer.cachedPps
        if (sps != null && pps != null && !videoPacketizer.isHeaderGenerated) {
            val header = videoPacketizer.buildAvcSequenceHeader(sps, pps, 0L)
            videoPacketizer.markHeaderGenerated()
            packetQueue.offer(header)
            onRequestKeyFrame?.invoke()
            Log.i(TAG, "Queued initial AVC Sequence Header (timestamp 0) at pipeline start")
        }

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
                    packetQueue.offer(audioPacketizer.buildAacSequenceHeader(0L))
                    val sps = videoPacketizer.cachedSps
                    val pps = videoPacketizer.cachedPps
                    if (sps != null && pps != null) {
                        packetQueue.offer(videoPacketizer.buildAvcSequenceHeader(sps, pps, 0L))
                        videoPacketizer.markHeaderGenerated()
                    }
                    onRequestKeyFrame?.invoke()
                    Log.i(TAG, "Reconnected: re-injected metadata, sequence headers, and requested sync keyframe")
                }

                // Main packet transmission loop
                while (isRunning.get() && rtmpClient.isPublishing) {
                    val packet = packetQueue.poll(100L) ?: continue

                    val writeStart = SystemClock.elapsedRealtime()
                    rtmpClient.sendPacket(packet)
                    val latency = SystemClock.elapsedRealtime() - writeStart
                    if (latency >= 100L) {
                        socketWriteStalls++
                    }
                    val nowWrite = SystemClock.elapsedRealtime()
                    window5s.add(packet.payload.size, nowWrite)
                    window10s.add(packet.payload.size, nowWrite)
                    window30s.add(packet.payload.size, nowWrite)

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

                val isFatalAuth = e is com.fairylive.app.core.stream.rtmp.RtmpPublishRejectedException ||
                        (e.message?.contains("rejected", ignoreCase = true) == true) ||
                        (e.message?.contains("BadName", ignoreCase = true) == true) ||
                        (e.message?.contains("Denied", ignoreCase = true) == true)

                // Strictly prevent false LIVE reporting
                StreamTelemetryNotifier.updateState(RtmpConnectionState.DISCONNECTED)
                StreamTelemetryNotifier.updateTelemetry(
                    StreamTelemetryNotifier.currentTelemetry.copy(
                        connectionState = RtmpConnectionState.DISCONNECTED,
                        lastError = e.message
                    )
                )

                if (isFatalAuth) {
                    Log.e(TAG, "YouTube / RTMP server permanently rejected publish. Halting stream without retry.")
                    com.fairylive.app.core.lifecycle.StreamLifecycleDiagnostics.streamStopReason(
                        com.fairylive.app.core.lifecycle.StreamStopReason.STOP_REASON_YOUTUBE_REJECTED,
                        e.message ?: "YouTube rejected stream key"
                    )
                    StreamTelemetryNotifier.updateState(RtmpConnectionState.FAILED)
                    StreamTelemetryNotifier.updateTelemetry(
                        StreamTelemetryNotifier.currentTelemetry.copy(
                            connectionState = RtmpConnectionState.FAILED,
                            lastError = "YouTube rejected stream: ${e.message}"
                        )
                    )
                    com.fairylive.app.core.common.CreatorErrorNotifier.postError(
                        title = "YouTube Stream Key Rejected",
                        message = "YouTube rejected the stream key: ${e.message}. Please verify your stream key in Settings.",
                        resolution = "Copy your stream key from YouTube Studio and update Settings."
                    )
                    break
                }

                if (!isRunning.get()) {
                    Log.i(TAG, "StreamPipeline stopped intentionally, exiting network loop.")
                    break
                }

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
                    com.fairylive.app.core.lifecycle.StreamLifecycleDiagnostics.streamStopReason(
                        com.fairylive.app.core.lifecycle.StreamStopReason.STOP_REASON_RTMP_ERROR,
                        e.message ?: "Reconnection exhausted"
                    )
                    StreamTelemetryNotifier.updateState(RtmpConnectionState.FAILED)
                    StreamTelemetryNotifier.updateTelemetry(
                        StreamTelemetryNotifier.currentTelemetry.copy(
                            connectionState = RtmpConnectionState.FAILED,
                            lastError = "Reconnection exhausted: ${e.message}"
                        )
                    )

                    val creatorError = com.fairylive.app.core.common.CreatorErrorNotifier.mapNetworkException(
                        e = e,
                        host = endpoint.host,
                        port = endpoint.port,
                        appName = endpoint.appName,
                        streamKey = endpoint.streamKey
                    )
                    com.fairylive.app.core.common.CreatorErrorNotifier.postError(
                        title = creatorError.title,
                        message = creatorError.message,
                        resolution = creatorError.resolution,
                        diagnosticInfo = creatorError.diagnosticInfo
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
        val now = SystemClock.elapsedRealtime()
        if (frame.isKeyFrame) {
            lastKeyframeTimeMs = now
        }
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
            emptyList()
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
        if (isRunning.get() && !videoPacketizer.isHeaderGenerated) {
            val header = videoPacketizer.buildAvcSequenceHeader(sps, pps, 0L)
            videoPacketizer.markHeaderGenerated()
            packetQueue.offer(header)
            onRequestKeyFrame?.invoke()
            Log.i(TAG, "Queued initial AVC Sequence Header (timestamp 0) from csd-0/csd-1")
        }
    }

    fun setAudioSpecificConfig(config: ByteArray) {
        audioPacketizer.setAudioSpecificConfig(config)
        val header = audioPacketizer.buildAacSequenceHeader(0L)
        packetQueue.offer(header)
        Log.i(TAG, "Queued initial AAC Sequence Header (timestamp 0) from csd-0")
    }

    val queueEvictions: Long get() = packetQueue.queueEvictions.get()
    val deltaFramesEvicted: Long get() = packetQueue.deltaFramesEvicted.get()
    val keyframesEvicted: Long get() = packetQueue.keyframesEvicted.get()
    val unbufferedDrops: Long get() = packetQueue.unbufferedDrops.get()

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
        val rtmpBps5s = window5s.getRateBps(now)
        val rtmpBps10s = window10s.getRateBps(now)
        val rtmpBps30s = window30s.getRateBps(now)
        val rtmpKbps5s = rtmpBps5s / 1000L
        val rtmpKbps10s = rtmpBps10s / 1000L
        val rtmpKbps30s = rtmpBps30s / 1000L
        val kfInterval = if (lastKeyframeTimeMs > 0) now - lastKeyframeTimeMs else 0L

        Log.i(
            TAG,
            "[BITRATE-AUDIT] encoderBitrate=${abrController.currentBitrateKbps}kbps " +
                "rtmp5s=${rtmpKbps5s}kbps rtmp10s=${rtmpKbps10s}kbps rtmp30s=${rtmpKbps30s}kbps " +
                "muxV=$muxedVideoTags muxA=$muxedAudioTags sentV=$videoFramesSent sentA=$audioFramesSent " +
                "qDepth=${packetQueue.size}/$maxQueuePackets socketStalls=$socketWriteStalls " +
                "reconnects=${reconnectionManager.attemptCount} drops=${packetQueue.droppedCount.get()} " +
                "kfInterval=${kfInterval}ms health=${networkMonitor.currentHealth}"
        )
        Log.i(
            TAG,
            "STAGE-AUDIT muxedV=$muxedVideoTags muxedA=$muxedAudioTags sentV=$videoFramesSent sentA=$audioFramesSent " +
                "drops=${packetQueue.droppedCount.get()} qEvict=${packetQueue.queueEvictions.get()} deltaEvict=${packetQueue.deltaFramesEvicted.get()} " +
                "keyEvict=${packetQueue.keyframesEvicted.get()} unbufDrop=${packetQueue.unbufferedDrops.get()} " +
                "qDepth=${packetQueue.size} elapsed=${"%.1f".format(elapsedSec)}s " +
                "muxVfps=${"%.1f".format(muxedVideoTags / elapsedSec)} muxAfps=${"%.1f".format(muxedAudioTags / elapsedSec)} " +
                "sentVfps=${"%.1f".format(videoFramesSent / elapsedSec)} sentAfps=${"%.1f".format(audioFramesSent / elapsedSec)} " +
                "abr=${abrController.currentBitrateKbps}kbps health=${networkMonitor.currentHealth}"
        )
        Log.i(TAG, "STAGE-TRACE: VIDEO_PACKETS_SENT [count=$videoFramesSent]")
        Log.i(TAG, "STAGE-TRACE: AUDIO_PACKETS_SENT [count=$audioFramesSent]")
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
        StreamTelemetryNotifier.updateState(RtmpConnectionState.IDLE)
        networkMonitor.reset()
        reconnectionManager.reset()
        window5s.reset()
        window10s.reset()
        window30s.reset()
        socketWriteStalls = 0L

        StreamTelemetryNotifier.updateState(RtmpConnectionState.IDLE)
    }

    companion object {
        private const val TAG = "StreamPipeline"
    }
}
