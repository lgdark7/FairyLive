package com.fairylive.app.core.updater

/**
 * Metadata representing a remote Over-The-Air (OTA) application update.
 */
data class UpdateInfo(
    val versionCode: Int,
    val versionName: String,
    val releaseNotes: String,
    val downloadUrl: String,
    val isMandatory: Boolean = false,
    val sha256: String? = null
)
