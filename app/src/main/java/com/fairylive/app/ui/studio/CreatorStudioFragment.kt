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
import com.google.android.material.bottomsheet.BottomSheetDialog

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

    private val imagePickerLauncher = registerForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        if (uri != null) {
            importImageFromUri(uri)
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
            val app = requireActivity().application as FairyLiveApplication
            val elem = app.sceneManager.activeScene.overlayElements.firstOrNull { it.id == selId } ?: return@setOnClickListener
            showEditElementBottomSheet(elem)
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
        val dialog = BottomSheetDialog(requireContext())
        val sheetView = layoutInflater.inflate(R.layout.sheet_add_overlay, null)
        dialog.setContentView(sheetView)

        sheetView.findViewById<View>(R.id.item_add_text).setOnClickListener {
            dialog.dismiss()
            showAddTextDialog()
        }
        sheetView.findViewById<View>(R.id.item_add_image).setOnClickListener {
            dialog.dismiss()
            addDefaultImage()
        }
        sheetView.findViewById<View>(R.id.item_add_camera).setOnClickListener {
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
        sheetView.findViewById<View>(R.id.item_add_widget).setOnClickListener {
            dialog.dismiss()
            addDefaultWidget()
        }
        sheetView.findViewById<View>(R.id.item_add_alert).setOnClickListener {
            dialog.dismiss()
            addDefaultAlert()
        }

        dialog.show()
    }

    private fun showLayersBottomSheet() {
        val dialog = BottomSheetDialog(requireContext())
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
            val row = createLayerSheetRow("Camera (Facecam)", "📷", true) {
                dialog.dismiss()
                binding.broadcastPreviewView.setSelectedElement("facecam")
                onElementSelected("facecam")
            }
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

            val row = createLayerSheetRow(label, icon, elem.visible) {
                dialog.dismiss()
                binding.broadcastPreviewView.setSelectedElement(elem.id)
                onElementSelected(elem.id)
            }
            listContainer.addView(row)
        }

        dialog.show()
    }

    private fun createLayerSheetRow(name: String, iconStr: String, isVisible: Boolean, onClick: () -> Unit): View {
        val row = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
            setPadding(dp(4), dp(10), dp(4), dp(10))
            isClickable = true
            isFocusable = true
            setBackgroundResource(android.R.drawable.list_selector_background)
            setOnClickListener { onClick() }
        }

        val handle = TextView(requireContext()).apply {
            text = "☰"
            setTextColor(ContextCompat.getColor(context, R.color.text_muted))
            textSize = 14f
            layoutParams = LinearLayout.LayoutParams(dp(28), LinearLayout.LayoutParams.WRAP_CONTENT)
        }
        row.addView(handle)

        val icon = TextView(requireContext()).apply {
            text = iconStr
            textSize = 14f
            setTextColor(ContextCompat.getColor(context, R.color.accent_primary))
            layoutParams = LinearLayout.LayoutParams(dp(24), LinearLayout.LayoutParams.WRAP_CONTENT)
        }
        row.addView(icon)

        val title = TextView(requireContext()).apply {
            text = name
            setTextColor(ContextCompat.getColor(context, if (isVisible) R.color.text_primary else R.color.text_muted))
            textSize = 14f
            maxLines = 1
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                marginStart = dp(10)
            }
        }
        row.addView(title)

        val eye = TextView(requireContext()).apply {
            text = if (isVisible) "👁" else "🔒"
            textSize = 14f
        }
        row.addView(eye)

        return row
    }

    private fun showEditElementBottomSheet(elem: OverlayElement) {
        val dialog = BottomSheetDialog(requireContext())
        val sheetView = layoutInflater.inflate(R.layout.sheet_edit_element, null)
        dialog.setContentView(sheetView)

        val tvTitle = sheetView.findViewById<TextView>(R.id.tv_edit_sheet_title)
        val etContent = sheetView.findViewById<EditText>(R.id.et_edit_content)
        val sbOpacity = sheetView.findViewById<SeekBar>(R.id.sb_edit_opacity)
        val btnSave = sheetView.findViewById<Button>(R.id.btn_edit_save)
        val btnDelete = sheetView.findViewById<Button>(R.id.btn_edit_delete)

        tvTitle.text = "Edit ${elem.type.name.lowercase().replaceFirstChar { it.uppercase() }}"
        sbOpacity.progress = (elem.opacity * 100).toInt()

        val initialContent = when (elem.type) {
            OverlayType.TEXT -> elem.textConfig?.text ?: ""
            OverlayType.GOAL -> elem.goalConfig?.title ?: ""
            OverlayType.LOWER_THIRD -> elem.lowerThirdConfig?.title ?: ""
            OverlayType.QR -> elem.qrConfig?.payloadUrl ?: ""
            else -> ""
        }
        etContent.setText(initialContent)

        btnSave.setOnClickListener {
            val app = requireActivity().application as FairyLiveApplication
            val newText = etContent.text.toString().trim()
            val newOpacity = sbOpacity.progress / 100f
            var updated = elem.copy(opacity = newOpacity)

            when (elem.type) {
                OverlayType.TEXT -> if (newText.isNotEmpty()) {
                    updated = updated.copy(textConfig = elem.textConfig?.copy(text = newText))
                }
                OverlayType.GOAL -> if (newText.isNotEmpty()) {
                    updated = updated.copy(goalConfig = elem.goalConfig?.copy(title = newText))
                }
                OverlayType.LOWER_THIRD -> if (newText.isNotEmpty()) {
                    updated = updated.copy(lowerThirdConfig = elem.lowerThirdConfig?.copy(title = newText))
                }
                OverlayType.QR -> if (newText.isNotEmpty()) {
                    updated = updated.copy(qrConfig = elem.qrConfig?.copy(payloadUrl = newText))
                }
                else -> {}
            }

            val newScene = app.sceneManager.updateOverlayInActiveScene(updated)
            persistAndSyncScene(newScene)
            updateUndoRedoState()
            dialog.dismiss()
        }

        btnDelete.setOnClickListener {
            val app = requireActivity().application as FairyLiveApplication
            val newScene = app.sceneManager.removeOverlayFromActiveScene(elem.id)
            persistAndSyncScene(newScene)
            binding.broadcastPreviewView.setSelectedElement(null)
            onElementSelected(null)
            updateUndoRedoState()
            dialog.dismiss()
        }

        dialog.show()
    }

    private fun showSceneOptionsBottomSheet(scene: BroadcastScene) {
        val dialog = BottomSheetDialog(requireContext())
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

    private fun addDefaultGif() {
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
