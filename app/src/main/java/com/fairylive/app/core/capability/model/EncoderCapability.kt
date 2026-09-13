package com.fairylive.app.core.capability.model

data class EncoderCapability(
    val codecName: String,
    val mimeType: String,
    val isHardwareAccelerated: Boolean,
    val maxSupportedWidth: Int = 1920,
    val maxSupportedHeight: Int = 1080
)
