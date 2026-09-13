package com.fairylive.app.core.common

import java.util.concurrent.CopyOnWriteArrayList

data class CreatorError(
    val title: String,
    val message: String,
    val resolution: String,
    val timestamp: Long = System.currentTimeMillis()
)

/**
 * Thread-safe observer delivering creator-friendly error notifications to the UI.
 * Sanitizes technical exceptions so stream credentials or raw stack traces are never exposed.
 */
object CreatorErrorNotifier {

    fun interface ErrorListener {
        fun onError(error: CreatorError)
    }

    private val listeners = CopyOnWriteArrayList<ErrorListener>()

    @Volatile
    var lastError: CreatorError? = null
        private set

    fun postError(title: String, message: String, resolution: String) {
        val error = CreatorError(title, message, resolution)
        lastError = error
        for (l in listeners) {
            l.onError(error)
        }
    }

    fun clear() {
        lastError = null
    }

    fun addListener(listener: ErrorListener) {
        if (!listeners.contains(listener)) {
            listeners.add(listener)
        }
    }

    fun removeListener(listener: ErrorListener) {
        listeners.remove(listener)
    }

    /**
     * Translates technical socket and protocol errors into actionable creator guidance.
     */
    fun mapNetworkException(e: Throwable): CreatorError {
        val msg = e.message ?: ""
        return when {
            msg.contains("ECONNREFUSED", ignoreCase = true) || msg.contains("Connection refused", ignoreCase = true) -> {
                CreatorError(
                    title = "Connection Refused",
                    message = "Could not reach the streaming server. The server may be offline or the port is blocked.",
                    resolution = "Check your RTMP Server URL and port in Settings, or verify the server is running."
                )
            }
            msg.contains("timeout", ignoreCase = true) || msg.contains("ETIMEDOUT", ignoreCase = true) -> {
                CreatorError(
                    title = "Connection Timed Out",
                    message = "The streaming server took too long to respond.",
                    resolution = "Check your device internet connection or server firewall."
                )
            }
            msg.contains("handshake", ignoreCase = true) -> {
                CreatorError(
                    title = "RTMP Handshake Failed",
                    message = "The server did not respond with standard RTMP protocol headers.",
                    resolution = "Verify that the target address is an RTMP ingest server and not an HTTP or RTSP server."
                )
            }
            msg.contains("rejected", ignoreCase = true) || msg.contains("_error", ignoreCase = true) -> {
                CreatorError(
                    title = "Server Rejected Connection",
                    message = "The streaming server rejected the connection or stream key.",
                    resolution = "Double-check your stream key in Settings."
                )
            }
            else -> {
                CreatorError(
                    title = "Streaming Interrupted",
                    message = "A network error occurred while broadcasting.",
                    resolution = "Please check your network connection and try streaming again."
                )
            }
        }
    }
}
