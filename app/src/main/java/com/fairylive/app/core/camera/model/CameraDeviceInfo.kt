package com.fairylive.app.core.camera.model

import android.util.Range

/**
 * Pure Kotlin camera frame resolution representation.
 * Eliminates android.util.Size JVM unit-test stubbing limitations.
 */
data class CameraSize(val width: Int, val height: Int) : Comparable<CameraSize> {
    val area: Int get() = width * height
    val aspectRatio: Float get() = if (height > 0) width.toFloat() / height.toFloat() else 1.0f

    override fun compareTo(other: CameraSize): Int = area.compareTo(other.area)
    override fun toString(): String = "${width}x${height}"
}

/**
 * Camera hardware facing directions.
 */
enum class CameraFacing {
    FRONT,
    BACK,
    EXTERNAL,
    UNKNOWN
}

/**
 * Hardware and capability profile for a discovered Camera2 device.
 */
data class CameraDeviceInfo(
    val cameraId: String,
    val facing: CameraFacing,
    val sensorOrientation: Int,
    val supportedSizes: List<CameraSize>,
    val supportedFpsRanges: List<Range<Int>>,
    val hardwareLevel: Int,
    val isFrontFacing: Boolean = (facing == CameraFacing.FRONT)
)
