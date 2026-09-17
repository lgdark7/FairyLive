package com.fairylive.app.core.stream.model

/**
 * Sanitized, credential-safe technical diagnostic snapshot for stream operations.
 * Strictly guarantees that secret tokens, passwords, or full stream keys are never exposed.
 */
data class StreamDiagnosticInfo(
    val errorType: StreamErrorType,
    val host: String = "unknown",
    val port: Int = 1935,
    val appName: String = "live",
    val streamKeyPresent: Boolean = false,
    val streamKeyLength: Int = 0,
    val streamKeyPrefix: String? = null
) {
    fun toSafeString(): String {
        val prefixStr = streamKeyPrefix?.let { "$it••••" } ?: if (streamKeyPresent) "••••" else "none"
        return "[$errorType] $host:$port/$appName | Key: present=$streamKeyPresent len=$streamKeyLength prefix=$prefixStr"
    }

    companion object {
        fun create(
            errorType: StreamErrorType,
            host: String? = null,
            port: Int? = null,
            appName: String? = null,
            streamKey: String? = null
        ): StreamDiagnosticInfo {
            val cleanKey = streamKey?.trim() ?: ""
            val hasKey = cleanKey.isNotEmpty()
            val len = cleanKey.length
            val prefix = if (len >= 4) cleanKey.take(2) else null
            return StreamDiagnosticInfo(
                errorType = errorType,
                host = host?.takeIf { it.isNotBlank() } ?: "unknown",
                port = if (port != null && port > 0) port else 1935,
                appName = appName?.takeIf { it.isNotBlank() } ?: "live",
                streamKeyPresent = hasKey,
                streamKeyLength = len,
                streamKeyPrefix = prefix
            )
        }
    }
}
