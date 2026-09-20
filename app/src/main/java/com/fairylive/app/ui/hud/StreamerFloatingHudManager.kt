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
import android.widget.SeekBar
import android.widget.EditText
import android.widget.Button
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
                showChatOverlay()
                val repo = com.fairylive.app.core.settings.StreamSettingsRepository(context)
                val savedYtUrl = repo.getYouTubeLiveUrl()
                if (savedYtUrl.isNotBlank() && !YouTubeLiveIntegrationService.isPollingActive) {
                    YouTubeLiveIntegrationService.startLiveChatPolling(savedYtUrl)
                }
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
        hideAudioMixerDialog()
        hideConnectYouTubeChatDialog()

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
        val btnMixer = view.findViewById<ImageButton>(R.id.hudBtnMixer)
        val btnCamera = view.findViewById<ImageButton>(R.id.hudBtnCamera)
        val btnPause = view.findViewById<ImageButton>(R.id.hudBtnPause)
        val btnMore = view.findViewById<ImageButton>(R.id.hudBtnMore)
        val liveStatus = view.findViewById<TextView>(R.id.hudLiveStatus)

        btnToggle?.setOnClickListener {
            isExpanded = !isExpanded
            applyExpandedState(view)
        }
        applyExpandedState(view)

        btnMixer?.setOnClickListener {
            showAudioMixerDialog()
        }

        // Ephemeral chat overlay click toggle; long-press opens YouTube connect / full chat sheet
        btnChat?.setOnClickListener {
            if (!YouTubeLiveIntegrationService.isPollingActive) {
                showConnectYouTubeChatDialog()
            } else {
                toggleChatOverlay(btnChat)
            }
        }
        btnChat?.setOnLongClickListener {
            showConnectYouTubeChatDialog()
            true
        }

        btnMic?.setOnClickListener {
            isMicMuted = !isMicMuted
            FairyStreamService.toggleMicrophone(context)
            btnMic.setImageResource(
                if (isMicMuted) android.R.drawable.ic_lock_silent_mode
                else android.R.drawable.ic_btn_speak_now
            )
            displayEphemeralMessage("Microphone", if (isMicMuted) "Voice Muted (Game Audio Active)" else "Microphone Active", isSystem = true)
        }
        btnMic?.setOnLongClickListener {
            showAudioMixerDialog()
            true
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
    fun displayEphemeralMessage(
        author: String,
        text: String,
        isSuperChat: Boolean = false,
        isSystem: Boolean = false
    ) {
        if (chatOverlayView == null) {
            showChatOverlay()
        }
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

    private var mixerDialogView: View? = null
    private var connectChatDialogView: View? = null

    fun showAudioMixerDialog() {
        if (mixerDialogView != null) {
            hideAudioMixerDialog()
            return
        }

        val wm = (context.getSystemService(Context.WINDOW_SERVICE) as? WindowManager) ?: return
        val card = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dpToPx(16), dpToPx(16), dpToPx(16), dpToPx(16))
            val bg = android.graphics.drawable.GradientDrawable().apply {
                setColor(0xF0111827.toInt())
                cornerRadius = dpToPx(14).toFloat()
                setStroke(dpToPx(1), 0xFF374151.toInt())
            }
            background = bg
        }

        // Header
        val header = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        }
        val title = TextView(context).apply {
            text = "🎚️ AUDIO STUDIO"
            setTextColor(0xFFFFFFFF.toInt())
            textSize = 14f
            setTypeface(null, android.graphics.Typeface.BOLD)
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        val closeBtn = ImageButton(context).apply {
            setImageResource(android.R.drawable.ic_menu_close_clear_cancel)
            setBackgroundColor(0)
            setColorFilter(0xFF9CA3AF.toInt())
            setOnClickListener { hideAudioMixerDialog() }
        }
        header.addView(title)
        header.addView(closeBtn)
        card.addView(header)

        var currentMicVol = 0.9f
        var currentGameVol = 0.8f
        var currentMediaVol = 0.5f
        var currentMasterVol = 1.0f

        // 1. Device Microphone Channel (Universal Device Mic - ZERO Pixel mentions)
        val micLabel = TextView(context).apply {
            text = "🎙 Device Microphone: ${(currentMicVol * 100).toInt()}%"
            setTextColor(0xFFE5E7EB.toInt())
            textSize = 12f
            setPadding(0, dpToPx(8), 0, dpToPx(2))
        }
        val micSeekBar = SeekBar(context).apply {
            max = 150
            progress = (currentMicVol * 100).toInt()
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(sb: SeekBar?, progress: Int, fromUser: Boolean) {
                    currentMicVol = progress / 100f
                    micLabel.text = "🎙 Device Microphone: ${progress}%"
                    FairyStreamService.setAudioVolumes(
                        context = context,
                        micVolume = currentMicVol,
                        gameVolume = currentGameVol,
                        mediaVolume = currentMediaVol,
                        masterVolume = currentMasterVol
                    )
                }
                override fun onStartTrackingTouch(sb: SeekBar?) {}
                override fun onStopTrackingTouch(sb: SeekBar?) {}
            })
        }
        card.addView(micLabel)
        card.addView(micSeekBar)

        // 2. Game Audio Channel
        val gameLabel = TextView(context).apply {
            text = "🎮 Game & System Audio: ${(currentGameVol * 100).toInt()}%"
            setTextColor(0xFFE5E7EB.toInt())
            textSize = 12f
            setPadding(0, dpToPx(6), 0, dpToPx(2))
        }
        val gameSeekBar = SeekBar(context).apply {
            max = 150
            progress = (currentGameVol * 100).toInt()
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(sb: SeekBar?, progress: Int, fromUser: Boolean) {
                    currentGameVol = progress / 100f
                    gameLabel.text = "🎮 Game & System Audio: ${progress}%"
                    FairyStreamService.setAudioVolumes(
                        context = context,
                        micVolume = currentMicVol,
                        gameVolume = currentGameVol,
                        mediaVolume = currentMediaVol,
                        masterVolume = currentMasterVol
                    )
                }
                override fun onStartTrackingTouch(sb: SeekBar?) {}
                override fun onStopTrackingTouch(sb: SeekBar?) {}
            })
        }
        card.addView(gameLabel)
        card.addView(gameSeekBar)

        // 3. Media / BGM Channel
        val mediaLabel = TextView(context).apply {
            text = "🎵 Media & BGM: ${(currentMediaVol * 100).toInt()}%"
            setTextColor(0xFFE5E7EB.toInt())
            textSize = 12f
            setPadding(0, dpToPx(6), 0, dpToPx(2))
        }
        val mediaSeekBar = SeekBar(context).apply {
            max = 100
            progress = (currentMediaVol * 100).toInt()
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(sb: SeekBar?, progress: Int, fromUser: Boolean) {
                    currentMediaVol = progress / 100f
                    mediaLabel.text = "🎵 Media & BGM: ${progress}%"
                    FairyStreamService.setAudioVolumes(
                        context = context,
                        micVolume = currentMicVol,
                        gameVolume = currentGameVol,
                        mediaVolume = currentMediaVol,
                        masterVolume = currentMasterVol
                    )
                }
                override fun onStartTrackingTouch(sb: SeekBar?) {}
                override fun onStopTrackingTouch(sb: SeekBar?) {}
            })
        }
        card.addView(mediaLabel)
        card.addView(mediaSeekBar)

        // 4. Master Volume Channel
        val masterLabel = TextView(context).apply {
            text = "🎚 Master Output: ${(currentMasterVol * 100).toInt()}%"
            setTextColor(0xFFE5E7EB.toInt())
            textSize = 12f
            setPadding(0, dpToPx(6), 0, dpToPx(2))
        }
        val masterSeekBar = SeekBar(context).apply {
            max = 100
            progress = (currentMasterVol * 100).toInt()
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(sb: SeekBar?, progress: Int, fromUser: Boolean) {
                    currentMasterVol = progress / 100f
                    masterLabel.text = "🎚 Master Output: ${progress}%"
                    FairyStreamService.setAudioVolumes(
                        context = context,
                        micVolume = currentMicVol,
                        gameVolume = currentGameVol,
                        mediaVolume = currentMediaVol,
                        masterVolume = currentMasterVol
                    )
                }
                override fun onStartTrackingTouch(sb: SeekBar?) {}
                override fun onStopTrackingTouch(sb: SeekBar?) {}
            })
        }
        card.addView(masterLabel)
        card.addView(masterSeekBar)

        // DSP Processors Row (Noise Gate, Limiter)
        val dspRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, dpToPx(8), 0, 0)
        }
        val btnNoise = TextView(context).apply {
            text = "🚫 Noise Gate: ON"
            setTextColor(0xFF10B981.toInt())
            textSize = 11f
            var enabled = true
            setOnClickListener {
                enabled = !enabled
                text = if (enabled) "🚫 Noise Gate: ON" else "🚫 Noise Gate: OFF"
                setTextColor(if (enabled) 0xFF10B981.toInt() else 0xFF9CA3AF.toInt())
                FairyStreamService.setAudioDspFilters(context, noiseGate = enabled, agc = false, compressor = true, limiter = true)
            }
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        val btnLimiter = TextView(context).apply {
            text = "🔊 Limiter: ON"
            setTextColor(0xFF10B981.toInt())
            textSize = 11f
            var enabled = true
            setOnClickListener {
                enabled = !enabled
                text = if (enabled) "🔊 Limiter: ON" else "🔊 Limiter: OFF"
                setTextColor(if (enabled) 0xFF10B981.toInt() else 0xFF9CA3AF.toInt())
                FairyStreamService.setAudioDspFilters(context, noiseGate = true, agc = false, compressor = true, limiter = enabled)
            }
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        dspRow.addView(btnNoise)
        dspRow.addView(btnLimiter)
        card.addView(dspRow)

        val layoutType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        else
            @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_PHONE

        val params = WindowManager.LayoutParams(
            dpToPx(300),
            WindowManager.LayoutParams.WRAP_CONTENT,
            layoutType,
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.CENTER
        }

        try {
            wm.addView(card, params)
            mixerDialogView = card
        } catch (e: Exception) {
            Log.e(TAG, "Failed to show audio mixer overlay: ${e.message}")
        }
    }

    fun hideAudioMixerDialog() {
        mixerDialogView?.let { v ->
            try {
                (context.getSystemService(Context.WINDOW_SERVICE) as? WindowManager)?.removeView(v)
            } catch (_: Exception) {}
            mixerDialogView = null
        }
    }

    fun showConnectYouTubeChatDialog() {
        if (connectChatDialogView != null) {
            hideConnectYouTubeChatDialog()
            return
        }

        val wm = (context.getSystemService(Context.WINDOW_SERVICE) as? WindowManager) ?: return
        val repo = com.fairylive.app.core.settings.StreamSettingsRepository(context)
        val savedUrl = repo.getYouTubeLiveUrl()

        val card = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            val bg = android.graphics.drawable.GradientDrawable().apply {
                setColor(0xF0111827.toInt())
                cornerRadius = dpToPx(14).toFloat()
                setStroke(dpToPx(1), 0xFF374151.toInt())
            }
            background = bg
            setPadding(dpToPx(16), dpToPx(16), dpToPx(16), dpToPx(16))
        }

        val header = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        }
        val title = TextView(context).apply {
            text = "💬 CONNECT YOUTUBE CHAT"
            setTextColor(0xFFFFFFFF.toInt())
            textSize = 14f
            setTypeface(null, android.graphics.Typeface.BOLD)
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        val closeBtn = ImageButton(context).apply {
            setImageResource(android.R.drawable.ic_menu_close_clear_cancel)
            setBackgroundColor(0)
            setColorFilter(0xFF9CA3AF.toInt())
            setOnClickListener { hideConnectYouTubeChatDialog() }
        }
        header.addView(title)
        header.addView(closeBtn)
        card.addView(header)

        val desc = TextView(context).apply {
            text = "Enter YouTube Live URL, Video ID, or @Channel Handle:"
            setTextColor(0xFF9CA3AF.toInt())
            textSize = 11f
            setPadding(0, dpToPx(8), 0, dpToPx(4))
        }
        card.addView(desc)

        val editText = EditText(context).apply {
            hint = "e.g. @MyChannel or youtube.com/live/xxx"
            setText(savedUrl)
            setTextColor(0xFFFFFFFF.toInt())
            setHintTextColor(0xFF6B7280.toInt())
            textSize = 13f
            val editBg = android.graphics.drawable.GradientDrawable().apply {
                setColor(0xFF1F2937.toInt())
                cornerRadius = dpToPx(8).toFloat()
                setStroke(dpToPx(1), 0xFF4B5563.toInt())
            }
            background = editBg
            setPadding(dpToPx(10), dpToPx(8), dpToPx(10), dpToPx(8))
        }
        card.addView(editText)

        val btnConnect = Button(context).apply {
            text = if (YouTubeLiveIntegrationService.isPollingActive) "RECONNECT CHAT" else "CONNECT CHAT"
            setTextColor(0xFFFFFFFF.toInt())
            textSize = 12f
            setTypeface(null, android.graphics.Typeface.BOLD)
            val btnBg = android.graphics.drawable.GradientDrawable().apply {
                setColor(0xFF0284C7.toInt())
                cornerRadius = dpToPx(8).toFloat()
            }
            background = btnBg
            val lp = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                dpToPx(40)
            ).apply {
                topMargin = dpToPx(12)
            }
            layoutParams = lp
            setOnClickListener {
                val input = editText.text.toString().trim()
                if (input.isNotEmpty()) {
                    repo.setYouTubeLiveUrl(input)
                    YouTubeLiveIntegrationService.startLiveChatPolling(input)
                    displayEphemeralMessage("YouTube Chat", "Connecting to live chat...", isSystem = true)
                    hideConnectYouTubeChatDialog()
                } else {
                    editText.error = "Please enter a URL or handle"
                }
            }
        }
        card.addView(btnConnect)

        val layoutType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        else
            @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_PHONE

        val params = WindowManager.LayoutParams(
            dpToPx(300),
            WindowManager.LayoutParams.WRAP_CONTENT,
            layoutType,
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.CENTER
        }

        try {
            wm.addView(card, params)
            connectChatDialogView = card
        } catch (e: Exception) {
            Log.e(TAG, "Failed to show connect chat dialog: ${e.message}")
        }
    }

    fun hideConnectYouTubeChatDialog() {
        connectChatDialogView?.let { v ->
            try {
                (context.getSystemService(Context.WINDOW_SERVICE) as? WindowManager)?.removeView(v)
            } catch (_: Exception) {}
            connectChatDialogView = null
        }
    }

    private fun openCreatorQuickActions() {
        if (context is Activity) {
            val builder = androidx.appcompat.app.AlertDialog.Builder(context)
            builder.setTitle("Streamer Controls")
            builder.setItems(arrayOf(
                "Aspect Ratio: FIT (Full Screen)",
                "Aspect Ratio: FILL (Crop to 16:9)",
                "🔊 Audio Mixer (Mic & Game Volume)",
                "💬 Connect YouTube Live Chat",
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
                    2 -> showAudioMixerDialog()
                    3 -> showConnectYouTubeChatDialog()
                    4 -> dismiss()
                    5 -> {
                        val intent = Intent(context, MainActivity::class.java).apply {
                            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
                        }
                        context.startActivity(intent)
                    }
                }
            }
            builder.show()
        } else {
            // Service context: Show Audio Mixer
            showAudioMixerDialog()
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
                            applyExpandedState(view)
                        }
                    }
                    true
                }
                else -> false
            }
        }
    }

    private fun applyExpandedState(view: View) {
        val expandedControls = view.findViewById<View>(R.id.hudExpandedControls)
        val btn = view.findViewById<ImageButton>(R.id.hudBtnToggleCollapse)
        expandedControls?.visibility = if (isExpanded) View.VISIBLE else View.GONE
        if (isExpanded) {
            view.setBackgroundResource(R.drawable.bg_streamer_hud_pill)
            val p = dpToPx(4)
            view.setPadding(p, p, p, p)
            btn?.setImageResource(android.R.drawable.ic_menu_close_clear_cancel)
            btn?.imageTintList = android.content.res.ColorStateList.valueOf(android.graphics.Color.WHITE)
            btn?.contentDescription = "Collapse Stream Controls"
        } else {
            view.setBackgroundResource(0)
            view.setPadding(0, 0, 0, 0)
            btn?.setImageResource(R.drawable.ic_fairy_logo)
            btn?.imageTintList = null
            btn?.contentDescription = "Fairy Live Stream Controls"
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

        fun hasActiveInstance(): Boolean = activeInstances.isNotEmpty()

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
