package com.fairylive.app.core.audio

import kotlin.math.min

/**
 * Thread-safe, fixed-capacity circular PCM audio buffer.
 * Bridges playback capture and microphone capture threads with zero allocations in steady state.
 */
class PcmRingBuffer(val capacity: Int) {
    private val buffer = ByteArray(capacity)
    private var writePos = 0
    private var readPos = 0
    private var available = 0
    private val lock = Any()

    val availableBytes: Int
        get() = synchronized(lock) { available }

    fun write(data: ByteArray, offset: Int, length: Int) {
        synchronized(lock) {
            val toWrite = min(length, capacity)
            val dataStart = offset + (length - toWrite)

            // If incoming data exceeds remaining capacity, discard oldest unread samples
            val overflow = (available + toWrite) - capacity
            if (overflow > 0) {
                readPos = (readPos + overflow) % capacity
                available -= overflow
            }

            val firstChunk = min(toWrite, capacity - writePos)
            System.arraycopy(data, dataStart, buffer, writePos, firstChunk)
            val secondChunk = toWrite - firstChunk
            if (secondChunk > 0) {
                System.arraycopy(data, dataStart + firstChunk, buffer, 0, secondChunk)
            }
            writePos = (writePos + toWrite) % capacity
            available += toWrite
        }
    }

    fun read(out: ByteArray, offset: Int, length: Int): Int {
        synchronized(lock) {
            val toRead = min(length, available)
            if (toRead > 0) {
                val firstChunk = min(toRead, capacity - readPos)
                System.arraycopy(buffer, readPos, out, offset, firstChunk)
                val secondChunk = toRead - firstChunk
                if (secondChunk > 0) {
                    System.arraycopy(buffer, 0, out, offset + firstChunk, secondChunk)
                }
                readPos = (readPos + toRead) % capacity
                available -= toRead
            }
            if (toRead < length) {
                out.fill(0, offset + toRead, offset + length)
            }
            return toRead
        }
    }

    fun clear() {
        synchronized(lock) {
            writePos = 0
            readPos = 0
            available = 0
        }
    }
}
