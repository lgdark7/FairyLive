package com.fairylive.app.ui.chat

import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.*
import androidx.fragment.app.Fragment
import com.fairylive.app.core.youtube.YouTubeLiveIntegrationService
import com.fairylive.app.core.youtube.YouTubeLiveIntegrationService.LiveChatMessage
import kotlinx.coroutines.*

/**
 * Phase 6 §11: YouTube Live Chat Panel.
 *
 * Dedicated creator chat interface:
 * - Live message feed with auto-scroll to latest
 * - Message timestamp and author badge
 * - Unread message counter badge
 * - Creator-only domain — NEVER rendered into broadcast composite
 * - Reconnect / refresh capability
 * - Warm Ivory (#FBF9F5) and Espresso (#231B15) theme
 */
class YouTubeChatFragment : Fragment() {

    private var messageContainer: LinearLayout? = null
    private var scrollView: ScrollView? = null
    private var statusBadge: TextView? = null
    private var unreadBadge: TextView? = null
    private var unreadCount = 0
    private var isScrolledToBottom = true
    private var serviceJob: Job? = null

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        val root = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#FBF9F5"))
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        }

        // Header: Title + Status + Unread badge
        val header = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
            setPadding(dp(16), dp(12), dp(16), dp(8))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        }

        val title = TextView(requireContext()).apply {
            text = "Live Chat"
            textSize = 16f
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            setTextColor(Color.parseColor("#231B15"))
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }

        statusBadge = TextView(requireContext()).apply {
            text = "● Connected"
            textSize = 11f
            setTextColor(Color.parseColor("#2E7D32")) // Green
            setPadding(dp(8), dp(3), dp(8), dp(3))
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#E8F5E9"))
                cornerRadius = dp(10).toFloat()
            }
        }

        unreadBadge = TextView(requireContext()).apply {
            visibility = View.GONE
            textSize = 11f
            setTextColor(Color.WHITE)
            setPadding(dp(6), dp(2), dp(6), dp(2))
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#C27D48")) // Caramel
                cornerRadius = dp(10).toFloat()
            }
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { marginStart = dp(6) }
        }

        header.addView(title)
        header.addView(statusBadge)
        header.addView(unreadBadge)
        root.addView(header)

        // Chat message list
        scrollView = ScrollView(requireContext()).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                0,
                1f
            )
            setOnScrollChangeListener { _, _, scrollY, _, _ ->
                val child = getChildAt(0) ?: return@setOnScrollChangeListener
                val diff = (child.bottom - (height + scrollY))
                isScrolledToBottom = diff <= dp(16)
                if (isScrolledToBottom) {
                    unreadCount = 0
                    unreadBadge?.visibility = View.GONE
                }
            }
        }

        messageContainer = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), dp(4), dp(12), dp(8))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        }
        scrollView?.addView(messageContainer)
        root.addView(scrollView)

        startObservingChat()
        return root
    }

    private fun startObservingChat() {
        val scope = CoroutineScope(Dispatchers.Main)
        serviceJob = scope.launch {
            YouTubeLiveIntegrationService.chatMessages.collect { messages ->
                renderMessages(messages)
            }
        }
    }

    private fun renderMessages(messages: List<LiveChatMessage>) {
        val container = messageContainer ?: return
        container.removeAllViews()

        if (messages.isEmpty()) {
            val empty = TextView(requireContext()).apply {
                text = "Waiting for live chat messages…"
                textSize = 13f
                setTextColor(Color.parseColor("#6E6259"))
                gravity = android.view.Gravity.CENTER
                setPadding(0, dp(32), 0, dp(32))
            }
            container.addView(empty)
            return
        }

        messages.forEach { msg ->
            container.addView(createMessageView(msg))
        }

        if (isScrolledToBottom) {
            scrollView?.post { scrollView?.fullScroll(View.FOCUS_DOWN) }
        } else {
            unreadCount++
            unreadBadge?.apply {
                text = "$unreadCount"
                visibility = View.VISIBLE
            }
        }
    }

    private fun createMessageView(msg: LiveChatMessage): View {
        val row = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(10), dp(6), dp(10), dp(6))
            background = GradientDrawable().apply {
                setColor(Color.WHITE)
                setStroke(dp(1), Color.parseColor("#EBE5DF"))
                cornerRadius = dp(6).toFloat()
            }
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(4) }
        }

        // Author line
        val authorLine = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        }

        val authorText = TextView(requireContext()).apply {
            text = msg.authorName
            textSize = 11f
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            setTextColor(Color.parseColor("#7C5C8A")) // Plum accent
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }

        val timeText = TextView(requireContext()).apply {
            val minsAgo = (System.currentTimeMillis() - msg.timestampMs) / 60000
            text = if (minsAgo <= 0) "now" else "${minsAgo}m"
            textSize = 10f
            setTextColor(Color.parseColor("#6E6259"))
        }

        authorLine.addView(authorText)
        authorLine.addView(timeText)
        row.addView(authorLine)

        // Content
        val contentText = TextView(requireContext()).apply {
            text = msg.messageText
            textSize = 12f
            setTextColor(Color.parseColor("#231B15"))
            setPadding(0, dp(2), 0, 0)
        }
        row.addView(contentText)

        return row
    }

    override fun onDestroyView() {
        super.onDestroyView()
        serviceJob?.cancel()
    }

    private fun dp(value: Int): Int =
        (value * resources.displayMetrics.density + 0.5f).toInt()

    companion object {
        const val TAG = "YouTubeChatFragment"
        fun newInstance() = YouTubeChatFragment()
    }
}
