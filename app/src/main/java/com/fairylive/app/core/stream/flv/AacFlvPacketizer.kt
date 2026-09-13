package com.fairylive.app.core.stream.flv

import com.fairylive.app.core.stream.model.FlvPacket
import java.io.ByteArrayOutputStream

/**
 * Converts raw AAC audio frames into FLV audio packets.
 * Manages AudioSpecificConfig sequence header generation and audio tag headers.
 */
class AacFlvPacketizer(
    val sampleRate: Int = 44100,
    val channelCount: Int = 1
) {
    private var cachedAudioSpecificConfig: ByteArray? = null
    private var isHeaderGenerated = false

    private val audioTagHeaderByte: Int
        get() {
            // SoundFormat = 10 (AAC, 0xA0)
            // SoundRate = 3 (44kHz, 0x0C) - for AAC this is always 3 in FLV
            // SoundSize = 1 (16-bit, 0x02)
            // SoundType = 1 for stereo, 0 for mono
            val soundType = if (channelCount == 2) 1 else 0
            return 0xAE or soundType
        }

    fun setAudioSpecificConfig(config: ByteArray) {
        this.cachedAudioSpecificConfig = config
    }

    /**
     * Builds the AAC sequence header tag containing the AudioSpecificConfig (2 bytes).
     */
    fun buildAacSequenceHeader(ptsMs: Long): FlvPacket {
        val config = cachedAudioSpecificConfig ?: generateAudioSpecificConfig(sampleRate, channelCount)

        val out = ByteArrayOutputStream()
        out.write(audioTagHeaderByte)
        out.write(0) // AACPacketType = 0 (Sequence Header)
        out.write(config)

        isHeaderGenerated = true

        return FlvPacket(
            packetType = FlvPacket.TYPE_AUDIO,
            timestampMs = ptsMs,
            payload = out.toByteArray(),
            isKeyFrame = true,
            isSequenceHeader = true
        )
    }

    /**
     * Packages a raw AAC compressed audio access unit into an FLV audio packet.
     */
    fun processAacFrame(aacData: ByteArray, ptsMs: Long): List<FlvPacket> {
        val list = mutableListOf<FlvPacket>()

        if (!isHeaderGenerated) {
            list.add(buildAacSequenceHeader(ptsMs))
        }

        val out = ByteArrayOutputStream()
        out.write(audioTagHeaderByte)
        out.write(1) // AACPacketType = 1 (Raw AAC data)
        out.write(aacData)

        list.add(
            FlvPacket(
                packetType = FlvPacket.TYPE_AUDIO,
                timestampMs = ptsMs,
                payload = out.toByteArray(),
                isKeyFrame = false,
                isSequenceHeader = false
            )
        )

        return list
    }

    fun reset() {
        isHeaderGenerated = false
    }

    companion object {
        /**
         * Generates standard ISO/IEC 14496-3 AudioSpecificConfig:
         * 5 bits: audioObjectType (2 = AAC-LC)
         * 4 bits: samplingFrequencyIndex
         * 4 bits: channelConfiguration
         * 3 bits: padding
         */
        fun generateAudioSpecificConfig(sampleRate: Int, channelCount: Int): ByteArray {
            val freqIndex = when (sampleRate) {
                96000 -> 0
                88200 -> 1
                64000 -> 2
                48000 -> 3
                44100 -> 4
                32000 -> 5
                24000 -> 6
                22050 -> 7
                16000 -> 8
                12000 -> 9
                11025 -> 10
                8000 -> 11
                7350 -> 12
                else -> 4 // default 44100
            }

            val audioObjectType = 2 // AAC-LC
            val val16 = ((audioObjectType and 0x1F) shl 11) or
                    ((freqIndex and 0x0F) shl 7) or
                    ((channelCount and 0x0F) shl 3)

            return byteArrayOf(
                ((val16 shr 8) and 0xFF).toByte(),
                (val16 and 0xFF).toByte()
            )
        }
    }
}
