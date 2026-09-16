package com.fairylive.app.ui.hud

import android.app.Activity
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.TextView
import com.fairylive.app.R
import com.fairylive.app.core.render.RenderDomain
import com.fairylive.app.service.FairyStreamService
import com.google.android.material.bottomsheet.BottomSheetDialog

/**
 * Manages the Streamer Floating HUD window.
 * Strictly adheres to RenderDomain.CREATOR_ONLY:
 * Rendered directly into the host Activity's DecorView / Window decor hierarchy,
 * completely isolated from the broadcast GLES Compositor and RTMP encoder pipeline.
 */
class StreamerFloatingHudManager(private val activity: Activity) {

    val renderDomain: RenderDomain = RenderDomain.CREATOR_ONLY

    private var hudView: View? = null
    private var isMicMuted: Boolean = false
    private var isStreamPaused: Boolean = false

    fun show() {
        if (hudView != null) return

        val decorView = activity.window.decorView as? ViewGroup ?: return
        val inflater = LayoutInflater.from(activity)
        val view = inflater.inflate(R.layout.view_streamer_floating_hud, decorView, false)

        val params = FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.WRAP_CONTENT,
            FrameLayout.LayoutParams.WRAP_CONTENT
        ).apply {
            gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
            topMargin = 120
        }

        setupHudControls(view)
        setupDraggability(view)

        decorView.addView(view, params)
        hudView = view
    }

    fun dismiss() {
        hudView?.let {
            val parent = it.parent as? ViewGroup
            parent?.removeView(it)
            hudView = null
        }
    }

    fun isShowing(): Boolean = hudView != null

    private fun setupHudControls(view: View) {
        val btnChat = view.findViewById<ImageButton>(R.id.hudBtnChat)
        val btnMic = view.findViewById<ImageButton>(R.id.hudBtnMic)
        val btnPause = view.findViewById<ImageButton>(R.id.hudBtnPause)
        val btnMore = view.findViewById<ImageButton>(R.id.hudBtnMore)
        val liveStatus = view.findViewById<TextView>(R.id.hudLiveStatus)

        btnChat?.setOnClickListener {
            openCreatorChatSheet()
        }

        btnMic?.setOnClickListener {
            isMicMuted = !isMicMuted
            FairyStreamService.toggleMicrophone(activity)
            btnMic.setImageResource(
                if (isMicMuted) android.R.drawable.ic_lock_silent_mode
                else android.R.drawable.ic_btn_speak_now
            )
        }

        btnPause?.setOnClickListener {
            isStreamPaused = !isStreamPaused
            if (isStreamPaused) {
                FairyStreamService.pauseCapture(activity)
                liveStatus?.text = "⏸ PAUSED"
                liveStatus?.setTextColor(0xFFFFA000.toInt())
                btnPause.setImageResource(android.R.drawable.ic_media_play)
            } else {
                FairyStreamService.resumeCapture(activity)
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
        val dialog = BottomSheetDialog(activity)
        val sheetView = LayoutInflater.from(activity).inflate(R.layout.sheet_creator_chat, null)
        dialog.setContentView(sheetView)
        dialog.show()
    }

    private fun openCreatorQuickActions() {
        android.widget.Toast.makeText(activity, "Creator Control: Settings & Audio Diagnostics", android.widget.Toast.LENGTH_SHORT).show()
    }

    private fun setupDraggability(view: View) {
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
}
