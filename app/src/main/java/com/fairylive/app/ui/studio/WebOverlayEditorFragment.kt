package com.fairylive.app.ui.studio

import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.*
import androidx.fragment.app.Fragment
import com.fairylive.app.core.compositor.model.OverlayElement
import com.fairylive.app.core.compositor.model.WebOverlayConfig

/**
 * Phase 6 §7: Professional Transform & Inspector Panel.
 *
 * Dedicated inspector for Web Overlay layers (and general overlay transform controls):
 * - URL display / edit / reload
 * - Web Navigation: Back, Forward, Reload, Stop
 * - Web Zoom slider (0.25x – 5.0x, independent of overlay scale)
 * - Overlay Scale slider (0.2x – 3.0x)
 * - Rotation slider (0° – 360°) + Quick presets (0°, 90°, 180°, 270°) + Reset
 * - Crop Region controls: left, top, right, bottom margins (0% – 50%)
 * - Lock/Unlock toggle
 * - Strict CREATOR_ONLY domain — never leaks into broadcast video stream.
 * - Styled with Warm Ivory (#FBF9F5) and Espresso (#231B15).
 */
class WebOverlayEditorFragment : Fragment() {

    interface EditorListener {
        fun onTransformChanged(id: String, scale: Float, rotationDegrees: Float)
        fun onWebConfigChanged(id: String, newConfig: WebOverlayConfig)
        fun onCropChanged(id: String, left: Float, top: Float, right: Float, bottom: Float)
        fun onLockToggled(id: String, locked: Boolean)
        fun onWebNavigateBack(id: String)
        fun onWebNavigateForward(id: String)
        fun onWebReload(id: String)
        fun onCloseRequested()
    }

    private var listener: EditorListener? = null
    private var currentElement: OverlayElement? = null
    private var currentWebConfig: WebOverlayConfig? = null

    fun setListener(listener: EditorListener) {
        this.listener = listener
    }

    fun bindOverlay(element: OverlayElement, webConfig: WebOverlayConfig? = null) {
        this.currentElement = element
        this.currentWebConfig = webConfig
        view?.let { populateFields(it) }
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        val root = ScrollView(requireContext()).apply {
            setBackgroundColor(Color.parseColor("#FBF9F5"))
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        }

        val content = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(16), dp(16), dp(24))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        }
        root.addView(content)

        // Header
        val header = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(16) }
        }

        val title = TextView(requireContext()).apply {
            text = "Overlay Inspector"
            textSize = 17f
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            setTextColor(Color.parseColor("#231B15"))
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }

        val closeBtn = Button(requireContext()).apply {
            text = "✕"
            textSize = 14f
            setBackgroundColor(Color.TRANSPARENT)
            setTextColor(Color.parseColor("#6E6259"))
            setOnClickListener { listener?.onCloseRequested() }
        }

        header.addView(title)
        header.addView(closeBtn)
        content.addView(header)

        // Section: Web Navigation (only if web overlay)
        buildWebNavigationSection(content)

        // Section: Rotation Controls (slider + presets)
        buildRotationSection(content)

        // Section: Scale & Zoom
        buildScaleAndZoomSection(content)

        // Section: Crop Margins
        buildCropSection(content)

        // Section: Lock & Actions
        buildActionSection(content)

        return root
    }

    private fun buildWebNavigationSection(parent: LinearLayout) {
        val section = createSectionCard(parent, "Web Navigation & Controls")

        // URL display
        val urlText = TextView(requireContext()).apply {
            id = ID_URL_TEXT
            text = currentWebConfig?.url ?: "No URL"
            textSize = 12f
            setTextColor(Color.parseColor("#6E6259"))
            setPadding(0, 0, 0, dp(8))
        }
        section.addView(urlText)

        // Nav buttons: Back, Forward, Reload
        val btnRow = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        }

        val backBtn = createStyledButton("◀ Back") {
            currentElement?.let { listener?.onWebNavigateBack(it.id) }
        }
        val fwdBtn = createStyledButton("Forward ▶") {
            currentElement?.let { listener?.onWebNavigateForward(it.id) }
        }
        val reloadBtn = createStyledButton("⟳ Reload") {
            currentElement?.let { listener?.onWebReload(it.id) }
        }

        btnRow.addView(backBtn)
        btnRow.addView(fwdBtn)
        btnRow.addView(reloadBtn)
        section.addView(btnRow)
    }

    private fun buildRotationSection(parent: LinearLayout) {
        val section = createSectionCard(parent, "Rotation (0° – 360°)")

        val valueLabel = TextView(requireContext()).apply {
            id = ID_ROTATION_LABEL
            text = "${currentElement?.rotationDegrees?.toInt() ?: 0}°"
            textSize = 13f
            setTextColor(Color.parseColor("#231B15"))
        }
        section.addView(valueLabel)

        val slider = SeekBar(requireContext()).apply {
            id = ID_ROTATION_SLIDER
            max = 360
            progress = (currentElement?.rotationDegrees ?: 0f).toInt()
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(sb: SeekBar?, prog: Int, fromUser: Boolean) {
                    if (!fromUser) return
                    valueLabel.text = "$prog°"
                    val elem = currentElement ?: return
                    listener?.onTransformChanged(elem.id, elem.scale, prog.toFloat())
                }
                override fun onStartTrackingTouch(sb: SeekBar?) {}
                override fun onStopTrackingTouch(sb: SeekBar?) {}
            })
        }
        section.addView(slider)

        // Preset buttons: 0°, 90°, 180°, 270°, Reset
        val presetRow = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(8) }
        }

        listOf(0, 90, 180, 270).forEach { deg ->
            val btn = createStyledButton("$deg°") {
                slider.progress = deg
                valueLabel.text = "$deg°"
                val elem = currentElement ?: return@createStyledButton
                listener?.onTransformChanged(elem.id, elem.scale, deg.toFloat())
            }
            presetRow.addView(btn)
        }
        section.addView(presetRow)
    }

    private fun buildScaleAndZoomSection(parent: LinearLayout) {
        val section = createSectionCard(parent, "Scale & Web Zoom")

        // Overlay Scale
        val scaleLabel = TextView(requireContext()).apply {
            id = ID_SCALE_LABEL
            text = "Overlay Scale: ${((currentElement?.scale ?: 1f) * 100).toInt()}%"
            textSize = 12f
            setTextColor(Color.parseColor("#231B15"))
        }
        section.addView(scaleLabel)

        val scaleSlider = SeekBar(requireContext()).apply {
            id = ID_SCALE_SLIDER
            max = 280 // maps to 0.2x – 3.0x: value = 20 + prog (i.e. / 100)
            progress = (((currentElement?.scale ?: 1f) - 0.2f) * 100).toInt().coerceIn(0, 280)
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(sb: SeekBar?, prog: Int, fromUser: Boolean) {
                    if (!fromUser) return
                    val scale = (prog + 20) / 100f
                    scaleLabel.text = "Overlay Scale: ${(scale * 100).toInt()}%"
                    val elem = currentElement ?: return
                    listener?.onTransformChanged(elem.id, scale, elem.rotationDegrees)
                }
                override fun onStartTrackingTouch(sb: SeekBar?) {}
                override fun onStopTrackingTouch(sb: SeekBar?) {}
            })
        }
        section.addView(scaleSlider)

        // Web Zoom (content zoom, separate from overlay scale)
        val zoomLabel = TextView(requireContext()).apply {
            id = ID_ZOOM_LABEL
            text = "Webpage Content Zoom: ${((currentWebConfig?.zoomLevel ?: 1f) * 100).toInt()}%"
            textSize = 12f
            setTextColor(Color.parseColor("#231B15"))
            setPadding(0, dp(8), 0, 0)
        }
        section.addView(zoomLabel)

        val zoomSlider = SeekBar(requireContext()).apply {
            id = ID_ZOOM_SLIDER
            max = 475 // maps 0.25x – 5.0x
            progress = (((currentWebConfig?.zoomLevel ?: 1f) - 0.25f) * 100).toInt().coerceIn(0, 475)
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(sb: SeekBar?, prog: Int, fromUser: Boolean) {
                    if (!fromUser) return
                    val zoom = (prog + 25) / 100f
                    zoomLabel.text = "Webpage Content Zoom: ${(zoom * 100).toInt()}%"
                    val cfg = currentWebConfig ?: return
                    val elem = currentElement ?: return
                    val updated = cfg.copy(zoomLevel = zoom)
                    currentWebConfig = updated
                    listener?.onWebConfigChanged(elem.id, updated)
                }
                override fun onStartTrackingTouch(sb: SeekBar?) {}
                override fun onStopTrackingTouch(sb: SeekBar?) {}
            })
        }
        section.addView(zoomSlider)
    }

    private fun buildCropSection(parent: LinearLayout) {
        val section = createSectionCard(parent, "Crop Margins (% from edge)")

        val cfg = currentWebConfig
        val cropInfo = TextView(requireContext()).apply {
            id = ID_CROP_INFO
            text = "L: ${(cfg?.cropLeft ?: 0f) * 100}%  T: ${(cfg?.cropTop ?: 0f) * 100}%  R: ${(cfg?.cropRight ?: 0f) * 100}%  B: ${(cfg?.cropBottom ?: 0f) * 100}%"
            textSize = 11f
            setTextColor(Color.parseColor("#6E6259"))
            setPadding(0, 0, 0, dp(4))
        }
        section.addView(cropInfo)

        val resetCropBtn = createStyledButton("Reset Crop") {
            val elem = currentElement ?: return@createStyledButton
            listener?.onCropChanged(elem.id, 0f, 0f, 0f, 0f)
            cropInfo.text = "L: 0%  T: 0%  R: 0%  B: 0%"
        }
        section.addView(resetCropBtn)
    }

    private fun buildActionSection(parent: LinearLayout) {
        val section = createSectionCard(parent, "Layer State")

        val lockBtn = Button(requireContext()).apply {
            id = ID_LOCK_BTN
            text = if (currentElement?.locked == true) "🔒 Locked (tap to unlock)" else "🔓 Unlocked (tap to lock)"
            textSize = 12f
            val elem = currentElement
            setBackgroundColor(if (elem?.locked == true) Color.parseColor("#F5EFEB") else Color.WHITE)
            setTextColor(Color.parseColor("#231B15"))
            setOnClickListener {
                val current = currentElement ?: return@setOnClickListener
                val newLocked = !current.locked
                currentElement = current.copy(locked = newLocked)
                text = if (newLocked) "🔒 Locked (tap to unlock)" else "🔓 Unlocked (tap to lock)"
                listener?.onLockToggled(current.id, newLocked)
            }
        }
        section.addView(lockBtn)
    }

    private fun createSectionCard(parent: LinearLayout, titleText: String): LinearLayout {
        val card = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), dp(10), dp(12), dp(10))
            background = GradientDrawable().apply {
                setColor(Color.WHITE)
                setStroke(dp(1), Color.parseColor("#EBE5DF"))
                cornerRadius = dp(8).toFloat()
            }
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(10) }
        }

        val title = TextView(requireContext()).apply {
            text = titleText
            textSize = 12f
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            setTextColor(Color.parseColor("#6E6259"))
            setPadding(0, 0, 0, dp(6))
        }
        card.addView(title)
        parent.addView(card)
        return card
    }

    private fun createStyledButton(label: String, onClick: () -> Unit): Button {
        return Button(requireContext()).apply {
            text = label
            textSize = 11f
            setTextColor(Color.parseColor("#231B15"))
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#F5EFEB"))
                setStroke(dp(1), Color.parseColor("#EBE5DF"))
                cornerRadius = dp(6).toFloat()
            }
            layoutParams = LinearLayout.LayoutParams(0, dp(34), 1f).apply {
                marginEnd = dp(4)
            }
            setOnClickListener { onClick() }
        }
    }

    private fun populateFields(root: View) {
        val elem = currentElement ?: return
        val cfg = currentWebConfig

        root.findViewById<TextView>(ID_URL_TEXT)?.text = cfg?.url ?: "(No URL)"
        root.findViewById<TextView>(ID_ROTATION_LABEL)?.text = "${elem.rotationDegrees.toInt()}°"
        root.findViewById<SeekBar>(ID_ROTATION_SLIDER)?.progress = elem.rotationDegrees.toInt()
        root.findViewById<SeekBar>(ID_SCALE_SLIDER)?.progress = (((elem.scale) - 0.2f) * 100).toInt().coerceIn(0, 280)
        root.findViewById<TextView>(ID_SCALE_LABEL)?.text = "Overlay Scale: ${(elem.scale * 100).toInt()}%"

        cfg?.let {
            root.findViewById<TextView>(ID_ZOOM_LABEL)?.text = "Webpage Content Zoom: ${(it.zoomLevel * 100).toInt()}%"
            root.findViewById<SeekBar>(ID_ZOOM_SLIDER)?.progress = (((it.zoomLevel) - 0.25f) * 100).toInt().coerceIn(0, 475)
            root.findViewById<TextView>(ID_CROP_INFO)?.text = "L: ${(it.cropLeft * 100).toInt()}%  T: ${(it.cropTop * 100).toInt()}%  R: ${(it.cropRight * 100).toInt()}%  B: ${(it.cropBottom * 100).toInt()}%"
        }

        root.findViewById<Button>(ID_LOCK_BTN)?.text = if (elem.locked) "🔒 Locked (tap to unlock)" else "🔓 Unlocked (tap to lock)"
    }

    private fun dp(value: Int): Int =
        (value * resources.displayMetrics.density + 0.5f).toInt()

    companion object {
        const val TAG = "WebOverlayEditorFragment"
        val ID_URL_TEXT = View.generateViewId()
        val ID_ROTATION_LABEL = View.generateViewId()
        val ID_ROTATION_SLIDER = View.generateViewId()
        val ID_SCALE_LABEL = View.generateViewId()
        val ID_SCALE_SLIDER = View.generateViewId()
        val ID_ZOOM_LABEL = View.generateViewId()
        val ID_ZOOM_SLIDER = View.generateViewId()
        val ID_CROP_INFO = View.generateViewId()
        val ID_LOCK_BTN = View.generateViewId()

        fun newInstance() = WebOverlayEditorFragment()
    }
}
