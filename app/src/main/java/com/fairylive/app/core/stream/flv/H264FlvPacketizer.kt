package com.fairylive.app.core.stream.flv

import com.fairylive.app.core.stream.model.FlvPacket
import java.io.ByteArrayOutputStream

/**
 * Converts Annex-B formatted H.264 elementary streams into FLV AVC video packets.
 * Strictly adheres to Adobe FLV Video Tag and ISO/IEC 14496-15 AVCDecoderConfigurationRecord specifications.
 */
class H264FlvPacketizer {

    private var cachedSps: ByteArray? = null
    private var cachedPps: ByteArray? = null
    private var isHeaderGenerated = false

    /**
     * Updates SPS/PPS cache if provided by MediaFormat csd-0 / csd-1.
     */
    fun setSpsPps(sps: ByteArray, pps: ByteArray) {
        this.cachedSps = stripStartCode(sps)
        this.cachedPps = stripStartCode(pps)
    }

    /**
     * Processes an encoded H.264 Annex-B buffer and returns one or more FlvPackets.
     * Generates AVC sequence header tag prior to the first keyframe.
     */
    fun processAnnexBFrame(
        annexBData: ByteArray,
        ptsMs: Long,
        isKeyFrameHint: Boolean
    ): List<FlvPacket> {
        val nalUnits = extractNalUnits(annexBData)
        if (nalUnits.isEmpty()) return emptyList()

        val outputPackets = mutableListOf<FlvPacket>()
        var containsIdr = false

        for (nal in nalUnits) {
            val type = nal[0].toInt() and 0x1F
            when (type) {
                NAL_SPS -> cachedSps = nal
                NAL_PPS -> cachedPps = nal
                NAL_IDR -> containsIdr = true
            }
        }

        val isKeyFrame = containsIdr || isKeyFrameHint

        // Emit AVC sequence header (AVCDecoderConfigurationRecord) on first keyframe or when SPS/PPS changes
        if (isKeyFrame && (!isHeaderGenerated || cachedSps != null && cachedPps != null)) {
            val sps = cachedSps
            val pps = cachedPps
            if (sps != null && pps != null) {
                val headerTag = buildAvcSequenceHeader(sps, pps, ptsMs)
                outputPackets.add(headerTag)
                isHeaderGenerated = true
            }
        }

        // Build AVC NALU packet
        val videoPayload = ByteArrayOutputStream()
        val frameTypeHeader = if (isKeyFrame) 0x17.toByte() else 0x27.toByte() // 1=Key, 2=Inter + 7=AVC
        videoPayload.write(frameTypeHeader.toInt())
        videoPayload.write(1) // AVCPacketType = 1 (AVC NALU)
        videoPayload.write(0) // CompositionTime offset = 0 (3 bytes)
        videoPayload.write(0)
        videoPayload.write(0)

        var hasNaluPayload = false
        for (nal in nalUnits) {
            val type = nal[0].toInt() and 0x1F
            // Exclude in-band SPS/PPS from regular NALU packet to preserve standard AVC packet structure
            if (type == NAL_SPS || type == NAL_PPS) continue

            val len = nal.size
            videoPayload.write((len shr 24) and 0xFF)
            videoPayload.write((len shr 16) and 0xFF)
            videoPayload.write((len shr 8) and 0xFF)
            videoPayload.write(len and 0xFF)
            videoPayload.write(nal)
            hasNaluPayload = true
        }

        if (hasNaluPayload) {
            outputPackets.add(
                FlvPacket(
                    packetType = FlvPacket.TYPE_VIDEO,
                    timestampMs = ptsMs,
                    payload = videoPayload.toByteArray(),
                    isKeyFrame = isKeyFrame,
                    isSequenceHeader = false
                )
            )
        }

        return outputPackets
    }

    /**
     * Constructs the AVCDecoderConfigurationRecord FLV video tag.
     */
    fun buildAvcSequenceHeader(sps: ByteArray, pps: ByteArray, ptsMs: Long): FlvPacket {
        val out = ByteArrayOutputStream()
        // Video Tag Header
        out.write(0x17) // 1=Keyframe, 7=AVC
        out.write(0)    // AVCPacketType = 0 (Sequence Header)
        out.write(0)    // CompositionTime = 0
        out.write(0)
        out.write(0)

        // AVCDecoderConfigurationRecord
        out.write(1) // configurationVersion = 1
        out.write(if (sps.size > 1) sps[1].toInt() else 0x42) // AVCProfileIndication (Baseline = 66 / 0x42)
        out.write(if (sps.size > 2) sps[2].toInt() else 0x00) // profile_compatibility
        out.write(if (sps.size > 3) sps[3].toInt() else 0x1E) // AVCLevelIndication (e.g. 3.0 = 30 / 0x1E)
        out.write(0xFF) // 11111100 | lengthSizeMinusOne(3) -> 4-byte NALU length
        out.write(0xE1) // 11100000 | numOfSequenceParameterSets(1)

        // SPS length & data
        out.write((sps.size shr 8) and 0xFF)
        out.write(sps.size and 0xFF)
        out.write(sps)

        // PPS count, length & data
        out.write(1) // numOfPictureParameterSets = 1
        out.write((pps.size shr 8) and 0xFF)
        out.write(pps.size and 0xFF)
        out.write(pps)

        return FlvPacket(
            packetType = FlvPacket.TYPE_VIDEO,
            timestampMs = ptsMs,
            payload = out.toByteArray(),
            isKeyFrame = true,
            isSequenceHeader = true
        )
    }

    fun reset() {
        isHeaderGenerated = false
    }

    companion object {
        const val NAL_SLICE = 1
        const val NAL_IDR = 5
        const val NAL_SEI = 6
        const val NAL_SPS = 7
        const val NAL_PPS = 8

        fun extractNalUnits(data: ByteArray): List<ByteArray> {
            val nals = mutableListOf<ByteArray>()
            var startPos = -1

            var i = 0
            while (i < data.size - 3) {
                val is4BytePrefix = data[i] == 0.toByte() && data[i + 1] == 0.toByte() && data[i + 2] == 0.toByte() && data[i + 3] == 1.toByte()
                val is3BytePrefix = data[i] == 0.toByte() && data[i + 1] == 0.toByte() && data[i + 2] == 1.toByte()

                if (is4BytePrefix || is3BytePrefix) {
                    if (startPos != -1) {
                        val nal = data.copyOfRange(startPos, i)
                        if (nal.isNotEmpty()) nals.add(nal)
                    }
                    val prefixLen = if (is4BytePrefix) 4 else 3
                    startPos = i + prefixLen
                    i += prefixLen
                } else {
                    i++
                }
            }

            if (startPos in 0 until data.size) {
                val nal = data.copyOfRange(startPos, data.size)
                if (nal.isNotEmpty()) nals.add(nal)
            }

            return nals
        }

        fun stripStartCode(data: ByteArray): ByteArray {
            return if (data.size >= 4 && data[0] == 0.toByte() && data[1] == 0.toByte() && data[2] == 0.toByte() && data[3] == 1.toByte()) {
                data.copyOfRange(4, data.size)
            } else if (data.size >= 3 && data[0] == 0.toByte() && data[1] == 0.toByte() && data[2] == 1.toByte()) {
                data.copyOfRange(3, data.size)
            } else {
                data
            }
        }
    }
}
