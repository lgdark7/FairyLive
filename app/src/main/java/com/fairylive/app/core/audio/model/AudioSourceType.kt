package com.fairylive.app.core.audio.model

enum class AudioSourceType(val title: String) {
    MIC("Microphone"),
    INTERNAL_PLAYBACK("Internal Device Audio"),
    MIXED("Mic + Device Audio")
}
