package com.fairylive.app.core.common

import com.fairylive.app.core.stream.model.StreamDiagnosticInfo
import com.fairylive.app.core.stream.model.StreamErrorType
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.util.concurrent.CopyOnWriteArrayList

data class CreatorError(
    val title: String,
    val message: String,
    val resolution: String,
    val diagnosticInfo: StreamDiagnosticInfo? = null,
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

    fun postError(
        title: String,
        message: String,
        resolution: String,
        diagnosticInfo: StreamDiagnosticInfo? = null
    ) {
        val error = CreatorError(title, message, resolution, diagnosticInfo)
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
     * Translates technical socket and protocol errors into actionable creator guidance
     * along with sanitized diagnostics that strictly redact credentials.
     */
    fun mapNetworkException(
        e: Throwable,
        host: String? = null,
        port: Int? = null,
        appName: String? = null,
        streamKey: String? = null
    ): CreatorError {
        val msg = e.message ?: ""

        val (errorType, title, message, resolution) = when {
            e is UnknownHostException || msg.contains("UnknownHostException", ignoreCase = true) || msg.contains("No address associated", ignoreCase = true) -> {
                Quadruple(
                    StreamErrorType.DNS_LOOKUP_FAILED,
                    "DNS Lookup Failed",
                    "Could not resolve the address for '${host ?: "the streaming server"}'.",
                    "Check your internet connection or verify the RTMP host spelling in Settings."
                )
            }
            e is ConnectException || msg.contains("ECONNREFUSED", ignoreCase = true) || msg.contains("Connection refused", ignoreCase = true) -> {
                Quadruple(
                    StreamErrorType.CONNECTION_REFUSED,
                    "Connection Refused",
                    "Could not reach the server at ${host ?: "target host"}:${port ?: 1935}. The server may be offline or the port is blocked.",
                    "Check your RTMP Server URL and port in Settings, or verify the server is active."
                )
            }
            e is SocketTimeoutException || msg.contains("timeout", ignoreCase = true) || msg.contains("ETIMEDOUT", ignoreCase = true) -> {
                Quadruple(
                    StreamErrorType.CONNECTION_TIMEOUT,
                    "Connection Timed Out",
                    "The streaming server took too long to respond during connection.",
                    "Check your device internet connection or server firewall."
                )
            }
            msg.contains("handshake", ignoreCase = true) || msg.contains("reading S0", ignoreCase = true) || msg.contains("RTMP handshake", ignoreCase = true) -> {
                Quadruple(
                    StreamErrorType.HANDSHAKE_FAILED,
                    "RTMP Handshake Failed",
                    "The server did not respond with standard RTMP protocol headers.",
                    "Verify that the target destination is an RTMP ingest endpoint and not HTTP/RTSP."
                )
            }
            msg.contains("rejected connect", ignoreCase = true) || msg.contains("NetConnection.Connect.Rejected", ignoreCase = true) -> {
                Quadruple(
                    StreamErrorType.SERVER_CONNECT_REJECTED,
                    "Server Rejected Connection",
                    "The RTMP server rejected the connection request for application '${appName ?: "live"}'.",
                    "Double-check your Server URL in Settings (e.g. rtmp://a.rtmp.youtube.com/live2)."
                )
            }
            msg.contains("BadName", ignoreCase = true) || msg.contains("NetStream.Publish.BadName", ignoreCase = true) || msg.contains("rejected stream key", ignoreCase = true) -> {
                Quadruple(
                    StreamErrorType.STREAM_KEY_REJECTED,
                    "Stream Key Rejected",
                    "The streaming server rejected your stream key.",
                    "Your YouTube or Twitch stream key is invalid, revoked, or expired. Copy the current stream key from YouTube Studio and paste it in Settings."
                )
            }
            msg.contains("broken pipe", ignoreCase = true) || msg.contains("connection reset", ignoreCase = true) || msg.contains("connection abort", ignoreCase = true) -> {
                Quadruple(
                    StreamErrorType.STREAM_KEY_REJECTED,
                    "Connection Closed by Server",
                    "The streaming server abruptly closed the connection immediately after publishing.",
                    "YouTube closes the socket when the stream key is invalid or unrecognized. Double-check your Stream Key in Settings."
                )
            }
            else -> {
                Quadruple(
                    StreamErrorType.BROKEN_PIPE_PREMATURE_CLOSE,
                    "Streaming Interrupted",
                    "A network error occurred while broadcasting.",
                    "Please check your network connection and retry."
                )
            }
        }

        val diag = StreamDiagnosticInfo.create(
            errorType = errorType,
            host = host,
            port = port,
            appName = appName,
            streamKey = streamKey
        )

        return CreatorError(
            title = title,
            message = message,
            resolution = resolution,
            diagnosticInfo = diag
        )
    }

    private data class Quadruple<A, B, C, D>(val first: A, val second: B, val third: C, val fourth: D)
}
