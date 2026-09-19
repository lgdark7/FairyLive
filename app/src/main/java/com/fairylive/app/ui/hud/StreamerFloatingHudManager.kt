package com.fairylive.app.ui.hud

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.os.Build
import android.os.SystemClock
import android.provider.Settings
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
import android.widget.TextView
import com.fairylive.app.MainActivity
import com.fairylive.app.R
import com.fairylive.app.core.render.RenderDomain
import com.fairylive.app.service.FairyStreamService
import com.google.android.material.bottomsheet.BottomSheetDialog

/**
 * Manages the Streamer Floating HUD window.
 * Strictly adheres to RenderDomain.CREATOR_ONLY:
 *
 * When Settings.canDrawOverlays(context) is true, attaches directly to WindowManager
 * with TYPE_APPLICATION_OVERLAY so the streamer retains complete control while playing
 * full-screen landscape games (e.g. Free Fire MAX).
 *
 * Excluded from stream capture: On Android 14+ RECORD_CONTENT_TASK captures only the target
 * application task window, so this system overlay is 100% invisible on the RTMP/YouTube broadcast.
 *
 * Falls back to host Activity DecorView when system overlay permission is not granted.
 */
class StreamerFloatingHudManager(private val context: Context) {

    val renderDomain: RenderDomain = RenderDomain.CREATOR_ONLY

    private var windowManager: WindowManager? = null
    private var windowLayoutParams: WindowManager.LayoutParams? = null
    private var hudView: View? = null

    private var isMicMuted: Boolean = false
    private var isStreamPaused: Boolean = false
    private var isExpanded: Boolean = false

    fun show() {
        if (hudView != null) return

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
        hudView?.let { view ->
            val wm = windowManager
            if (wm != null) {
                try {
                    wm.removeView(view)
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to remove system overlay view", e)
                }
                windowManager = null
                windowLayoutParams = null
            } else {
                val parent = view.parent as? ViewGroup
                parent?.removeView(view)
            }
            hudView = null
        }
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

        btnChat?.setOnClickListener {
            openCreatorChatSheet()
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
                for (msg in com.fairylive.app.core.youtube.YouTubeLiveIntegrationService.chatMessages.value) {
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
                    com.fairylive.app.core.youtube.YouTubeLiveIntegrationService.postChatMessage("Streamer", text, isStreamer = true)
                    editMsg?.text?.clear()
                    renderMessages()
                }
            }

            dialog.show()
        } else {
            // Service context: open MainActivity to Creator Studio
            try {
                val intent = Intent(context, MainActivity::class.java).apply {
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
                }
                context.startActivity(intent)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to launch MainActivity from service", e)
            }
        }
    }

    private fun openCreatorQuickActions() {
        try {
            val intent = Intent(context, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
            }
            context.startActivity(intent)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to launch MainActivity", e)
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
                    } catch (_: Exception) {}
                    true
                }
                MotionEvent.ACTION_UP -> {
                    val duration = SystemClock.elapsedRealtime() - startClickTime
                    val distSq = (event.rawX - initialTouchX) * (event.rawX - initialTouchX) +
                            (event.rawY - initialTouchY) * (event.rawY - initialTouchY)
                    if (duration < 300 && distSq < 150) {
                        isExpanded = !isExpanded
                        val expandedControls = view.findViewById<View>(R.id.hudExpandedControls)
                        val btn = view.findViewById<ImageButton>(R.id.hudBtnToggleCollapse)
                        expandedControls?.visibility = if (isExpanded) View.VISIBLE else View.GONE
                        btn?.setImageResource(
                            if (isExpanded) android.R.drawable.ic_menu_close_clear_cancel
                            else android.R.drawable.ic_menu_manage
                        )
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

    companion object {
        private const val TAG = "StreamerFloatingHud"
    }
}