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

    /**
     * Checks if battery optimizations are ignored for Fairy Live (unrestricted background execution).
     * When false on OEM devices (Xiaomi, Vivo, Oppo, Samsung), the OS may throttle or mute
     * the background microphone thread when the streamer switches into a game.
     */
    fun isIgnoringBatteryOptimizations(context: Context): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val powerManager = context.getSystemService(Context.POWER_SERVICE) as? android.os.PowerManager
            return powerManager?.isIgnoringBatteryOptimizations(context.packageName) ?: true
        }
        return true
    }

    /**
     * Creates an intent to request battery optimization exemption via native system dialog.
     */
    fun createIgnoreBatteryOptimizationIntent(context: Context): android.content.Intent {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            android.content.Intent(android.provider.Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                data = android.net.Uri.parse("package:${context.packageName}")
            }
        } else {
            android.content.Intent(android.provider.Settings.ACTION_SETTINGS)
        }
    }

    /**
     * Returns friendly OEM brand name for UI messaging.
     */
    fun getOemBrand(): String {
        val mfr = (Build.MANUFACTURER ?: "").lowercase()
        return when {
            mfr.contains("xiaomi") || mfr.contains("redmi") || mfr.contains("poco") -> "Xiaomi / Redmi"
            mfr.contains("samsung") -> "Samsung"
            mfr.contains("oppo") || mfr.contains("realme") || mfr.contains("oneplus") -> "Oppo / Realme"
            mfr.contains("vivo") || mfr.contains("iqoo") -> "Vivo / iQOO"
            mfr.contains("huawei") || mfr.contains("honor") -> "Huawei / Honor"
            mfr.contains("transsion") || mfr.contains("tecno") || mfr.contains("infinix") -> "Tecno / Infinix"
            mfr.isNotBlank() -> mfr.replaceFirstChar { if (it.isLowerCase()) it.titlecase(java.util.Locale.US) else it.toString() }
            else -> "Device"
        }
    }

    /**
     * Determines if device manufacturer is known to aggressively kill background audio/services.
     */
    fun isOemRestrictedDevice(): Boolean {
        val mfr = (Build.MANUFACTURER ?: "").lowercase()
        return mfr.contains("xiaomi") || mfr.contains("redmi") || mfr.contains("poco") ||
                mfr.contains("samsung") || mfr.contains("vivo") || mfr.contains("iqoo") ||
                mfr.contains("oppo") || mfr.contains("realme") || mfr.contains("oneplus") ||
                mfr.contains("huawei") || mfr.contains("honor") || mfr.contains("transsion")
    }

    /**
     * Attempts to resolve manufacturer-specific background permission / security manager screens.
     */
    fun getOemBackgroundSettingsIntent(context: Context): android.content.Intent? {
        val mfr = (Build.MANUFACTURER ?: "").lowercase()
        val pkg = context.packageName

        val intents = mutableListOf<android.content.Intent>()
        if (mfr.contains("xiaomi") || mfr.contains("redmi") || mfr.contains("poco")) {
            // MIUI / HyperOS App Permission Editor (Display pop-ups in background, autostart)
            intents.add(android.content.Intent("miui.intent.action.APP_PERM_EDITOR").apply {
                setClassName("com.miui.securitycenter", "com.miui.permcenter.permissions.PermissionsEditorActivity")
                putExtra("extra_pkgname", pkg)
            })
            intents.add(android.content.Intent().apply {
                component = android.content.ComponentName("com.miui.securitycenter", "com.miui.permcenter.autostart.AutoStartManagementActivity")
            })
            intents.add(android.content.Intent("miui.intent.action.POWER_HIDE_MODE_APP_LIST").apply {
                putExtra("package_name", pkg)
            })
        } else if (mfr.contains("vivo") || mfr.contains("iqoo")) {
            intents.add(android.content.Intent().apply {
                component = android.content.ComponentName("com.iqoo.secure", "com.iqoo.secure.ui.phoneoptimize.AddWhiteListActivity")
            })
            intents.add(android.content.Intent().apply {
                component = android.content.ComponentName("com.vivo.permissionmanager", "com.vivo.permissionmanager.activity.PurviewTabActivity")
            })
        } else if (mfr.contains("oppo") || mfr.contains("realme") || mfr.contains("oneplus")) {
            intents.add(android.content.Intent().apply {
                component = android.content.ComponentName("com.coloros.safecenter", "com.coloros.safecenter.permission.startup.StartupAppListActivity")
            })
            intents.add(android.content.Intent().apply {
                component = android.content.ComponentName("com.coloros.safecenter", "com.coloros.safecenter.startupapp.StartupAppListActivity")
            })
        } else if (mfr.contains("huawei") || mfr.contains("honor")) {
            intents.add(android.content.Intent().apply {
                component = android.content.ComponentName("com.huawei.systemmanager", "com.huawei.systemmanager.optimize.process.ProtectActivity")
            })
        }

        for (intent in intents) {
            try {
                if (intent.resolveActivity(context.packageManager) != null) {
                    intent.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                    return intent
                }
            } catch (_: Exception) {}
        }

        // Universal fallback to Application Details Settings
        return try {
            android.content.Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                data = android.net.Uri.parse("package:$pkg")
                addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
            }
        } catch (_: Exception) {
            null
        }
    }
}
