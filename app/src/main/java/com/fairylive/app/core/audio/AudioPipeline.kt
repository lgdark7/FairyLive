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
    val audioEncoder = AacMediaCodecEncoder()

    var onAudioSpecificConfigAvailable: ((ByteArray) -> Unit)? = null
        set(value) {
            field = value
            audioEncoder.onAudioSpecificConfigAvailable = value
        }

    private var config: AudioCaptureConfig = AudioCaptureConfig()

    fun setEncodedAudioFrameListener(listener: AacMediaCodecEncoder.EncodedAudioFrameListener?) {
        audioEncoder.setEncodedAudioFrameListener(listener)
    }

    @Synchronized
    fun start(
        config: AudioCaptureConfig = AudioCaptureConfig(),
        mediaProjection: MediaProjection? = null
    ) {
        stop()

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
        com.fairylive.app.core.lifecycle.StreamLifecycleDiagnostics.audioStarted(config.sampleRate, config.channelCount)
    }

    fun mute() {
        captureEngine.mute()
    }

    fun unmute() {
        captureEngine.unmute()
    }

    fun isMuted(): Boolean = captureEngine.isMuted

    fun setMicVolume(volume: Float) {
        captureEngine.setMicVolume(volume)
    }

    fun setGameVolume(volume: Float) {
        captureEngine.setGameVolume(volume)
    }

    fun setMicMuted(muted: Boolean) {
        captureEngine.setMicMuted(muted)
    }

    fun setStreamPaused(paused: Boolean) {
        captureEngine.setStreamPaused(paused)
    }

    val micVolume: Float get() = captureEngine.micVolume
    val gameVolume: Float get() = captureEngine.gameVolume
    val mediaVolume: Float get() = captureEngine.mediaVolume
    val masterVolume: Float get() = captureEngine.masterVolume
    val isMicMuted: Boolean get() = captureEngine.isMicMuted
    val isGameMuted: Boolean get() = captureEngine.isGameMuted
    val isMediaMuted: Boolean get() = captureEngine.isMediaMuted

    fun setMediaVolume(volume: Float) = captureEngine.setMediaVolume(volume)
    fun setMasterVolume(volume: Float) = captureEngine.setMasterVolume(volume)
    fun setGameMuted(muted: Boolean) = captureEngine.setGameMuted(muted)
    fun setMediaMuted(muted: Boolean) = captureEngine.setMediaMuted(muted)
    fun setDspFilters(noiseGate: Boolean, agc: Boolean, compressor: Boolean, limiter: Boolean) =
        captureEngine.setDspFilters(noiseGate, agc, compressor, limiter)
    fun applyPreset(preset: com.fairylive.app.core.audio.dsp.AudioMixerProcessor.AudioPreset) =
        captureEngine.applyPreset(preset)
    fun getAudioMixer() = captureEngine.audioMixer

    @Synchronized
    fun stop() {
        captureEngine.setAudioSampleListener(null)
        captureEngine.stop()
        audioEncoder.stop()
    }
}
