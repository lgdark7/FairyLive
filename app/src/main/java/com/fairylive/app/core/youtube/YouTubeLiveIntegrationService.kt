package com.fairylive.app.core.youtube

import android.util.Log
import com.fairylive.app.core.compositor.model.YouTubeSubscriberWidgetConfig
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL
import java.util.Collections
import java.util.regex.Pattern

/**
 * Phase 7 §4 & §5: Production YouTube Live Integration Service.
 * Manages:
 * 1. OAuth2 Auth State Machine: DISCONNECTED, AUTHORIZING, CONNECTED, EXPIRED, ERROR.
 * 2. Subscriber Count updates for RenderDomain.BROADCAST (Subscriber Counter Widget).
 * 3. Real-time incoming live chat polling directly from YouTube Innertube API
 *    with zero authentication or API key required.
 * Strictly guarantees ZERO plaintext API token leakage into logcat, diagnostics, or stream.
 */
object YouTubeLiveIntegrationService {

    private const val TAG = "YouTubeLiveService"
    private const val USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/122.0.0.0 Safari/537.36"

    enum class YouTubeAuthState {
        DISCONNECTED,
        AUTHORIZING,
        CONNECTED,
        EXPIRED,
        ERROR
    }

    data class LiveChatMessage(
        val id: String,
        val authorName: String,
        val messageText: String,
        val timestampMs: Long,
        val isSuperChat: Boolean = false,
        val superChatAmount: String? = null,
        val isStreamer: Boolean = false
    )

    private val _authState = MutableStateFlow(YouTubeAuthState.DISCONNECTED)
    val authState: StateFlow<YouTubeAuthState> = _authState.asStateFlow()

    private val _subscriberConfig = MutableStateFlow(
        YouTubeSubscriberWidgetConfig(
            channelId = "",
            channelTitle = "FAIRY LIVE",
            subscriberCountFormatted = "–",
            rawSubscriberCount = -1L,
            loadingState = YouTubeSubscriberWidgetConfig.LoadingState.LOADING
        )
    )
    val subscriberConfig: StateFlow<YouTubeSubscriberWidgetConfig> = _subscriberConfig.asStateFlow()

    private val initialDemoMessages = listOf(
        LiveChatMessage("msg_init", "System", "YouTube Live chat channel connected.", System.currentTimeMillis())
    )

    private val _chatMessages = MutableStateFlow<List<LiveChatMessage>>(initialDemoMessages)
    val chatMessages: StateFlow<List<LiveChatMessage>> = _chatMessages.asStateFlow()

    private val pollerScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var pollerJob: Job? = null

    val isPollingActive: Boolean
        get() = pollerJob?.isActive == true

    private val seenMessageIds = Collections.synchronizedSet(HashSet<String>())

    fun initiateAuth() {
        _authState.value = YouTubeAuthState.AUTHORIZING
    }

    fun onAuthSuccess(channelId: String, channelTitle: String) {
        _authState.value = YouTubeAuthState.CONNECTED
        _subscriberConfig.value = _subscriberConfig.value.copy(
            channelId = channelId,
            channelTitle = channelTitle,
            loadingState = YouTubeSubscriberWidgetConfig.LoadingState.LIVE
        )
    }

    fun onAuthExpired() {
        _authState.value = YouTubeAuthState.EXPIRED
        _subscriberConfig.value = _subscriberConfig.value.copy(
            loadingState = YouTubeSubscriberWidgetConfig.LoadingState.CACHED
        )
    }

    fun onAuthError(errorMsg: String) {
        _authState.value = YouTubeAuthState.ERROR
        _subscriberConfig.value = _subscriberConfig.value.copy(
            loadingState = YouTubeSubscriberWidgetConfig.LoadingState.ERROR
        )
    }

    fun disconnect() {
        stopLiveChatPolling()
        _authState.value = YouTubeAuthState.DISCONNECTED
        _subscriberConfig.value = YouTubeSubscriberWidgetConfig(
            subscriberCountFormatted = "–",
            rawSubscriberCount = -1L,
            loadingState = YouTubeSubscriberWidgetConfig.LoadingState.LOADING
        )
        _chatMessages.value = initialDemoMessages
    }

    fun updateSubscriberCount(rawCount: Long, channelTitle: String = "FAIRY LIVE") {
        val formatted = formatSubscriberCount(rawCount)
        _subscriberConfig.value = _subscriberConfig.value.copy(
            rawSubscriberCount = rawCount,
            subscriberCountFormatted = formatted,
            channelTitle = channelTitle,
            loadingState = YouTubeSubscriberWidgetConfig.LoadingState.LIVE
        )
    }

    fun postChatMessage(author: String, text: String, isStreamer: Boolean = true) {
        val newMsg = LiveChatMessage(
            id = "msg_${System.currentTimeMillis()}",
            authorName = author,
            messageText = text,
            timestampMs = System.currentTimeMillis(),
            isStreamer = isStreamer
        )
        seenMessageIds.add(newMsg.id)
        _chatMessages.value = _chatMessages.value + newMsg
    }

    fun formatSubscriberCount(count: Long): String {
        return when {
            count < 0 -> "–"
            count >= 1_000_000 -> String.format("%.1fM", count / 1_000_000.0).replace(".0M", "M")
            count >= 1_000 -> String.format("%.1fK", count / 1_000.0).replace(".0K", "K")
            else -> count.toString()
        }
    }

    /**
     * Starts continuous real-time live chat polling for a YouTube stream.
     * [input] can be:
     * - Video ID (e.g. "dQw4w9WgXcQ")
     * - Full live stream URL (e.g. "https://youtube.com/live/dQw4w9WgXcQ" or "https://www.youtube.com/watch?v=...")
     * - Channel handle (e.g. "@ChannelName" or "https://youtube.com/@ChannelName/live")
     */
    fun startLiveChatPolling(input: String) {
        stopLiveChatPolling()
        if (input.isBlank()) {
            Log.w(TAG, "Cannot start live chat polling: empty input")
            return
        }

        pollerJob = pollerScope.launch {
            try {
                Log.i(TAG, "Resolving live stream video ID for: $input")
                val videoId = resolveVideoId(input.trim())
                if (videoId == null) {
                    Log.e(TAG, "Failed to resolve video ID from: $input")
                    postChatMessage("FairyLive", "Unable to find active YouTube stream for: $input", isStreamer = false)
                    return@launch
                }

                Log.i(TAG, "Starting live chat session for video ID: $videoId")
                runLiveChatPoller(videoId)
            } catch (e: CancellationException) {
                Log.i(TAG, "Live chat polling cancelled")
            } catch (e: Exception) {
                Log.e(TAG, "Error in live chat polling: ${e.message}", e)
            }
        }
    }

    fun stopLiveChatPolling() {
        pollerJob?.cancel()
        pollerJob = null
    }

    private suspend fun resolveVideoId(input: String): String? = withContext(Dispatchers.IO) {
        // Direct 11-char ID
        if (input.matches(Regex("^[a-zA-Z0-9_-]{11}$"))) {
            return@withContext input
        }

        // Standard watch?v= or live/ URL
        val vMatch = Pattern.compile("(?:v=|/live/|youtu\\.be/)([a-zA-Z0-9_-]{11})").matcher(input)
        if (vMatch.find()) {
            return@withContext vMatch.group(1)
        }

        // Channel handle or channel URL: e.g. "@username" or "https://youtube.com/@username/live"
        val channelHandle = if (input.startsWith("@")) {
            input
        } else if (input.contains("/@")) {
            val idx = input.indexOf("/@")
            val endIdx = input.indexOf('/', idx + 2).let { if (it == -1) input.length else it }
            input.substring(idx + 1, endIdx)
        } else {
            "@$input"
        }

        val channelLiveUrl = "https://www.youtube.com/$channelHandle/live"
        Log.i(TAG, "Fetching channel live page: $channelLiveUrl")
        try {
            val conn = (URL(channelLiveUrl).openConnection() as HttpURLConnection).apply {
                connectTimeout = 8000
                readTimeout = 8000
                setRequestProperty("User-Agent", USER_AGENT)
                setRequestProperty("Accept-Language", "en-US,en;q=0.9")
                instanceFollowRedirects = true
            }

            val html = conn.inputStream.bufferedReader().use { it.readText() }
            val vidPattern = Pattern.compile("\"videoId\":\"([a-zA-Z0-9_-]{11})\"")
            val matcher = vidPattern.matcher(html)
            if (matcher.find()) {
                return@withContext matcher.group(1)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to resolve handle $channelHandle: ${e.message}")
        }
        null
    }

    private suspend fun runLiveChatPoller(videoId: String) = withContext(Dispatchers.IO) {
        val chatPageUrl = "https://www.youtube.com/live_chat?v=$videoId"
        val conn = (URL(chatPageUrl).openConnection() as HttpURLConnection).apply {
            connectTimeout = 10000
            readTimeout = 10000
            setRequestProperty("User-Agent", USER_AGENT)
            setRequestProperty("Accept-Language", "en-US,en;q=0.9")
        }

        val html = conn.inputStream.bufferedReader().use { it.readText() }
        val apiKeyMatcher = Pattern.compile("\"INNERTUBE_API_KEY\":\"([^\"]+)\"").matcher(html)
        val continuationMatcher = Pattern.compile("\"continuation\":\"([^\"]+)\"").matcher(html)

        if (!apiKeyMatcher.find() || !continuationMatcher.find()) {
            Log.e(TAG, "Failed to extract Innertube API key or initial continuation for video $videoId")
            postChatMessage("FairyLive", "Live chat not accessible or disabled on YouTube stream.", isStreamer = false)
            return@withContext
        }

        val apiKey = apiKeyMatcher.group(1)
        var continuation: String? = continuationMatcher.group(1)
        Log.i(TAG, "Innertube session acquired for $videoId! Polling live comments...")
        postChatMessage("FairyLive", "Connected to YouTube Live Chat! Messages will appear live.", isStreamer = false)

        while (isActive && continuation != null) {
            try {
                val endpoint = "https://www.youtube.com/youtubei/v1/live_chat/get_live_chat?key=$apiKey"
                val postConn = (URL(endpoint).openConnection() as HttpURLConnection).apply {
                    requestMethod = "POST"
                    connectTimeout = 8000
                    readTimeout = 8000
                    doOutput = true
                    setRequestProperty("Content-Type", "application/json")
                    setRequestProperty("User-Agent", USER_AGENT)
                    setRequestProperty("Accept-Language", "en-US,en;q=0.9")
                }

                val payload = JSONObject().apply {
                    put("context", JSONObject().apply {
                        put("client", JSONObject().apply {
                            put("clientName", "WEB")
                            put("clientVersion", "2.20240101.01.00")
                        })
                    })
                    put("continuation", continuation)
                }

                OutputStreamWriter(postConn.outputStream).use { it.write(payload.toString()) }

                val responseStr = if (postConn.responseCode == 200) {
                    postConn.inputStream.bufferedReader().use { it.readText() }
                } else {
                    Log.w(TAG, "get_live_chat HTTP response: ${postConn.responseCode}")
                    delay(3500)
                    continue
                }

                val json = JSONObject(responseStr)
                val liveChatContinuation = json.optJSONObject("continuationContents")?.optJSONObject("liveChatContinuation")
                if (liveChatContinuation == null) {
                    delay(3000)
                    continue
                }

                // Extract messages
                val actions = liveChatContinuation.optJSONArray("actions")
                if (actions != null) {
                    val incoming = mutableListOf<LiveChatMessage>()
                    for (i in 0 until actions.length()) {
                        val action = actions.optJSONObject(i) ?: continue
                        val item = action.optJSONObject("addChatItemAction")?.optJSONObject("item") ?: continue

                        // Standard text message
                        val textRenderer = item.optJSONObject("liveChatTextMessageRenderer")
                        if (textRenderer != null) {
                            val id = textRenderer.optString("id", "msg_${System.currentTimeMillis()}_$i")
                            if (!seenMessageIds.contains(id)) {
                                seenMessageIds.add(id)
                                val author = textRenderer.optJSONObject("authorName")?.optString("simpleText") ?: "Viewer"
                                val runs = textRenderer.optJSONObject("message")?.optJSONArray("runs")
                                val textBuilder = StringBuilder()
                                if (runs != null) {
                                    for (r in 0 until runs.length()) {
                                        textBuilder.append(runs.optJSONObject(r)?.optString("text", "") ?: "")
                                    }
                                }
                                val text = textBuilder.toString().trim()
                                if (text.isNotEmpty()) {
                                    val time = textRenderer.optLong("timestampUsec", System.currentTimeMillis() * 1000) / 1000
                                    incoming.add(
                                        LiveChatMessage(
                                            id = id,
                                            authorName = author,
                                            messageText = text,
                                            timestampMs = time
                                        )
                                    )
                                }
                            }
                            continue
                        }

                        // Super Chat message
                        val paidRenderer = item.optJSONObject("liveChatPaidMessageRenderer")
                        if (paidRenderer != null) {
                            val id = paidRenderer.optString("id", "msg_sc_${System.currentTimeMillis()}_$i")
                            if (!seenMessageIds.contains(id)) {
                                seenMessageIds.add(id)
                                val author = paidRenderer.optJSONObject("authorName")?.optString("simpleText") ?: "Supporter"
                                val amount = paidRenderer.optJSONObject("purchaseAmountText")?.optString("simpleText") ?: "Super Chat"
                                val runs = paidRenderer.optJSONObject("message")?.optJSONArray("runs")
                                val textBuilder = StringBuilder()
                                if (runs != null) {
                                    for (r in 0 until runs.length()) {
                                        textBuilder.append(runs.optJSONObject(r)?.optString("text", "") ?: "")
                                    }
                                }
                                val text = textBuilder.toString().trim().ifEmpty { amount }
                                incoming.add(
                                    LiveChatMessage(
                                        id = id,
                                        authorName = author,
                                        messageText = "$amount: $text",
                                        timestampMs = System.currentTimeMillis(),
                                        isSuperChat = true,
                                        superChatAmount = amount
                                    )
                                )
                            }
                        }
                    }

                    if (incoming.isNotEmpty()) {
                        Log.i(TAG, "Received ${incoming.size} new YouTube live chat messages")
                        _chatMessages.value = _chatMessages.value + incoming
                    }
                }

                // Extract next continuation token and timeout
                val continuations = liveChatContinuation.optJSONArray("continuations")
                var nextContinuation: String? = null
                var nextTimeoutMs = 3000L

                if (continuations != null && continuations.length() > 0) {
                    val firstCont = continuations.optJSONObject(0)
                    val invalidation = firstCont?.optJSONObject("invalidationContinuationData")
                    val timed = firstCont?.optJSONObject("timedContinuationData")
                    val replay = firstCont?.optJSONObject("liveChatReplayContinuationData")

                    val targetObj = invalidation ?: timed ?: replay
                    nextContinuation = targetObj?.optString("continuation")
                    val pollTimeout = targetObj?.optLong("timeoutMs", 3000L) ?: 3000L
                    nextTimeoutMs = pollTimeout.coerceIn(2000L, 8000L)
                }

                continuation = nextContinuation
                delay(nextTimeoutMs)
            } catch (e: CancellationException) {
                break
            } catch (e: Exception) {
                Log.w(TAG, "Poller iteration error: ${e.message}")
                delay(4000)
            }
        }
    }
}