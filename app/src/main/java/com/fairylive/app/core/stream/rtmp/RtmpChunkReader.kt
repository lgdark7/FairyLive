package com.fairylive.app.core.stream.rtmp

import java.io.ByteArrayInputStream
import java.io.InputStream
import java.util.concurrent.ConcurrentHashMap

data class InboundRtmpMessage(
    val csid: Int,
    val messageTypeId: Int,
    val messageStreamId: Int,
    val timestampMs: Long,
    val payload: ByteArray
)

/**
 * Reassembles incoming RTMP chunks into complete messages.
 */
class RtmpChunkReader(
    private val input: InputStream,
    var inChunkSize: Int = 128
) {
    private val incompleteMessages = HashMap<Int, PartialMessage>()

    private data class PartialMessage(
        var csid: Int,
        var messageTypeId: Int,
        var messageStreamId: Int,
        var timestampMs: Long,
        var totalLength: Int,
        var bytesRead: Int,
        var buffer: ByteArray
    )

    fun readNextMessage(): InboundRtmpMessage {
        while (true) {
            val basicHeaderByte = input.read()
            if (basicHeaderByte < 0) throw IllegalStateException("Unexpected EOF reading basic header")

            val fmt = (basicHeaderByte shr 6) and 0x03
            val csid = basicHeaderByte and 0x3F

            var partial = incompleteMessages[csid]

            when (fmt) {
                0 -> {
                    // Type 0 (11 bytes header)
                    val ts = read3Bytes(input)
                    val len = read3Bytes(input)
                    val type = input.read()
                    val streamId = read4BytesLittleEndian(input)
                    var finalTs = ts.toLong()
                    if (ts == 0xFFFFFF) {
                        finalTs = read4BytesBigEndian(input).toLong()
                    }

                    partial = PartialMessage(
                        csid = csid,
                        messageTypeId = type,
                        messageStreamId = streamId,
                        timestampMs = finalTs,
                        totalLength = len,
                        bytesRead = 0,
                        buffer = ByteArray(len)
                    )
                    incompleteMessages[csid] = partial
                }
                1 -> {
                    // Type 1 (7 bytes header)
                    val delta = read3Bytes(input)
                    val len = read3Bytes(input)
                    val type = input.read()

                    val prev = partial ?: throw IllegalStateException("Type 1 chunk without previous context")
                    prev.timestampMs += delta
                    prev.totalLength = len
                    prev.messageTypeId = type
                    prev.bytesRead = 0
                    prev.buffer = ByteArray(len)
                }
                2 -> {
                    // Type 2 (3 bytes header)
                    val delta = read3Bytes(input)
                    val prev = partial ?: throw IllegalStateException("Type 2 chunk without previous context")
                    prev.timestampMs += delta
                    prev.bytesRead = 0
                    prev.buffer = ByteArray(prev.totalLength)
                }
                3 -> {
                    // Type 3 (0 bytes header)
                    if (partial == null) throw IllegalStateException("Type 3 chunk without previous context")
                }
            }

            val msg = partial!!
            val toRead = (msg.totalLength - msg.bytesRead).coerceAtMost(inChunkSize)
            readFully(input, msg.buffer, msg.bytesRead, toRead)
            msg.bytesRead += toRead

            if (msg.bytesRead >= msg.totalLength) {
                // Check if this was a Set Chunk Size message (Type 1)
                if (msg.messageTypeId == 1 && msg.totalLength >= 4) {
                    val newChunkSize = ((msg.buffer[0].toInt() and 0x7F) shl 24) or
                            ((msg.buffer[1].toInt() and 0xFF) shl 16) or
                            ((msg.buffer[2].toInt() and 0xFF) shl 8) or
                            (msg.buffer[3].toInt() and 0xFF)
                    if (newChunkSize in 1..65536) {
                        inChunkSize = newChunkSize
                    }
                }

                return InboundRtmpMessage(
                    csid = msg.csid,
                    messageTypeId = msg.messageTypeId,
                    messageStreamId = msg.messageStreamId,
                    timestampMs = msg.timestampMs,
                    payload = msg.buffer
                )
            }
        }
    }

    private fun read3Bytes(stream: InputStream): Int {
        val b1 = stream.read()
        val b2 = stream.read()
        val b3 = stream.read()
        if (b3 < 0) throw IllegalStateException("Unexpected EOF reading 3 bytes")
        return (b1 shl 16) or (b2 shl 8) or b3
    }

    private fun read4BytesBigEndian(stream: InputStream): Int {
        val b1 = stream.read()
        val b2 = stream.read()
        val b3 = stream.read()
        val b4 = stream.read()
        if (b4 < 0) throw IllegalStateException("Unexpected EOF reading 4 bytes")
        return (b1 shl 24) or (b2 shl 16) or (b3 shl 8) or b4
    }

    private fun read4BytesLittleEndian(stream: InputStream): Int {
        val b1 = stream.read()
        val b2 = stream.read()
        val b3 = stream.read()
        val b4 = stream.read()
        if (b4 < 0) throw IllegalStateException("Unexpected EOF reading 4 bytes LE")
        return b1 or (b2 shl 8) or (b3 shl 16) or (b4 shl 24)
    }

    private fun readFully(stream: InputStream, buffer: ByteArray, offset: Int, length: Int) {
        var total = 0
        while (total < length) {
            val count = stream.read(buffer, offset + total, length - total)
            if (count < 0) throw IllegalStateException("Unexpected EOF reading chunk payload")
            total += count
        }
    }
}
