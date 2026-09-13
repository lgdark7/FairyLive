package com.fairylive.app.core.stream.model

/**
 * Encapsulates an FLV/RTMP media tag ready for chunk serialization.
 */
data class FlvPacket(
    val packetType: Int, // 8 = Audio, 9 = Video, 18 = ScriptData (Metadata)
    val timestampMs: Long,
    val payload: ByteArray,
    val isKeyFrame: Boolean = false,
    val isSequenceHeader: Boolean = false
) {
    val isVideo: Boolean
        get() = packetType == TYPE_VIDEO

    val isAudio: Boolean
        get() = packetType == TYPE_AUDIO

    val isMetadata: Boolean
        get() = packetType == TYPE_DATA

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false
        other as FlvPacket
        return packetType == other.packetType &&
                timestampMs == other.timestampMs &&
                isKeyFrame == other.isKeyFrame &&
                isSequenceHeader == other.isSequenceHeader &&
                payload.contentEquals(other.payload)
    }

    override fun hashCode(): Int {
        var result = packetType
        result = 31 * result + timestampMs.hashCode()
        result = 31 * result + isKeyFrame.hashCode()
        result = 31 * result + isSequenceHeader.hashCode()
        result = 31 * result + payload.contentHashCode()
        return result
    }

    companion object {
        const val TYPE_AUDIO = 8
        const val TYPE_VIDEO = 9
        const val TYPE_DATA = 18
    }
}
