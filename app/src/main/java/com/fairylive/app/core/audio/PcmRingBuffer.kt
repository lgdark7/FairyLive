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

    fun write(data: ByteArray, offset: Int, length: Int) {
        synchronized(lock) {
            val toWrite = min(length, capacity)
            for (i in 0 until toWrite) {
                buffer[writePos] = data[offset + (length - toWrite) + i]
                writePos = (writePos + 1) % capacity
                if (available < capacity) {
                    available++
                } else {
                    readPos = (readPos + 1) % capacity
                }
            }
        }
    }

    fun read(out: ByteArray, offset: Int, length: Int): Int {
        synchronized(lock) {
            val toRead = min(length, available)
            for (i in 0 until toRead) {
                out[offset + i] = buffer[readPos]
                readPos = (readPos + 1) % capacity
            }
            available -= toRead
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
