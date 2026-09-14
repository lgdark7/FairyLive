package com.fairylive.app.core.optimization.model

enum class DeviceTier {
    LOW_END,
    MID_RANGE,
    FLAGSHIP
}

enum class ThermalThrottlingLevel {
    NOMINAL,
    MODERATE,
    SEVERE,
    CRITICAL
}

data class DeviceProfile(
    val tier: DeviceTier,
    val totalRamMb: Long,
    val availableCores: Int,
    val isLowRamDevice: Boolean,
    val recommendedFps: Int,
    val recommendedBitrateKbps: Int,
    val enableHighQualityOverlays: Boolean
)
