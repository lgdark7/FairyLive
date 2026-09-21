package com.fairylive.app.core.permission

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat

/**
 * Centralized permission manager for Fairy Live.
 * Validates audio, camera, and notification permissions across Android API levels.
 */
object PermissionManager {

    val REQUIRED_PERMISSIONS: Array<String> by lazy {
        val list = mutableListOf(
            Manifest.permission.RECORD_AUDIO,
            Manifest.permission.CAMERA
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            list.add(Manifest.permission.POST_NOTIFICATIONS)
        }
        list.toTypedArray()
    }

    fun isPermissionGranted(context: Context, permission: String): Boolean {
        return ContextCompat.checkSelfPermission(
            context,
            permission
        ) == PackageManager.PERMISSION_GRANTED
    }

    fun areCorePermissionsGranted(context: Context): Boolean {
        return REQUIRED_PERMISSIONS.all { isPermissionGranted(context, it) }
    }

    fun getMissingPermissions(context: Context): List<String> {
        return REQUIRED_PERMISSIONS.filter { !isPermissionGranted(context, it) }
    }

    fun hasAudioPermission(context: Context): Boolean {
        return isPermissionGranted(context, Manifest.permission.RECORD_AUDIO)
    }

    fun hasCameraPermission(context: Context): Boolean {
        return isPermissionGranted(context, Manifest.permission.CAMERA)
    }

    fun hasNotificationPermission(context: Context): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            isPermissionGranted(context, Manifest.permission.POST_NOTIFICATIONS)
        } else {
            true
        }
    }

    /**
     * Checks if the app has permission to draw overlays over other apps (SYSTEM_ALERT_WINDOW).
     */
    fun hasOverlayPermission(context: Context): Boolean {
        return android.provider.Settings.canDrawOverlays(context)
    }
}
