package com.fairylive.app.core.stream.rtmp

import android.util.Log
import com.fairylive.app.core.stream.model.FlvPacket
import com.fairylive.app.core.stream.model.RtmpConnectionState
import com.fairylive.app.core.stream.rtmp.amf.Amf0Codec
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketTimeoutException
import java.net.URI

data class RtmpEndpoint(
    val host: String,
    val port: Int,
    val appName: String,
    val streamKey: String,
    val isSsl: Boolean = false
) {
    val tcUrl: String
        get() = "${if (isSsl) "rtmps" else "rtmp"}://$host:$port/$appName"

    companion object {
        fun parse(url: String, fallbackStreamKey: String = ""): RtmpEndpoint {
            val raw = url.trim()
            val isSsl = raw.startsWith("rtmps://", ignoreCase = true)
            val cleanUrl = if (!raw.contains("://")) "rtmp://$raw" else raw
            val uri = try {
                URI(cleanUrl)
            } catch (e: Exception) {
                throw IllegalArgumentException("Malformed RTMP URL: '$raw'", e)
            }
            val host = uri.host?.takeIf { it.isNotBlank() }
                ?: throw IllegalArgumentException("Invalid RTMP URL: Host cannot be empty in '$raw'")
            val defaultPort = if (isSsl) 443 else 1935
            val port = if (uri.port > 0) uri.port else defaultPort
            val path = (uri.path ?: "").trimStart('/').trimEnd('/')
            val segments = if (path.isEmpty()) emptyList() else path.split('/')
            val cleanFallbackKey = fallbackStreamKey.trim()

            return if (segments.size >= 2) {
                // e.g. rtmp://host/live/my_key
                val keyFromUrl = segments.subList(1, segments.size).joinToString("/")
                RtmpEndpoint(
                    host = host,
                    port = port,
                    appName = segments[0],
                    streamKey = if (cleanFallbackKey.isNotEmpty()) cleanFallbackKey else keyFromUrl,
                    isSsl = isSsl
                )
            } else if (segments.size == 1 && segments[0].isNotEmpty()) {
                RtmpEndpoint(
                    host = host,
                    port = port,
                    appName = segments[0],
                    streamKey = cleanFallbackKey,
                    isSsl = isSsl
                )
            } else {
                RtmpEndpoint(
                    host = host,
                    port = port,
                    appName = "live",
                    streamKey = cleanFallbackKey,
                    isSsl = isSsl
                )
            }
        }
    }
}

class RtmpPublishRejectedException(message: String) : IllegalStateException(message)

/**
 * Native, zero-dependency RTMP client.
 * Manages Handshake, Connect, CreateStream, Publish, and multiplexed chunk delivery.
 */
class RtmpClient(
    val timeoutMs: Int = 5000,
    val targetChunkSize: Int = 4096
) {
    private var socket: Socket? = null
    private var chunkWriter: RtmpChunkWriter? = null
    private var chunkReader: RtmpChunkReader? = null
    private var readThread: Thread? = null

    var publishStreamId: Int = 1
        private set

    @Volatile
    var isConnected: Boolean = false
        private set

    @Volatile
    var isPublishing: Boolean = false
        private set

    var onStateChangedListener: ((RtmpConnectionState) -> Unit)? = null

    fun connect(endpoint: RtmpEndpoint) {
        com.fairylive.app.core.lifecycle.StreamLifecycleDiagnostics.rtmpConnecting(endpoint.tcUrl, endpoint.streamKey)
        onStateChangedListener?.invoke(RtmpConnectionState.CONNECTING)
        Log.i(TAG, "Connecting to RTMP: ${endpoint.host}:${endpoint.port}/${endpoint.appName}")

        val rawSocket = Socket()
        rawSocket.tcpNoDelay = true
        rawSocket.sendBufferSize = 512 * 1024
        rawSocket.soTimeout = timeoutMs * 2
        rawSocket.connect(InetSocketAddress(endpoint.host, endpoint.port), timeoutMs)

        val sock: Socket = if (endpoint.isSsl) {
            val sslFactory = javax.net.ssl.SSLSocketFactory.getDefault() as javax.net.ssl.SSLSocketFactory
            val sslSocket = sslFactory.createSocket(rawSocket, endpoint.host, endpoint.port, true) as javax.net.ssl.SSLSocket
            sslSocket.startHandshake()
            sslSocket
        } else {
            rawSocket
        }
        this.socket = sock
        Log.i(TAG, "STAGE-TRACE: TCP_CONNECTED [${endpoint.host}:${endpoint.port}]")

        val inStream = BufferedInputStream(sock.getInputStream(), 8192)
        val outStream = BufferedOutputStream(sock.getOutputStream(), 8192)

        // 1. Handshake
        onStateChangedListener?.invoke(RtmpConnectionState.HANDSHAKING)
        Log.i(TAG, "STAGE-TRACE: RTMP_HANDSHAKE_STARTED")
        RtmpHandshake.execute(inStream, outStream)
        Log.i(TAG, "STAGE-TRACE: RTMP_HANDSHAKE_COMPLETE")
        com.fairylive.app.core.lifecycle.StreamLifecycleDiagnostics.rtmpHandshakeComplete()

        val writer = RtmpChunkWriter(outStream, chunkSize = 128)
        val reader = RtmpChunkReader(inStream, inChunkSize = 128)
        this.chunkWriter = writer
        this.chunkReader = reader

        // 2. Set chunk size to 4096 for reduced chunk header overhead
        writer.sendSetChunkSize(targetChunkSize)

        // 3. Connect Command
        onStateChangedListener?.invoke(RtmpConnectionState.CONNECTED)
        Log.i(TAG, "STAGE-TRACE: RTMP_CONNECT_SENT [app=${endpoint.appName}]")
        sendConnectCommand(writer, endpoint)

        // Wait for connect result
        waitForConnectResult(reader)
        Log.i(TAG, "STAGE-TRACE: RTMP_CONNECT_ACCEPTED")
        com.fairylive.app.core.lifecycle.StreamLifecycleDiagnostics.rtmpConnected()

        // 4. CreateStream Command
        onStateChangedListener?.invoke(RtmpConnectionState.CREATING_STREAM)
        sendReleaseAndFcPublish(writer, endpoint.streamKey)
        sendCreateStreamCommand(writer)

        val assignedStreamId = waitForCreateStreamResult(reader)
        this.publishStreamId = assignedStreamId
        Log.i(TAG, "STAGE-TRACE: RTMP_CREATE_STREAM [assignedStreamId=$assignedStreamId]")

        // 5. Publish Command
        Log.i(TAG, "STAGE-TRACE: RTMP_PUBLISH [streamKeyLength=${endpoint.streamKey.length}]")
        sendPublishCommand(writer, assignedStreamId, endpoint.streamKey)

        // Wait for publish result / onStatus
        waitForPublishResult(reader)

        this.isConnected = true
        this.isPublishing = true
        com.fairylive.app.core.lifecycle.StreamLifecycleDiagnostics.rtmpPublishStarted()
        onStateChangedListener?.invoke(RtmpConnectionState.PUBLISHING)
        Log.i(TAG, "STAGE-TRACE: YOUTUBE_STREAM_RECEIVING [Stream ID: $assignedStreamId]")

        // Start background incoming message reader to handle pings and keep socket healthy
        startIncomingMessageReader()
    }

    private fun sendConnectCommand(writer: RtmpChunkWriter, endpoint: RtmpEndpoint) {
        val out = ByteArrayOutputStream()
        Amf0Codec.writeString(out, "connect")
        Amf0Codec.writeNumber(out, 1.0) // Transaction ID 1

        val commandObject = linkedMapOf<String, Any?>(
            "app" to endpoint.appName,
            "flashVer" to "FMLE/3.0 (compatible; FairyLive)",
            "tcUrl" to endpoint.tcUrl,
            "fpad" to false,
            "capabilities" to 15.0,
            "audioCodecs" to 3191.0,
            "videoCodecs" to 252.0,
            "videoFunction" to 1.0
        )
        Amf0Codec.writeObject(out, commandObject)

        writer.writeMessage(
            csid = 3,
            messageTypeId = 20, // 0x14 AMF0 Command
            messageStreamId = 0,
            timestampMs = 0L,
            payload = out.toByteArray()
        )
    }

    private fun waitForConnectResult(reader: RtmpChunkReader) {
        val startTime = System.currentTimeMillis()
        while (System.currentTimeMillis() - startTime < timeoutMs) {
            val msg = reader.readNextMessage()
            if (msg.messageTypeId == 20) { // Command message
                val inStream = ByteArrayInputStream(msg.payload)
                val cmdName = Amf0Codec.readValue(inStream) as? String
                val txId = Amf0Codec.readValue(inStream) as? Double

                if (cmdName == "_result" && txId == 1.0) {
                    return
                } else if (cmdName == "_error") {
                    throw IllegalStateException("RTMP server rejected connect command: $cmdName")
                }
            }
        }
        throw IllegalStateException("Timeout waiting for RTMP connect _result")
    }

    private fun sendReleaseAndFcPublish(writer: RtmpChunkWriter, streamKey: String) {
        if (streamKey.isEmpty()) return

        // releaseStream
        val outRelease = ByteArrayOutputStream()
        Amf0Codec.writeString(outRelease, "releaseStream")
        Amf0Codec.writeNumber(outRelease, 2.0)
        Amf0Codec.writeNull(outRelease)
        Amf0Codec.writeString(outRelease, streamKey)
        writer.writeMessage(3, 20, 0, 0L, outRelease.toByteArray())

        // FCPublish
        val outFc = ByteArrayOutputStream()
        Amf0Codec.writeString(outFc, "FCPublish")
        Amf0Codec.writeNumber(outFc, 3.0)
        Amf0Codec.writeNull(outFc)
        Amf0Codec.writeString(outFc, streamKey)
        writer.writeMessage(3, 20, 0, 0L, outFc.toByteArray())
    }

    private fun sendCreateStreamCommand(writer: RtmpChunkWriter) {
        val out = ByteArrayOutputStream()
        Amf0Codec.writeString(out, "createStream")
        Amf0Codec.writeNumber(out, 4.0) // Transaction ID 4
        Amf0Codec.writeNull(out)

        writer.writeMessage(3, 20, 0, 0L, out.toByteArray())
    }

    private fun waitForCreateStreamResult(reader: RtmpChunkReader): Int {
        val startTime = System.currentTimeMillis()
        while (System.currentTimeMillis() - startTime < timeoutMs) {
            val msg = reader.readNextMessage()
            if (msg.messageTypeId == 20) {
                val inStream = ByteArrayInputStream(msg.payload)
                val cmdName = Amf0Codec.readValue(inStream) as? String
                val txId = Amf0Codec.readValue(inStream) as? Double

                if (cmdName == "_result" && txId == 4.0) {
                    Amf0Codec.readValue(inStream) // Command object (null)
                    val streamId = Amf0Codec.readValue(inStream) as? Double
                    return streamId?.toInt() ?: 1
                }
            }
        }
        return 1
    }

    private fun sendPublishCommand(writer: RtmpChunkWriter, streamId: Int, streamKey: String) {
        val out = ByteArrayOutputStream()
        Amf0Codec.writeString(out, "publish")
        Amf0Codec.writeNumber(out, 5.0)
        Amf0Codec.writeNull(out)
        Amf0Codec.writeString(out, streamKey)
        Amf0Codec.writeString(out, "live")

        writer.writeMessage(
            csid = 3,
            messageTypeId = 20,
            messageStreamId = streamId,
            timestampMs = 0L,
            payload = out.toByteArray()
        )
    }

    private fun waitForPublishResult(reader: RtmpChunkReader) {
        val startTime = System.currentTimeMillis()
        while (System.currentTimeMillis() - startTime < timeoutMs) {
            val msg = try {
                reader.readNextMessage()
            } catch (_: SocketTimeoutException) {
                Log.w(TAG, "SocketTimeout waiting for publish onStatus, continuing to stream")
                return
            }
            Log.i(TAG, "STAGE-TRACE: Post-publish msg type=${msg.messageTypeId}, csid=${msg.csid}, len=${msg.payload.size}")
            if (msg.messageTypeId == 20) { // Command message
                val inStream = ByteArrayInputStream(msg.payload)
                val cmdName = Amf0Codec.readValue(inStream) as? String
                val txId = Amf0Codec.readValue(inStream) as? Double
                Amf0Codec.readValue(inStream) // null command object
                val infoObj = Amf0Codec.readValue(inStream) as? Map<*, *>

                Log.i(TAG, "STAGE-TRACE: RTMP publish response: cmd=$cmdName, txId=$txId, info=$infoObj")

                val code = infoObj?.get("code") as? String
                val level = infoObj?.get("level") as? String

                if (code == "NetStream.Publish.Start" || level == "status") {
                    Log.i(TAG, "STAGE-TRACE: RTMP_PUBLISH_ACCEPTED ($code)")
                    return
                } else if (code == "NetStream.Publish.BadName" || code == "NetStream.Publish.Denied" || code == "NetStream.Publish.Rejected" || level == "error") {
                    val desc = infoObj?.get("description") as? String ?: code ?: "Publish rejected"
                    throw RtmpPublishRejectedException("RTMP publish rejected by server: $code ($desc)")
                }
            } else if (msg.messageTypeId == 1) { // Set Chunk Size from server
                val newChunkSize = ((msg.payload[0].toInt() and 0x7F) shl 24) or
                        ((msg.payload[1].toInt() and 0xFF) shl 16) or
                        ((msg.payload[2].toInt() and 0xFF) shl 8) or
                        (msg.payload[3].toInt() and 0xFF)
                Log.i(TAG, "STAGE-TRACE: Server Set Chunk Size: $newChunkSize")
                reader.inChunkSize = newChunkSize
            } else if (msg.messageTypeId == 4) { // User Control Message
                val eventType = ((msg.payload[0].toInt() and 0xFF) shl 8) or (msg.payload[1].toInt() and 0xFF)
                Log.i(TAG, "STAGE-TRACE: User Control message: eventType=$eventType")
            }
        }
        Log.w(TAG, "Timeout waiting for publish onStatus, continuing to stream")
    }

    private fun startIncomingMessageReader() {
        val reader = chunkReader ?: return
        val sock = socket ?: return
        readThread = Thread({
            Log.i(TAG, "RTMP incoming message reader started")
            while (isConnected && !sock.isClosed) {
                try {
                    val msg = reader.readNextMessage()
                    when (msg.messageTypeId) {
                        1 -> { // Set Chunk Size
                            val newChunkSize = ((msg.payload[0].toInt() and 0x7F) shl 24) or
                                    ((msg.payload[1].toInt() and 0xFF) shl 16) or
                                    ((msg.payload[2].toInt() and 0xFF) shl 8) or
                                    (msg.payload[3].toInt() and 0xFF)
                            Log.i(TAG, "Server Set Chunk Size: $newChunkSize")
                            reader.inChunkSize = newChunkSize
                        }
                        4 -> { // User Control Message
                            val eventType = ((msg.payload[0].toInt() and 0xFF) shl 8) or (msg.payload[1].toInt() and 0xFF)
                            if (eventType == 6 && msg.payload.size >= 6) { // Ping Request
                                val respPayload = ByteArray(6)
                                respPayload[0] = 0x00
                                respPayload[1] = 0x07 // Event 7 = Ping Response
                                System.arraycopy(msg.payload, 2, respPayload, 2, 4)
                                synchronized(this) {
                                    chunkWriter?.writeMessage(
                                        csid = 2,
                                        messageTypeId = 4,
                                        messageStreamId = 0,
                                        timestampMs = 0L,
                                        payload = respPayload
                                    )
                                }
                                Log.i(TAG, "Responded to RTMP Ping Request (Event 6 -> 7)")
                            }
                        }
                        20 -> { // Command Message
                            val inStream = ByteArrayInputStream(msg.payload)
                            val cmdName = Amf0Codec.readValue(inStream) as? String
                            Log.i(TAG, "Received incoming RTMP command during streaming: $cmdName")
                        }
                    }
                } catch (_: SocketTimeoutException) {
                    // Normal timeout when no incoming message, keep waiting
                } catch (e: Exception) {
                    if (isConnected) {
                        Log.w(TAG, "RTMP incoming message reader terminated: ${e.message}")
                    }
                    break
                }
            }
            Log.i(TAG, "RTMP incoming message reader stopped")
        }, "FairyRtmpReader").apply {
            isDaemon = true
            start()
        }
    }

    /**
     * Serializes and pushes an FLV packet onto the RTMP chunk stream.
     */
    fun sendPacket(packet: FlvPacket) {
        val writer = chunkWriter ?: throw IllegalStateException("Writer not initialized")
        val csid = when (packet.packetType) {
            FlvPacket.TYPE_VIDEO -> 6
            FlvPacket.TYPE_AUDIO -> 4
            FlvPacket.TYPE_DATA -> 5
            else -> 3
        }

        synchronized(this) {
            writer.writeMessage(
                csid = csid,
                messageTypeId = packet.packetType,
                messageStreamId = publishStreamId,
                timestampMs = packet.timestampMs,
                payload = packet.payload
            )
        }
    }

    fun close() {
        isPublishing = false
        isConnected = false
        try {
            readThread?.interrupt()
        } catch (_: Exception) {}
        readThread = null
        try {
            socket?.close()
        } catch (_: Exception) {}
        socket = null
        chunkWriter = null
        chunkReader = null
        com.fairylive.app.core.lifecycle.StreamLifecycleDiagnostics.rtmpDisconnected()
        onStateChangedListener?.invoke(RtmpConnectionState.IDLE)
    }

    companion object {
        private const val TAG = "RtmpClient"
    }
}
