package com.fairylive.app.ui.studio

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Color
import android.net.Uri
import android.media.projection.MediaProjectionConfig
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import com.fairylive.app.FairyLiveApplication
import com.fairylive.app.R
import com.fairylive.app.core.audio.AudioStateNotifier
import com.fairylive.app.core.audio.model.AudioCaptureState
import com.fairylive.app.core.audio.model.AudioMetrics
import com.fairylive.app.core.camera.CameraStateNotifier
import com.fairylive.app.core.camera.model.FacecamConfig
import com.fairylive.app.core.compositor.model.GoalOverlayConfig
import com.fairylive.app.core.compositor.model.LowerThirdOverlayConfig
import com.fairylive.app.core.compositor.model.OverlayElement
import com.fairylive.app.core.compositor.model.OverlayType
import com.fairylive.app.core.compositor.model.QrOverlayConfig
import com.fairylive.app.core.compositor.model.TextOverlayConfig
import com.fairylive.app.core.compositor.model.WidgetOverlayConfig
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

/**
 * Milestone 15-A & 15-B: Native Creator Studio Fragment.
 * Provides real-time scene editing, interactive live preview, layer management,
 * overlay customization, and broadcast composition controls.
 */
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
                binding.btnToggleMic.text = "🔇"
                binding.btnToggleMic.backgroundTintList = ColorStateList.valueOf(ContextCompat.getColor(requireContext(), R.color.status_red))
            } else {
                FairyStreamService.unmuteAudio(requireContext())
                binding.btnToggleMic.text = "🎙️"
                binding.btnToggleMic.backgroundTintList = ColorStateList.valueOf(ContextCompat.getColor(requireContext(), R.color.bg_card))
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
        }

        binding.btnHudToggle.setOnClickListener {
            isHudVisible = !isHudVisible
            binding.layoutHudOverlay.visibility = if (isHudVisible) View.VISIBLE else View.GONE
            binding.btnHudToggle.setTextColor(
                ContextCompat.getColor(requireContext(), if (isHudVisible) R.color.status_green else R.color.fairy_cyan)
            )
        }

        binding.btnAddOverlay.setOnClickListener {
            showAddOverlayDialog()
        }

        binding.btnLayersToggle.setOnClickListener {
            val isVisible = binding.layoutLayerPanel.visibility == View.VISIBLE
            binding.layoutLayerPanel.visibility = if (isVisible) View.GONE else View.VISIBLE
            if (!isVisible) {
                populateLayersList()
            }
        }

        binding.btnScenesToggle.setOnClickListener {
            showSceneManagerDialog()
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

        // Selected element controls
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
            binding.layoutSelectedProperties.visibility = View.GONE
            populateLayersList()
            updateUndoRedoState()
        }

        binding.btnElementVisibility.setOnClickListener {
            val selId = binding.broadcastPreviewView.getSelectedElementId() ?: return@setOnClickListener
            val app = requireActivity().application as FairyLiveApplication
            val elem = app.sceneManager.activeScene.overlayElements.firstOrNull { it.id == selId } ?: return@setOnClickListener
            val updatedElem = elem.copy(visible = !elem.visible)
            val newScene = app.sceneManager.updateOverlayInActiveScene(updatedElem)
            persistAndSyncScene(newScene)
            binding.btnElementVisibility.text = if (updatedElem.visible) "Hide" else "Show"
            populateLayersList()
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
                populateLayersList()
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
                populateLayersList()
                updateUndoRedoState()
            }
        }

        binding.btnElementEdit.setOnClickListener {
            val selId = binding.broadcastPreviewView.getSelectedElementId() ?: return@setOnClickListener
            val app = requireActivity().application as FairyLiveApplication
            val elem = app.sceneManager.activeScene.overlayElements.firstOrNull { it.id == selId } ?: return@setOnClickListener
            showEditElementDialog(elem)
        }

        binding.btnElementOpacity.setOnClickListener {
            val selId = binding.broadcastPreviewView.getSelectedElementId() ?: return@setOnClickListener
            val app = requireActivity().application as FairyLiveApplication
            val elem = app.sceneManager.activeScene.overlayElements.firstOrNull { it.id == selId } ?: return@setOnClickListener
            showOpacityDialog(elem)
        }
    }

    private fun updateSceneUI(scene: BroadcastScene) {
        binding.tvActiveSceneLabel.text = "Scene: ${scene.name}"
        binding.broadcastPreviewView.setScene(scene)
        populateSceneChips()
        populateLayersList()
    }

    private fun populateSceneChips() {
        val app = requireActivity().application as FairyLiveApplication
        val container = binding.layoutSceneChips
        container.removeAllViews()

        val activeScene = app.sceneManager.activeScene
        for (scene in app.sceneManager.allScenes) {
            val isActive = scene.id == activeScene.id
            val btn = Button(requireContext()).apply {
                text = scene.name
                isAllCaps = false
                textSize = 11f
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    resources.getDimensionPixelSize(R.dimen.chip_height)
                ).apply {
                    marginEnd = 8
                }
                backgroundTintList = ColorStateList.valueOf(
                    ContextCompat.getColor(
                        context,
                        if (isActive) R.color.fairy_purple else R.color.bg_card
                    )
                )
                setTextColor(ContextCompat.getColor(context, R.color.text_primary))
                setOnClickListener {
                    app.sceneManager.switchScene(scene.id)
                    app.sceneRepository.saveActiveSceneId(scene.id)
                    FairyStreamService.activeService?.overlayCompositor?.applyScene(scene)
                }
            }
            container.addView(btn)
        }

        // Add "+ New" scene chip
        val addBtn = Button(requireContext()).apply {
            text = "+ New"
            isAllCaps = false
            textSize = 11f
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                resources.getDimensionPixelSize(R.dimen.chip_height)
            )
            backgroundTintList = ColorStateList.valueOf(ContextCompat.getColor(context, R.color.bg_surface))
            setTextColor(ContextCompat.getColor(context, R.color.fairy_cyan))
            setOnClickListener {
                showCreateSceneDialog()
            }
        }
        container.addView(addBtn)
    }

    private fun populateLayersList() {
        val container = binding.layoutLayersList
        container.removeAllViews()
        val app = requireActivity().application as FairyLiveApplication
        val scene = app.sceneManager.activeScene

        // Facecam row
        if (scene.facecamConfig.enabled) {
            val fcRow = createLayerRow("📷 Facecam", isVisible = true, isSelected = binding.broadcastPreviewView.getSelectedElementId() == "facecam") {
                binding.broadcastPreviewView.setSelectedElement("facecam")
                showSelectedProperties("facecam", "📷 Facecam", isVisible = true)
            }
            container.addView(fcRow)
        }

        // Overlays in reverse order (top first)
        for (elem in scene.overlayElements.reversed()) {
            val name = "${elem.type.name}: ${elem.id.take(14)}"
            val isSelected = binding.broadcastPreviewView.getSelectedElementId() == elem.id
            val row = createLayerRow(name, elem.visible, isSelected) {
                binding.broadcastPreviewView.setSelectedElement(elem.id)
                showSelectedProperties(elem.id, name, elem.visible)
            }
            container.addView(row)
        }
    }

    private fun createLayerRow(name: String, isVisible: Boolean, isSelected: Boolean, onClick: () -> Unit): View {
        val row = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
            setPadding(8, 6, 8, 6)
            setBackgroundColor(if (isSelected) Color.parseColor("#3300E5FF") else Color.TRANSPARENT)
            setOnClickListener { onClick() }
        }

        val tv = TextView(requireContext()).apply {
            text = name
            setTextColor(if (isVisible) Color.WHITE else Color.GRAY)
            textSize = 12f
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        row.addView(tv)

        val eyeTv = TextView(requireContext()).apply {
            text = if (isVisible) "👁️" else "🔒"
            textSize = 12f
        }
        row.addView(eyeTv)

        return row
    }

    private fun showSelectedProperties(id: String, title: String, isVisible: Boolean) {
        binding.layoutSelectedProperties.visibility = View.VISIBLE
        binding.tvSelectedElementTitle.text = title
        binding.btnElementVisibility.text = if (isVisible) "Hide" else "Show"
    }

    override fun onElementSelected(elementId: String?) {
        if (elementId == null) {
            binding.layoutSelectedProperties.visibility = View.GONE
        } else {
            val app = requireActivity().application as FairyLiveApplication
            val scene = app.sceneManager.activeScene
            val title = if (elementId == "facecam") "📷 Facecam" else {
                val elem = scene.overlayElements.firstOrNull { it.id == elementId }
                "${elem?.type?.name ?: "Overlay"}: $elementId"
            }
            val isVis = if (elementId == "facecam") true else {
                scene.overlayElements.firstOrNull { it.id == elementId }?.visible ?: true
            }
            showSelectedProperties(elementId, title, isVis)
        }
        populateLayersList()
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

    private fun showAddOverlayDialog() {
        val options = arrayOf(
            "Text Overlay",
            "Stream Goal Progress",
            "Lower Third Banner",
            "QR Code",
            "Facecam (Camera PIP)",
            "System Diagnostics Widget",
            "Image / Logo",
            "Animated GIF",
            "Stream Alert"
        )

        AlertDialog.Builder(requireContext(), android.R.style.Theme_DeviceDefault_Dialog_Alert)
            .setTitle("Add to Broadcast")
            .setItems(options) { _, which ->
                when (which) {
                    0 -> showAddTextDialog()
                    1 -> addDefaultGoal()
                    2 -> addDefaultLowerThird()
                    3 -> showAddQrDialog()
                    4 -> toggleAddFacecam()
                    5 -> addDefaultWidget()
                    6 -> addDefaultImage()
                    7 -> addDefaultGif()
                    8 -> addDefaultAlert()
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun showAddTextDialog() {
        val input = EditText(requireContext()).apply {
            hint = "Enter overlay text"
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
            updateUndoRedoState()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to import custom image asset: ${e.message}", e)
        }
    }

    private fun showEditElementDialog(elem: OverlayElement) {
        val app = requireActivity().application as FairyLiveApplication
        when (elem.type) {
            OverlayType.TEXT -> {
                val input = EditText(requireContext()).apply {
                    setText(elem.textConfig?.text ?: "")
                    setTextColor(Color.WHITE)
                    setHintTextColor(Color.GRAY)
                }
                AlertDialog.Builder(requireContext(), android.R.style.Theme_DeviceDefault_Dialog_Alert)
                    .setTitle("Edit Text Overlay")
                    .setView(input)
                    .setPositiveButton("Save") { _, _ ->
                        val newText = input.text.toString().trim()
                        if (newText.isNotEmpty()) {
                            val updated = elem.copy(textConfig = elem.textConfig?.copy(text = newText))
                            val newScene = app.sceneManager.updateOverlayInActiveScene(updated)
                            persistAndSyncScene(newScene)
                            updateUndoRedoState()
                        }
                    }
                    .setNegativeButton("Cancel", null)
                    .show()
            }
            OverlayType.QR -> {
                val input = EditText(requireContext()).apply {
                    setText(elem.qrConfig?.payloadUrl ?: "")
                    setTextColor(Color.WHITE)
                    setHintTextColor(Color.GRAY)
                }
                AlertDialog.Builder(requireContext(), android.R.style.Theme_DeviceDefault_Dialog_Alert)
                    .setTitle("Edit QR Payload URL")
                    .setView(input)
                    .setPositiveButton("Save") { _, _ ->
                        val newUrl = input.text.toString().trim()
                        if (newUrl.isNotEmpty()) {
                            val updated = elem.copy(qrConfig = elem.qrConfig?.copy(payloadUrl = newUrl))
                            val newScene = app.sceneManager.updateOverlayInActiveScene(updated)
                            persistAndSyncScene(newScene)
                            updateUndoRedoState()
                        }
                    }
                    .setNegativeButton("Cancel", null)
                    .show()
            }
            OverlayType.GOAL -> {
                val input = EditText(requireContext()).apply {
                    setText(elem.goalConfig?.title ?: "")
                    setTextColor(Color.WHITE)
                    setHintTextColor(Color.GRAY)
                }
                AlertDialog.Builder(requireContext(), android.R.style.Theme_DeviceDefault_Dialog_Alert)
                    .setTitle("Edit Goal Title")
                    .setView(input)
                    .setPositiveButton("Save") { _, _ ->
                        val newTitle = input.text.toString().trim()
                        if (newTitle.isNotEmpty()) {
                            val updated = elem.copy(goalConfig = elem.goalConfig?.copy(title = newTitle))
                            val newScene = app.sceneManager.updateOverlayInActiveScene(updated)
                            persistAndSyncScene(newScene)
                            updateUndoRedoState()
                        }
                    }
                    .setNegativeButton("Cancel", null)
                    .show()
            }
            else -> {
                AlertDialog.Builder(requireContext(), android.R.style.Theme_DeviceDefault_Dialog_Alert)
                    .setTitle("Layer Properties")
                    .setMessage("Type: ${elem.type.name}\nZ-Index: ${elem.zIndex}\nPosition: (${(elem.normalizedX * 100).toInt()}%, ${(elem.normalizedY * 100).toInt()}%)")
                    .setPositiveButton("OK", null)
                    .show()
            }
        }
    }

    private fun showOpacityDialog(elem: OverlayElement) {
        val app = requireActivity().application as FairyLiveApplication
        val options = arrayOf("100%", "80%", "60%", "40%", "20%")
        val values = floatArrayOf(1.0f, 0.8f, 0.6f, 0.4f, 0.2f)
        AlertDialog.Builder(requireContext(), android.R.style.Theme_DeviceDefault_Dialog_Alert)
            .setTitle("Set Layer Opacity")
            .setItems(options) { _, which ->
                val newOpacity = values[which]
                val updated = elem.copy(opacity = newOpacity)
                val newScene = app.sceneManager.updateOverlayInActiveScene(updated)
                persistAndSyncScene(newScene)
                updateUndoRedoState()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun updateUndoRedoState() {
        val app = (activity?.application as? FairyLiveApplication) ?: return
        binding.btnUndo.alpha = if (app.sceneManager.history.canUndo) 1.0f else 0.35f
        binding.btnUndo.isEnabled = app.sceneManager.history.canUndo
        binding.btnRedo.alpha = if (app.sceneManager.history.canRedo) 1.0f else 0.35f
        binding.btnRedo.isEnabled = app.sceneManager.history.canRedo
    }

    private fun addDefaultGif() {
        val app = requireActivity().application as FairyLiveApplication
        val id = "gif_${System.currentTimeMillis() % 10000}"
        val elem = OverlayElement.createGif(
            id = id,
            config = com.fairylive.app.core.compositor.model.GifOverlayConfig(resourcePath = "sample_alert.gif"),
            x = 0.35f,
            y = 0.35f,
            width = 0.30f,
            zIndex = 25
        )
        val newScene = app.sceneManager.addOverlayToActiveScene(elem)
        persistAndSyncScene(newScene)
        binding.broadcastPreviewView.setSelectedElement(id)
    }

    private fun addDefaultAlert() {
        val app = requireActivity().application as FairyLiveApplication
        val id = "alert_${System.currentTimeMillis() % 10000}"
        val elem = OverlayElement.createAlert(
            id = id,
            config = com.fairylive.app.core.compositor.model.AlertOverlayConfig(
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

    private fun showSceneManagerDialog() {
        val app = requireActivity().application as FairyLiveApplication
        val active = app.sceneManager.activeScene
        val items = arrayOf("Rename '${active.name}'", "Duplicate '${active.name}'", "Delete '${active.name}'")

        AlertDialog.Builder(requireContext(), android.R.style.Theme_DeviceDefault_Dialog_Alert)
            .setTitle("Scene: ${active.name}")
            .setItems(items) { _, which ->
                when (which) {
                    0 -> {
                        val input = EditText(requireContext()).apply {
                            setText(active.name)
                            setTextColor(Color.WHITE)
                        }
                        AlertDialog.Builder(requireContext(), android.R.style.Theme_DeviceDefault_Dialog_Alert)
                            .setTitle("Rename Scene")
                            .setView(input)
                            .setPositiveButton("Save") { _, _ ->
                                val newName = input.text.toString().trim()
                                if (newName.isNotEmpty()) {
                                    app.sceneManager.renameScene(active.id, newName)
                                    app.sceneRepository.saveScenes(app.sceneManager.allScenes)
                                }
                            }
                            .show()
                    }
                    1 -> {
                        val dup = app.sceneManager.duplicateScene(active.id)
                        if (dup != null) {
                            app.sceneManager.switchScene(dup.id)
                            app.sceneRepository.saveScenes(app.sceneManager.allScenes)
                        }
                    }
                    2 -> {
                        if (app.sceneManager.allScenes.size <= 1) {
                            Toast.makeText(requireContext(), "Cannot delete the only scene", Toast.LENGTH_SHORT).show()
                        } else {
                            app.sceneManager.removeScene(active.id)
                            app.sceneRepository.saveScenes(app.sceneManager.allScenes)
                        }
                    }
                }
            }
            .setNegativeButton("Close", null)
            .show()
    }

    private fun requestStartBroadcast() {
        val projMgr = requireContext().getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        val intent = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            val config = MediaProjectionConfig.createConfigForDefaultDisplay()
            projMgr.createScreenCaptureIntent(config)
        } else {
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
            }
            StreamState.STARTING -> {
                binding.btnGoLive.text = "CONNECTING..."
                binding.btnGoLive.backgroundTintList = ColorStateList.valueOf(ContextCompat.getColor(requireContext(), R.color.fairy_cyan))
                binding.tvStudioStatusBadge.text = "STARTING..."
                binding.tvStudioStatusBadge.setTextColor(ContextCompat.getColor(requireContext(), R.color.fairy_cyan))
            }
            StreamState.READY -> {
                binding.btnGoLive.text = "● GO LIVE"
                binding.btnGoLive.backgroundTintList = ColorStateList.valueOf(ContextCompat.getColor(requireContext(), R.color.fairy_cyan))
                binding.tvStudioStatusBadge.text = "READY"
                binding.tvStudioStatusBadge.setTextColor(ContextCompat.getColor(requireContext(), R.color.fairy_cyan))
            }
            StreamState.STOPPING -> {
                binding.btnGoLive.text = "STOPPING..."
                binding.btnGoLive.backgroundTintList = ColorStateList.valueOf(ContextCompat.getColor(requireContext(), R.color.status_amber))
                binding.tvStudioStatusBadge.text = "STOPPING"
                binding.tvStudioStatusBadge.setTextColor(ContextCompat.getColor(requireContext(), R.color.status_amber))
            }
            else -> {
                binding.btnGoLive.text = "● GO LIVE"
                binding.btnGoLive.backgroundTintList = ColorStateList.valueOf(ContextCompat.getColor(requireContext(), R.color.fairy_cyan))
                binding.tvStudioStatusBadge.text = "OFFLINE"
                binding.tvStudioStatusBadge.setTextColor(ContextCompat.getColor(requireContext(), R.color.status_amber))
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
            binding.tvHudBitrate.text = "Bitrate: ${telemetry.bitrateKbps} kbps"
        }
    }

    override fun onEncoderMetricsUpdated(metrics: com.fairylive.app.core.video.model.EncoderMetrics) {
        activity?.runOnUiThread {
            if (_binding == null) return@runOnUiThread
            binding.tvHudFps.text = "FPS: ${"%.1f".format(metrics.outputFps)}"
        }
    }

    override fun onAudioMetricsUpdated(metrics: AudioMetrics) {
        activity?.runOnUiThread {
            if (_binding == null) return@runOnUiThread
            binding.tvHudAudioRate.text = "Audio: ${metrics.samplesRead} spls (${metrics.peakAmplitudePercent}%)"
        }
    }

    companion object {
        private const val TAG = "CreatorStudioFragment"
    }
}
