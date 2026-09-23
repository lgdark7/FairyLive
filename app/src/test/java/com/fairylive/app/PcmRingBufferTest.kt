package com.fairylive.app

import com.fairylive.app.core.audio.PcmRingBuffer
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

class PcmRingBufferTest {

    @Test
    fun testBasicWriteAndRead() {
        val rb = PcmRingBuffer(1024)
        val testData = byteArrayOf(1, 2, 3, 4, 5, 6, 7, 8)
        rb.write(testData, 0, testData.size)

        assertEquals(8, rb.availableBytes)

        val out = ByteArray(8)
        val readBytes = rb.read(out, 0, 8)
        assertEquals(8, readBytes)
        assertArrayEquals(testData, out)
        assertEquals(0, rb.availableBytes)
    }

    @Test
    fun testCircularWrapAround() {
        val capacity = 10
        val rb = PcmRingBuffer(capacity)

        // Write 6 bytes
        val data1 = byteArrayOf(10, 20, 30, 40, 50, 60)
        rb.write(data1, 0, data1.size)
        assertEquals(6, rb.availableBytes)

        // Read 4 bytes -> readPos advances to 4, available becomes 2
        val read1 = ByteArray(4)
        val count1 = rb.read(read1, 0, 4)
        assertEquals(4, count1)
        assertArrayEquals(byteArrayOf(10, 20, 30, 40), read1)
        assertEquals(2, rb.availableBytes)

        // Write 6 bytes -> this will wrap around the end of the buffer (capacity 10)
        val data2 = byteArrayOf(70, 80, 90, 100, 110, 120)
        rb.write(data2, 0, data2.size)
        assertEquals(8, rb.availableBytes)

        // Read all 8 available bytes across the circular boundary
        val read2 = ByteArray(8)
        val count2 = rb.read(read2, 0, 8)
        assertEquals(8, count2)
        assertArrayEquals(byteArrayOf(50, 60, 70, 80, 90, 100, 110, 120), read2)
        assertEquals(0, rb.availableBytes)
    }

    @Test
    fun testBufferOverflowOverwritesOldest() {
        val capacity = 10
        val rb = PcmRingBuffer(capacity)

        // Write 8 bytes
        val data1 = byteArrayOf(1, 2, 3, 4, 5, 6, 7, 8)
        rb.write(data1, 0, data1.size)
        assertEquals(8, rb.availableBytes)

        // Write 6 bytes -> total 14 > 10. Oldest 4 bytes (1, 2, 3, 4) must be dropped
        val data2 = byteArrayOf(9, 10, 11, 12, 13, 14)
        rb.write(data2, 0, data2.size)
        assertEquals(10, rb.availableBytes)

        // Read 10 bytes: expected [5, 6, 7, 8, 9, 10, 11, 12, 13, 14]
        val out = ByteArray(10)
        val readBytes = rb.read(out, 0, 10)
        assertEquals(10, readBytes)
        assertArrayEquals(byteArrayOf(5, 6, 7, 8, 9, 10, 11, 12, 13, 14), out)
        assertEquals(0, rb.availableBytes)
    }

    @Test
    fun testUnderflowFillsWithZero() {
        val rb = PcmRingBuffer(64)
        val testData = byteArrayOf(42, 43, 44)
        rb.write(testData, 0, 3)

        val out = ByteArray(6)
        out.fill(99)
        val readBytes = rb.read(out, 0, 6)

        assertEquals(3, readBytes)
        // First 3 bytes read, remaining 3 zero-filled
        assertArrayEquals(byteArrayOf(42, 43, 44, 0, 0, 0), out)
    }

    @Test
    fun testClearResetsBuffer() {
        val rb = PcmRingBuffer(128)
        rb.write(byteArrayOf(1, 2, 3), 0, 3)
        assertEquals(3, rb.availableBytes)

        rb.clear()
        assertEquals(0, rb.availableBytes)

        val out = ByteArray(4)
        val count = rb.read(out, 0, 4)
        assertEquals(0, count)
        assertArrayEquals(byteArrayOf(0, 0, 0, 0), out)
    }
}
