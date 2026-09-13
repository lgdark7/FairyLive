package com.fairylive.app.core.stream.flv

import com.fairylive.app.core.stream.model.FlvPacket
import com.fairylive.app.core.stream.rtmp.amf.Amf0Codec
import java.io.ByteArrayOutputStream

/**
 * Builds the FLV `@setDataFrame` onMetaData script tag.
 */
object FlvMetadataBuilder {

    fun buildMetadataPacket(
        width: Int,
        height: Int,
        videoFps: Int,
        videoBitrateKbps: Int,
        audioSampleRate: Int = 44100,
        audioBitrateKbps: Int = 96,
        audioChannels: Int = 1
    ): FlvPacket {
        val out = ByteArrayOutputStream()

        // 1. String "@setDataFrame"
        Amf0Codec.writeString(out, "@setDataFrame")

        // 2. String "onMetaData"
        Amf0Codec.writeString(out, "onMetaData")

        // 3. ECMA Array with stream metadata properties
        val meta = linkedMapOf<String, Any?>(
            "duration" to 0.0,
            "width" to width.toDouble(),
            "height" to height.toDouble(),
            "videodatarate" to videoBitrateKbps.toDouble(),
            "framerate" to videoFps.toDouble(),
            "videocodecid" to 7.0, // 7 = AVC/H.264
            "audiodatarate" to audioBitrateKbps.toDouble(),
            "audiosamplerate" to audioSampleRate.toDouble(),
            "audiosamplesize" to 16.0,
            "stereo" to (audioChannels == 2),
            "audiocodecid" to 10.0, // 10 = AAC
            "encoder" to "FairyLive Native Android Streamer"
        )

        Amf0Codec.writeEcmaArray(out, meta)

        return FlvPacket(
            packetType = FlvPacket.TYPE_DATA,
            timestampMs = 0L,
            payload = out.toByteArray(),
            isKeyFrame = false,
            isSequenceHeader = true
        )
    }
}
