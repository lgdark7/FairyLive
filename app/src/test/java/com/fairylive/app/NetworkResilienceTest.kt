package com.fairylive.app

import com.fairylive.app.core.stream.model.FlvPacket
import com.fairylive.app.core.stream.model.RtmpConnectionState
import com.fairylive.app.core.stream.queue.BoundedStreamQueue
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NetworkResilienceTest {

    @Test
    fun testRtmpStateNeverFalselyLive() {
        // Non-publishing states must never report isStreaming == true
        assertFalse("IDLE must not report streaming", RtmpConnectionState.IDLE.isStreaming)
        assertFalse("CONNECTING must not report streaming", RtmpConnectionState.CONNECTING.isStreaming)
        assertFalse("HANDSHAKING must not report streaming", RtmpConnectionState.HANDSHAKING.isStreaming)
        assertFalse("CONNECTED must not report streaming", RtmpConnectionState.CONNECTED.isStreaming)
        assertFalse("CREATING_STREAM must not report streaming", RtmpConnectionState.CREATING_STREAM.isStreaming)
        assertFalse("RECONNECTING must not report streaming", RtmpConnectionState.RECONNECTING.isStreaming)
        assertFalse("DISCONNECTED must not report streaming", RtmpConnectionState.DISCONNECTED.isStreaming)
        assertFalse("FAILED must not report streaming", RtmpConnectionState.FAILED.isStreaming)
        assertFalse("ERROR must not report streaming", RtmpConnectionState.ERROR.isStreaming)
        assertFalse("STOPPING must not report streaming", RtmpConnectionState.STOPPING.isStreaming)

        // Only PUBLISHING is streaming (LIVE)
        assertTrue("PUBLISHING must report streaming", RtmpConnectionState.PUBLISHING.isStreaming)
    }

    @Test
    fun testBoundedQueueBackpressureEvictsDeltaFramesWhilePreservingKeyframes() {
        // Small test queue: max 5 packets, 5000 bytes
        val queue = BoundedStreamQueue(maxPacketCount = 5, maxBytesCapacity = 5000L)

        // 1. Offer sequence header (protected)
        val seqHeader = FlvPacket(
            packetType = FlvPacket.TYPE_VIDEO,
            timestampMs = 0L,
            payload = ByteArray(100),
            isKeyFrame = true,
            isSequenceHeader = true
        )
        assertTrue(queue.offer(seqHeader))

        // 2. Offer keyframe (protected)
        val keyFrame = FlvPacket(
            packetType = FlvPacket.TYPE_VIDEO,
            timestampMs = 33L,
            payload = ByteArray(200),
            isKeyFrame = true,
            isSequenceHeader = false
        )
        assertTrue(queue.offer(keyFrame))

        // 3. Offer 3 delta frames (dispensable)
        for (i in 1..3) {
            val deltaFrame = FlvPacket(
                packetType = FlvPacket.TYPE_VIDEO,
                timestampMs = 33L + i * 33L,
                payload = ByteArray(150),
                isKeyFrame = false,
                isSequenceHeader = false
            )
            assertTrue(queue.offer(deltaFrame))
        }
        assertEquals(5, queue.size)

        // 4. Offer 6th packet (exceeds capacity) -> backpressure must evict oldest delta frame
        val newDelta = FlvPacket(
            packetType = FlvPacket.TYPE_VIDEO,
            timestampMs = 165L,
            payload = ByteArray(150),
            isKeyFrame = false,
            isSequenceHeader = false
        )
        assertTrue("Queue must accept packet after backpressure eviction", queue.offer(newDelta))
        assertEquals(5, queue.size)
        assertEquals(1L, queue.droppedCount.get())

        // 5. Verify sequence header and keyframe were NOT evicted
        val polled1 = queue.poll(0L)
        assertTrue("Sequence header must remain at head of queue", polled1?.isSequenceHeader == true)

        val polled2 = queue.poll(0L)
        assertTrue("Keyframe must remain protected in queue", polled2?.isKeyFrame == true)
    }
}
