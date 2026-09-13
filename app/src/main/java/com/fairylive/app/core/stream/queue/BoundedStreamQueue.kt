package com.fairylive.app.core.stream.queue

import com.fairylive.app.core.stream.model.FlvPacket
import java.util.ArrayDeque
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * Bounded, backpressure-aware packet queue.
 * Strictly caps memory consumption and drops delta video frames during network congestion
 * while preserving critical sequence headers and keyframes.
 */
class BoundedStreamQueue(
    val maxPacketCount: Int = 90, // ~2-3 seconds of packets at 30fps
    val maxBytesCapacity: Long = 4 * 1024 * 1024L // 4 MB memory ceiling
) {
    private val queue = ArrayDeque<FlvPacket>()
    private val lock = ReentrantLock()
    private val notEmpty = lock.newCondition()

    private var currentByteCount: Long = 0L
    val droppedCount = AtomicLong(0L)

    val size: Int
        get() = lock.withLock { queue.size }

    val bytesInQueue: Long
        get() = lock.withLock { currentByteCount }

    /**
     * Attempts to offer a packet into the queue.
     * If capacity is exceeded, applies backpressure eviction policy.
     */
    fun offer(packet: FlvPacket): Boolean {
        lock.withLock {
            val packetSize = packet.payload.size.toLong()

            // Backpressure eviction if limits are breached
            while ((queue.size >= maxPacketCount || (currentByteCount + packetSize) > maxBytesCapacity) && queue.isNotEmpty()) {
                val evicted = evictLowestPriorityPacket()
                if (!evicted) {
                    // Could not safely evict (e.g. queue only contains sequence headers)
                    // If the incoming packet is not a sequence header or keyframe, drop it
                    if (!packet.isSequenceHeader && !packet.isKeyFrame) {
                        droppedCount.incrementAndGet()
                        return false
                    }
                    break
                } else {
                    droppedCount.incrementAndGet()
                }
            }

            queue.addLast(packet)
            currentByteCount += packetSize
            notEmpty.signal()
            return true
        }
    }

    /**
     * Evicts the oldest dispensable non-keyframe video packet.
     * Sequence headers and keyframes are protected from eviction.
     */
    private fun evictLowestPriorityPacket(): Boolean {
        val iterator = queue.iterator()
        while (iterator.hasNext()) {
            val p = iterator.next()
            // Priority 1: Evict non-keyframe video packets
            if (p.isVideo && !p.isKeyFrame && !p.isSequenceHeader) {
                iterator.remove()
                currentByteCount -= p.payload.size
                return true
            }
        }

        // Priority 2: Evict oldest audio packet if desperate
        val audioIter = queue.iterator()
        while (audioIter.hasNext()) {
            val p = audioIter.next()
            if (p.isAudio && !p.isSequenceHeader) {
                audioIter.remove()
                currentByteCount -= p.payload.size
                return true
            }
        }

        return false
    }

    /**
     * Polls the next packet with timeout.
     */
    fun poll(timeoutMs: Long): FlvPacket? {
        lock.withLock {
            if (queue.isEmpty()) {
                val hasItem = notEmpty.await(timeoutMs, TimeUnit.MILLISECONDS)
                if (!hasItem || queue.isEmpty()) return null
            }
            val packet = queue.removeFirst()
            currentByteCount -= packet.payload.size
            return packet
        }
    }

    fun clear() {
        lock.withLock {
            queue.clear()
            currentByteCount = 0L
            notEmpty.signalAll()
        }
    }
}
