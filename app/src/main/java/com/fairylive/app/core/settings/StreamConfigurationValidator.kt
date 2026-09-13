package com.fairylive.app.core.settings

import java.net.URI

/**
 * Validates RTMP endpoint URLs and stream keys.
 * Ensures strict credential masking to prevent secret exposure in UI and logs.
 */
object StreamConfigurationValidator {

    sealed class ValidationResult {
        object Valid : ValidationResult()
        data class Error(val userMessage: String, val technicalReason: String) : ValidationResult()

        val isValid: Boolean
            get() = this is Valid
    }

    /**
     * Validates an RTMP URL and stream key.
     */
    fun validate(serverUrl: String?, streamKey: String?): ValidationResult {
        if (serverUrl.isNullOrBlank()) {
            return ValidationResult.Error(
                userMessage = "RTMP Server URL cannot be empty. Please enter a valid stream destination.",
                technicalReason = "Empty or blank server URL"
            )
        }

        val cleanUrl = serverUrl.trim()

        if (!cleanUrl.startsWith("rtmp://", ignoreCase = true) && !cleanUrl.startsWith("rtmps://", ignoreCase = true)) {
            return ValidationResult.Error(
                userMessage = "Invalid protocol. Stream URL must begin with rtmp:// or rtmps://",
                technicalReason = "URL does not start with rtmp:// or rtmps://"
            )
        }

        val uri = try {
            URI(cleanUrl)
        } catch (e: Exception) {
            return ValidationResult.Error(
                userMessage = "Malformed RTMP URL. Please check formatting.",
                technicalReason = "URI parse failure: ${e.message}"
            )
        }

        val host = uri.host
        if (host.isNullOrBlank()) {
            return ValidationResult.Error(
                userMessage = "RTMP Server address (host) is missing.",
                technicalReason = "URI host is null or empty"
            )
        }

        val port = uri.port
        if (port != -1 && (port < 1 || port > 65535)) {
            return ValidationResult.Error(
                userMessage = "Invalid port number. Port must be between 1 and 65535.",
                technicalReason = "Port out of range: $port"
            )
        }

        val path = (uri.path ?: "").trimStart('/')
        if (path.isEmpty()) {
            return ValidationResult.Error(
                userMessage = "Application path missing (e.g. rtmp://host:1935/live).",
                technicalReason = "URI path segment is empty"
            )
        }

        if (streamKey.isNullOrBlank()) {
            return ValidationResult.Error(
                userMessage = "Stream key cannot be empty.",
                technicalReason = "Empty stream key"
            )
        }

        return ValidationResult.Valid
    }

    /**
     * Masks a sensitive stream key for UI display and logging.
     * Ex: "live_abcdef123456" -> "li••••••••••56"  (first 2 kept, last 2 kept, middle all masked)
     * Ex: "abc" -> "••••"
     */
    fun maskStreamKey(key: String?): String {
        if (key.isNullOrEmpty()) return "none"
        return when {
            key.length >= 8 -> key.take(2) + "•".repeat(key.length - 4) + key.takeLast(2)
            key.length >= 4 -> key.take(1) + "•".repeat(key.length - 2) + key.takeLast(1)
            else -> "••••"
        }
    }

    /**
     * Formats an endpoint for safe display.
     */
    fun formatSafeEndpoint(serverUrl: String?, streamKey: String?): String {
        val url = serverUrl?.trim() ?: ""
        if (url.isEmpty()) return "Not Configured"
        val masked = maskStreamKey(streamKey)
        return "$url | Key: $masked"
    }
}
