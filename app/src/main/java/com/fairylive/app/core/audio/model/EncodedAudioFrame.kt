package com.fairylive.app.core.audio.model

import android.media.MediaCodec

/**
 * Encapsulates an encoded AAC audio packet.
 */
data class EncodedAudioFrame(
    val data: ByteArray,
    val presentationTimeUs: Long,
    val flags: Int,
    val size: Int
) {
    val isConfigFrame: Boolean
        get() = (flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0

    val isEndOfStream: Boolean
        get() = (flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false
        other as EncodedAudioFrame
        return presentationTimeUs == other.presentationTimeUs && flags == other.flags && size == other.size
    }

    override fun hashCode(): Int {
        var result = presentationTimeUs.hashCode()
        result = 31 * result + flags
        result = 31 * result + size
        return result
    }
}
