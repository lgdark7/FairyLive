package com.fairylive.app.core.updater

import android.app.Activity
import android.app.ProgressDialog
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.Log
import android.widget.Toast
import androidx.core.content.FileProvider
import com.fairylive.app.BuildConfig
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors

/**
 * Enterprise-grade In-App OTA (Over-The-Air) Auto-Updater for Fairy Live.
 *
 * Responsibilities:
 * - Checks remote version metadata asynchronously on GitHub CDN (zero rate-limiting).
 * - Compares remote versionCode against installed BuildConfig.VERSION_CODE.
 * - Displays an elegant Nocturnal Violet update dialog with release notes.
 * - Streams and caches APK updates securely into app cache.
 * - Dispatches Android PackageInstaller via FileProvider for 1-tap in-place upgrade.
 */
object AppUpdateManager {

    private const val TAG = "AppUpdateManager"

    // Primary endpoint: static version.json on main branch (high CDN availability, zero rate limits)
    private const val VERSION_JSON_URL =
        "https://raw.githubusercontent.com/lgdark7/fairylive-releases/main/version.json"

    private val executor = Executors.newSingleThreadExecutor()
    private val mainHandler = Handler(Looper.getMainLooper())

    /**
     * Check for available updates in the background.
     * Invoked automatically on app launch (e.g. from MainActivity).
     */
    fun checkForUpdate(
        context: Context,
        onUpdateFound: (UpdateInfo) -> Unit,
        onUpToDate: (() -> Unit)? = null,
        onError: ((String) -> Unit)? = null
    ) {
        executor.execute {
            try {
                val updateInfo = fetchRemoteVersionInfo()
                mainHandler.post {
                    if (updateInfo != null && updateInfo.versionCode > BuildConfig.VERSION_CODE) {
                        Log.i(TAG, "New update available: v${updateInfo.versionName} (code=${updateInfo.versionCode} > current=${BuildConfig.VERSION_CODE})")
                        onUpdateFound(updateInfo)
                    } else {
                        Log.d(TAG, "App is up to date (current=${BuildConfig.VERSION_CODE})")
                        onUpToDate?.invoke()
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "Failed to check for updates: ${e.message}")
                mainHandler.post {
                    onError?.invoke(e.message ?: "Network error")
                }
            }
        }
    }

    /**
     * Manual update check triggered by user from Settings.
     */
    fun checkUpdateManually(activity: Activity) {
        Toast.makeText(activity, "Checking for Fairy Live updates...", Toast.LENGTH_SHORT).show()

        checkForUpdate(
            context = activity,
            onUpdateFound = { updateInfo ->
                showUpdateDialog(activity, updateInfo)
            },
            onUpToDate = {
                Toast.makeText(
                    activity,
                    "✓ Fairy Live is up to date (v${BuildConfig.VERSION_NAME})",
                    Toast.LENGTH_LONG
                ).show()
            },
            onError = { error ->
                Toast.makeText(
                    activity,
                    "Unable to check for updates: $error",
                    Toast.LENGTH_LONG
                ).show()
            }
        )
    }

    /**
     * Shows a Nocturnal Violet update alert dialog with release notes.
     */
    fun showUpdateDialog(activity: Activity, updateInfo: UpdateInfo) {
        if (activity.isFinishing || activity.isDestroyed) return

        val builder = MaterialAlertDialogBuilder(activity)
            .setTitle("🎉 Update Available (v${updateInfo.versionName})")
            .setMessage(
                "A new version of Fairy Live is ready to install!\n\n" +
                "What's New in v${updateInfo.versionName}:\n" +
                "${updateInfo.releaseNotes}\n\n" +
                "Current version: v${BuildConfig.VERSION_NAME}"
            )
            .setPositiveButton("Update Now") { _, _ ->
                promptInstallPermissionAndDownload(activity, updateInfo)
            }

        if (!updateInfo.isMandatory) {
            builder.setNegativeButton("Later", null)
        } else {
            builder.setCancelable(false)
        }

        builder.show()
    }

    private fun promptInstallPermissionAndDownload(activity: Activity, updateInfo: UpdateInfo) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            if (!activity.packageManager.canRequestPackageInstalls()) {
                Toast.makeText(
                    activity,
                    "Please allow Fairy Live to install updates.",
                    Toast.LENGTH_LONG
                ).show()

                try {
                    val intent = Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES).apply {
                        data = Uri.parse("package:${activity.packageName}")
                    }
                    activity.startActivity(intent)
                } catch (e: Exception) {
                    Log.w(TAG, "Unable to launch ACTION_MANAGE_UNKNOWN_APP_SOURCES: ${e.message}")
                }
                return
            }
        }

        downloadAndInstallApk(activity, updateInfo)
    }

    /**
     * Downloads the APK file to cache with a progress dialog and dispatches PackageInstaller.
     */
    @Suppress("DEPRECATION")
    private fun downloadAndInstallApk(activity: Activity, updateInfo: UpdateInfo) {
        val progressDialog = ProgressDialog(activity).apply {
            setTitle("Downloading Update")
            setMessage("Downloading Fairy Live v${updateInfo.versionName}...")
            isIndeterminate = false
            setProgressStyle(ProgressDialog.STYLE_HORIZONTAL)
            max = 100
            progress = 0
            setCancelable(false)
            show()
        }

        executor.execute {
            var connection: HttpURLConnection? = null
            var input: java.io.InputStream? = null
            var output: FileOutputStream? = null

            try {
                val updatesDir = File(activity.cacheDir, "updates").apply { mkdirs() }
                val apkFile = File(updatesDir, "fairy_live_v${updateInfo.versionName}.apk")
                if (apkFile.exists()) {
                    apkFile.delete()
                }

                var currentConnection: HttpURLConnection = (URL(updateInfo.downloadUrl).openConnection() as HttpURLConnection).apply {
                    connectTimeout = 15000
                    readTimeout = 30000
                    instanceFollowRedirects = true
                    setRequestProperty("User-Agent", "FairyLive-OTA/${BuildConfig.VERSION_NAME}")
                }
                connection = currentConnection

                // Handle HTTP redirects (e.g. 302/301 from GitHub Releases)
                var responseCode = currentConnection.responseCode
                var redirectCount = 0
                while ((responseCode == HttpURLConnection.HTTP_MOVED_TEMP ||
                        responseCode == HttpURLConnection.HTTP_MOVED_PERM ||
                        responseCode == HttpURLConnection.HTTP_SEE_OTHER) &&
                    redirectCount < 5
                ) {
                    val newUrl = currentConnection.getHeaderField("Location") ?: break
                    currentConnection.disconnect()
                    currentConnection = (URL(newUrl).openConnection() as HttpURLConnection).apply {
                        connectTimeout = 15000
                        readTimeout = 30000
                        instanceFollowRedirects = true
                        setRequestProperty("User-Agent", "FairyLive-OTA/${BuildConfig.VERSION_NAME}")
                    }
                    connection = currentConnection
                    responseCode = currentConnection.responseCode
                    redirectCount++
                }

                if (responseCode != HttpURLConnection.HTTP_OK) {
                    throw IllegalStateException("Server returned HTTP $responseCode")
                }

                val fileLength = currentConnection.contentLength
                input = currentConnection.inputStream
                output = FileOutputStream(apkFile)

                val buffer = ByteArray(8192)
                var total: Long = 0
                var count: Int

                while (input.read(buffer).also { count = it } != -1) {
                    total += count
                    output.write(buffer, 0, count)

                    if (fileLength > 0) {
                        val percent = ((total * 100) / fileLength).toInt()
                        mainHandler.post {
                            progressDialog.progress = percent
                        }
                    }
                }

                output.flush()
                mainHandler.post {
                    progressDialog.dismiss()
                    launchPackageInstaller(activity, apkFile)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Download failed: ${e.message}", e)
                mainHandler.post {
                    progressDialog.dismiss()
                    Toast.makeText(
                        activity,
                        "Update download failed: ${e.localizedMessage}",
                        Toast.LENGTH_LONG
                    ).show()
                }
            } finally {
                try { output?.close() } catch (_: Exception) {}
                try { input?.close() } catch (_: Exception) {}
                try { connection?.disconnect() } catch (_: Exception) {}
            }
        }
    }

    /**
     * Launches the system PackageInstaller via FileProvider.
     */
    private fun launchPackageInstaller(activity: Activity, apkFile: File) {
        try {
            val authority = "${activity.packageName}.fileprovider"
            val contentUri = FileProvider.getUriForFile(activity, authority, apkFile)

            val installIntent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(contentUri, "application/vnd.android.package-archive")
                flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or
                        Intent.FLAG_ACTIVITY_NEW_TASK or
                        Intent.FLAG_ACTIVITY_CLEAR_TOP
            }

            activity.startActivity(installIntent)
            Log.i(TAG, "Dispatched system package installer for ${apkFile.name}")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to launch package installer: ${e.message}", e)
            Toast.makeText(
                activity,
                "Unable to launch package installer: ${e.message}",
                Toast.LENGTH_LONG
            ).show()
        }
    }

    /**
     * Synchronously fetches and parses remote version.json.
     */
    private fun fetchRemoteVersionInfo(): UpdateInfo? {
        val url = URL(VERSION_JSON_URL)
        val conn = (url.openConnection() as HttpURLConnection).apply {
            connectTimeout = 8000
            readTimeout = 10000
            setRequestProperty("User-Agent", "FairyLive-OTA/${BuildConfig.VERSION_NAME}")
        }

        return try {
            if (conn.responseCode == HttpURLConnection.HTTP_OK) {
                val jsonString = conn.inputStream.bufferedReader().use { it.readText() }
                parseVersionJson(jsonString)
            } else {
                Log.w(TAG, "version.json request returned HTTP ${conn.responseCode}")
                null
            }
        } finally {
            conn.disconnect()
        }
    }

    /**
     * Parses remote version JSON object.
     */
    fun parseVersionJson(jsonString: String): UpdateInfo {
        val json = JSONObject(jsonString)
        return UpdateInfo(
            versionCode = json.getInt("versionCode"),
            versionName = json.getString("versionName"),
            releaseNotes = if (json.has("releaseNotes")) json.getString("releaseNotes") else "Bug fixes & performance improvements",
            downloadUrl = json.getString("downloadUrl"),
            isMandatory = json.optBoolean("isMandatory", false),
            sha256 = if (json.has("sha256")) json.getString("sha256") else null
        )
    }
}
