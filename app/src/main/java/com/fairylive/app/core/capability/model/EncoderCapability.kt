package com.fairylive.app.core.capability.model

enum class EncoderType {
    HARDWARE,
    SOFTWARE,
    UNKNOWN
}

data class EncoderCapability(
    val codecName: String,
    val mimeType: String,
    val encoderType: EncoderType,
    val maxSupportedWidth: Int = 1920,
    val maxSupportedHeight: Int = 1080,
    val supportsSurfaceInput: Boolean = false
) {
    val isHardwareAccelerated: Boolean
        get() = encoderType == EncoderType.HARDWARE

    val isSoftwareOnly: Boolean
        get() = encoderType == EncoderType.SOFTWARE
}
