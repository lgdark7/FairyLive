package com.fairylive.app.core.capability.model

data class RamMetrics(
    val totalMemMb: Long,
    val availMemMb: Long,
    val isLowRamDevice: Boolean,
    val memoryClassMb: Int
) {
    val isConstrained: Boolean
        get() = isLowRamDevice || totalMemMb < 3072L // Under 3GB is treated as constrained
}
