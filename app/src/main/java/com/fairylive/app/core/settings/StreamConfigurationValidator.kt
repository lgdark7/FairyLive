package com.fairylive.app.core.settings

import java.net.URI

/**
 * Enterprise-grade validator for RTMP endpoint URLs, stream keys, and operational parameters.
 * Provides strict input sanitization, buffer boundary enforcement, CRLF injection defense,
 * and comprehensive credential masking to prevent secret exposure in UI and logs.
 */
object StreamConfigurationValidator {

    private const val MAX_URL_LENGTH = 2048
    private const val MAX_STREAM_KEY_LENGTH = 512
    private const val MAX_HOST_LENGTH = 253
    private const val MAX_OVERLAY_TEXT_LENGTH = 500

    sealed class ValidationResult {
        object Valid : ValidationResult()
        data class Error(val userMessage: String, val technicalReason: String) : ValidationResult()

        val isValid: Boolean
            get() = this is Valid
    }

    /**
     * Validates an RTMP URL and stream key with comprehensive security and boundary checks.
     */
    fun validate(serverUrl: String?, streamKey: String?): ValidationResult {
        if (serverUrl.isNullOrBlank()) {
            return ValidationResult.Error(
                userMessage = "RTMP Server URL cannot be empty. Please enter a valid stream destination.",
                technicalReason = "Empty or blank server URL"
            )
        }

        if (serverUrl.length > MAX_URL_LENGTH) {
            return ValidationResult.Error(
                userMessage = "Server URL exceeds maximum allowed length of $MAX_URL_LENGTH characters.",
                technicalReason = "URL length ${serverUrl.length} exceeds $MAX_URL_LENGTH"
            )
        }

        // Detect control characters and injection attempts (CR, LF, NULL)
        if (containsControlCharacters(serverUrl)) {
            return ValidationResult.Error(
                userMessage = "Server URL contains invalid control characters or line breaks.",
                technicalReason = "Control character or CRLF injection detected in URL"
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

        if (host.length > MAX_HOST_LENGTH) {
            return ValidationResult.Error(
                userMessage = "Host address exceeds RFC maximum limit of $MAX_HOST_LENGTH characters.",
                technicalReason = "Host length ${host.length} exceeds $MAX_HOST_LENGTH"
            )
        }

        val port = uri.port
        if (port != -1 && (port < 1 || port > 65535)) {
            return ValidationResult.Error(
                userMessage = "Invalid port number. Port must be between 1 and 65535.",
                technicalReason = "Port out of range: $port"
            )
        }

        val rawPath = uri.path ?: ""
        if (rawPath.contains("..")) {
            return ValidationResult.Error(
                userMessage = "Invalid path syntax. Path traversal (..) is not permitted.",
                technicalReason = "Path traversal sequence detected in URL path"
            )
        }

        val path = rawPath.trimStart('/')
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

        if (streamKey.length > MAX_STREAM_KEY_LENGTH) {
            return ValidationResult.Error(
                userMessage = "Stream key exceeds maximum allowed length of $MAX_STREAM_KEY_LENGTH characters.",
                technicalReason = "Stream key length ${streamKey.length} exceeds $MAX_STREAM_KEY_LENGTH"
            )
        }

        if (containsControlCharacters(streamKey)) {
            return ValidationResult.Error(
                userMessage = "Stream key contains invalid control characters or line breaks.",
                technicalReason = "Control character or CRLF injection detected in stream key"
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
     * Formats an endpoint for safe display without credential disclosure.
     */
    fun formatSafeEndpoint(serverUrl: String?, streamKey: String?): String {
        val url = serverUrl?.trim() ?: ""
        if (url.isEmpty()) return "Not Configured"
        val masked = maskStreamKey(streamKey)
        return "$url | Key: $masked"
    }

    /**
     * Validates and sanitizes overlay text input.
     */
    fun sanitizeOverlayText(text: String?): String {
        if (text == null) return ""
        val truncated = if (text.length > MAX_OVERLAY_TEXT_LENGTH) {
            text.substring(0, MAX_OVERLAY_TEXT_LENGTH)
        } else {
            text
        }
        return truncated.replace(Regex("[\\x00-\\x08\\x0B\\x0C\\x0E-\\x1F]"), "")
    }

    private fun containsControlCharacters(s: String): Boolean {
        for (ch in s) {
            if (ch == '\r' || ch == '\n' || ch == '\u0000' || (ch.code in 1..31 && ch != '\t')) {
                return true
            }
        }
        return false
    }
}
