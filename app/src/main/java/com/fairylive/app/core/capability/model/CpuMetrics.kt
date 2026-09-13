package com.fairylive.app.core.capability.model

data class CpuMetrics(
    val coreCount: Int,
    val primaryAbi: String
) {
    val isConstrained: Boolean
        get() = coreCount <= 4
}
