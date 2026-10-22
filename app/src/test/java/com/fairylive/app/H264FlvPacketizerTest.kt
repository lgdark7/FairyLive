package com.fairylive.app

import com.fairylive.app.core.stream.flv.H264FlvPacketizer
import com.fairylive.app.core.stream.model.FlvPacket
import com.fairylive.app.core.video.HardwareVideoEncoder
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayOutputStream

/**
 * Deterministic unit tests for H.264 FLV packetization, SPS/PPS caching,
 * sequence header ordering, pre-IDR frame drop policy, and multi-NALU processing.
 */
class H264FlvPacketizerTest {

    private val sampleSps = byteArrayOf(0x67, 0x42, 0x00, 0x1E)
    private val samplePps = byteArrayOf(0x68, 0xCE.toByte())
    private val sampleIdr = byteArrayOf(0x65, 0x01, 0x02, 0x03)
    private val samplePFrame = byteArrayOf(0x61, 0x04, 0x05)
    private val sampleSei = byteArrayOf(0x66, 0x08, 0x09)

    private fun buildAnnexB(vararg nalUnits: ByteArray): ByteArray {
        val out = ByteArrayOutputStream()
        for (nal in nalUnits) {
            out.write(byteArrayOf(0x00, 0x00, 0x00, 0x01))
            out.write(nal)
        }
        return out.toByteArray()
    }

    // --- 1. HARDWARE ENCODER SPS/PPS CACHING TESTS ---

    @Test
    fun testSpsPpsAvailableBeforeCallbackRegistration() {
        val encoder = HardwareVideoEncoder()
        assertNull(encoder.cachedSps)
        assertNull(encoder.cachedPps)

        // csd-0 / csd-1 extracted before callback is registered
        encoder.onFormatSpsPpsExtracted(sampleSps, samplePps)
        assertArrayEquals(sampleSps, encoder.cachedSps)
        assertArrayEquals(samplePps, encoder.cachedPps)

        // Attach listener later: must receive cached SPS/PPS immediately
        var receivedSps: ByteArray? = null
        var receivedPps: ByteArray? = null
        encoder.onSpsPpsAvailable = { sps, pps ->
            receivedSps = sps
            receivedPps = pps
        }

        assertNotNull("Listener must receive cached SPS upon registration", receivedSps)
        assertNotNull("Listener must receive cached PPS upon registration", receivedPps)
        assertArrayEquals(sampleSps, receivedSps)
        assertArrayEquals(samplePps, receivedPps)
    }

    @Test
    fun testSpsPpsAvailableAfterCallbackRegistration() {
        val encoder = HardwareVideoEncoder()
        var receivedSps: ByteArray? = null
        var receivedPps: ByteArray? = null

        encoder.onSpsPpsAvailable = { sps, pps ->
            receivedSps = sps
            receivedPps = pps
        }

        // csd-0 / csd-1 extracted after callback is registered
        encoder.onFormatSpsPpsExtracted(sampleSps, samplePps)
        assertArrayEquals(sampleSps, receivedSps)
        assertArrayEquals(samplePps, receivedPps)
        assertArrayEquals(sampleSps, encoder.cachedSps)
        assertArrayEquals(samplePps, encoder.cachedPps)
    }

    @Test
    fun testEncoderStopClearsCachedSpsPps() {
        val encoder = HardwareVideoEncoder()
        encoder.onFormatSpsPpsExtracted(sampleSps, samplePps)
        assertNotNull(encoder.cachedSps)
        assertNotNull(encoder.cachedPps)

        encoder.stop()
        assertNull(encoder.cachedSps)
        assertNull(encoder.cachedPps)
    }

    // --- 2. H264 FLV PACKETIZER TESTS ---

    @Test
    fun testPreIdrPFramesDiscardedUntilFirstIdr() {
        val packetizer = H264FlvPacketizer()
        packetizer.setSpsPps(sampleSps, samplePps)

        // Frame 1: P-frame before any IDR
        val pFrame1 = buildAnnexB(samplePFrame)
        val packets1 = packetizer.processAnnexBFrame(pFrame1, ptsMs = 33L, isKeyFrameHint = false)
        assertTrue("Pre-IDR P-frames must be discarded", packets1.isEmpty())
        assertFalse(packetizer.hasSeenFirstIdr)

        // Frame 2: Another P-frame
        val pFrame2 = buildAnnexB(samplePFrame)
        val packets2 = packetizer.processAnnexBFrame(pFrame2, ptsMs = 66L, isKeyFrameHint = false)
        assertTrue("Subsequent pre-IDR P-frames must also be discarded", packets2.isEmpty())
        assertFalse(packetizer.hasSeenFirstIdr)

        // Frame 3: First IDR arrives
        val idrFrame = buildAnnexB(sampleIdr)
        val packets3 = packetizer.processAnnexBFrame(idrFrame, ptsMs = 100L, isKeyFrameHint = true)
        assertTrue("hasSeenFirstIdr must become true upon first IDR", packetizer.hasSeenFirstIdr)
        assertEquals("Must emit sequence header and first IDR frame", 2, packets3.size)

        // Verify sequence header
        val seqHeader = packets3[0]
        assertTrue(seqHeader.isSequenceHeader)
        assertEquals(0L, seqHeader.timestampMs)

        // Verify IDR frame
        val idrPacket = packets3[1]
        assertFalse(idrPacket.isSequenceHeader)
        assertTrue(idrPacket.isKeyFrame)
        assertEquals(100L, idrPacket.timestampMs)

        // Frame 4: Post-IDR P-frame must now be emitted normally
        val packets4 = packetizer.processAnnexBFrame(pFrame1, ptsMs = 133L, isKeyFrameHint = false)
        assertEquals("Post-IDR P-frame must be emitted", 1, packets4.size)
        val postIdrPacket = packets4[0]
        assertFalse(postIdrPacket.isSequenceHeader)
        assertFalse(postIdrPacket.isKeyFrame)
        assertEquals(133L, postIdrPacket.timestampMs)
    }

    @Test
    fun testExactlyOneSequenceHeaderWithoutDuplicates() {
        val packetizer = H264FlvPacketizer()
        packetizer.setSpsPps(sampleSps, samplePps)
        // Simulate pipeline proactively generating sequence header at startup
        packetizer.markHeaderGenerated()
        assertTrue(packetizer.isHeaderGenerated)

        // First IDR arrives
        val idrFrame = buildAnnexB(sampleIdr)
        val packets = packetizer.processAnnexBFrame(idrFrame, ptsMs = 100L, isKeyFrameHint = true)

        assertEquals("Must NOT re-emit sequence header if already generated", 1, packets.size)
        val packet = packets[0]
        assertFalse("Packet must not be sequence header", packet.isSequenceHeader)
        assertTrue("Packet must be keyframe", packet.isKeyFrame)
        assertEquals(100L, packet.timestampMs)
    }

    @Test
    fun testMultipleNalusInSingleInputBuffer() {
        val packetizer = H264FlvPacketizer()
        packetizer.setSpsPps(sampleSps, samplePps)
        packetizer.markHeaderGenerated()

        // Single Annex-B buffer containing SEI (type 6) and IDR (type 5)
        val multiNaluBuffer = buildAnnexB(sampleSei, sampleIdr)
        val packets = packetizer.processAnnexBFrame(multiNaluBuffer, ptsMs = 200L, isKeyFrameHint = true)

        assertEquals(1, packets.size)
        val packet = packets[0]
        assertTrue(packet.isKeyFrame)

        // Check payload contains both NALUs, each preceded by 4-byte length prefix
        val payload = packet.payload
        assertEquals(0x17, payload[0].toInt() and 0xFF) // Keyframe + AVC
        assertEquals(0x01, payload[1].toInt() and 0xFF) // AVC NALU

        // First NALU (SEI, 3 bytes)
        var offset = 5
        val len1 = (payload[offset].toInt() and 0xFF shl 24) or
                (payload[offset + 1].toInt() and 0xFF shl 16) or
                (payload[offset + 2].toInt() and 0xFF shl 8) or
                (payload[offset + 3].toInt() and 0xFF)
        assertEquals(sampleSei.size, len1)
        offset += 4 + len1

        // Second NALU (IDR, 4 bytes)
        val len2 = (payload[offset].toInt() and 0xFF shl 24) or
                (payload[offset + 1].toInt() and 0xFF shl 16) or
                (payload[offset + 2].toInt() and 0xFF shl 8) or
                (payload[offset + 3].toInt() and 0xFF)
        assertEquals(sampleIdr.size, len2)
    }

    @Test
    fun testInBandSpsPpsExtractedAndCachedWithoutMediaEmission() {
        val packetizer = H264FlvPacketizer()
        assertNull(packetizer.cachedSps)
        assertNull(packetizer.cachedPps)

        // Standalone codec-config Annex-B buffer (SPS + PPS)
        val configBuffer = buildAnnexB(sampleSps, samplePps)
        val packets = packetizer.processAnnexBFrame(configBuffer, ptsMs = 0L, isKeyFrameHint = false)

        assertTrue("Standalone codec-config buffer must not emit media frames", packets.isEmpty())
        assertArrayEquals(sampleSps, packetizer.cachedSps)
        assertArrayEquals(samplePps, packetizer.cachedPps)
        assertFalse(packetizer.hasSeenFirstIdr)
    }

    @Test
    fun testResetClearsAllPacketizerState() {
        val packetizer = H264FlvPacketizer()
        packetizer.setSpsPps(sampleSps, samplePps)
        packetizer.markHeaderGenerated()
        val idrFrame = buildAnnexB(sampleIdr)
        packetizer.processAnnexBFrame(idrFrame, ptsMs = 100L, isKeyFrameHint = true)

        assertTrue(packetizer.hasSeenFirstIdr)
        assertTrue(packetizer.isHeaderGenerated)
        assertNotNull(packetizer.cachedSps)
        assertNotNull(packetizer.cachedPps)

        packetizer.reset()
        assertFalse(packetizer.hasSeenFirstIdr)
        assertFalse(packetizer.isHeaderGenerated)
        assertNull(packetizer.cachedSps)
        assertNull(packetizer.cachedPps)
    }
}
