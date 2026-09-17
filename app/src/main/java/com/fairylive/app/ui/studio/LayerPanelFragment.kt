package com.fairylive.app.ui.studio

import android.content.Context
import android.graphics.Color
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.*
import androidx.fragment.app.Fragment
import com.fairylive.app.core.compositor.model.OverlayElement
import com.fairylive.app.core.compositor.model.OverlayType

/**
 * Phase 6 §9: Layer Management Panel.
 *
 * Provides a dedicated creator UI to:
 * - View all layers in visual z-order (top to bottom)
 * - Toggle visibility (eye icon)
 * - Toggle lock state (padlock icon)
 * - Reorder layers (move up / move down)
 * - Duplicate layers
 * - Delete layers
 *
 * Strictly CREATOR_ONLY render domain — never leaks into broadcast.
 * Styled with Warm Ivory (#FBF9F5) design tokens.
 */
class LayerPanelFragment : Fragment() {

    interface LayerActionListener {
        fun onLayersReordered(newOrder: List<OverlayElement>)
        fun onLayerVisibilityToggled(id: String, visible: Boolean)
        fun onLayerLockToggled(id: String, locked: Boolean)
        fun onLayerDuplicated(id: String)
        fun onLayerDeleted(id: String)
        fun onLayerSelected(id: String)
    }

    private var actionListener: LayerActionListener? = null
    private var layerListContainer: LinearLayout? = null
    private var layers: MutableList<OverlayElement> = mutableListOf()
    private var selectedId: String? = null

    fun setActionListener(listener: LayerActionListener) {
        this.actionListener = listener
    }

    fun updateLayers(newLayers: List<OverlayElement>, currentSelectedId: String? = null) {
        this.layers = newLayers.toMutableList()
        this.selectedId = currentSelectedId
        renderLayerItems()
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        val root = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#FBF9F5")) // Warm Ivory
            setPadding(dp(16), dp(16), dp(16), dp(16))
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        }

        // Header: Title + Close Button
        val header = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(12) }
        }

        val title = TextView(requireContext()).apply {
            text = "Layers"
            textSize = 18f
            setTextColor(Color.parseColor("#231B15")) // Espresso
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }

        val layerCount = TextView(requireContext()).apply {
            text = "${layers.size} active"
            textSize = 12f
            setTextColor(Color.parseColor("#6E6259")) // Warm Coffee
            id = View.generateViewId()
        }

        header.addView(title)
        header.addView(layerCount)
        root.addView(header)

        // Scrollable list container
        val scrollView = ScrollView(requireContext()).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                0,
                1f
            )
        }

        layerListContainer = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        }

        scrollView.addView(layerListContainer)
        root.addView(scrollView)

        renderLayerItems()
        return root
    }

    private fun renderLayerItems() {
        val container = layerListContainer ?: return
        container.removeAllViews()

        if (layers.isEmpty()) {
            val emptyView = TextView(requireContext()).apply {
                text = "No layers added yet\nUse + to add Camera, Chat, or Web Overlay"
                textSize = 14f
                setTextColor(Color.parseColor("#6E6259"))
                gravity = android.view.Gravity.CENTER
                setPadding(0, dp(40), 0, dp(40))
            }
            container.addView(emptyView)
            return
        }

        // Display layers top-to-bottom (highest zIndex first)
        val sorted = layers.sortedByDescending { it.zIndex }

        sorted.forEachIndexed { index, element ->
            container.addView(createLayerRow(element, index, sorted.size))
        }
    }

    private fun createLayerRow(element: OverlayElement, index: Int, total: Int): View {
        val ctx = requireContext()
        val isSelected = element.id == selectedId

        val row = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
            setPadding(dp(12), dp(8), dp(12), dp(8))
            val bg = android.graphics.drawable.GradientDrawable().apply {
                setColor(if (isSelected) Color.parseColor("#F5EFEB") else Color.WHITE)
                setStroke(dp(1), if (isSelected) Color.parseColor("#C27D48") else Color.parseColor("#EBE5DF"))
                cornerRadius = dp(8).toFloat()
            }
            background = bg
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(6) }

            setOnClickListener {
                selectedId = element.id
                actionListener?.onLayerSelected(element.id)
                renderLayerItems()
            }
        }

        // Layer type icon / indicator
        val icon = TextView(ctx).apply {
            text = when (element.type) {
                OverlayType.WEB -> "🌐"
                OverlayType.YOUTUBE_SUBSCRIBER -> "▶"
                OverlayType.IMAGE, OverlayType.PNG -> "🖼"
                OverlayType.TEXT -> "T"
                OverlayType.GOAL -> "🎯"
                OverlayType.ALERT -> "🔔"
                OverlayType.QR -> "▦"
                OverlayType.LOWER_THIRD -> "▬"
                OverlayType.GIF -> "🎬"
                OverlayType.WIDGET -> "⚙"
            }
            textSize = 16f
            layoutParams = LinearLayout.LayoutParams(dp(28), LinearLayout.LayoutParams.WRAP_CONTENT)
        }
        row.addView(icon)

        // Layer Name / Type label
        val nameCol = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                marginStart = dp(8)
            }
        }

        val nameText = TextView(ctx).apply {
            text = element.id.ifEmpty { element.type.name.lowercase().replaceFirstChar { it.uppercase() } }
            textSize = 13f
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            setTextColor(Color.parseColor("#231B15"))
        }

        val subText = TextView(ctx).apply {
            text = "z: ${element.zIndex}  •  ${(element.scale * 100).toInt()}%  •  ${element.rotationDegrees.toInt()}°"
            textSize = 11f
            setTextColor(Color.parseColor("#6E6259"))
        }

        nameCol.addView(nameText)
        nameCol.addView(subText)
        row.addView(nameCol)

        // Visibility Toggle Button
        val visBtn = ImageButton(ctx).apply {
            setImageResource(
                if (element.visible) android.R.drawable.ic_menu_view
                else android.R.drawable.ic_menu_close_clear_cancel
            )
            setBackgroundColor(Color.TRANSPARENT)
            contentDescription = if (element.visible) "Hide layer" else "Show layer"
            layoutParams = LinearLayout.LayoutParams(dp(36), dp(36))
            setOnClickListener {
                actionListener?.onLayerVisibilityToggled(element.id, !element.visible)
            }
        }
        row.addView(visBtn)

        // Lock Toggle Button
        val lockBtn = Button(ctx).apply {
            text = if (element.locked) "🔒" else "🔓"
            textSize = 12f
            setBackgroundColor(Color.TRANSPARENT)
            contentDescription = if (element.locked) "Unlock layer" else "Lock layer"
            layoutParams = LinearLayout.LayoutParams(dp(36), dp(36))
            setOnClickListener {
                actionListener?.onLayerLockToggled(element.id, !element.locked)
            }
        }
        row.addView(lockBtn)

        // Move Up Button (brings forward in z-order)
        val upBtn = Button(ctx).apply {
            text = "▲"
            textSize = 10f
            isEnabled = index > 0
            setBackgroundColor(Color.TRANSPARENT)
            setTextColor(if (index > 0) Color.parseColor("#231B15") else Color.LTGRAY)
            contentDescription = "Move layer up"
            layoutParams = LinearLayout.LayoutParams(dp(30), dp(36))
            setOnClickListener {
                reorderLayer(element.id, moveUp = true)
            }
        }
        row.addView(upBtn)

        // Move Down Button (pushes back in z-order)
        val downBtn = Button(ctx).apply {
            text = "▼"
            textSize = 10f
            isEnabled = index < total - 1
            setBackgroundColor(Color.TRANSPARENT)
            setTextColor(if (index < total - 1) Color.parseColor("#231B15") else Color.LTGRAY)
            contentDescription = "Move layer down"
            layoutParams = LinearLayout.LayoutParams(dp(30), dp(36))
            setOnClickListener {
                reorderLayer(element.id, moveUp = false)
            }
        }
        row.addView(downBtn)

        return row
    }

    private fun reorderLayer(id: String, moveUp: Boolean) {
        val sorted = layers.sortedByDescending { it.zIndex }.toMutableList()
        val idx = sorted.indexOfFirst { it.id == id }
        if (idx < 0) return

        val targetIdx = if (moveUp) idx - 1 else idx + 1
        if (targetIdx !in sorted.indices) return

        // Swap zIndices
        val currentZ = sorted[idx].zIndex
        val targetZ = sorted[targetIdx].zIndex

        val updatedCurrent = sorted[idx].copy(zIndex = targetZ)
        val updatedTarget = sorted[targetIdx].copy(zIndex = currentZ)

        layers = layers.map {
            when (it.id) {
                updatedCurrent.id -> updatedCurrent
                updatedTarget.id -> updatedTarget
                else -> it
            }
        }.toMutableList()

        actionListener?.onLayersReordered(layers)
        renderLayerItems()
    }

    private fun dp(value: Int): Int =
        (value * resources.displayMetrics.density + 0.5f).toInt()

    companion object {
        const val TAG = "LayerPanelFragment"
        fun newInstance() = LayerPanelFragment()
    }
}
