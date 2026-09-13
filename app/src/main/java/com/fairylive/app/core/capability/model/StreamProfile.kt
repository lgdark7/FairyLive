package com.fairylive.app.core.capability.model

/**
 * Streaming configuration profiles tuned for device capabilities.
 * The LOW profile is prioritized for constrained hardware (low RAM, weak SoC, software encoders).
 */
enum class StreamProfile(
    val title: String,
    val width: Int,
    val height: Int,
    val fps: Int,
    val videoBitrateKbps: Int,
    val audioBitrateKbps: Int,
    val description: String
) {
    LOW(
        title = "Low-End Safe",
        width = 854,
        height = 480,
        fps = 24,
        videoBitrateKbps = 1200,
        audioBitrateKbps = 96,
        description = "480p @ 24fps - Maximum stability for low-RAM & budget hardware"
    ),
    BALANCED(
        title = "Balanced HD",
        width = 1280,
        height = 720,
        fps = 30,
        videoBitrateKbps = 2200,
        audioBitrateKbps = 128,
        description = "720p @ 30fps - Standard HD for mid-tier devices"
    ),
    HIGH(
        title = "High Quality",
        width = 1920,
        height = 1080,
        fps = 30,
        videoBitrateKbps = 4000,
        audioBitrateKbps = 160,
        description = "1080p @ 30fps - High performance devices only"
    ),
    CUSTOM(
        title = "Custom",
        width = 1280,
        height = 720,
        fps = 30,
        videoBitrateKbps = 2200,
        audioBitrateKbps = 128,
        description = "User-defined custom resolution, bitrate, and framerate"
    )
}
