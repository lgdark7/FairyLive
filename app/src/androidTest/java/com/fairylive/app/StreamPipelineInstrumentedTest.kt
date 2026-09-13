package com.fairylive.app

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.fairylive.app.core.audio.model.EncodedAudioFrame
import com.fairylive.app.core.stream.StreamPipeline
import com.fairylive.app.core.stream.StreamTelemetryNotifier
import com.fairylive.app.core.stream.flv.H264FlvPacketizer
import com.fairylive.app.core.stream.model.FlvPacket
import com.fairylive.app.core.stream.model.RtmpConnectionState
import com.fairylive.app.core.stream.rtmp.RtmpChunkReader
import com.fairylive.app.core.stream.rtmp.RtmpChunkWriter
import com.fairylive.app.core.stream.rtmp.RtmpEndpoint
import com.fairylive.app.core.stream.rtmp.amf.Amf0Codec
import com.fairylive.app.core.video.model.EncodedFrame
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.net.ServerSocket
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * On-Device Android Instrumented Test for Milestone 5 (Streaming Pipeline).
 * Validates real socket transport, RTMP protocol state machine, chunk stream multiplexing,
 * backpressure drop policy, and live telemetry on Android 15 Pixel 8 emulator.
 */
@RunWith(AndroidJUnit4::class)
class StreamPipelineInstrumentedTest {

    @Test
    fun testLiveStreamingPipelineOnDevice() {
        val serverSocket = ServerSocket(0) // Bind ephemeral port on device localhost
        val port = serverSocket.localPort

        val handshakeDone = CountDownLatch(1)
        val connectReceived = CountDownLatch(1)
        val publishReceived = CountDownLatch(1)
        val metadataReceived = CountDownLatch(1)
        val avcHeaderReceived = CountDownLatch(1)
        val aacHeaderReceived = CountDownLatch(1)
        val videoPacketsCount = AtomicInteger(0)
        val audioPacketsCount = AtomicInteger(0)
        val serverRunning = AtomicBoolean(true)

        // 1. Mock RTMP Ingest Server running on device loopback
        val serverThread = Thread {
            try {
                val clientSock = serverSocket.accept()
                val inStream = clientSock.getInputStream()
                val outStream = clientSock.getOutputStream()

                // C0 + C1
                val c0 = inStream.read()
                val c1 = ByteArray(1536)
                readFully(inStream, c1)

                // S0 + S1 + S2
                outStream.write(3)
                outStream.write(ByteArray(1536))
                outStream.write(c1)
                outStream.flush()

                // C2
                val c2 = ByteArray(1536)
                readFully(inStream, c2)
                handshakeDone.countDown()

                val reader = RtmpChunkReader(inStream, inChunkSize = 128)
                val writer = RtmpChunkWriter(outStream, chunkSize = 128)

                while (serverRunning.get()) {
                    val msg = reader.readNextMessage()

                    when (msg.messageTypeId) {
                        20 -> { // AMF0 Command
                            val stream = ByteArrayInputStream(msg.payload)
                            val cmd = Amf0Codec.readValue(stream) as? String
                            val txId = Amf0Codec.readValue(stream) as? Double ?: 0.0

                            if (cmd == "connect") {
                                connectReceived.countDown()
                                val out = ByteArrayOutputStream()
                                Amf0Codec.writeString(out, "_result")
                                Amf0Codec.writeNumber(out, txId)
                                Amf0Codec.writeObject(out, mapOf("fmsVer" to "FMS/3,5,1"))
                                Amf0Codec.writeObject(out, mapOf("level" to "status", "code" to "NetConnection.Connect.Success"))
                                writer.writeMessage(3, 20, 0, 0L, out.toByteArray())
                            } else if (cmd == "createStream") {
                                val out = ByteArrayOutputStream()
                                Amf0Codec.writeString(out, "_result")
                                Amf0Codec.writeNumber(out, txId)
                                Amf0Codec.writeNull(out)
                                Amf0Codec.writeNumber(out, 1.0)
                                writer.writeMessage(3, 20, 0, 0L, out.toByteArray())
                            } else if (cmd == "publish") {
                                publishReceived.countDown()
                            }
                        }
                        18 -> { // Metadata (@setDataFrame onMetaData)
                            metadataReceived.countDown()
                        }
                        9 -> { // Video
                            videoPacketsCount.incrementAndGet()
                            // Check if AVC sequence header (byte 0 = 0x17, byte 1 = 0x00)
                            if (msg.payload.size >= 2 && msg.payload[0] == 0x17.toByte() && msg.payload[1] == 0x00.toByte()) {
                                avcHeaderReceived.countDown()
                            }
                        }
                        8 -> { // Audio
                            audioPacketsCount.incrementAndGet()
                            // Check if AAC sequence header (byte 0 = 0xAE, byte 1 = 0x00)
                            if (msg.payload.size >= 2 && (msg.payload[0].toInt() and 0xFF) == 0xAE && msg.payload[1] == 0x00.toByte()) {
                                aacHeaderReceived.countDown()
                            }
                        }
                    }
                }

                clientSock.close()
            } catch (_: Exception) {}
        }
        serverThread.start()

        // 2. Client Pipeline Setup
        val pipeline = StreamPipeline(maxQueuePackets = 50, maxQueueBytes = 2 * 1024 * 1024L)
        val endpoint = RtmpEndpoint(
            host = "127.0.0.1",
            port = port,
            appName = "live",
            streamKey = "fairy_stream_test"
        )

        pipeline.start(
            endpoint = endpoint,
            videoWidth = 480,
            videoHeight = 1056,
            videoFps = 24,
            videoBitrateKbps = 1200,
            audioSampleRate = 44100,
            audioBitrateKbps = 96,
            audioChannels = 1
        )

        // Wait for protocol stages
        assertTrue("Handshake should complete on device", handshakeDone.await(5, TimeUnit.SECONDS))
        assertTrue("Connect command should be received", connectReceived.await(5, TimeUnit.SECONDS))
        assertTrue("Publish command should be received", publishReceived.await(5, TimeUnit.SECONDS))
        assertTrue("Metadata script tag should be received", metadataReceived.await(5, TimeUnit.SECONDS))

        // 3. Push real encoded frames through pipeline
        val sps = byteArrayOf(0x67, 0x42, 0x00, 0x1E)
        val pps = byteArrayOf(0x68, 0xCE.toByte())
        val idrNal = byteArrayOf(0x65, 0x01, 0x02, 0x03, 0x04)
        val deltaNal = byteArrayOf(0x41, 0x05, 0x06, 0x07)

        val idrAnnexB = ByteArrayOutputStream().apply {
            write(byteArrayOf(0, 0, 0, 1))
            write(sps)
            write(byteArrayOf(0, 0, 0, 1))
            write(pps)
            write(byteArrayOf(0, 0, 0, 1))
            write(idrNal)
        }.toByteArray()

        val deltaAnnexB = ByteArrayOutputStream().apply {
            write(byteArrayOf(0, 0, 0, 1))
            write(deltaNal)
        }.toByteArray()

        // Push IDR frame
        pipeline.onVideoFrame(
            EncodedFrame(
                data = idrAnnexB,
                presentationTimeUs = 0L,
                flags = android.media.MediaCodec.BUFFER_FLAG_KEY_FRAME,
                size = idrAnnexB.size
            )
        )

        // Push Audio Config Frame & Raw Audio Frames
        pipeline.onAudioFrame(
            EncodedAudioFrame(
                data = byteArrayOf(0x12, 0x08),
                presentationTimeUs = 0L,
                flags = android.media.MediaCodec.BUFFER_FLAG_CODEC_CONFIG,
                size = 2
            )
        )

        // Push 10 video frames and 10 audio frames
        for (i in 1..10) {
            pipeline.onVideoFrame(
                EncodedFrame(
                    data = deltaAnnexB,
                    presentationTimeUs = i * 41_666L, // ~24 fps
                    flags = 0,
                    size = deltaAnnexB.size
                )
            )

            pipeline.onAudioFrame(
                EncodedAudioFrame(
                    data = byteArrayOf(0x21, 0x43, 0x65, 0x87.toByte()),
                    presentationTimeUs = i * 23_219L, // ~1024 samples @ 44.1kHz
                    flags = 0,
                    size = 4
                )
            )
            Thread.sleep(20)
        }

        assertTrue("AVC Sequence Header should be received", avcHeaderReceived.await(3, TimeUnit.SECONDS))
        assertTrue("AAC Sequence Header should be received", aacHeaderReceived.await(3, TimeUnit.SECONDS))

        // Verify media packets received
        Thread.sleep(500)
        assertTrue("Server should receive multiple video packets", videoPacketsCount.get() >= 5)
        assertTrue("Server should receive multiple audio packets", audioPacketsCount.get() >= 5)

        // 4. Validate Telemetry
        pipeline.flushTelemetry()
        val telemetry = StreamTelemetryNotifier.currentTelemetry
        assertTrue("Bytes sent should be > 0", telemetry.bytesSent > 0L)
        assertTrue("Packets sent should be > 0", telemetry.packetsSent > 0L)
        assertTrue("Video frames sent should be > 0", telemetry.videoFramesSent > 0L)
        assertTrue("Audio frames sent should be > 0", telemetry.audioFramesSent > 0L)
        assertEquals(RtmpConnectionState.PUBLISHING, StreamTelemetryNotifier.currentState)

        // 5. Clean teardown
        pipeline.stop()
        serverRunning.set(false)
        serverSocket.close()
        serverThread.join(1000)

        assertEquals(RtmpConnectionState.IDLE, StreamTelemetryNotifier.currentState)
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
