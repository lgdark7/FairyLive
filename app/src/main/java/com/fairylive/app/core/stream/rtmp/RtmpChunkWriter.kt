package com.fairylive.app.core.stream.rtmp

import java.io.ByteArrayOutputStream
import java.io.OutputStream

/**
 * Serializes RTMP messages into standard RTMP chunk streams with negotiated chunk sizing.
 */
class RtmpChunkWriter(
    private val output: OutputStream,
    var chunkSize: Int = 128
) {

    /**
     * Writes an RTMP message as Type 0 chunk(s).
     */
    fun writeMessage(
        csid: Int,
        messageTypeId: Int,
        messageStreamId: Int,
        timestampMs: Long,
        payload: ByteArray
    ) {
        val totalLength = payload.size
        var offset = 0

        // 1. First chunk (Type 0 Chunk Header)
        val firstChunkSize = totalLength.coerceAtMost(chunkSize)
        writeType0Header(csid, messageTypeId, messageStreamId, timestampMs, totalLength)
        output.write(payload, 0, firstChunkSize)
        offset += firstChunkSize

        // 2. Subsequent chunks (Type 3 Continuation Chunk Headers)
        while (offset < totalLength) {
            val nextChunkSize = (totalLength - offset).coerceAtMost(chunkSize)
            writeType3Header(csid)
            if (timestampMs >= 0xFFFFFFL) {
                writeExtendedTimestamp(timestampMs)
            }
            output.write(payload, offset, nextChunkSize)
            offset += nextChunkSize
        }
        output.flush()
    }

    fun flush() {
        output.flush()
    }

    private fun writeType0Header(
        csid: Int,
        messageTypeId: Int,
        messageStreamId: Int,
        timestampMs: Long,
        messageLength: Int
    ) {
        // Basic Header (Format 0)
        output.write(csid and 0x3F)

        // Timestamp (3 bytes)
        val ts3Bytes = if (timestampMs >= 0xFFFFFFL) 0xFFFFFFL else timestampMs
        output.write(((ts3Bytes shr 16) and 0xFF).toInt())
        output.write(((ts3Bytes shr 8) and 0xFF).toInt())
        output.write((ts3Bytes and 0xFF).toInt())

        // Message Length (3 bytes)
        output.write((messageLength shr 16) and 0xFF)
        output.write((messageLength shr 8) and 0xFF)
        output.write(messageLength and 0xFF)

        // Message Type ID (1 byte)
        output.write(messageTypeId and 0xFF)

        // Message Stream ID (4 bytes, LITTLE ENDIAN per RTMP spec)
        output.write(messageStreamId and 0xFF)
        output.write((messageStreamId shr 8) and 0xFF)
        output.write((messageStreamId shr 16) and 0xFF)
        output.write((messageStreamId shr 24) and 0xFF)

        // Extended timestamp if needed
        if (timestampMs >= 0xFFFFFFL) {
            writeExtendedTimestamp(timestampMs)
        }
    }

    private fun writeType3Header(csid: Int) {
        // Format 3 = 0xC0 or csid
        output.write(0xC0 or (csid and 0x3F))
    }

    private fun writeExtendedTimestamp(timestampMs: Long) {
        output.write(((timestampMs shr 24) and 0xFF).toInt())
        output.write(((timestampMs shr 16) and 0xFF).toInt())
        output.write(((timestampMs shr 8) and 0xFF).toInt())
        output.write((timestampMs and 0xFF).toInt())
    }

    /**
     * Sends the protocol control message to update the outbound chunk size.
     */
    fun sendSetChunkSize(newSize: Int) {
        this.chunkSize = newSize
        val payload = ByteArray(4).apply {
            this[0] = ((newSize shr 24) and 0x7F).toByte() // MSB is 0 per spec
            this[1] = ((newSize shr 16) and 0xFF).toByte()
            this[2] = ((newSize shr 8) and 0xFF).toByte()
            this[3] = (newSize and 0xFF).toByte()
        }
        // CSID 2, Message Type 1 (Set Chunk Size), Message Stream ID 0, Timestamp 0
        writeMessage(
            csid = 2,
            messageTypeId = 1,
            messageStreamId = 0,
            timestampMs = 0L,
            payload = payload
        )
        output.flush()
    }
}
