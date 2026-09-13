package com.fairylive.app.core.audio

import android.media.projection.MediaProjection
import com.fairylive.app.core.audio.model.AudioCaptureConfig
import com.fairylive.app.core.audio.model.EncodedAudioFrame

/**
 * High-level coordinator uniting AudioRecord capture, sample-accurate timestamping,
 * mute management, and MediaCodec AAC encoding into a unified audio streaming pipeline.
 */
class AudioPipeline {

    val captureEngine = AudioCaptureEngine()
    val audioEncoder = HardwareAudioEncoder()

    private var config: AudioCaptureConfig = AudioCaptureConfig()

    fun setEncodedAudioFrameListener(listener: HardwareAudioEncoder.EncodedAudioFrameListener?) {
        audioEncoder.setEncodedAudioFrameListener(listener)
    }

    @Synchronized
    fun start(
        config: AudioCaptureConfig = AudioCaptureConfig(),
        mediaProjection: MediaProjection? = null
    ) {
        this.config = config

        // 1. Initialize and start AAC audio encoder
        audioEncoder.initialize(config)
        audioEncoder.start()

        // 2. Route raw PCM buffers directly into encoder input with monotonic PTS
        captureEngine.setAudioSampleListener { data, size, ptsUs ->
            audioEncoder.queuePcm(data, size, ptsUs)
        }

        // 3. Start AudioRecord capture
        captureEngine.start(config, mediaProjection)
    }

    fun mute() {
        captureEngine.mute()
    }

    fun unmute() {
        captureEngine.unmute()
    }

    fun isMuted(): Boolean = captureEngine.isMuted

    @Synchronized
    fun stop() {
        captureEngine.stop()
        audioEncoder.stop()
    }
}
