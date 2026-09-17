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
import java.net.URI

data class RtmpEndpoint(
    val host: String,
    val port: Int = 1935,
    val appName: String = "live",
    val streamKey: String = ""
) {
    val tcUrl: String
        get() = "rtmp://$host:$port/$appName"

    companion object {
        fun parse(url: String, fallbackStreamKey: String = ""): RtmpEndpoint {
            val raw = url.trim()
            val cleanUrl = if (!raw.contains("://")) "rtmp://$raw" else raw
            val uri = try {
                URI(cleanUrl)
            } catch (_: Exception) {
                URI("rtmp://127.0.0.1:1935/live")
            }
            val host = uri.host?.takeIf { it.isNotBlank() } ?: "127.0.0.1"
            val port = if (uri.port > 0) uri.port else 1935
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
                    streamKey = if (cleanFallbackKey.isNotEmpty()) cleanFallbackKey else keyFromUrl
                )
            } else if (segments.size == 1 && segments[0].isNotEmpty()) {
                RtmpEndpoint(
                    host = host,
                    port = port,
                    appName = segments[0],
                    streamKey = cleanFallbackKey
                )
            } else {
                RtmpEndpoint(
                    host = host,
                    port = port,
                    appName = "live",
                    streamKey = cleanFallbackKey
                )
            }
        }
    }
}

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
        onStateChangedListener?.invoke(RtmpConnectionState.CONNECTING)
        Log.i(TAG, "Connecting to RTMP: ${endpoint.host}:${endpoint.port}/${endpoint.appName}")

        val sock = Socket()
        sock.tcpNoDelay = true
        sock.soTimeout = timeoutMs * 2
        sock.connect(InetSocketAddress(endpoint.host, endpoint.port), timeoutMs)
        this.socket = sock

        val inStream = BufferedInputStream(sock.getInputStream(), 8192)
        val outStream = BufferedOutputStream(sock.getOutputStream(), 8192)

        // 1. Handshake
        onStateChangedListener?.invoke(RtmpConnectionState.HANDSHAKING)
        RtmpHandshake.execute(inStream, outStream)

        val writer = RtmpChunkWriter(outStream, chunkSize = 128)
        val reader = RtmpChunkReader(inStream, inChunkSize = 128)
        this.chunkWriter = writer
        this.chunkReader = reader

        // 2. Set chunk size to 4096 for reduced chunk header overhead
        writer.sendSetChunkSize(targetChunkSize)

        // 3. Connect Command
        onStateChangedListener?.invoke(RtmpConnectionState.CONNECTED)
        sendConnectCommand(writer, endpoint)

        // Wait for connect result
        waitForConnectResult(reader)

        // 4. CreateStream Command
        onStateChangedListener?.invoke(RtmpConnectionState.CREATING_STREAM)
        sendReleaseAndFcPublish(writer, endpoint.streamKey)
        sendCreateStreamCommand(writer)

        val assignedStreamId = waitForCreateStreamResult(reader)
        this.publishStreamId = assignedStreamId

        // 5. Publish Command
        sendPublishCommand(writer, assignedStreamId, endpoint.streamKey)
        this.isConnected = true
        this.isPublishing = true
        onStateChangedListener?.invoke(RtmpConnectionState.PUBLISHING)
        Log.i(TAG, "Successfully published RTMP stream with Stream ID: $assignedStreamId")
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

        writer.writeMessage(
            csid = csid,
            messageTypeId = packet.packetType,
            messageStreamId = publishStreamId,
            timestampMs = packet.timestampMs,
            payload = packet.payload
        )
    }

    fun close() {
        isPublishing = false
        isConnected = false
        try {
            socket?.close()
        } catch (_: Exception) {}
        socket = null
        chunkWriter = null
        chunkReader = null
        onStateChangedListener?.invoke(RtmpConnectionState.IDLE)
    }

    companion object {
        private const val TAG = "RtmpClient"
    }
}
