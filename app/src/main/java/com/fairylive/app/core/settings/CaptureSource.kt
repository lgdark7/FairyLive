package com.fairylive.app.core.settings

/**
 * Defines the MediaProjection capture isolation source.
 * - [SINGLE_APP]: Android 14+ task-isolated capture (excludes Creator Studio, HUD, and notifications).
 * - [ENTIRE_SCREEN]: Full-display capture.
 */
enum class CaptureSource {
    SINGLE_APP,
    ENTIRE_SCREEN
}
