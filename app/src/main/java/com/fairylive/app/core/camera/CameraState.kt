package com.fairylive.app.core.camera

import com.fairylive.app.core.camera.model.CameraFacing

enum class CameraState {
    IDLE,
    OPENING,
    STREAMING,
    SWITCHING,
    STOPPING,
    ERROR
}

data class CameraMetrics(
    val framesCaptured: Long = 0L,
    val currentFps: Double = 0.0,
    val activeCameraId: String? = null,
    val facing: CameraFacing = CameraFacing.UNKNOWN,
    val width: Int = 0,
    val height: Int = 0,
    val errorCount: Long = 0L
)
