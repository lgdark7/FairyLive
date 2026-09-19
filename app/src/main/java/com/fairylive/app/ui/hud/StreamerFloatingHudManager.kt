package com.fairylive.app.ui.hud

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.os.Build
import android.os.SystemClock
import android.provider.Settings
import android.text.Spannable
import android.text.SpannableStringBuilder
import android.text.style.ForegroundColorSpan
import android.text.style.StyleSpan
import android.util.Log
import android.view.ContextThemeWrapper
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.TextView
import com.fairylive.app.MainActivity
import com.fairylive.app.R
import com.fairylive.app.core.render.RenderDomain
import com.fairylive.app.core.youtube.YouTubeLiveIntegrationService
import com.fairylive.app.service.FairyStreamService
import com.google.android.material.bottomsheet.BottomSheetDialog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.util.Collections
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Manages the Streamer Floating HUD window.
 * Strictly adheres to RenderDomain.CREATOR_ONLY:
 *
 * When Settings.canDrawOverlays(context) is true, attaches directly to WindowManager
 * with TYPE_APPLICATION_OVERLAY so the streamer retains complete control while playing
 * full-screen landscape games (e.g. Free Fire MAX).
 *
 * Features:
 * - Ephemeral Live Chat Overlay: Displays only sender name & text, auto-vanishes after 5 seconds.
 * - Aspect Ratio Control: Dynamic FIT (100% full game UI preserved) vs FILL modes.
 * - Robust Lifecycle: Automatically removes views on app exit / task removed / stop.
 */
class StreamerFloatingHudManager(private val context: Context) {

    val renderDomain: RenderDomain = RenderDomain.CREATOR_ONLY

    private var windowManager: WindowManager? = null
    private var windowLayoutParams: WindowManager.LayoutParams? = null
    private var hudView: View? = null

    private var isMicMuted: Boolean = false
    private var isStreamPaused: Boolean = false
    private var isExpanded: Boolean = false

    // Ephemeral Chat Overlay State
    private var isChatOverlayEnabled: Boolean = false
    private var chatOverlayView: View? = null
    private var chatOverlayParams: WindowManager.LayoutParams? = null
    private var chatContainer: LinearLayout? = null
    private var chatJob: Job? = null
    private val coroutineScope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private val seenMessageIds = Collections.synchronizedSet(mutableSetOf<String>())

    fun show() {
        if (hudView != null) return
        activeInstances.add(this)

        if (Settings.canDrawOverlays(context)) {
            val wm = context.getSystemService(Context.WINDOW_SERVICE) as? WindowManager ?: return
            this.windowManager = wm

            val themedContext = ContextThemeWrapper(context, R.style.Theme_FairyLive)
            val inflater = LayoutInflater.from(themedContext)
            val view = inflater.inflate(R.layout.view_streamer_floating_hud, null)

            val layoutType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            } else {
                @Suppress("DEPRECATION")
                WindowManager.LayoutParams.TYPE_PHONE
            }

            val params = WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                layoutType,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                PixelFormat.TRANSLUCENT
            ).apply {
                gravity = Gravity.TOP or Gravity.START
                x = 60
                y = 160
            }
            this.windowLayoutParams = params

            setupHudControls(view)
            setupOverlayDraggability(view, params, wm)

            try {
                wm.addView(view, params)
                hudView = view
                Log.i(TAG, "Streamer Floating HUD added as TYPE_APPLICATION_OVERLAY")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to add system overlay view", e)
            }
        } else if (context is Activity) {
            val decorView = context.window.decorView as? ViewGroup ?: return
            val inflater = LayoutInflater.from(context)
            val view = inflater.inflate(R.layout.view_streamer_floating_hud, decorView, false)

            val params = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
                topMargin = 120
            }

            setupHudControls(view)
            setupDecorDraggability(view)

            decorView.addView(view, params)
            hudView = view
            Log.i(TAG, "Streamer Floating HUD added to Activity DecorView fallback")
        }
    }

    fun dismiss() {
        coroutineScope.cancel()
        hideChatOverlay()

        hudView?.let { view ->
            val wm = windowManager
            if (wm != null) {
                try {
                    wm.removeView(view)
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to remove system overlay view: ${e.message}")
                }
                windowManager = null
                windowLayoutParams = null
            } else {
                val parent = view.parent as? ViewGroup
                parent?.removeView(view)
            }
            hudView = null
        }
        activeInstances.remove(this)
    }

    fun isShowing(): Boolean = hudView != null

    private fun setupHudControls(view: View) {
        val btnToggle = view.findViewById<ImageButton>(R.id.hudBtnToggleCollapse)
        val expandedControls = view.findViewById<View>(R.id.hudExpandedControls)
        val btnChat = view.findViewById<ImageButton>(R.id.hudBtnChat)
        val btnMic = view.findViewById<ImageButton>(R.id.hudBtnMic)
        val btnCamera = view.findViewById<ImageButton>(R.id.hudBtnCamera)
        val btnPause = view.findViewById<ImageButton>(R.id.hudBtnPause)
        val btnMore = view.findViewById<ImageButton>(R.id.hudBtnMore)
        val liveStatus = view.findViewById<TextView>(R.id.hudLiveStatus)

        fun updateExpandedState() {
            expandedControls?.visibility = if (isExpanded) View.VISIBLE else View.GONE
            btnToggle?.setImageResource(
                if (isExpanded) android.R.drawable.ic_menu_close_clear_cancel
                else android.R.drawable.ic_menu_manage
            )
        }

        btnToggle?.setOnClickListener {
            isExpanded = !isExpanded
            updateExpandedState()
        }
        updateExpandedState()

        // Ephemeral chat overlay click toggle; long-press opens full chat sheet
        btnChat?.setOnClickListener {
            toggleChatOverlay(btnChat)
        }
        btnChat?.setOnLongClickListener {
            openCreatorChatSheet()
            true
        }

        btnMic?.setOnClickListener {
            isMicMuted = !isMicMuted
            FairyStreamService.toggleMicrophone(context)
            btnMic.setImageResource(
                if (isMicMuted) android.R.drawable.ic_lock_silent_mode
                else android.R.drawable.ic_btn_speak_now
            )
        }

        btnCamera?.setOnClickListener {
            val app = context.applicationContext as? com.fairylive.app.FairyLiveApplication
            val scene = app?.sceneManager?.activeScene
            if (app != null && scene != null) {
                val newEnabled = !scene.facecamConfig.enabled
                val updatedFc = scene.facecamConfig.copy(enabled = newEnabled)
                app.sceneManager.updateFacecamInActiveScene(updatedFc)
                btnCamera.alpha = if (newEnabled) 1.0f else 0.4f
            }
        }

        btnPause?.setOnClickListener {
            isStreamPaused = !isStreamPaused
            if (isStreamPaused) {
                FairyStreamService.pauseCapture(context)
                liveStatus?.text = "⏸ PAUSED"
                liveStatus?.setTextColor(0xFFFFA000.toInt())
                btnPause.setImageResource(android.R.drawable.ic_media_play)
            } else {
                FairyStreamService.resumeCapture(context)
                liveStatus?.text = "● LIVE"
                liveStatus?.setTextColor(0xFFE53935.toInt())
                btnPause.setImageResource(android.R.drawable.ic_media_pause)
            }
        }

        btnMore?.setOnClickListener {
            openCreatorQuickActions()
        }
    }

    private fun toggleChatOverlay(btnChat: ImageButton) {
        isChatOverlayEnabled = !isChatOverlayEnabled
        if (isChatOverlayEnabled) {
            btnChat.setColorFilter(0xFF38BDF8.toInt())
            showChatOverlay()
        } else {
            btnChat.clearColorFilter()
            hideChatOverlay()
        }
    }

    private fun showChatOverlay() {
        if (chatOverlayView != null) return
        val wm = windowManager ?: (context.getSystemService(Context.WINDOW_SERVICE) as? WindowManager) ?: return

        val container = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.START
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        }
        this.chatContainer = container

        val layoutType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }

        // FLAG_NOT_FOCUSABLE and FLAG_NOT_TOUCHABLE ensure streamer can touch in-game buttons through chat!
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            layoutType,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            val hudParams = windowLayoutParams
            x = (hudParams?.x ?: 60)
            y = (hudParams?.y ?: 160) + dpToPx(48)
        }
        this.chatOverlayParams = params

        try {
            wm.addView(container, params)
            chatOverlayView = container
            Log.i(TAG, "Chat overlay attached to WindowManager")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to attach chat overlay to WindowManager: ${e.message}")
            return
        }

        // Ephemeral welcome pill
        displayEphemeralMessage("FairyLive", "Chat overlay active — comments auto-vanish", isSystem = true)

        // Listen to live chat stream
        chatJob?.cancel()
        chatJob = coroutineScope.launch {
            YouTubeLiveIntegrationService.chatMessages.collect { messages ->
                for (msg in messages) {
                    if (!seenMessageIds.contains(msg.id)) {
                        seenMessageIds.add(msg.id)
                        displayEphemeralMessage(msg.authorName, msg.messageText, msg.isSuperChat)
                    }
                }
            }
        }
    }

    private fun hideChatOverlay() {
        chatJob?.cancel()
        chatJob = null
        chatOverlayView?.let { view ->
            val wm = windowManager ?: (context.getSystemService(Context.WINDOW_SERVICE) as? WindowManager)
            try {
                wm?.removeView(view)
            } catch (e: Exception) {
                Log.w(TAG, "Failed to remove chatOverlayView: ${e.message}")
            }
        }
        chatOverlayView = null
        chatContainer = null
        chatOverlayParams = null
    }

    /**
     * Overlays ONLY the sender name and their message text temporarily on the screen.
     * The message bubble smoothly fades in and automatically vanishes after 5 seconds.
     */
    private fun displayEphemeralMessage(
        author: String,
        text: String,
        isSuperChat: Boolean = false,
        isSystem: Boolean = false
    ) {
        val container = chatContainer ?: return

        val sb = SpannableStringBuilder()
        val authorPrefix = if (isSystem) "ℹ " else if (isSuperChat) "★ " else ""
        val authorColor = if (isSystem) 0xFF94A3B8.toInt() else if (isSuperChat) 0xFFFFB703.toInt() else 0xFF38BDF8.toInt()

        val authorFormatted = "$authorPrefix$author: "
        sb.append(authorFormatted)
        sb.setSpan(
            ForegroundColorSpan(authorColor),
            0,
            authorFormatted.length,
            Spannable.SPAN_EXCLUSIVE_EXCLUSIVE
        )
        sb.setSpan(
            StyleSpan(Typeface.BOLD),
            0,
            authorFormatted.length,
            Spannable.SPAN_EXCLUSIVE_EXCLUSIVE
        )

        val textStart = sb.length
        sb.append(text)
        sb.setSpan(
            ForegroundColorSpan(0xFFFFFFFF.toInt()),
            textStart,
            sb.length,
            Spannable.SPAN_EXCLUSIVE_EXCLUSIVE
        )

        val textView = TextView(context).apply {
            this.text = sb
            textSize = 12f
            maxWidth = dpToPx(280)
            setBackgroundResource(R.drawable.bg_floating_chat_bubble)
            elevation = 8f
        }

        val itemParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply {
            bottomMargin = dpToPx(4)
        }

        // Keep maximum 3 simultaneous messages on screen
        while (container.childCount >= 3) {
            container.removeViewAt(0)
        }

        textView.alpha = 0f
        textView.translationY = 10f
        container.addView(textView, itemParams)
        textView.animate()
            .alpha(1f)
            .translationY(0f)
            .setDuration(250)
            .start()

        // Vanish after 5 seconds
        textView.postDelayed({
            textView.animate()
                .alpha(0f)
                .translationX(-20f)
                .setDuration(400)
                .withEndAction {
                    container.removeView(textView)
                }
                .start()
        }, 5000L)
    }

    private fun openCreatorChatSheet() {
        if (context is Activity) {
            val dialog = BottomSheetDialog(context)
            val sheetView = LayoutInflater.from(context).inflate(R.layout.sheet_creator_chat, null)
            dialog.setContentView(sheetView)

            val container = sheetView.findViewById<ViewGroup>(R.id.chatMessagesContainer)
            val editMsg = sheetView.findViewById<android.widget.EditText>(R.id.editChatMessage)
            val btnSend = sheetView.findViewById<android.widget.Button>(R.id.btnSendChat)

            fun renderMessages() {
                container?.removeAllViews()
                for (msg in YouTubeLiveIntegrationService.chatMessages.value) {
                    val tv = TextView(context).apply {
                        val prefix = if (msg.isStreamer) "[Streamer] " else ""
                        text = "$prefix${msg.authorName}: ${msg.messageText}"
                        setTextColor(if (msg.isStreamer) 0xFF0284C7.toInt() else 0xFF231B15.toInt())
                        textSize = 13f
                        setPadding(0, 4, 0, 4)
                    }
                    container?.addView(tv)
                }
            }

            renderMessages()

            btnSend?.setOnClickListener {
                val text = editMsg?.text?.toString()?.trim() ?: ""
                if (text.isNotEmpty()) {
                    YouTubeLiveIntegrationService.postChatMessage("Streamer", text, isStreamer = true)
                    editMsg?.text?.clear()
                    renderMessages()
                }
            }

            dialog.show()
        } else {
            // Service context: ephemeral indicator
            displayEphemeralMessage("Chat", "Tap chat icon to view temporary chat overlay", isSystem = true)
        }
    }

    private fun openCreatorQuickActions() {
        if (context is Activity) {
            val builder = androidx.appcompat.app.AlertDialog.Builder(context)
            builder.setTitle("Streamer Controls")
            builder.setItems(arrayOf(
                "Aspect Ratio: FIT (Full Screen)",
                "Aspect Ratio: FILL (Crop to 16:9)",
                "Close Floating HUD",
                "Open Fairy Live App"
            )) { _, which ->
                when (which) {
                    0 -> {
                        FairyStreamService.setScaleMode(context, com.fairylive.app.core.capture.model.ScaleMode.FIT)
                        displayEphemeralMessage("Ratio", "Set to FIT (100% full screen visible)", isSystem = true)
                    }
                    1 -> {
                        FairyStreamService.setScaleMode(context, com.fairylive.app.core.capture.model.ScaleMode.FILL)
                        displayEphemeralMessage("Ratio", "Set to FILL (Cropped)", isSystem = true)
                    }
                    2 -> dismiss()
                    3 -> {
                        val intent = Intent(context, MainActivity::class.java).apply {
                            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
                        }
                        context.startActivity(intent)
                    }
                }
            }
            builder.show()
        } else {
            // From service context: toggle Aspect Ratio mode
            FairyStreamService.setScaleMode(context, com.fairylive.app.core.capture.model.ScaleMode.FIT)
            displayEphemeralMessage("Ratio", "Auto-adjusted to FIT (Full Game UI visible)", isSystem = true)
        }
    }

    private fun setupOverlayDraggability(
        view: View,
        params: WindowManager.LayoutParams,
        wm: WindowManager
    ) {
        val btnToggle = view.findViewById<View>(R.id.hudBtnToggleCollapse) ?: view
        var initialX = 0
        var initialY = 0
        var initialTouchX = 0f
        var initialTouchY = 0f
        var startClickTime = 0L

        btnToggle.setOnTouchListener { _, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    initialX = params.x
                    initialY = params.y
                    initialTouchX = event.rawX
                    initialTouchY = event.rawY
                    startClickTime = SystemClock.elapsedRealtime()
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = (event.rawX - initialTouchX).toInt()
                    val dy = (event.rawY - initialTouchY).toInt()
                    params.x = initialX + dx
                    params.y = initialY + dy
                    try {
                        wm.updateViewLayout(view, params)
                        // Also update chat overlay position so it follows the HUD
                        chatOverlayParams?.let { cp ->
                            cp.x = params.x
                            cp.y = params.y + dpToPx(48)
                            chatOverlayView?.let { cv -> wm.updateViewLayout(cv, cp) }
                        }
                    } catch (_: Exception) {}
                    true
                }
                MotionEvent.ACTION_UP -> {
                    val duration = SystemClock.elapsedRealtime() - startClickTime
                    val distSq = (event.rawX - initialTouchX) * (event.rawX - initialTouchX) +
                            (event.rawY - initialTouchY) * (event.rawY - initialTouchY)
                    if (distSq < 150) {
                        if (duration > 800) {
                            // Long-press: Dismiss HUD immediately
                            dismiss()
                            return@setOnTouchListener true
                        } else {
                            isExpanded = !isExpanded
                            val expandedControls = view.findViewById<View>(R.id.hudExpandedControls)
                            val btn = view.findViewById<ImageButton>(R.id.hudBtnToggleCollapse)
                            expandedControls?.visibility = if (isExpanded) View.VISIBLE else View.GONE
                            btn?.setImageResource(
                                if (isExpanded) android.R.drawable.ic_menu_close_clear_cancel
                                else android.R.drawable.ic_menu_manage
                            )
                        }
                    }
                    true
                }
                else -> false
            }
        }
    }

    private fun setupDecorDraggability(view: View) {
        var dX = 0f
        var dY = 0f

        view.setOnTouchListener { v, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    dX = v.x - event.rawX
                    dY = v.y - event.rawY
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    v.animate()
                        .x(event.rawX + dX)
                        .y(event.rawY + dY)
                        .setDuration(0)
                        .start()
                    true
                }
                MotionEvent.ACTION_UP -> {
                    v.performClick()
                    true
                }
                else -> false
            }
        }
    }

    private fun dpToPx(dp: Int): Int =
        (dp * context.resources.displayMetrics.density).toInt()

    companion object {
        private const val TAG = "StreamerFloatingHud"
        private val activeInstances = CopyOnWriteArrayList<StreamerFloatingHudManager>()

        fun dismissAll() {
            for (instance in activeInstances) {
                try {
                    instance.dismiss()
                } catch (e: Exception) {
                    Log.w(TAG, "Error dismissing active HUD: ${e.message}")
                }
            }
            activeInstances.clear()
        }
    }
}
