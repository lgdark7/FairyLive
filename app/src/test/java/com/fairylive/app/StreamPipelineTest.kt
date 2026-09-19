package com.fairylive.app

import com.fairylive.app.core.stream.flv.AacFlvPacketizer
import com.fairylive.app.core.stream.flv.FlvMetadataBuilder
import com.fairylive.app.core.stream.flv.H264FlvPacketizer
import com.fairylive.app.core.stream.model.FlvPacket
import com.fairylive.app.core.stream.model.RtmpConnectionState
import com.fairylive.app.core.stream.queue.BoundedStreamQueue
import com.fairylive.app.core.stream.rtmp.RtmpChunkReader
import com.fairylive.app.core.stream.rtmp.RtmpChunkWriter
import com.fairylive.app.core.stream.rtmp.RtmpClient
import com.fairylive.app.core.stream.rtmp.RtmpEndpoint
import com.fairylive.app.core.stream.rtmp.RtmpHandshake
import com.fairylive.app.core.stream.rtmp.amf.Amf0Codec
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * Comprehensive unit and integration test suite for Milestone 5 (Streaming Pipeline).
 */
class StreamPipelineTest {

    // --- 1. AMF0 CODEC TESTS ---

    @Test
    fun testAmf0NumberSerialization() {
        val out = ByteArrayOutputStream()
        Amf0Codec.writeNumber(out, 12345.67)

        val inStream = ByteArrayInputStream(out.toByteArray())
        val decoded = Amf0Codec.readValue(inStream) as? Double
        assertNotNull(decoded)
        assertEquals(12345.67, decoded!!, 0.001)
    }

    @Test
    fun testAmf0StringSerialization() {
        val out = ByteArrayOutputStream()
        Amf0Codec.writeString(out, "FairyLiveStream")

        val inStream = ByteArrayInputStream(out.toByteArray())
        val decoded = Amf0Codec.readValue(inStream) as? String
        assertEquals("FairyLiveStream", decoded)
    }

    @Test
    fun testAmf0ObjectSerialization() {
        val out = ByteArrayOutputStream()
        val original = mapOf<String, Any?>(
            "app" to "live",
            "flashVer" to "FMLE/3.0",
            "fpad" to false,
            "tcUrl" to "rtmp://127.0.0.1/live"
        )
        Amf0Codec.writeObject(out, original)

        val inStream = ByteArrayInputStream(out.toByteArray())
        @Suppress("UNCHECKED_CAST")
        val decoded = Amf0Codec.readValue(inStream) as? Map<String, Any?>
        assertNotNull(decoded)
        assertEquals("live", decoded!!["app"])
        assertEquals("FMLE/3.0", decoded["flashVer"])
        assertEquals(false, decoded["fpad"])
        assertEquals("rtmp://127.0.0.1/live", decoded["tcUrl"])
    }

    @Test
    fun testAmf0EcmaArraySerialization() {
        val out = ByteArrayOutputStream()
        val original = mapOf<String, Any?>(
            "width" to 480.0,
            "height" to 1056.0,
            "videocodecid" to 7.0
        )
        Amf0Codec.writeEcmaArray(out, original)

        val inStream = ByteArrayInputStream(out.toByteArray())
        @Suppress("UNCHECKED_CAST")
        val decoded = Amf0Codec.readValue(inStream) as? Map<String, Any?>
        assertNotNull(decoded)
        assertEquals(480.0, (decoded!!["width"] as Double), 0.1)
        assertEquals(1056.0, (decoded["height"] as Double), 0.1)
        assertEquals(7.0, (decoded["videocodecid"] as Double), 0.1)
    }

    // --- 2. H.264 FLV PACKETIZER TESTS ---

    @Test
    fun testH264NalExtractionAndAnnexBStripping() {
        // Annex-B buffer with SPS (type 7) and PPS (type 8) and IDR (type 5)
        val spsPayload = byteArrayOf(0x67, 0x42, 0x00, 0x1E) // SPS
        val ppsPayload = byteArrayOf(0x68, 0xCE.toByte(), 0x3C, 0x80.toByte()) // PPS
        val idrPayload = byteArrayOf(0x65, 0x88.toByte(), 0x84.toByte(), 0x00) // IDR

        val annexB = ByteArrayOutputStream().apply {
            write(byteArrayOf(0, 0, 0, 1))
            write(spsPayload)
            write(byteArrayOf(0, 0, 0, 1))
            write(ppsPayload)
            write(byteArrayOf(0, 0, 1))
            write(idrPayload)
        }.toByteArray()

        val nals = H264FlvPacketizer.extractNalUnits(annexB)
        assertEquals(3, nals.size)
        assertEquals(0x67, nals[0][0].toInt())
        assertEquals(0x68, nals[1][0].toInt())
        assertEquals(0x65, nals[2][0].toInt())
    }

    @Test
    fun testH264AvcSequenceHeaderGeneration() {
        val packetizer = H264FlvPacketizer()
        val sps = byteArrayOf(0x67, 0x42, 0x00, 0x1E)
        val pps = byteArrayOf(0x68, 0xCE.toByte())

        val headerPacket = packetizer.buildAvcSequenceHeader(sps, pps, ptsMs = 0L)
        assertEquals(FlvPacket.TYPE_VIDEO, headerPacket.packetType)
        assertTrue(headerPacket.isKeyFrame)
        assertTrue(headerPacket.isSequenceHeader)

        val payload = headerPacket.payload
        // Video Tag Header: 0x17 (Keyframe + AVC), AVCPacketType = 0
        assertEquals(0x17, payload[0].toInt() and 0xFF)
        assertEquals(0x00, payload[1].toInt() and 0xFF)
        // AVCDecoderConfigurationRecord: version 1
        assertEquals(0x01, payload[5].toInt() and 0xFF)
        // AVCProfileIndication: 0x42
        assertEquals(0x42, payload[6].toInt() and 0xFF)
    }

    @Test
    fun testH264FlvPacketizerOutputsSequenceHeaderBeforeIdr() {
        val packetizer = H264FlvPacketizer()
        val sps = byteArrayOf(0x67, 0x42, 0x00, 0x1E)
        val pps = byteArrayOf(0x68, 0xCE.toByte())
        val idr = byteArrayOf(0x65, 0x01, 0x02)

        val annexB = ByteArrayOutputStream().apply {
            write(byteArrayOf(0, 0, 0, 1))
            write(sps)
            write(byteArrayOf(0, 0, 0, 1))
            write(pps)
            write(byteArrayOf(0, 0, 0, 1))
            write(idr)
        }.toByteArray()

        val packets = packetizer.processAnnexBFrame(annexB, ptsMs = 100L, isKeyFrameHint = true)
        assertEquals(2, packets.size)

        val seqHeader = packets[0]
        assertTrue(seqHeader.isSequenceHeader)
        assertTrue(seqHeader.isKeyFrame)

        val idrPacket = packets[1]
        assertFalse(idrPacket.isSequenceHeader)
        assertTrue(idrPacket.isKeyFrame)
        assertEquals(100L, idrPacket.timestampMs)

        // Verify 4-byte length-prefixed NALU
        val idrPayload = idrPacket.payload
        assertEquals(0x17, idrPayload[0].toInt() and 0xFF) // Keyframe + AVC
        assertEquals(0x01, idrPayload[1].toInt() and 0xFF) // NALU packet
        val naluLen = (idrPayload[5].toInt() and 0xFF shl 24) or
                (idrPayload[6].toInt() and 0xFF shl 16) or
                (idrPayload[7].toInt() and 0xFF shl 8) or
                (idrPayload[8].toInt() and 0xFF)
        assertEquals(3, naluLen)
    }

    // --- 3. AAC FLV PACKETIZER TESTS ---

    @Test
    fun testAacAudioSpecificConfigGeneration() {
        // Mono (1 channel)
        val ascMono = AacFlvPacketizer.generateAudioSpecificConfig(sampleRate = 44100, channelCount = 1)
        assertEquals(2, ascMono.size)
        assertEquals(0x12, ascMono[0].toInt() and 0xFF)
        assertEquals(0x08, ascMono[1].toInt() and 0xFF)

        // Stereo (2 channels)
        val ascStereo = AacFlvPacketizer.generateAudioSpecificConfig(sampleRate = 44100, channelCount = 2)
        assertEquals(2, ascStereo.size)
        assertEquals(0x12, ascStereo[0].toInt() and 0xFF)
        assertEquals(0x10, ascStereo[1].toInt() and 0xFF)
    }

    @Test
    fun testAacFlvPacketizerAudioTagHeader() {
        val packetizer = AacFlvPacketizer(sampleRate = 44100, channelCount = 1)
        val rawAac = byteArrayOf(0x21, 0x43, 0x65)

        val packets = packetizer.processAacFrame(rawAac, ptsMs = 50L)
        // First frame should emit Sequence Header + Raw Audio Frame
        assertEquals(2, packets.size)

        val seqHeader = packets[0]
        assertTrue(seqHeader.isSequenceHeader)
        assertEquals(FlvPacket.TYPE_AUDIO, seqHeader.packetType)
        // Tag Header: 0xAF (Per FLV spec, AAC is always 0xAF: AAC + 44kHz + 16-bit + stereo flag), AACPacketType = 0
        assertEquals(0xAF, seqHeader.payload[0].toInt() and 0xFF)
        assertEquals(0x00, seqHeader.payload[1].toInt() and 0xFF)

        val rawPacket = packets[1]
        assertFalse(rawPacket.isSequenceHeader)
        assertEquals(50L, rawPacket.timestampMs)
        assertEquals(0xAF, rawPacket.payload[0].toInt() and 0xFF)
        assertEquals(0x01, rawPacket.payload[1].toInt() and 0xFF) // AACPacketType = 1
    }

    // --- 4. FLV METADATA BUILDER TESTS ---

    @Test
    fun testFlvMetadataBuilder() {
        val metaPacket = FlvMetadataBuilder.buildMetadataPacket(
            width = 480,
            height = 1056,
            videoFps = 24,
            videoBitrateKbps = 1200,
            audioSampleRate = 44100,
            audioBitrateKbps = 96,
            audioChannels = 1
        )

        assertEquals(FlvPacket.TYPE_DATA, metaPacket.packetType)
        assertEquals(0L, metaPacket.timestampMs)
        assertTrue(metaPacket.payload.isNotEmpty())

        val inStream = ByteArrayInputStream(metaPacket.payload)
        val setDataFrame = Amf0Codec.readValue(inStream) as? String
        val onMetaData = Amf0Codec.readValue(inStream) as? String
        @Suppress("UNCHECKED_CAST")
        val metaMap = Amf0Codec.readValue(inStream) as? Map<String, Any?>

        assertEquals("@setDataFrame", setDataFrame)
        assertEquals("onMetaData", onMetaData)
        assertNotNull(metaMap)
        assertEquals(480.0, metaMap!!["width"] as Double, 0.1)
        assertEquals(1056.0, metaMap["height"] as Double, 0.1)
        assertEquals(24.0, metaMap["framerate"] as Double, 0.1)
        assertEquals(1200.0, metaMap["videodatarate"] as Double, 0.1)
        assertEquals(7.0, metaMap["videocodecid"] as Double, 0.1)
        assertEquals(10.0, metaMap["audiocodecid"] as Double, 0.1)
    }

    // --- 5. BOUNDED STREAM QUEUE & BACKPRESSURE TESTS ---

    @Test
    fun testBoundedQueueCapacityAndEviction() {
        // Queue capacity: 5 packets
        val queue = BoundedStreamQueue(maxPacketCount = 5, maxBytesCapacity = 1024 * 1024L)

        val seqHeader = FlvPacket(FlvPacket.TYPE_VIDEO, 0L, ByteArray(10), isKeyFrame = true, isSequenceHeader = true)
        val keyFrame1 = FlvPacket(FlvPacket.TYPE_VIDEO, 30L, ByteArray(10), isKeyFrame = true, isSequenceHeader = false)
        val delta1 = FlvPacket(FlvPacket.TYPE_VIDEO, 60L, ByteArray(10), isKeyFrame = false, isSequenceHeader = false)
        val delta2 = FlvPacket(FlvPacket.TYPE_VIDEO, 90L, ByteArray(10), isKeyFrame = false, isSequenceHeader = false)
        val audio1 = FlvPacket(FlvPacket.TYPE_AUDIO, 100L, ByteArray(10), isKeyFrame = false, isSequenceHeader = false)

        queue.offer(seqHeader)
        queue.offer(keyFrame1)
        queue.offer(delta1)
        queue.offer(delta2)
        queue.offer(audio1)

        assertEquals(5, queue.size)
        assertEquals(0L, queue.droppedCount.get())

        // Offer 6th packet (exceeds maxPacketCount 5)
        val delta3 = FlvPacket(FlvPacket.TYPE_VIDEO, 120L, ByteArray(10), isKeyFrame = false, isSequenceHeader = false)
        queue.offer(delta3)

        // Size should stay clamped at 5, droppedCount should be 1
        assertEquals(5, queue.size)
        assertEquals(1L, queue.droppedCount.get())

        // Verify sequence header and keyframe were NOT evicted
        var foundSeqHeader = false
        var foundKeyFrame = false
        for (i in 0 until 5) {
            val p = queue.poll(10L)
            if (p?.isSequenceHeader == true) foundSeqHeader = true
            if (p?.isKeyFrame == true) foundKeyFrame = true
        }

        assertTrue("Sequence header must never be dropped under backpressure", foundSeqHeader)
        assertTrue("Keyframe must be preserved under backpressure", foundKeyFrame)
    }

    // --- 6. IN-PROCESS RTMP LOOPBACK SERVER INTEGRATION TEST ---

    @Test
    fun testRtmpClientConnectAndPublishLoopback() {
        val serverSocket = ServerSocket(0) // Bind to ephemeral port
        val port = serverSocket.localPort

        val serverHandshakeDone = CountDownLatch(1)
        val publishReceivedLatch = CountDownLatch(1)
        val packetsReceived = AtomicInteger(0)
        val serverRunning = AtomicBoolean(true)

        // Start background mock RTMP loopback server
        val serverThread = Thread {
            try {
                val clientSock = serverSocket.accept()
                val inStream = clientSock.getInputStream()
                val outStream = clientSock.getOutputStream()

                // 1. Read C0 + C1
                val c0 = inStream.read()
                assertEquals(3, c0)
                val c1 = ByteArray(1536)
                readFully(inStream, c1)

                // 2. Send S0 + S1 + S2
                outStream.write(3) // S0
                val s1 = ByteArray(1536)
                outStream.write(s1) // S1
                outStream.write(c1) // S2 (echo C1)
                outStream.flush()

                // 3. Read C2
                val c2 = ByteArray(1536)
                readFully(inStream, c2)
                serverHandshakeDone.countDown()

                val reader = RtmpChunkReader(inStream, inChunkSize = 128)
                val writer = RtmpChunkWriter(outStream, chunkSize = 128)

                // 4. Server receives Set Chunk Size or Connect
                while (serverRunning.get()) {
                    val msg = reader.readNextMessage()

                    if (msg.messageTypeId == 20) { // Command message
                        val amfStream = ByteArrayInputStream(msg.payload)
                        val cmd = Amf0Codec.readValue(amfStream) as? String
                        val txId = Amf0Codec.readValue(amfStream) as? Double ?: 0.0

                        if (cmd == "connect") {
                            // Send _result for connect
                            val out = ByteArrayOutputStream()
                            Amf0Codec.writeString(out, "_result")
                            Amf0Codec.writeNumber(out, txId)
                            val props = mapOf("fmsVer" to "FMS/3,0,1,123", "capabilities" to 31.0)
                            Amf0Codec.writeObject(out, props)
                            val info = mapOf("level" to "status", "code" to "NetConnection.Connect.Success")
                            Amf0Codec.writeObject(out, info)
                            writer.writeMessage(3, 20, 0, 0L, out.toByteArray())
                            outStream.flush()
                        } else if (cmd == "createStream") {
                            // Send _result for createStream with streamId = 1.0
                            val out = ByteArrayOutputStream()
                            Amf0Codec.writeString(out, "_result")
                            Amf0Codec.writeNumber(out, txId)
                            Amf0Codec.writeNull(out)
                            Amf0Codec.writeNumber(out, 1.0)
                            writer.writeMessage(3, 20, 0, 0L, out.toByteArray())
                            outStream.flush()
                        } else if (cmd == "publish") {
                            publishReceivedLatch.countDown()
                            // Send onStatus NetStream.Publish.Start
                            val out = ByteArrayOutputStream()
                            Amf0Codec.writeString(out, "onStatus")
                            Amf0Codec.writeNumber(out, 0.0)
                            Amf0Codec.writeNull(out)
                            val info = mapOf("level" to "status", "code" to "NetStream.Publish.Start")
                            Amf0Codec.writeObject(out, info)
                            writer.writeMessage(3, 20, 1, 0L, out.toByteArray())
                            outStream.flush()
                        }
                    } else if (msg.messageTypeId == 8 || msg.messageTypeId == 9 || msg.messageTypeId == 18) {
                        // Media packets
                        packetsReceived.incrementAndGet()
                    }
                }

                clientSock.close()
            } catch (_: Exception) {
            }
        }
        serverThread.start()

        // Client side
        val endpoint = RtmpEndpoint(host = "127.0.0.1", port = port, appName = "live", streamKey = "test_key_123")
        val client = RtmpClient(timeoutMs = 3000, targetChunkSize = 4096)

        client.connect(endpoint)

        assertTrue("Server should complete handshake", serverHandshakeDone.await(3, TimeUnit.SECONDS))
        assertTrue("Server should receive publish command", publishReceivedLatch.await(3, TimeUnit.SECONDS))
        assertTrue("Client should report connected", client.isConnected)
        assertTrue("Client should report publishing", client.isPublishing)

        // Send a video packet and audio packet
        client.sendPacket(FlvPacket(FlvPacket.TYPE_VIDEO, 0L, byteArrayOf(0x17, 0x00, 0x00, 0x00, 0x00, 0x01)))
        client.sendPacket(FlvPacket(FlvPacket.TYPE_AUDIO, 0L, byteArrayOf(0xAE.toByte(), 0x00, 0x12, 0x10)))

        Thread.sleep(200)
        assertTrue("Server should receive media packets", packetsReceived.get() >= 2)

        client.close()
        serverRunning.set(false)
        serverSocket.close()
        serverThread.join(1000)

        assertFalse("Client should be disconnected after close", client.isConnected)
        assertFalse("Client should not be publishing after close", client.isPublishing)
    }

    private fun readFully(stream: java.io.InputStream, buffer: ByteArray) {
        var total = 0
        while (total < buffer.size) {
            val count = stream.read(buffer, total, buffer.size - total)
            if (count < 0) throw IllegalStateException("EOF")
            total += count
        }
    }
}
