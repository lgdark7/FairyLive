package com.fairylive.app.ui.studio

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Color
import android.media.projection.MediaProjectionConfig
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.*
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import com.fairylive.app.FairyLiveApplication
import com.fairylive.app.R
import com.fairylive.app.core.audio.AudioStateNotifier
import com.fairylive.app.core.audio.model.AudioMetrics
import com.fairylive.app.core.camera.CameraStateNotifier
import com.fairylive.app.core.compositor.model.*
import com.fairylive.app.core.lifecycle.StreamState
import com.fairylive.app.core.lifecycle.StreamStateNotifier
import com.fairylive.app.core.permission.PermissionManager
import com.fairylive.app.core.scene.BroadcastSceneManager
import com.fairylive.app.core.scene.SceneStateNotifier
import com.fairylive.app.core.scene.model.BroadcastScene
import com.fairylive.app.core.scene.model.SceneBackgroundMode
import com.fairylive.app.core.stream.StreamTelemetryNotifier
import com.fairylive.app.core.stream.model.StreamTelemetry
import com.fairylive.app.databinding.FragmentCreatorStudioBinding
import com.fairylive.app.service.FairyStreamService
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.button.MaterialButton
import com.google.android.material.switchmaterial.SwitchMaterial
import androidx.core.widget.doAfterTextChanged
import com.fairylive.app.core.camera.model.CameraFacing
import com.fairylive.app.core.camera.model.FacecamConfig
import com.fairylive.app.core.compositor.render.OffscreenWebRenderer
import kotlin.math.roundToInt

class CreatorStudioFragment : Fragment(),
    StreamStateNotifier.Listener,
    StreamTelemetryNotifier.TelemetryListener,
    com.fairylive.app.core.video.EncoderStateNotifier.MetricsListener,
    AudioStateNotifier.MetricsListener,
    BroadcastPreviewView.InteractionListener {

    private var _binding: FragmentCreatorStudioBinding? = null
    private val binding get() = _binding!!

    private var isMicMuted = false
    private var isHudVisible = false

    private val sceneListener: (BroadcastScene) -> Unit = { scene ->
        activity?.runOnUiThread {
            if (_binding != null) {
                updateSceneUI(scene)
            }
        }
    }

    private val projectionLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK && result.data != null) {
            startStreamingWithProjection(result.resultCode, result.data!!)
        } else {
            Toast.makeText(requireContext(), "Screen capture permission denied", Toast.LENGTH_SHORT).show()
        }
    }

    private var pendingImageTargetElementId: String? = null
    private var pendingGifTargetElementId: String? = null

    private val imagePickerLauncher = registerForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        if (uri != null) {
            val targetId = pendingImageTargetElementId
            if (targetId != null) {
                replaceImageFromUri(targetId, uri)
                pendingImageTargetElementId = null
            } else {
                importImageFromUri(uri)
            }
        } else {
            pendingImageTargetElementId = null
        }
    }

    private val gifPickerLauncher = registerForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        if (uri != null) {
            val targetId = pendingGifTargetElementId
            if (targetId != null) {
                replaceGifFromUri(targetId, uri)
                pendingGifTargetElementId = null
            } else {
                importGifFromUri(uri)
            }
        } else {
            pendingGifTargetElementId = null
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentCreatorStudioBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        binding.broadcastPreviewView.interactionListener = this

        setupClickListeners()

        StreamStateNotifier.addListener(this)
        StreamTelemetryNotifier.addTelemetryListener(this)
        com.fairylive.app.core.video.EncoderStateNotifier.addMetricsListener(this)
        AudioStateNotifier.addMetricsListener(this)
        SceneStateNotifier.addListener(sceneListener)

        val app = requireActivity().application as FairyLiveApplication
        val activeScene = app.sceneManager.activeScene
        updateSceneUI(activeScene)
        updateStreamStateUI(StreamStateNotifier.currentState)
        updateUndoRedoState()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        StreamStateNotifier.removeListener(this)
        StreamTelemetryNotifier.removeTelemetryListener(this)
        com.fairylive.app.core.video.EncoderStateNotifier.removeMetricsListener(this)
        AudioStateNotifier.removeMetricsListener(this)
        SceneStateNotifier.removeListener(sceneListener)
        _binding = null
    }

    private fun setupClickListeners() {
        binding.btnGoLive.setOnClickListener {
            val currentState = StreamStateNotifier.currentState
            if (currentState == StreamState.STREAMING || currentState == StreamState.STARTING) {
                FairyStreamService.stopCapture(requireContext())
            } else {
                requestStartBroadcast()
            }
        }

        binding.btnToggleMic.setOnClickListener {
            isMicMuted = !isMicMuted
            if (isMicMuted) {
                FairyStreamService.muteAudio(requireContext())
                binding.btnToggleMic.iconTint = ColorStateList.valueOf(ContextCompat.getColor(requireContext(), R.color.status_red))
                binding.btnToggleMic.strokeColor = ColorStateList.valueOf(ContextCompat.getColor(requireContext(), R.color.status_red))
            } else {
                FairyStreamService.unmuteAudio(requireContext())
                binding.btnToggleMic.iconTint = ColorStateList.valueOf(ContextCompat.getColor(requireContext(), R.color.text_primary))
                binding.btnToggleMic.strokeColor = ColorStateList.valueOf(ContextCompat.getColor(requireContext(), R.color.bg_card_stroke))
            }
        }

        binding.btnToggleCamera.setOnClickListener {
            val app = requireActivity().application as FairyLiveApplication
            val scene = app.sceneManager.activeScene
            val currentFacing = scene.facecamConfig.mirrorHorizontal
            val updated = scene.facecamConfig.copy(
                enabled = !scene.facecamConfig.enabled,
                mirrorHorizontal = currentFacing
            )
            val newScene = app.sceneManager.updateFacecamInActiveScene(updated)
            persistAndSyncScene(newScene)
            if (FairyStreamService.activeService != null) {
                FairyStreamService.toggleFacecam(requireContext())
            }
            updateCameraIcon(newScene.facecamConfig.enabled)
        }

        binding.btnHudToggle.setOnClickListener {
            isHudVisible = !isHudVisible
            binding.layoutHudOverlay.visibility = if (isHudVisible) View.VISIBLE else View.GONE
            binding.btnHudToggle.setTextColor(
                ContextCompat.getColor(requireContext(), if (isHudVisible) R.color.accent_primary else R.color.text_secondary)
            )
            binding.btnHudToggle.strokeColor = ColorStateList.valueOf(
                ContextCompat.getColor(requireContext(), if (isHudVisible) R.color.accent_primary else R.color.bg_card_stroke)
            )
        }

        binding.btnAddOverlay.setOnClickListener {
            showAddOverlayBottomSheet()
        }

        binding.btnLayersToggle.setOnClickListener {
            showLayersBottomSheet()
        }

        binding.btnUndo.setOnClickListener {
            val app = requireActivity().application as FairyLiveApplication
            val prev = app.sceneManager.undoActiveScene()
            if (prev != null) {
                updateSceneUI(prev)
                persistAndSyncScene(prev)
                updateUndoRedoState()
            }
        }

        binding.btnRedo.setOnClickListener {
            val app = requireActivity().application as FairyLiveApplication
            val next = app.sceneManager.redoActiveScene()
            if (next != null) {
                updateSceneUI(next)
                persistAndSyncScene(next)
                updateUndoRedoState()
            }
        }

        // Contextual selected element actions
        binding.btnElementCloseSelection.setOnClickListener {
            binding.broadcastPreviewView.setSelectedElement(null)
            onElementSelected(null)
        }

        binding.btnElementDelete.setOnClickListener {
            val selId = binding.broadcastPreviewView.getSelectedElementId() ?: return@setOnClickListener
            val app = requireActivity().application as FairyLiveApplication
            if (selId == "facecam") {
                val newScene = app.sceneManager.updateFacecamInActiveScene(app.sceneManager.activeScene.facecamConfig.copy(enabled = false))
                persistAndSyncScene(newScene)
            } else {
                val newScene = app.sceneManager.removeOverlayFromActiveScene(selId)
                persistAndSyncScene(newScene)
            }
            binding.broadcastPreviewView.setSelectedElement(null)
            onElementSelected(null)
            updateUndoRedoState()
        }

        binding.btnElementDuplicate.setOnClickListener {
            val selId = binding.broadcastPreviewView.getSelectedElementId() ?: return@setOnClickListener
            val app = requireActivity().application as FairyLiveApplication
            val elem = app.sceneManager.activeScene.overlayElements.firstOrNull { it.id == selId } ?: return@setOnClickListener
            val dup = elem.copy(
                id = "${elem.type.name.lowercase()}_${System.currentTimeMillis() % 10000}",
                normalizedX = (elem.normalizedX + 0.05f).coerceAtMost(0.85f),
                normalizedY = (elem.normalizedY + 0.05f).coerceAtMost(0.85f),
                zIndex = elem.zIndex + 1
            )
            val newScene = app.sceneManager.addOverlayToActiveScene(dup)
            persistAndSyncScene(newScene)
            binding.broadcastPreviewView.setSelectedElement(dup.id)
            onElementSelected(dup.id)
            updateUndoRedoState()
        }

        binding.btnElementLayerUp.setOnClickListener {
            val selId = binding.broadcastPreviewView.getSelectedElementId() ?: return@setOnClickListener
            val app = requireActivity().application as FairyLiveApplication
            val list = app.sceneManager.activeScene.overlayElements
            val idx = list.indexOfFirst { it.id == selId }
            if (idx >= 0 && idx < list.size - 1) {
                val newScene = app.sceneManager.reorderLayersInActiveScene(idx, idx + 1)
                persistAndSyncScene(newScene)
                updateUndoRedoState()
            }
        }

        binding.btnElementLayerDown.setOnClickListener {
            val selId = binding.broadcastPreviewView.getSelectedElementId() ?: return@setOnClickListener
            val app = requireActivity().application as FairyLiveApplication
            val list = app.sceneManager.activeScene.overlayElements
            val idx = list.indexOfFirst { it.id == selId }
            if (idx > 0) {
                val newScene = app.sceneManager.reorderLayersInActiveScene(idx, idx - 1)
                persistAndSyncScene(newScene)
                updateUndoRedoState()
            }
        }

        binding.btnElementEdit.setOnClickListener {
            val selId = binding.broadcastPreviewView.getSelectedElementId() ?: return@setOnClickListener
            if (selId == "facecam") {
                showFacecamInspector()
                return@setOnClickListener
            }
            val app = requireActivity().application as FairyLiveApplication
            val elem = app.sceneManager.activeScene.overlayElements.firstOrNull { it.id == selId } ?: return@setOnClickListener
            showInspectorBottomSheet(elem)
        }
    }

    private fun updateCameraIcon(enabled: Boolean) {
        if (_binding == null) return
        val color = if (enabled) R.color.accent_primary else R.color.text_primary
        val stroke = if (enabled) R.color.accent_primary else R.color.bg_card_stroke
        binding.btnToggleCamera.iconTint = ColorStateList.valueOf(ContextCompat.getColor(requireContext(), color))
        binding.btnToggleCamera.strokeColor = ColorStateList.valueOf(ContextCompat.getColor(requireContext(), stroke))
    }

    private fun updateSceneUI(scene: BroadcastScene) {
        if (_binding == null) return
        binding.broadcastPreviewView.setScene(scene)
        populateSceneChips()
        updateCameraIcon(scene.facecamConfig.enabled)
    }

    private fun populateSceneChips() {
        val app = requireActivity().application as FairyLiveApplication
        val container = binding.layoutSceneChips
        container.removeAllViews()

        val activeScene = app.sceneManager.activeScene
        for (scene in app.sceneManager.allScenes) {
            val isActive = scene.id == activeScene.id
            val chip = TextView(requireContext()).apply {
                text = scene.name
                textSize = 12f
                setPadding(dp(14), dp(7), dp(14), dp(7))
                setBackgroundResource(if (isActive) R.drawable.bg_scene_chip_active else R.drawable.bg_scene_chip)
                setTextColor(ContextCompat.getColor(context, if (isActive) R.color.text_primary else R.color.text_secondary))
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply {
                    marginEnd = dp(8)
                }
                setOnClickListener {
                    app.sceneManager.switchScene(scene.id)
                    app.sceneRepository.saveActiveSceneId(scene.id)
                    FairyStreamService.activeService?.overlayCompositor?.applyScene(scene)
                }
                setOnLongClickListener {
                    showSceneOptionsBottomSheet(scene)
                    true
                }
            }
            container.addView(chip)
        }

        // Add "+ " scene chip
        val addChip = TextView(requireContext()).apply {
            text = "+ "
            textSize = 14f
            setPadding(dp(14), dp(6), dp(14), dp(6))
            setBackgroundResource(R.drawable.bg_scene_chip)
            setTextColor(ContextCompat.getColor(context, R.color.accent_primary))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
            setOnClickListener {
                showCreateSceneDialog()
            }
        }
        container.addView(addChip)
    }

    private fun showAddOverlayBottomSheet() {
        val dialog = BottomSheetDialog(requireContext(), R.style.Theme_FairyLive_BottomSheetDialog)
        val sheetView = layoutInflater.inflate(R.layout.sheet_add_overlay, null)
        dialog.setContentView(sheetView)

        dialog.setOnShowListener {
            val bottomSheet = dialog.findViewById<View>(com.google.android.material.R.id.design_bottom_sheet) as? FrameLayout
            if (bottomSheet != null) {
                val behavior = BottomSheetBehavior.from(bottomSheet)
                val displayMetrics = resources.displayMetrics
                behavior.peekHeight = (displayMetrics.heightPixels * 0.75f).toInt()
                behavior.state = BottomSheetBehavior.STATE_EXPANDED
            }
        }

        sheetView.findViewById<View>(R.id.item_add_text).setOnClickListener {
            dialog.dismiss()
            showAddTextDialog()
        }
        sheetView.findViewById<View>(R.id.item_add_image).setOnClickListener {
            dialog.dismiss()
            addDefaultImage()
        }
        sheetView.findViewById<View>(R.id.item_add_facecam)?.setOnClickListener {
            dialog.dismiss()
            toggleAddFacecam()
        }
        sheetView.findViewById<View>(R.id.item_add_qr).setOnClickListener {
            dialog.dismiss()
            showAddQrDialog()
        }
        sheetView.findViewById<View>(R.id.item_add_goal).setOnClickListener {
            dialog.dismiss()
            addDefaultGoal()
        }
        sheetView.findViewById<View>(R.id.item_add_lower_third).setOnClickListener {
            dialog.dismiss()
            addDefaultLowerThird()
        }
        sheetView.findViewById<View>(R.id.item_add_gif).setOnClickListener {
            dialog.dismiss()
            addDefaultGif()
        }
        sheetView.findViewById<View>(R.id.item_add_youtube_sub)?.setOnClickListener {
            dialog.dismiss()
            addDefaultYouTubeSub()
        }
        sheetView.findViewById<View>(R.id.item_add_widget).setOnClickListener {
            dialog.dismiss()
            addDefaultWebOverlay()
        }
        sheetView.findViewById<View>(R.id.item_add_alert).setOnClickListener {
            dialog.dismiss()
            addDefaultAlert()
        }

        dialog.show()
    }

    private fun showLayersBottomSheet() {
        val dialog = BottomSheetDialog(requireContext(), R.style.Theme_FairyLive_BottomSheetDialog)
        val sheetView = layoutInflater.inflate(R.layout.sheet_layers, null)
        dialog.setContentView(sheetView)

        val listContainer = sheetView.findViewById<LinearLayout>(R.id.layout_sheet_layers_list)
        val tvCount = sheetView.findViewById<TextView>(R.id.tv_layers_count)

        val app = requireActivity().application as FairyLiveApplication
        val scene = app.sceneManager.activeScene
        val elements = scene.overlayElements.reversed()
        val totalLayers = elements.size + (if (scene.facecamConfig.enabled) 1 else 0)
        tvCount.text = "$totalLayers layer(s)"

        // Facecam layer
        if (scene.facecamConfig.enabled) {
            val row = createLayerSheetRow(
                name = "Camera (Facecam)",
                iconStr = "📷",
                isVisible = scene.facecamConfig.enabled,
                isLocked = false,
                onLockToggle = null,
                onVisibilityToggle = { _, _ ->
                    val newFc = scene.facecamConfig.copy(enabled = !scene.facecamConfig.enabled)
                    val newScene = app.sceneManager.updateFacecamInActiveScene(newFc)
                    persistAndSyncScene(newScene)
                    updateCameraIcon(newFc.enabled)
                    dialog.dismiss()
                },
                onClick = {
                    dialog.dismiss()
                    binding.broadcastPreviewView.setSelectedElement("facecam")
                    onElementSelected("facecam")
                }
            )
            listContainer.addView(row)
        }

        // Overlays
        for (elem in elements) {
            val icon = when (elem.type) {
                OverlayType.TEXT -> "T"
                OverlayType.IMAGE -> "🖼️"
                OverlayType.GOAL -> "★"
                OverlayType.LOWER_THIRD -> "▌"
                OverlayType.QR -> "▦"
                OverlayType.GIF -> "GIF"
                OverlayType.WIDGET -> "⚙"
                OverlayType.ALERT -> "🔔"
                else -> "🖼️"
            }
            val label = when (elem.type) {
                OverlayType.TEXT -> elem.textConfig?.text ?: "Text"
                OverlayType.GOAL -> elem.goalConfig?.title ?: "Goal"
                OverlayType.LOWER_THIRD -> elem.lowerThirdConfig?.title ?: "Lower Third"
                else -> elem.type.name.lowercase().replaceFirstChar { it.uppercase() }
            }

            val row = createLayerSheetRow(
                name = label,
                iconStr = icon,
                isVisible = elem.visible,
                isLocked = elem.locked,
                onLockToggle = { lockView ->
                    val currentLatest = app.sceneManager.activeScene.overlayElements.firstOrNull { it.id == elem.id } ?: elem
                    val updated = currentLatest.copy(locked = !currentLatest.locked)
                    val newScene = app.sceneManager.updateOverlayInActiveScene(updated)
                    persistAndSyncScene(newScene)
                    binding.broadcastPreviewView.invalidate()
                    lockView.text = if (updated.locked) "🔒" else "🔓"
                    lockView.setTextColor(
                        ContextCompat.getColor(requireContext(), if (updated.locked) R.color.status_amber else R.color.text_muted)
                    )
                },
                onVisibilityToggle = { eyeView, titleView ->
                    val currentLatest = app.sceneManager.activeScene.overlayElements.firstOrNull { it.id == elem.id } ?: elem
                    val updated = currentLatest.copy(visible = !currentLatest.visible)
                    val newScene = app.sceneManager.updateOverlayInActiveScene(updated)
                    persistAndSyncScene(newScene)
                    eyeView.text = if (updated.visible) "👁" else "⊘"
                    eyeView.setTextColor(
                        ContextCompat.getColor(requireContext(), if (updated.visible) R.color.text_primary else R.color.text_muted)
                    )
                    titleView.setTextColor(
                        ContextCompat.getColor(requireContext(), if (updated.visible) R.color.text_primary else R.color.text_muted)
                    )
                },
                onClick = {
                    dialog.dismiss()
                    binding.broadcastPreviewView.setSelectedElement(elem.id)
                    onElementSelected(elem.id)
                }
            )
            listContainer.addView(row)
        }

        dialog.show()
    }

    private fun createLayerSheetRow(
        name: String,
        iconStr: String,
        isVisible: Boolean,
        isLocked: Boolean,
        onLockToggle: ((TextView) -> Unit)?,
        onVisibilityToggle: ((TextView, TextView) -> Unit)?,
        onClick: () -> Unit
    ): View {
        val row = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
            setPadding(dp(4), dp(8), dp(4), dp(8))
            isClickable = true
            isFocusable = true
            setBackgroundResource(android.R.drawable.list_selector_background)
            setOnClickListener { onClick() }
        }

        val handle = TextView(requireContext()).apply {
            text = "☰"
            setTextColor(ContextCompat.getColor(context, R.color.text_muted))
            textSize = 14f
            gravity = android.view.Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(dp(28), LinearLayout.LayoutParams.WRAP_CONTENT)
        }
        row.addView(handle)

        val icon = TextView(requireContext()).apply {
            text = iconStr
            textSize = 14f
            gravity = android.view.Gravity.CENTER
            setTextColor(ContextCompat.getColor(context, R.color.accent_primary))
            layoutParams = LinearLayout.LayoutParams(dp(24), LinearLayout.LayoutParams.WRAP_CONTENT)
        }
        row.addView(icon)

        val title = TextView(requireContext()).apply {
            text = name
            setTextColor(ContextCompat.getColor(context, if (isVisible) R.color.text_primary else R.color.text_muted))
            textSize = 14f
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                marginStart = dp(10)
                marginEnd = dp(6)
            }
        }
        row.addView(title)

        // Lock Toggle Button (🔓 / 🔒)
        val lockBtn = TextView(requireContext()).apply {
            text = if (isLocked) "🔒" else "🔓"
            textSize = 15f
            gravity = android.view.Gravity.CENTER
            setTextColor(ContextCompat.getColor(context, if (isLocked) R.color.status_amber else R.color.text_muted))
            layoutParams = LinearLayout.LayoutParams(dp(36), dp(36))
            isClickable = true
            isFocusable = true
            setBackgroundResource(R.drawable.bg_control_icon_button)
            if (onLockToggle != null) {
                setOnClickListener {
                    onLockToggle.invoke(this)
                }
            } else {
                visibility = View.INVISIBLE
            }
        }
        row.addView(lockBtn)

        // Eye / Visibility Toggle Button (👁 / ⊘)
        val eyeBtn = TextView(requireContext()).apply {
            text = if (isVisible) "👁" else "⊘"
            textSize = 15f
            gravity = android.view.Gravity.CENTER
            setTextColor(ContextCompat.getColor(context, if (isVisible) R.color.text_primary else R.color.text_muted))
            layoutParams = LinearLayout.LayoutParams(dp(36), dp(36)).apply {
                marginStart = dp(6)
            }
            isClickable = true
            isFocusable = true
            setBackgroundResource(R.drawable.bg_control_icon_button)
            setOnClickListener {
                onVisibilityToggle?.invoke(this, title)
            }
        }
        row.addView(eyeBtn)

        return row
    }

    private fun showInspectorBottomSheet(elem: OverlayElement) {
        val dialog = BottomSheetDialog(requireContext(), R.style.Theme_FairyLive_BottomSheetDialog)
        val sheetView = layoutInflater.inflate(R.layout.sheet_inspector, null)
        dialog.setContentView(sheetView)

        dialog.setOnShowListener {
            val bottomSheet = dialog.findViewById<View>(com.google.android.material.R.id.design_bottom_sheet) as? FrameLayout
            if (bottomSheet != null) {
                val behavior = BottomSheetBehavior.from(bottomSheet)
                val displayMetrics = resources.displayMetrics
                behavior.peekHeight = (displayMetrics.heightPixels * 0.45f).toInt()
                behavior.state = BottomSheetBehavior.STATE_COLLAPSED
                behavior.isFitToContents = false
                behavior.halfExpandedRatio = 0.50f
            }
        }

        val app = requireActivity().application as FairyLiveApplication
        var currentElem = elem

        fun applyLiveUpdate(updated: OverlayElement, commitHistory: Boolean = false) {
            currentElem = updated
            val newScene = app.sceneManager.updateOverlayInActiveScene(updated, recordHistory = commitHistory)
            persistAndSyncScene(newScene)
            binding.broadcastPreviewView.invalidate()
        }

        // Header
        val tvTitle = sheetView.findViewById<TextView>(R.id.tv_inspector_title)
        val tvType = sheetView.findViewById<TextView>(R.id.tv_inspector_type)
        tvTitle.text = "${elem.type.name.lowercase().replaceFirstChar { it.uppercase() }} Inspector"
        tvType.text = elem.type.name

        // Opacity
        val tvOpacityVal = sheetView.findViewById<TextView>(R.id.tv_opacity_val)
        val sbOpacity = sheetView.findViewById<SeekBar>(R.id.sb_inspector_opacity)
        sbOpacity.progress = (currentElem.opacity * 100).toInt()
        tvOpacityVal.text = "${(currentElem.opacity * 100).toInt()}%"
        sbOpacity.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar?, progress: Int, fromUser: Boolean) {
                if (fromUser) {
                    tvOpacityVal.text = "$progress%"
                    applyLiveUpdate(currentElem.copy(opacity = progress / 100f))
                }
            }
            override fun onStartTrackingTouch(sb: SeekBar?) {}
            override fun onStopTrackingTouch(sb: SeekBar?) {
                applyLiveUpdate(currentElem, commitHistory = true)
            }
        })

        // Position, Scale & Rotation
        val sectionTransform = sheetView.findViewById<View>(R.id.section_transform_controls)
        val etPosX = sheetView.findViewById<EditText>(R.id.et_pos_x)
        val etPosY = sheetView.findViewById<EditText>(R.id.et_pos_y)
        val etScale = sheetView.findViewById<EditText>(R.id.et_scale)
        val etRot = sheetView.findViewById<EditText>(R.id.et_rotation)

        // Show transform for all visual overlay types (P3 requirement)
        sectionTransform.visibility = View.VISIBLE
        etPosX.setText("${(currentElem.normalizedX * 100).roundToInt()}")
        etPosY.setText("${(currentElem.normalizedY * 100).roundToInt()}")
        etScale.setText(String.format(java.util.Locale.US, "%.1f", currentElem.scale))
        etRot.setText("${currentElem.rotationDegrees.roundToInt()}")

        etPosX.doAfterTextChanged { text ->
            val v = text?.toString()?.toIntOrNull()
            if (v != null) {
                val normX = (v / 100f).coerceIn(0f, 1f - currentElem.normalizedWidth)
                applyLiveUpdate(currentElem.copy(normalizedX = normX))
            }
        }
        etPosY.doAfterTextChanged { text ->
            val v = text?.toString()?.toIntOrNull()
            if (v != null) {
                val normY = (v / 100f).coerceIn(0f, 0.95f)
                applyLiveUpdate(currentElem.copy(normalizedY = normY))
            }
        }
        etScale.doAfterTextChanged { text ->
            val v = text?.toString()?.toFloatOrNull()
            if (v != null && v > 0f) {
                applyLiveUpdate(currentElem.copy(scale = v.coerceIn(0.1f, 5.0f)))
            }
        }
        etRot.doAfterTextChanged { text ->
            val v = text?.toString()?.toFloatOrNull()
            if (v != null) {
                // Normalize to [-180..180] or [0..360]
                val normalizedRot = ((v % 360f) + 360f) % 360f
                applyLiveUpdate(currentElem.copy(rotationDegrees = normalizedRot))
            }
        }

        // Type-specific setups
        when (elem.type) {
            OverlayType.TEXT -> {
                val sec = sheetView.findViewById<View>(R.id.section_text_controls)
                sec.visibility = View.VISIBLE
                val etContent = sheetView.findViewById<EditText>(R.id.et_text_content)
                val sbFontSize = sheetView.findViewById<SeekBar>(R.id.sb_text_font_size)
                val tvFontSizeVal = sheetView.findViewById<TextView>(R.id.tv_text_font_size_val)
                val switchBg = sheetView.findViewById<SwitchMaterial>(R.id.switch_text_background)

                val cfg = elem.textConfig ?: TextOverlayConfig(text = "Text")
                etContent.setText(cfg.text)
                val initSizeSp = cfg.fontSizeSp.toInt().coerceIn(12, 72)
                sbFontSize.progress = initSizeSp - 12
                tvFontSizeVal.text = "$initSizeSp sp"

                etContent.doAfterTextChanged { text ->
                    val newText = text?.toString() ?: ""
                    val curCfg = currentElem.textConfig ?: TextOverlayConfig(text = newText)
                    applyLiveUpdate(currentElem.copy(textConfig = curCfg.copy(text = newText)))
                }

                sbFontSize.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                    override fun onProgressChanged(sb: SeekBar?, progress: Int, fromUser: Boolean) {
                        if (fromUser) {
                            val sp = 12 + progress
                            tvFontSizeVal.text = "$sp sp"
                            val curCfg = currentElem.textConfig ?: TextOverlayConfig(text = "")
                            applyLiveUpdate(currentElem.copy(textConfig = curCfg.copy(fontSizeSp = sp.toFloat())))
                        }
                    }
                    override fun onStartTrackingTouch(sb: SeekBar?) {}
                    override fun onStopTrackingTouch(sb: SeekBar?) {
                        applyLiveUpdate(currentElem, commitHistory = true)
                    }
                })

                // Preset Colors
                val colorChips = listOf(
                    Pair(R.id.color_chip_white, Color.WHITE),
                    Pair(R.id.color_chip_cyan, Color.parseColor("#00E5FF")),
                    Pair(R.id.color_chip_amber, Color.parseColor("#F59E0B")),
                    Pair(R.id.color_chip_red, Color.parseColor("#EF4444")),
                    Pair(R.id.color_chip_green, Color.parseColor("#10B981")),
                    Pair(R.id.color_chip_purple, Color.parseColor("#A855F7")),
                    Pair(R.id.color_chip_yellow, Color.parseColor("#FACC15")),
                    Pair(R.id.color_chip_pink, Color.parseColor("#EC4899"))
                )
                for ((viewId, colorInt) in colorChips) {
                    sheetView.findViewById<View>(viewId)?.setOnClickListener {
                        val curCfg = currentElem.textConfig ?: TextOverlayConfig(text = "")
                        applyLiveUpdate(currentElem.copy(textConfig = curCfg.copy(textColorRgba = colorInt)), commitHistory = true)
                    }
                }

                // Alignment buttons
                val btnLeft = sheetView.findViewById<MaterialButton>(R.id.btn_align_left)
                val btnCenter = sheetView.findViewById<MaterialButton>(R.id.btn_align_center)
                val btnRight = sheetView.findViewById<MaterialButton>(R.id.btn_align_right)

                fun updateAlignButtons(align: String) {
                    val accentCol = ContextCompat.getColor(requireContext(), R.color.accent_primary)
                    val strokeCol = ContextCompat.getColor(requireContext(), R.color.bg_card_stroke)
                    val textPrim = ContextCompat.getColor(requireContext(), R.color.text_primary)
                    val textSec = ContextCompat.getColor(requireContext(), R.color.text_secondary)

                    btnLeft.strokeColor = ColorStateList.valueOf(if (align == "LEFT") accentCol else strokeCol)
                    btnLeft.setTextColor(if (align == "LEFT") textPrim else textSec)

                    btnCenter.strokeColor = ColorStateList.valueOf(if (align == "CENTER") accentCol else strokeCol)
                    btnCenter.setTextColor(if (align == "CENTER") textPrim else textSec)

                    btnRight.strokeColor = ColorStateList.valueOf(if (align == "RIGHT") accentCol else strokeCol)
                    btnRight.setTextColor(if (align == "RIGHT") textPrim else textSec)
                }
                updateAlignButtons(cfg.alignment)

                btnLeft.setOnClickListener {
                    updateAlignButtons("LEFT")
                    val curCfg = currentElem.textConfig ?: TextOverlayConfig(text = "")
                    applyLiveUpdate(currentElem.copy(textConfig = curCfg.copy(alignment = "LEFT")), commitHistory = true)
                }
                btnCenter.setOnClickListener {
                    updateAlignButtons("CENTER")
                    val curCfg = currentElem.textConfig ?: TextOverlayConfig(text = "")
                    applyLiveUpdate(currentElem.copy(textConfig = curCfg.copy(alignment = "CENTER")), commitHistory = true)
                }
                btnRight.setOnClickListener {
                    updateAlignButtons("RIGHT")
                    val curCfg = currentElem.textConfig ?: TextOverlayConfig(text = "")
                    applyLiveUpdate(currentElem.copy(textConfig = curCfg.copy(alignment = "RIGHT")), commitHistory = true)
                }

                // Background toggle
                switchBg.isChecked = cfg.backgroundColorRgba != Color.TRANSPARENT
                switchBg.setOnCheckedChangeListener { _, isChecked ->
                    val curCfg = currentElem.textConfig ?: TextOverlayConfig(text = "")
                    val newBg = if (isChecked) Color.argb(180, 16, 20, 32) else Color.TRANSPARENT
                    applyLiveUpdate(currentElem.copy(textConfig = curCfg.copy(backgroundColorRgba = newBg)), commitHistory = true)
                }
            }

            OverlayType.IMAGE -> {
                val sec = sheetView.findViewById<View>(R.id.section_image_controls)
                sec.visibility = View.VISIBLE
                val btnReplace = sheetView.findViewById<MaterialButton>(R.id.btn_image_replace)
                val sbRadius = sheetView.findViewById<SeekBar>(R.id.sb_image_corner_radius)
                val tvRadiusVal = sheetView.findViewById<TextView>(R.id.tv_image_corner_radius_val)

                btnReplace.setOnClickListener {
                    pendingImageTargetElementId = currentElem.id
                    imagePickerLauncher.launch("image/*")
                    dialog.dismiss()
                }

                sbRadius.progress = currentElem.cornerRadiusPx.toInt().coerceIn(0, 50)
                tvRadiusVal.text = "${currentElem.cornerRadiusPx.toInt()} px"
                sbRadius.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                    override fun onProgressChanged(sb: SeekBar?, progress: Int, fromUser: Boolean) {
                        if (fromUser) {
                            tvRadiusVal.text = "$progress px"
                            applyLiveUpdate(currentElem.copy(cornerRadiusPx = progress.toFloat()))
                        }
                    }
                    override fun onStartTrackingTouch(sb: SeekBar?) {}
                    override fun onStopTrackingTouch(sb: SeekBar?) {
                        applyLiveUpdate(currentElem, commitHistory = true)
                    }
                })
            }

            OverlayType.GIF -> {
                val sec = sheetView.findViewById<View>(R.id.section_gif_controls)
                sec.visibility = View.VISIBLE
                val btnReplace = sheetView.findViewById<MaterialButton>(R.id.btn_gif_replace)
                val sbSpeed = sheetView.findViewById<SeekBar>(R.id.sb_gif_speed)
                val tvSpeedVal = sheetView.findViewById<TextView>(R.id.tv_gif_speed_val)
                val switchLoop = sheetView.findViewById<SwitchMaterial>(R.id.switch_gif_loop)

                btnReplace.setOnClickListener {
                    pendingGifTargetElementId = currentElem.id
                    gifPickerLauncher.launch("image/gif")
                    dialog.dismiss()
                }

                val curSpeed = currentElem.gifConfig?.playbackSpeed ?: 1.0f
                sbSpeed.progress = ((curSpeed - 0.5f) / 0.1f).roundToInt().coerceIn(0, 15)
                tvSpeedVal.text = String.format(java.util.Locale.US, "%.1fx", curSpeed)

                sbSpeed.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                    override fun onProgressChanged(sb: SeekBar?, progress: Int, fromUser: Boolean) {
                        if (fromUser) {
                            val spd = 0.5f + progress * 0.1f
                            tvSpeedVal.text = String.format(java.util.Locale.US, "%.1fx", spd)
                            val curCfg = currentElem.gifConfig ?: GifOverlayConfig(resourcePath = currentElem.resourcePath ?: "")
                            applyLiveUpdate(currentElem.copy(gifConfig = curCfg.copy(playbackSpeed = spd)))
                        }
                    }
                    override fun onStartTrackingTouch(sb: SeekBar?) {}
                    override fun onStopTrackingTouch(sb: SeekBar?) {
                        applyLiveUpdate(currentElem, commitHistory = true)
                    }
                })

                switchLoop.isChecked = currentElem.gifConfig?.isLooping ?: true
                switchLoop.setOnCheckedChangeListener { _, isChecked ->
                    val curCfg = currentElem.gifConfig ?: GifOverlayConfig(resourcePath = currentElem.resourcePath ?: "")
                    applyLiveUpdate(currentElem.copy(gifConfig = curCfg.copy(isLooping = isChecked)), commitHistory = true)
                }
            }

            OverlayType.GOAL -> {
                val sec = sheetView.findViewById<View>(R.id.section_goal_controls)
                sec.visibility = View.VISIBLE
                val etTitle = sheetView.findViewById<EditText>(R.id.et_goal_title)
                val etCurrent = sheetView.findViewById<EditText>(R.id.et_goal_current)
                val etTarget = sheetView.findViewById<EditText>(R.id.et_goal_target)

                val cfg = elem.goalConfig ?: GoalOverlayConfig(title = "Goal", currentVal = 0, targetVal = 100)
                etTitle.setText(cfg.title)
                etCurrent.setText("${cfg.currentVal}")
                etTarget.setText("${cfg.targetVal}")

                etTitle.doAfterTextChanged { text ->
                    val curCfg = currentElem.goalConfig ?: cfg
                    applyLiveUpdate(currentElem.copy(goalConfig = curCfg.copy(title = text?.toString() ?: "")))
                }
                etCurrent.doAfterTextChanged { text ->
                    val cur = text?.toString()?.toIntOrNull() ?: 0
                    val curCfg = currentElem.goalConfig ?: cfg
                    applyLiveUpdate(currentElem.copy(goalConfig = curCfg.copy(currentVal = cur)))
                }
                etTarget.doAfterTextChanged { text ->
                    val tgt = text?.toString()?.toIntOrNull() ?: 100
                    val curCfg = currentElem.goalConfig ?: cfg
                    applyLiveUpdate(currentElem.copy(goalConfig = curCfg.copy(targetVal = tgt)))
                }
            }

            OverlayType.QR -> {
                val sec = sheetView.findViewById<View>(R.id.section_qr_controls)
                sec.visibility = View.VISIBLE
                val etUrl = sheetView.findViewById<EditText>(R.id.et_qr_url)
                val cfg = elem.qrConfig ?: QrOverlayConfig(payloadUrl = "https://fairylive.stream")
                etUrl.setText(cfg.payloadUrl)
                etUrl.doAfterTextChanged { text ->
                    val curCfg = currentElem.qrConfig ?: cfg
                    applyLiveUpdate(currentElem.copy(qrConfig = curCfg.copy(payloadUrl = text?.toString() ?: "")))
                }
            }

            OverlayType.LOWER_THIRD -> {
                val sec = sheetView.findViewById<View>(R.id.section_lower_third_controls)
                sec.visibility = View.VISIBLE
                val etTitle = sheetView.findViewById<EditText>(R.id.et_lower_third_title)
                val etSubtitle = sheetView.findViewById<EditText>(R.id.et_lower_third_subtitle)
                val cfg = elem.lowerThirdConfig ?: LowerThirdOverlayConfig(title = "Headline", subtitle = "Subtitle")
                etTitle.setText(cfg.title)
                etSubtitle.setText(cfg.subtitle)

                etTitle.doAfterTextChanged { text ->
                    val curCfg = currentElem.lowerThirdConfig ?: cfg
                    applyLiveUpdate(currentElem.copy(lowerThirdConfig = curCfg.copy(title = text?.toString() ?: "")))
                }
                etSubtitle.doAfterTextChanged { text ->
                    val curCfg = currentElem.lowerThirdConfig ?: cfg
                    applyLiveUpdate(currentElem.copy(lowerThirdConfig = curCfg.copy(subtitle = text?.toString() ?: "")))
                }
            }

            OverlayType.ALERT -> {
                val sec = sheetView.findViewById<View>(R.id.section_alert_controls)
                sec.visibility = View.VISIBLE
                val etHeadline = sheetView.findViewById<EditText>(R.id.et_alert_headline)
                val etSubtext = sheetView.findViewById<EditText>(R.id.et_alert_subtext)
                val sbDur = sheetView.findViewById<SeekBar>(R.id.sb_alert_duration)
                val tvDurVal = sheetView.findViewById<TextView>(R.id.tv_alert_duration_val)

                val cfg = elem.alertConfig ?: AlertOverlayConfig(headline = "Alert", subtext = "")
                etHeadline.setText(cfg.headline)
                etSubtext.setText(cfg.subtext)
                val initDurSec = (cfg.durationMs / 1000L).toInt().coerceIn(1, 10)
                sbDur.progress = initDurSec - 1
                tvDurVal.text = "${initDurSec}s"

                etHeadline.doAfterTextChanged { text ->
                    val curCfg = currentElem.alertConfig ?: cfg
                    applyLiveUpdate(currentElem.copy(alertConfig = curCfg.copy(headline = text?.toString() ?: "")))
                }
                etSubtext.doAfterTextChanged { text ->
                    val curCfg = currentElem.alertConfig ?: cfg
                    applyLiveUpdate(currentElem.copy(alertConfig = curCfg.copy(subtext = text?.toString() ?: "")))
                }
                sbDur.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                    override fun onProgressChanged(sb: SeekBar?, progress: Int, fromUser: Boolean) {
                        if (fromUser) {
                            val durSec = progress + 1
                            tvDurVal.text = "${durSec}s"
                            val curCfg = currentElem.alertConfig ?: cfg
                            applyLiveUpdate(currentElem.copy(alertConfig = curCfg.copy(durationMs = durSec * 1000L)))
                        }
                    }
                    override fun onStartTrackingTouch(sb: SeekBar?) {}
                    override fun onStopTrackingTouch(sb: SeekBar?) {
                        applyLiveUpdate(currentElem, commitHistory = true)
                    }
                })
            }

            OverlayType.WIDGET -> {
                val sec = sheetView.findViewById<View>(R.id.section_widget_controls)
                sec.visibility = View.VISIBLE
                val etLabel = sheetView.findViewById<EditText>(R.id.et_widget_label)
                val etValue = sheetView.findViewById<EditText>(R.id.et_widget_value)

                val cfg = elem.widgetConfig ?: WidgetOverlayConfig(widgetType = "STATS", label = "FPS", value = "60")
                etLabel.setText(cfg.label)
                etValue.setText(cfg.value)

                etLabel.doAfterTextChanged { text ->
                    val curCfg = currentElem.widgetConfig ?: cfg
                    applyLiveUpdate(currentElem.copy(widgetConfig = curCfg.copy(label = text?.toString() ?: "")))
                }
                etValue.doAfterTextChanged { text ->
                    val curCfg = currentElem.widgetConfig ?: cfg
                    applyLiveUpdate(currentElem.copy(widgetConfig = curCfg.copy(value = text?.toString() ?: "")))
                }
            }

            OverlayType.WEB -> {
                val sec = sheetView.findViewById<View>(R.id.section_web_controls)
                sec.visibility = View.VISIBLE
                val etUrl = sheetView.findViewById<EditText>(R.id.et_web_url)
                val btnLoad = sheetView.findViewById<MaterialButton>(R.id.btn_web_load)
                val switchTransparent = sheetView.findViewById<SwitchMaterial>(R.id.switch_web_transparent)
                val etScrollX = sheetView.findViewById<EditText>(R.id.et_web_scroll_x)
                val etScrollY = sheetView.findViewById<EditText>(R.id.et_web_scroll_y)
                val btnCrop10 = sheetView.findViewById<MaterialButton>(R.id.btn_web_crop_10)
                val btnCropReset = sheetView.findViewById<MaterialButton>(R.id.btn_web_crop_reset)

                val cfg = elem.webConfig ?: WebOverlayConfig()
                etUrl.setText(cfg.url)
                switchTransparent.isChecked = cfg.transparentBackground
                etScrollX.setText(cfg.scrollX.toString())
                etScrollY.setText(cfg.scrollY.toString())

                fun updateWebConfig(
                    newUrl: String = etUrl.text?.toString() ?: "",
                    transparent: Boolean = switchTransparent.isChecked,
                    scrollX: Int = etScrollX.text?.toString()?.toIntOrNull() ?: 0,
                    scrollY: Int = etScrollY.text?.toString()?.toIntOrNull() ?: 0,
                    cropLeft: Float = (currentElem.webConfig ?: cfg).cropLeft,
                    cropTop: Float = (currentElem.webConfig ?: cfg).cropTop,
                    cropRight: Float = (currentElem.webConfig ?: cfg).cropRight,
                    cropBottom: Float = (currentElem.webConfig ?: cfg).cropBottom,
                    forceReload: Boolean = false
                ) {
                    val updatedCfg = (currentElem.webConfig ?: cfg).copy(
                        url = newUrl,
                        transparentBackground = transparent,
                        scrollX = scrollX,
                        scrollY = scrollY,
                        cropLeft = cropLeft,
                        cropTop = cropTop,
                        cropRight = cropRight,
                        cropBottom = cropBottom
                    )
                    val updatedElem = currentElem.copy(webConfig = updatedCfg)
                    applyLiveUpdate(updatedElem, commitHistory = forceReload)
                    if (forceReload) {
                        OffscreenWebRenderer.getInstance(requireContext()).reload(currentElem.id, updatedCfg)
                    } else {
                        OffscreenWebRenderer.getInstance(requireContext()).scrollWebPage(currentElem.id, updatedCfg, scrollX, scrollY)
                    }
                    binding.broadcastPreviewView.invalidate()
                }

                etUrl.doAfterTextChanged { text ->
                    updateWebConfig(newUrl = text?.toString() ?: "", forceReload = false)
                }

                btnLoad.setOnClickListener {
                    updateWebConfig(forceReload = true)
                }

                switchTransparent.setOnCheckedChangeListener { _, isChecked ->
                    updateWebConfig(transparent = isChecked, forceReload = true)
                }

                etScrollX.doAfterTextChanged { text ->
                    val sx = text?.toString()?.toIntOrNull() ?: 0
                    updateWebConfig(scrollX = sx)
                }

                etScrollY.doAfterTextChanged { text ->
                    val sy = text?.toString()?.toIntOrNull() ?: 0
                    updateWebConfig(scrollY = sy)
                }

                btnCrop10.setOnClickListener {
                    updateWebConfig(cropLeft = 0.10f, cropTop = 0.10f, cropRight = 0.90f, cropBottom = 0.90f)
                }

                btnCropReset.setOnClickListener {
                    updateWebConfig(cropLeft = 0f, cropTop = 0f, cropRight = 1f, cropBottom = 1f)
                }
            }

            else -> {}
        }

        // Save and Delete
        sheetView.findViewById<MaterialButton>(R.id.btn_inspector_save).setOnClickListener {
            applyLiveUpdate(currentElem, commitHistory = true)
            updateUndoRedoState()
            dialog.dismiss()
        }

        sheetView.findViewById<MaterialButton>(R.id.btn_inspector_delete).setOnClickListener {
            val newScene = app.sceneManager.removeOverlayFromActiveScene(elem.id)
            persistAndSyncScene(newScene)
            binding.broadcastPreviewView.setSelectedElement(null)
            onElementSelected(null)
            updateUndoRedoState()
            dialog.dismiss()
        }

        dialog.show()
    }

    private fun showFacecamInspector() {
        val dialog = BottomSheetDialog(requireContext(), R.style.Theme_FairyLive_BottomSheetDialog)
        val sheetView = layoutInflater.inflate(R.layout.sheet_inspector, null)
        dialog.setContentView(sheetView)

        dialog.setOnShowListener {
            val bottomSheet = dialog.findViewById<View>(com.google.android.material.R.id.design_bottom_sheet) as? FrameLayout
            if (bottomSheet != null) {
                val behavior = BottomSheetBehavior.from(bottomSheet)
                val displayMetrics = resources.displayMetrics
                behavior.peekHeight = (displayMetrics.heightPixels * 0.45f).toInt()
                behavior.state = BottomSheetBehavior.STATE_COLLAPSED
                behavior.isFitToContents = false
                behavior.halfExpandedRatio = 0.50f
            }
        }

        val app = requireActivity().application as FairyLiveApplication
        val scene = app.sceneManager.activeScene
        var currentFc = scene.facecamConfig

        fun applyFacecamUpdate(updated: FacecamConfig, commitHistory: Boolean = false) {
            currentFc = updated
            val newScene = app.sceneManager.updateFacecamInActiveScene(updated, recordHistory = commitHistory)
            persistAndSyncScene(newScene)
            updateCameraIcon(updated.enabled)
            binding.broadcastPreviewView.invalidate()
        }

        val tvTitle = sheetView.findViewById<TextView>(R.id.tv_inspector_title)
        val tvType = sheetView.findViewById<TextView>(R.id.tv_inspector_type)
        tvTitle.text = "Facecam Inspector"
        tvType.text = "CAMERA"

        val sec = sheetView.findViewById<View>(R.id.section_facecam_controls)
        sec.visibility = View.VISIBLE

        // Facing
        val btnFront = sheetView.findViewById<MaterialButton>(R.id.btn_facecam_front)
        val btnBack = sheetView.findViewById<MaterialButton>(R.id.btn_facecam_back)
        fun updateFacingUI(facing: CameraFacing) {
            val accentCol = ContextCompat.getColor(requireContext(), R.color.accent_primary)
            val strokeCol = ContextCompat.getColor(requireContext(), R.color.bg_card_stroke)
            val textPrim = ContextCompat.getColor(requireContext(), R.color.text_primary)
            val textSec = ContextCompat.getColor(requireContext(), R.color.text_secondary)

            val isFront = facing == CameraFacing.FRONT
            btnFront.strokeColor = ColorStateList.valueOf(if (isFront) accentCol else strokeCol)
            btnFront.setTextColor(if (isFront) textPrim else textSec)
            btnBack.strokeColor = ColorStateList.valueOf(if (!isFront) accentCol else strokeCol)
            btnBack.setTextColor(if (!isFront) textPrim else textSec)
        }
        updateFacingUI(currentFc.facing)

        btnFront.setOnClickListener {
            updateFacingUI(CameraFacing.FRONT)
            applyFacecamUpdate(currentFc.copy(facing = CameraFacing.FRONT, mirrorHorizontal = true), commitHistory = true)
        }
        btnBack.setOnClickListener {
            updateFacingUI(CameraFacing.BACK)
            applyFacecamUpdate(currentFc.copy(facing = CameraFacing.BACK, mirrorHorizontal = false), commitHistory = true)
        }

        // Shape
        val btnRect = sheetView.findViewById<MaterialButton>(R.id.btn_facecam_rect)
        val btnCircle = sheetView.findViewById<MaterialButton>(R.id.btn_facecam_circle)
        fun updateShapeUI(isCircle: Boolean) {
            val accentCol = ContextCompat.getColor(requireContext(), R.color.accent_primary)
            val strokeCol = ContextCompat.getColor(requireContext(), R.color.bg_card_stroke)
            val textPrim = ContextCompat.getColor(requireContext(), R.color.text_primary)
            val textSec = ContextCompat.getColor(requireContext(), R.color.text_secondary)

            btnCircle.strokeColor = ColorStateList.valueOf(if (isCircle) accentCol else strokeCol)
            btnCircle.setTextColor(if (isCircle) textPrim else textSec)
            btnRect.strokeColor = ColorStateList.valueOf(if (!isCircle) accentCol else strokeCol)
            btnRect.setTextColor(if (!isCircle) textPrim else textSec)
        }
        updateShapeUI(currentFc.isCircle)

        btnCircle.setOnClickListener {
            updateShapeUI(true)
            applyFacecamUpdate(currentFc.copy(isCircle = true), commitHistory = true)
        }
        btnRect.setOnClickListener {
            updateShapeUI(false)
            applyFacecamUpdate(currentFc.copy(isCircle = false), commitHistory = true)
        }

        // Border toggle
        val switchBorder = sheetView.findViewById<SwitchMaterial>(R.id.switch_facecam_border)
        switchBorder.isChecked = currentFc.showBorder
        switchBorder.setOnCheckedChangeListener { _, isChecked ->
            applyFacecamUpdate(currentFc.copy(showBorder = isChecked), commitHistory = true)
        }

        // Opacity
        val tvOpacityVal = sheetView.findViewById<TextView>(R.id.tv_opacity_val)
        val sbOpacity = sheetView.findViewById<SeekBar>(R.id.sb_inspector_opacity)
        sbOpacity.progress = (currentFc.opacity * 100).toInt()
        tvOpacityVal.text = "${(currentFc.opacity * 100).toInt()}%"
        sbOpacity.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar?, progress: Int, fromUser: Boolean) {
                if (fromUser) {
                    tvOpacityVal.text = "$progress%"
                    applyFacecamUpdate(currentFc.copy(opacity = progress / 100f))
                }
            }
            override fun onStartTrackingTouch(sb: SeekBar?) {}
            override fun onStopTrackingTouch(sb: SeekBar?) {
                applyFacecamUpdate(currentFc, commitHistory = true)
            }
        })

        // Save & Delete
        sheetView.findViewById<MaterialButton>(R.id.btn_inspector_save).setOnClickListener {
            applyFacecamUpdate(currentFc, commitHistory = true)
            updateUndoRedoState()
            dialog.dismiss()
        }

        sheetView.findViewById<MaterialButton>(R.id.btn_inspector_delete).setOnClickListener {
            val disabled = currentFc.copy(enabled = false)
            val newScene = app.sceneManager.updateFacecamInActiveScene(disabled, recordHistory = true)
            persistAndSyncScene(newScene)
            updateCameraIcon(false)
            binding.broadcastPreviewView.setSelectedElement(null)
            onElementSelected(null)
            updateUndoRedoState()
            dialog.dismiss()
        }

        dialog.show()
    }

    private fun showSceneOptionsBottomSheet(scene: BroadcastScene) {
        val dialog = BottomSheetDialog(requireContext(), R.style.Theme_FairyLive_BottomSheetDialog)
        val sheetView = layoutInflater.inflate(R.layout.sheet_scene_options, null)
        dialog.setContentView(sheetView)

        sheetView.findViewById<TextView>(R.id.tv_scene_options_title).text = "Scene: ${scene.name}"

        sheetView.findViewById<View>(R.id.row_scene_rename).setOnClickListener {
            dialog.dismiss()
            showRenameSceneDialog(scene)
        }

        sheetView.findViewById<View>(R.id.row_scene_duplicate).setOnClickListener {
            dialog.dismiss()
            val app = requireActivity().application as FairyLiveApplication
            val dup = app.sceneManager.duplicateScene(scene.id)
            if (dup != null) {
                app.sceneManager.switchScene(dup.id)
                app.sceneRepository.saveScenes(app.sceneManager.allScenes)
            }
        }

        sheetView.findViewById<View>(R.id.row_scene_delete).setOnClickListener {
            dialog.dismiss()
            val app = requireActivity().application as FairyLiveApplication
            if (app.sceneManager.allScenes.size <= 1) {
                Toast.makeText(requireContext(), "Cannot delete the only scene", Toast.LENGTH_SHORT).show()
            } else {
                app.sceneManager.removeScene(scene.id)
                app.sceneRepository.saveScenes(app.sceneManager.allScenes)
            }
        }

        dialog.show()
    }

    private fun showRenameSceneDialog(scene: BroadcastScene) {
        val input = EditText(requireContext()).apply {
            setText(scene.name)
            setTextColor(Color.WHITE)
        }
        AlertDialog.Builder(requireContext(), android.R.style.Theme_DeviceDefault_Dialog_Alert)
            .setTitle("Rename Scene")
            .setView(input)
            .setPositiveButton("Save") { _, _ ->
                val newName = input.text.toString().trim()
                if (newName.isNotEmpty()) {
                    val app = requireActivity().application as FairyLiveApplication
                    app.sceneManager.renameScene(scene.id, newName)
                    app.sceneRepository.saveScenes(app.sceneManager.allScenes)
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun showCreateSceneDialog() {
        val input = EditText(requireContext()).apply {
            hint = "Scene Name (e.g. Gameplay)"
            setTextColor(Color.WHITE)
        }
        AlertDialog.Builder(requireContext(), android.R.style.Theme_DeviceDefault_Dialog_Alert)
            .setTitle("Create New Scene")
            .setView(input)
            .setPositiveButton("Create") { _, _ ->
                val name = input.text.toString().trim().ifEmpty { "New Scene" }
                val app = requireActivity().application as FairyLiveApplication
                val newScene = app.sceneManager.createNewScene(name, SceneBackgroundMode.SCREEN_CAPTURE)
                app.sceneManager.switchScene(newScene.id)
                app.sceneRepository.saveScenes(app.sceneManager.allScenes)
                app.sceneRepository.saveActiveSceneId(newScene.id)
                FairyStreamService.activeService?.overlayCompositor?.applyScene(newScene)
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    override fun onElementSelected(elementId: String?) {
        if (elementId == null) {
            binding.layoutSelectedProperties.visibility = View.GONE
            binding.layoutDefaultToolbar.visibility = View.VISIBLE
        } else {
            val app = requireActivity().application as FairyLiveApplication
            val scene = app.sceneManager.activeScene
            val title = if (elementId == "facecam") "Camera (Facecam)" else {
                val elem = scene.overlayElements.firstOrNull { it.id == elementId }
                val typeName = elem?.type?.name?.lowercase()?.replaceFirstChar { it.uppercase() } ?: "Overlay"
                val contentSnippet = when (elem?.type) {
                    OverlayType.TEXT -> ": \"${elem.textConfig?.text?.take(14)}\""
                    OverlayType.GOAL -> ": \"${elem.goalConfig?.title?.take(14)}\""
                    else -> ""
                }
                "$typeName$contentSnippet"
            }
            binding.tvSelectedElementTitle.text = title
            binding.layoutDefaultToolbar.visibility = View.GONE
            binding.layoutSelectedProperties.visibility = View.VISIBLE
        }
    }

    override fun onElementTransformed(elementId: String, normX: Float, normY: Float, normW: Float, normH: Float) {
        val app = requireActivity().application as FairyLiveApplication
        val elem = app.sceneManager.activeScene.overlayElements.firstOrNull { it.id == elementId } ?: return
        val updated = elem.copy(
            normalizedX = normX,
            normalizedY = normY,
            normalizedWidth = normW,
            normalizedHeight = normH
        )
        val newScene = app.sceneManager.updateOverlayInActiveScene(updated)
        persistAndSyncScene(newScene)
    }

    override fun onElementRotated(elementId: String, rotationDegrees: Float) {
        val app = requireActivity().application as FairyLiveApplication
        val elem = app.sceneManager.activeScene.overlayElements.firstOrNull { it.id == elementId } ?: return
        val updated = elem.copy(rotationDegrees = rotationDegrees)
        val newScene = app.sceneManager.updateOverlayInActiveScene(updated)
        persistAndSyncScene(newScene)
    }

    override fun onWebScrolled(elementId: String, scrollX: Int, scrollY: Int) {
        val app = requireActivity().application as FairyLiveApplication
        val elem = app.sceneManager.activeScene.overlayElements.firstOrNull { it.id == elementId } ?: return
        val curCfg = elem.webConfig ?: WebOverlayConfig()
        val updatedCfg = curCfg.copy(scrollX = scrollX, scrollY = scrollY)
        val updated = elem.copy(webConfig = updatedCfg)
        val newScene = app.sceneManager.updateOverlayInActiveScene(updated)
        persistAndSyncScene(newScene)
    }

    override fun onCropUpdated(elementId: String, cropLeft: Float, cropTop: Float, cropRight: Float, cropBottom: Float) {
        val app = requireActivity().application as FairyLiveApplication
        val elem = app.sceneManager.activeScene.overlayElements.firstOrNull { it.id == elementId } ?: return
        val curCfg = elem.webConfig ?: WebOverlayConfig()
        val updatedCfg = curCfg.copy(cropLeft = cropLeft, cropTop = cropTop, cropRight = cropRight, cropBottom = cropBottom)
        val updated = elem.copy(webConfig = updatedCfg)
        val newScene = app.sceneManager.updateOverlayInActiveScene(updated)
        persistAndSyncScene(newScene)
    }

    override fun onFacecamTransformed(normX: Float, normY: Float, normW: Float, normH: Float) {
        val app = requireActivity().application as FairyLiveApplication
        val fc = app.sceneManager.activeScene.facecamConfig
        val updated = fc.copy(
            normalizedX = normX,
            normalizedY = normY,
            normalizedWidth = normW,
            normalizedHeight = normH
        )
        val newScene = app.sceneManager.updateFacecamInActiveScene(updated)
        persistAndSyncScene(newScene)
    }

    private fun persistAndSyncScene(scene: BroadcastScene) {
        val app = requireActivity().application as FairyLiveApplication
        app.sceneRepository.saveScenes(app.sceneManager.allScenes)
        FairyStreamService.activeService?.overlayCompositor?.applyScene(scene)
    }

    private fun showAddTextDialog() {
        val input = EditText(requireContext()).apply {
            hint = "Enter text overlay..."
            setTextColor(Color.WHITE)
            setHintTextColor(Color.GRAY)
        }
        AlertDialog.Builder(requireContext(), android.R.style.Theme_DeviceDefault_Dialog_Alert)
            .setTitle("Add Text Overlay")
            .setView(input)
            .setPositiveButton("Add") { _, _ ->
                val txt = input.text.toString().trim()
                if (txt.isNotEmpty()) {
                    val app = requireActivity().application as FairyLiveApplication
                    val id = "txt_${System.currentTimeMillis() % 10000}"
                    val elem = OverlayElement.createText(
                        id = id,
                        config = TextOverlayConfig(text = txt, fontSizeSp = 18f),
                        x = 0.1f,
                        y = 0.2f,
                        width = 0.5f,
                        zIndex = 10
                    )
                    val newScene = app.sceneManager.addOverlayToActiveScene(elem)
                    persistAndSyncScene(newScene)
                    binding.broadcastPreviewView.setSelectedElement(id)
                    onElementSelected(id)
                    updateUndoRedoState()
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun addDefaultGoal() {
        val app = requireActivity().application as FairyLiveApplication
        val id = "goal_${System.currentTimeMillis() % 10000}"
        val elem = OverlayElement.createGoal(
            id = id,
            config = GoalOverlayConfig(title = "Followers", currentVal = 42, targetVal = 100, unitPrefix = "★ "),
            x = 0.05f,
            y = 0.05f,
            width = 0.40f,
            zIndex = 10
        )
        val newScene = app.sceneManager.addOverlayToActiveScene(elem)
        persistAndSyncScene(newScene)
        binding.broadcastPreviewView.setSelectedElement(id)
        onElementSelected(id)
        updateUndoRedoState()
    }

    private fun addDefaultLowerThird() {
        val app = requireActivity().application as FairyLiveApplication
        val id = "l3_${System.currentTimeMillis() % 10000}"
        val elem = OverlayElement.createLowerThird(
            id = id,
            config = LowerThirdOverlayConfig(title = "LIVE BROADCAST", subtitle = "Fairy Live Studio Native"),
            x = 0.05f,
            y = 0.80f,
            width = 0.55f,
            zIndex = 15
        )
        val newScene = app.sceneManager.addOverlayToActiveScene(elem)
        persistAndSyncScene(newScene)
        binding.broadcastPreviewView.setSelectedElement(id)
        onElementSelected(id)
        updateUndoRedoState()
    }

    private fun showAddQrDialog() {
        val input = EditText(requireContext()).apply {
            hint = "https://fairy.live"
            setText("https://fairy.live")
            setTextColor(Color.WHITE)
        }
        AlertDialog.Builder(requireContext(), android.R.style.Theme_DeviceDefault_Dialog_Alert)
            .setTitle("Add QR Code")
            .setView(input)
            .setPositiveButton("Add") { _, _ ->
                val url = input.text.toString().trim().ifEmpty { "https://fairy.live" }
                val app = requireActivity().application as FairyLiveApplication
                val id = "qr_${System.currentTimeMillis() % 10000}"
                val elem = OverlayElement.createQr(
                    id = id,
                    config = QrOverlayConfig(payloadUrl = url),
                    x = 0.72f,
                    y = 0.65f,
                    width = 0.22f,
                    zIndex = 20
                )
                val newScene = app.sceneManager.addOverlayToActiveScene(elem)
                persistAndSyncScene(newScene)
                binding.broadcastPreviewView.setSelectedElement(id)
                onElementSelected(id)
                updateUndoRedoState()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun toggleAddFacecam() {
        val app = requireActivity().application as FairyLiveApplication
        val scene = app.sceneManager.activeScene
        val newFc = scene.facecamConfig.copy(
            enabled = true,
            normalizedX = 0.65f,
            normalizedY = 0.05f,
            normalizedWidth = 0.30f,
            normalizedHeight = 0.30f
        )
        val newScene = app.sceneManager.updateFacecamInActiveScene(newFc)
        persistAndSyncScene(newScene)
        binding.broadcastPreviewView.setSelectedElement("facecam")
        onElementSelected("facecam")
        updateCameraIcon(true)
        updateUndoRedoState()
    }

    private fun addDefaultYouTubeSub() {
        val app = requireActivity().application as FairyLiveApplication
        val id = "yt_sub_" + System.currentTimeMillis().toString().takeLast(4)
        val elem = OverlayElement.createYouTubeSubscriber(
            id = id,
            config = com.fairylive.app.core.compositor.model.YouTubeSubscriberWidgetConfig(
                channelId = "UC_DEMO",
                channelTitle = "FAIRY LIVE",
                subscriberCountFormatted = "12.4K",
                rawSubscriberCount = 12400L
            ),
            x = 0.05f,
            y = 0.05f,
            width = 0.40f,
            zIndex = 15
        )
        val newScene = app.sceneManager.addOverlayToActiveScene(elem)
        persistAndSyncScene(newScene)
        binding.broadcastPreviewView.setSelectedElement(id)
        onElementSelected(id)
        updateUndoRedoState()
    }

    private fun addDefaultWebOverlay() {
        val app = requireActivity().application as FairyLiveApplication
        val id = "web_${System.currentTimeMillis() % 10000}"
        val elem = OverlayElement.createWeb(
            id = id,
            config = WebOverlayConfig(
                url = "https://example.com",
                transparentBackground = true,
                customWidth = 800,
                customHeight = 450
            ),
            x = 0.15f,
            y = 0.20f,
            width = 0.45f,
            zIndex = 15
        )
        val newScene = app.sceneManager.addOverlayToActiveScene(elem)
        persistAndSyncScene(newScene)
        binding.broadcastPreviewView.setSelectedElement(id)
        onElementSelected(id)
        updateUndoRedoState()
    }

    private fun addDefaultWidget() {
        val app = requireActivity().application as FairyLiveApplication
        val id = "widget_${System.currentTimeMillis() % 10000}"
        val elem = OverlayElement.createWidget(
            id = id,
            config = WidgetOverlayConfig(widgetType = "STATS", label = "FPS/BPS", value = "60 FPS • 1.5M"),
            x = 0.05f,
            y = 0.15f,
            width = 0.35f,
            zIndex = 10
        )
        val newScene = app.sceneManager.addOverlayToActiveScene(elem)
        persistAndSyncScene(newScene)
        binding.broadcastPreviewView.setSelectedElement(id)
        onElementSelected(id)
        updateUndoRedoState()
    }

    private fun addDefaultImage() {
        val options = arrayOf("📁 Pick Image from Device Storage", "🎨 Use Default FAIRY Logo")
        AlertDialog.Builder(requireContext(), android.R.style.Theme_DeviceDefault_Dialog_Alert)
            .setTitle("Add Image Overlay")
            .setItems(options) { _, which ->
                if (which == 0) {
                    pendingImageTargetElementId = null
                    imagePickerLauncher.launch("image/*")
                } else {
                    val app = requireActivity().application as FairyLiveApplication
                    val id = "img_${System.currentTimeMillis() % 10000}"
                    val elem = OverlayElement.createImage(
                        id = id,
                        resourcePath = "overlay_logo.png",
                        x = 0.75f,
                        y = 0.05f,
                        width = 0.20f,
                        zIndex = 10
                    )
                    val newScene = app.sceneManager.addOverlayToActiveScene(elem)
                    persistAndSyncScene(newScene)
                    binding.broadcastPreviewView.setSelectedElement(id)
                    onElementSelected(id)
                    updateUndoRedoState()
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun importImageFromUri(uri: Uri) {
        try {
            val context = requireContext()
            val assetsDir = java.io.File(context.cacheDir, "studio_assets").apply { mkdirs() }
            val assetFile = java.io.File(assetsDir, "custom_asset_${System.currentTimeMillis()}.png")
            context.contentResolver.openInputStream(uri)?.use { input ->
                java.io.FileOutputStream(assetFile).use { output ->
                    input.copyTo(output)
                }
            }

            val app = requireActivity().application as FairyLiveApplication
            val id = "img_custom_${System.currentTimeMillis() % 10000}"
            val elem = OverlayElement.createImage(
                id = id,
                resourcePath = assetFile.absolutePath,
                x = 0.20f,
                y = 0.20f,
                width = 0.30f,
                zIndex = 15
            )
            val newScene = app.sceneManager.addOverlayToActiveScene(elem)
            persistAndSyncScene(newScene)
            binding.broadcastPreviewView.setSelectedElement(id)
            onElementSelected(id)
            updateUndoRedoState()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to import custom image asset: ${e.message}", e)
        }
    }

    private fun replaceImageFromUri(targetElementId: String, uri: Uri) {
        try {
            val context = requireContext()
            val assetsDir = java.io.File(context.cacheDir, "studio_assets").apply { mkdirs() }
            val assetFile = java.io.File(assetsDir, "custom_asset_${System.currentTimeMillis()}.png")
            context.contentResolver.openInputStream(uri)?.use { input ->
                java.io.FileOutputStream(assetFile).use { output ->
                    input.copyTo(output)
                }
            }

            val app = requireActivity().application as FairyLiveApplication
            val elem = app.sceneManager.activeScene.overlayElements.firstOrNull { it.id == targetElementId } ?: return
            val updated = elem.copy(resourcePath = assetFile.absolutePath)
            val newScene = app.sceneManager.updateOverlayInActiveScene(updated, recordHistory = true)
            persistAndSyncScene(newScene)
            binding.broadcastPreviewView.setSelectedElement(updated.id)
            onElementSelected(updated.id)
            updateUndoRedoState()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to replace image asset: ${e.message}", e)
        }
    }

    private fun addDefaultGif() {
        val options = arrayOf("📁 Pick GIF from Device Storage", "🎨 Use Sample GIF")
        AlertDialog.Builder(requireContext(), android.R.style.Theme_DeviceDefault_Dialog_Alert)
            .setTitle("Add GIF Overlay")
            .setItems(options) { _, which ->
                if (which == 0) {
                    pendingGifTargetElementId = null
                    gifPickerLauncher.launch("image/gif")
                } else {
                    addSampleGif()
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun addSampleGif() {
        val app = requireActivity().application as FairyLiveApplication
        val id = "gif_${System.currentTimeMillis() % 10000}"
        val elem = OverlayElement.createGif(
            id = id,
            config = GifOverlayConfig(resourcePath = "sample_alert.gif"),
            x = 0.35f,
            y = 0.35f,
            width = 0.30f,
            zIndex = 25
        )
        val newScene = app.sceneManager.addOverlayToActiveScene(elem)
        persistAndSyncScene(newScene)
        binding.broadcastPreviewView.setSelectedElement(id)
        onElementSelected(id)
        updateUndoRedoState()
    }

    private fun importGifFromUri(uri: Uri) {
        try {
            val context = requireContext()
            val assetsDir = java.io.File(context.cacheDir, "studio_assets").apply { mkdirs() }
            val assetFile = java.io.File(assetsDir, "custom_gif_${System.currentTimeMillis()}.gif")
            context.contentResolver.openInputStream(uri)?.use { input ->
                java.io.FileOutputStream(assetFile).use { output ->
                    input.copyTo(output)
                }
            }

            val app = requireActivity().application as FairyLiveApplication
            val id = "gif_custom_${System.currentTimeMillis() % 10000}"
            val elem = OverlayElement.createGif(
                id = id,
                config = GifOverlayConfig(resourcePath = assetFile.absolutePath),
                x = 0.25f,
                y = 0.25f,
                width = 0.35f,
                zIndex = 25
            )
            val newScene = app.sceneManager.addOverlayToActiveScene(elem)
            persistAndSyncScene(newScene)
            binding.broadcastPreviewView.setSelectedElement(id)
            onElementSelected(id)
            updateUndoRedoState()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to import custom GIF asset: ${e.message}", e)
        }
    }

    private fun replaceGifFromUri(targetElementId: String, uri: Uri) {
        try {
            val context = requireContext()
            val assetsDir = java.io.File(context.cacheDir, "studio_assets").apply { mkdirs() }
            val assetFile = java.io.File(assetsDir, "custom_gif_${System.currentTimeMillis()}.gif")
            context.contentResolver.openInputStream(uri)?.use { input ->
                java.io.FileOutputStream(assetFile).use { output ->
                    input.copyTo(output)
                }
            }

            val app = requireActivity().application as FairyLiveApplication
            val elem = app.sceneManager.activeScene.overlayElements.firstOrNull { it.id == targetElementId } ?: return
            val curCfg = elem.gifConfig ?: GifOverlayConfig(resourcePath = assetFile.absolutePath)
            val updated = elem.copy(
                resourcePath = assetFile.absolutePath,
                gifConfig = curCfg.copy(resourcePath = assetFile.absolutePath)
            )
            val newScene = app.sceneManager.updateOverlayInActiveScene(updated, recordHistory = true)
            persistAndSyncScene(newScene)
            binding.broadcastPreviewView.setSelectedElement(updated.id)
            onElementSelected(updated.id)
            updateUndoRedoState()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to replace GIF asset: ${e.message}", e)
        }
    }

    private fun addDefaultAlert() {
        val app = requireActivity().application as FairyLiveApplication
        val id = "alert_${System.currentTimeMillis() % 10000}"
        val elem = OverlayElement.createAlert(
            id = id,
            config = AlertOverlayConfig(
                headline = "NEW SUBSCRIBER!",
                subtext = "Welcome to the Fairy Live stream!",
                durationMs = 5000L
            ),
            x = 0.20f,
            y = 0.10f,
            width = 0.60f,
            zIndex = 50
        )
        val newScene = app.sceneManager.addOverlayToActiveScene(elem)
        persistAndSyncScene(newScene)
        binding.broadcastPreviewView.setSelectedElement(id)
        onElementSelected(id)
        updateUndoRedoState()
    }

    private fun updateUndoRedoState() {
        val app = (activity?.application as? FairyLiveApplication) ?: return
        if (_binding == null) return
        binding.btnUndo.alpha = if (app.sceneManager.history.canUndo) 1.0f else 0.30f
        binding.btnUndo.isEnabled = app.sceneManager.history.canUndo
        binding.btnRedo.alpha = if (app.sceneManager.history.canRedo) 1.0f else 0.30f
        binding.btnRedo.isEnabled = app.sceneManager.history.canRedo
    }

    private fun requestStartBroadcast() {
        val projMgr = requireContext().getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        val intent = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            val config = MediaProjectionConfig.createConfigForDefaultDisplay()
            projMgr.createScreenCaptureIntent(config)
        } else {
            @Suppress("DEPRECATION")
            projMgr.createScreenCaptureIntent()
        }
        projectionLauncher.launch(intent)
    }

    private fun startStreamingWithProjection(resultCode: Int, data: Intent) {
        val ctx = requireContext()
        val app = ctx.applicationContext as FairyLiveApplication
        val settings = app.settingsRepository.loadSettings(app.deviceCapability.recommendedProfile)

        val rawW = resources.displayMetrics.widthPixels
        val rawH = resources.displayMetrics.heightPixels
        val safeWidth = (rawW / 2) * 2
        val safeHeight = (rawH / 2) * 2
        val dpi = resources.configuration.densityDpi

        FairyStreamService.startCapture(
            context = ctx,
            resultCode = resultCode,
            data = data,
            width = safeWidth,
            height = safeHeight,
            dpi = dpi,
            enableEncoding = true,
            enableAudio = true,
            enableRtmp = true,
            enableCompositor = true,
            singleOverlayOnly = false,
            rtmpUrl = settings.serverUrl,
            streamKey = settings.streamKey,
            bitrateKbps = settings.videoBitrateKbps,
            fps = settings.fps
        )
    }

    private fun updateStreamStateUI(state: StreamState) {
        if (_binding == null) return
        when (state) {
            StreamState.STREAMING -> {
                binding.btnGoLive.text = "■ STOP STREAM"
                binding.btnGoLive.backgroundTintList = ColorStateList.valueOf(ContextCompat.getColor(requireContext(), R.color.status_red))
                binding.tvStudioStatusBadge.text = "● LIVE"
                binding.tvStudioStatusBadge.setTextColor(ContextCompat.getColor(requireContext(), R.color.status_green))
                binding.tvStreamTimer.visibility = View.VISIBLE
            }
            StreamState.STARTING -> {
                binding.btnGoLive.text = "CONNECTING..."
                binding.btnGoLive.backgroundTintList = ColorStateList.valueOf(ContextCompat.getColor(requireContext(), R.color.accent_primary))
                binding.tvStudioStatusBadge.text = "STARTING..."
                binding.tvStudioStatusBadge.setTextColor(ContextCompat.getColor(requireContext(), R.color.accent_primary))
                binding.tvStreamTimer.visibility = View.GONE
            }
            StreamState.STOPPING -> {
                binding.btnGoLive.text = "STOPPING..."
                binding.btnGoLive.backgroundTintList = ColorStateList.valueOf(ContextCompat.getColor(requireContext(), R.color.status_amber))
                binding.tvStudioStatusBadge.text = "STOPPING"
                binding.tvStudioStatusBadge.setTextColor(ContextCompat.getColor(requireContext(), R.color.status_amber))
                binding.tvStreamTimer.visibility = View.GONE
            }
            else -> {
                binding.btnGoLive.text = "● GO LIVE"
                binding.btnGoLive.backgroundTintList = ColorStateList.valueOf(ContextCompat.getColor(requireContext(), R.color.accent_primary))
                binding.tvStudioStatusBadge.text = "READY"
                binding.tvStudioStatusBadge.setTextColor(ContextCompat.getColor(requireContext(), R.color.accent_primary))
                binding.tvStreamTimer.visibility = View.GONE
            }
        }
    }

    override fun onStateChanged(newState: StreamState, previousState: StreamState) {
        activity?.runOnUiThread {
            updateStreamStateUI(newState)
        }
    }

    override fun onTelemetryUpdated(telemetry: StreamTelemetry) {
        activity?.runOnUiThread {
            if (_binding == null) return@runOnUiThread
            binding.tvHudBitrate.text = "${telemetry.bitrateKbps / 1000.0} Mbps"
            val seconds = (telemetry.durationMs / 1000) % 60
            val minutes = (telemetry.durationMs / (1000 * 60)) % 60
            val hours = (telemetry.durationMs / (1000 * 60 * 60))
            binding.tvStreamTimer.text = String.format(java.util.Locale.US, "%02d:%02d:%02d", hours, minutes, seconds)
        }
    }

    override fun onEncoderMetricsUpdated(metrics: com.fairylive.app.core.video.model.EncoderMetrics) {
        activity?.runOnUiThread {
            if (_binding == null) return@runOnUiThread
            binding.tvHudFps.text = "${metrics.outputFps.toInt()} FPS"
        }
    }

    override fun onAudioMetricsUpdated(metrics: AudioMetrics) {
        activity?.runOnUiThread {
            if (_binding == null) return@runOnUiThread
            binding.tvHudAudioRate.text = "48 kHz"
        }
    }

    private fun dp(value: Int): Int {
        return (value * resources.displayMetrics.density).toInt()
    }

    companion object {
        private const val TAG = "CreatorStudioFragment"
    }
}
