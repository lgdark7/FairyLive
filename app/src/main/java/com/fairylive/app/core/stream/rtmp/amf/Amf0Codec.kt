package com.fairylive.app.core.stream.rtmp.amf

import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Lightweight, zero-dependency Action Message Format (AMF0) encoder and decoder for RTMP.
 */
object Amf0Codec {

    const val TYPE_NUMBER = 0x00
    const val TYPE_BOOLEAN = 0x01
    const val TYPE_STRING = 0x02
    const val TYPE_OBJECT = 0x03
    const val TYPE_NULL = 0x05
    const val TYPE_ECMA_ARRAY = 0x08
    const val TYPE_OBJECT_END = 0x09

    // --- ENCODING METHODS ---

    fun writeNumber(out: ByteArrayOutputStream, value: Double) {
        out.write(TYPE_NUMBER)
        val buf = ByteBuffer.allocate(8).order(ByteOrder.BIG_ENDIAN).putDouble(value)
        out.write(buf.array())
    }

    fun writeBoolean(out: ByteArrayOutputStream, value: Boolean) {
        out.write(TYPE_BOOLEAN)
        out.write(if (value) 1 else 0)
    }

    fun writeString(out: ByteArrayOutputStream, value: String) {
        out.write(TYPE_STRING)
        writeShortStringPayload(out, value)
    }

    fun writeNull(out: ByteArrayOutputStream) {
        out.write(TYPE_NULL)
    }

    fun writeObject(out: ByteArrayOutputStream, properties: Map<String, Any?>) {
        out.write(TYPE_OBJECT)
        for ((key, value) in properties) {
            writeShortStringPayload(out, key)
            writeValue(out, value)
        }
        // Object end marker: empty string (0x00 0x00) + 0x09
        out.write(0)
        out.write(0)
        out.write(TYPE_OBJECT_END)
    }

    fun writeEcmaArray(out: ByteArrayOutputStream, properties: Map<String, Any?>) {
        out.write(TYPE_ECMA_ARRAY)
        // 4 bytes count
        val count = properties.size
        out.write((count shr 24) and 0xFF)
        out.write((count shr 16) and 0xFF)
        out.write((count shr 8) and 0xFF)
        out.write(count and 0xFF)

        for ((key, value) in properties) {
            writeShortStringPayload(out, key)
            writeValue(out, value)
        }
        // End marker
        out.write(0)
        out.write(0)
        out.write(TYPE_OBJECT_END)
    }

    private fun writeShortStringPayload(out: ByteArrayOutputStream, str: String) {
        val bytes = str.toByteArray(Charsets.UTF_8)
        val len = bytes.size
        out.write((len shr 8) and 0xFF)
        out.write(len and 0xFF)
        out.write(bytes)
    }

    fun writeValue(out: ByteArrayOutputStream, value: Any?) {
        when (value) {
            null -> writeNull(out)
            is Double -> writeNumber(out, value)
            is Number -> writeNumber(out, value.toDouble())
            is Boolean -> writeBoolean(out, value)
            is String -> writeString(out, value)
            is Map<*, *> -> {
                @Suppress("UNCHECKED_CAST")
                writeObject(out, value as Map<String, Any?>)
            }
            else -> writeString(out, value.toString())
        }
    }

    // --- DECODING METHODS ---

    fun readValue(input: InputStream): Any? {
        val type = input.read()
        if (type < 0) return null

        return when (type) {
            TYPE_NUMBER -> readNumber(input)
            TYPE_BOOLEAN -> input.read() != 0
            TYPE_STRING -> readStringPayload(input)
            TYPE_NULL -> null
            TYPE_OBJECT -> readObject(input)
            TYPE_ECMA_ARRAY -> readEcmaArray(input)
            else -> null
        }
    }

    fun readNumber(input: InputStream): Double {
        val bytes = ByteArray(8)
        readFully(input, bytes)
        return ByteBuffer.wrap(bytes).order(ByteOrder.BIG_ENDIAN).double
    }

    fun readStringPayload(input: InputStream): String {
        val b1 = input.read()
        val b2 = input.read()
        val len = (b1 shl 8) or b2
        if (len <= 0) return ""
        val bytes = ByteArray(len)
        readFully(input, bytes)
        return String(bytes, Charsets.UTF_8)
    }

    fun readObject(input: InputStream): Map<String, Any?> {
        val map = mutableMapOf<String, Any?>()
        while (true) {
            val key = readStringPayload(input)
            val type = input.read()
            if (type == TYPE_OBJECT_END || type < 0) {
                break
            }
            // Put unread type byte back into stream decoding logic
            val value = when (type) {
                TYPE_NUMBER -> readNumber(input)
                TYPE_BOOLEAN -> input.read() != 0
                TYPE_STRING -> readStringPayload(input)
                TYPE_NULL -> null
                TYPE_OBJECT -> readObject(input)
                TYPE_ECMA_ARRAY -> readEcmaArray(input)
                else -> null
            }
            if (key.isNotEmpty()) {
                map[key] = value
            }
        }
        return map
    }

    fun readEcmaArray(input: InputStream): Map<String, Any?> {
        // Skip 4-byte count
        input.read()
        input.read()
        input.read()
        input.read()
        return readObject(input)
    }

    private fun readFully(input: InputStream, buffer: ByteArray) {
        var offset = 0
        while (offset < buffer.size) {
            val count = input.read(buffer, offset, buffer.size - offset)
            if (count < 0) break
            offset += count
        }
    }
}
