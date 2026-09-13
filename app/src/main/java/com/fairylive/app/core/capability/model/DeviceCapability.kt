package com.fairylive.app.core.capability.model

data class DeviceCapability(
    val sdkInt: Int,
    val model: String,
    val manufacturer: String,
    val ram: RamMetrics,
    val cpu: CpuMetrics,
    val videoEncoders: List<EncoderCapability>,
    val isLowEndDevice: Boolean,
    val recommendedProfile: StreamProfile
) {
    val hasHardwareH264: Boolean
        get() = videoEncoders.any { it.isHardwareAccelerated && it.mimeType == "video/avc" }
}
