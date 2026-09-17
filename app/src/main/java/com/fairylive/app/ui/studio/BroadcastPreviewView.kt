package com.fairylive.app.ui.studio

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PointF
import android.graphics.RectF
import android.graphics.Typeface
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.TextView
import androidx.core.content.ContextCompat
import com.fairylive.app.R
import com.fairylive.app.core.camera.model.FacecamConfig
import com.fairylive.app.core.compositor.model.*
import com.fairylive.app.core.compositor.rasterizer.QrMatrixEncoder
import com.fairylive.app.core.compositor.render.OffscreenWebRenderer
import com.fairylive.app.core.scene.model.BroadcastScene
import com.fairylive.app.core.scene.model.SceneBackgroundMode
import kotlin.math.atan2
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * High-fidelity, touch-interactive Live Preview View for the Creator Studio.
 * Renders the shared scene model WYSIWYG without burning studio UI controls into the broadcast.
 * Supports:
 * - Direct Web Viewport internal scrolling (single finger inside web overlay)
 * - Move, Scale, and live Rotation Handle (0..360°)
 * - Two-finger simultaneous pinch-zoom and rotation
 * - Non-destructive Web Viewport Cropping
 * - RenderDomain isolation: All editing handles are Creator-Only UI and never leak to composite.
 */
class BroadcastPreviewView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : FrameLayout(context, attrs, defStyleAttr) {

    interface InteractionListener {
        fun onElementSelected(elementId: String?)
        fun onElementTransformed(elementId: String, normX: Float, normY: Float, normW: Float, normH: Float)
        fun onFacecamTransformed(normX: Float, normY: Float, normW: Float, normH: Float)
        fun onElementRotated(elementId: String, rotationDegrees: Float) {}
        fun onWebScrolled(elementId: String, scrollX: Int, scrollY: Int) {}
        fun onCropUpdated(elementId: String, cropLeft: Float, cropTop: Float, cropRight: Float, cropBottom: Float) {}
    }

    var interactionListener: InteractionListener? = null

    private var currentScene: BroadcastScene? = null
    private var selectedId: String? = null

    // Transform touch tracking via authoritative gesture state machine
    enum class GestureState {
        IDLE,
        DRAG,
        SCALE_ROTATE,
        ROTATE,
        RESIZE,
        CROP_LEFT,
        CROP_TOP,
        CROP_RIGHT,
        CROP_BOTTOM,
        WEB_SCROLL
    }

    var gestureState: GestureState = GestureState.IDLE
        private set

    private var lastTouchX = 0f
    private var lastTouchY = 0f
    private var activeCorner = -1 // 0: TL, 1: TR, 2: BR, 3: BL

    // Two-finger gesture tracking
    private var initialFingerDistance = 0f
    private var initialFingerAngle = 0f
    private var initialElementScale = 1f
    private var initialElementRotation = 0f

    private val selectionPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1.5f * resources.displayMetrics.density
        color = Color.parseColor("#6366F1")
    }

    private val selectionFillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Color.parseColor("#0F6366F1")
    }

    private val handlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Color.WHITE
    }

    private val handleStrokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1.5f * resources.displayMetrics.density
        color = Color.parseColor("#6366F1")
    }

    private val rotationStemPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1.5f * resources.displayMetrics.density
        color = Color.parseColor("#6366F1")
    }

    private val rotationHandlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Color.parseColor("#38BDF8") // Cyan accent for rotation
    }

    private val guidelinePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1f * resources.displayMetrics.density
        color = Color.parseColor("#6366F1")
        pathEffect = android.graphics.DashPathEffect(floatArrayOf(8f, 6f), 0f)
    }

    private var snapGuideVerticalX: Float? = null
    private var snapGuideHorizontalY: Float? = null

    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textSize = 11f * resources.displayMetrics.scaledDensity
        typeface = Typeface.DEFAULT_BOLD
    }

    private val labelBgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#E613141B")
        style = Paint.Style.FILL
    }

    private val HANDLE_RADIUS_DP = 6f
    private val handleRadiusPx: Float
        get() = HANDLE_RADIUS_DP * resources.displayMetrics.density
    private val handleTouchRadiusPx: Float
        get() = 24f * resources.displayMetrics.density
    private val rotationStemHeightPx: Float
        get() = 28f * resources.displayMetrics.density

    private val cropHandlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#C27D48") // Warm Caramel for Crop Handles
        style = Paint.Style.FILL
    }
    private val cropHandleStrokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        style = Paint.Style.STROKE
        strokeWidth = 2f * resources.displayMetrics.density
    }


    init {
        setWillNotDraw(false)
        setBackgroundColor(Color.parseColor("#0E0E14"))
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val width = MeasureSpec.getSize(widthMeasureSpec)
        val height = MeasureSpec.getSize(heightMeasureSpec)

        val targetRatio = 16f / 9f
        val calculatedHeight = (width / targetRatio).toInt()

        val finalWidth: Int
        val finalHeight: Int

        if (calculatedHeight <= height || height == 0) {
            finalWidth = width
            finalHeight = calculatedHeight
        } else {
            finalHeight = height
            finalWidth = (height * targetRatio).toInt()
        }

        super.onMeasure(
            MeasureSpec.makeMeasureSpec(finalWidth, MeasureSpec.EXACTLY),
            MeasureSpec.makeMeasureSpec(finalHeight, MeasureSpec.EXACTLY)
        )
    }

    fun setScene(scene: BroadcastScene) {
        this.currentScene = scene
        rebuildLayerViews()
        invalidate()
    }

    fun setSelectedElement(elementId: String?) {
        this.selectedId = elementId
        interactionListener?.onElementSelected(elementId)
        invalidate()
    }

    fun getSelectedElementId(): String? = selectedId

    private fun rebuildLayerViews() {
        removeAllViews()
        val scene = currentScene ?: return

        val viewW = measuredWidth.toFloat()
        val viewH = measuredHeight.toFloat()
        if (viewW <= 0f || viewH <= 0f) {
            post { rebuildLayerViews() }
            return
        }

        // 1. Background layer
        val bgView = View(context).apply {
            layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT)
            when (scene.backgroundMode) {
                SceneBackgroundMode.SCREEN_CAPTURE -> {
                    setBackgroundColor(Color.parseColor("#12121A"))
                }
                SceneBackgroundMode.SOLID_COLOR -> {
                    setBackgroundColor(scene.backgroundColorArgb)
                }
                SceneBackgroundMode.TRANSPARENT -> {
                    setBackgroundColor(Color.TRANSPARENT)
                }
            }
        }
        addView(bgView)

        // 2. Facecam PIP View (if enabled)
        if (scene.facecamConfig.enabled) {
            val fc = scene.facecamConfig
            val fcW = (fc.normalizedWidth * viewW).toInt()
            val fcH = (fc.normalizedHeight * viewH).toInt()
            val fcX = (fc.normalizedX * viewW).toInt()
            val fcY = (fc.normalizedY * viewH).toInt()

            val facecamView = TextView(context).apply {
                text = "📷 FACECAM (${if (fc.mirrorHorizontal) "FRONT" else "BACK"})"
                setTextColor(Color.WHITE)
                textSize = 10f
                gravity = android.view.Gravity.CENTER
                setBackgroundColor(Color.parseColor("#8033334D"))
                if (fc.isCircle) {
                    outlineProvider = object : android.view.ViewOutlineProvider() {
                        override fun getOutline(view: View, outline: android.graphics.Outline) {
                            outline.setOval(0, 0, view.width, view.height)
                        }
                    }
                    clipToOutline = true
                }
                layoutParams = LayoutParams(max(10, fcW), max(10, fcH)).apply {
                    leftMargin = fcX
                    topMargin = fcY
                }
                rotation = fc.rotationDegrees
            }
            addView(facecamView)
        }

        // 3. Overlay Elements (sorted by zIndex)
        val sorted = scene.overlayElements.sortedBy { it.zIndex }
        for (elem in sorted) {
            if (!elem.visible) continue
            val elemW = max(10, (elem.normalizedWidth * viewW).toInt())
            val elemH = if (elem.normalizedHeight > 0f) {
                max(10, (elem.normalizedHeight * viewH).toInt())
            } else {
                max(10, (elem.normalizedWidth * 0.4f * viewH).toInt())
            }
            val elemX = (elem.normalizedX * viewW).toInt()
            val elemY = (elem.normalizedY * viewH).toInt()

            val overlayView: View = when (elem.type) {
                OverlayType.TEXT -> {
                    val tv = TextView(context)
                    val cfg = elem.textConfig
                    tv.text = cfg?.text ?: "Text Overlay"
                    tv.setTextColor(cfg?.textColorRgba ?: Color.WHITE)
                    tv.textSize = (cfg?.fontSizeSp ?: 16f) * 0.65f
                    tv.setBackgroundColor(cfg?.backgroundColorRgba ?: Color.parseColor("#B4101420"))
                    val alignGravity = when (cfg?.alignment) {
                        "CENTER" -> android.view.Gravity.CENTER
                        "RIGHT" -> android.view.Gravity.END or android.view.Gravity.CENTER_VERTICAL
                        else -> android.view.Gravity.START or android.view.Gravity.CENTER_VERTICAL
                    }
                    tv.gravity = alignGravity
                    tv.setPadding(8, 4, 8, 4)
                    tv
                }
                OverlayType.GOAL -> {
                    val tv = TextView(context)
                    val cfg = elem.goalConfig
                    val title = cfg?.title ?: "Goal"
                    val cur = cfg?.currentVal ?: 0
                    val tgt = cfg?.targetVal ?: 100
                    tv.text = "★ $title: $cur / $tgt"
                    tv.setTextColor(Color.WHITE)
                    tv.textSize = 11f
                    tv.setBackgroundColor(Color.parseColor("#CC101420"))
                    tv.setPadding(8, 4, 8, 4)
                    tv
                }
                OverlayType.LOWER_THIRD -> {
                    val tv = TextView(context)
                    val cfg = elem.lowerThirdConfig
                    tv.text = "▌ ${cfg?.title ?: "Headline"}\n   ${cfg?.subtitle ?: "Subtitle"}"
                    tv.setTextColor(Color.WHITE)
                    tv.textSize = 11f
                    tv.setBackgroundColor(Color.parseColor("#D00C101A"))
                    tv.setPadding(8, 4, 8, 4)
                    tv
                }
                OverlayType.QR -> {
                    val iv = ImageView(context)
                    val payload = elem.qrConfig?.payloadUrl ?: "fairy.live"
                    try {
                        val qrMatrix = QrMatrixEncoder.encode(payload)
                        val qrBmp = Bitmap.createBitmap(qrMatrix.size, qrMatrix.size, Bitmap.Config.ARGB_8888)
                        for (y in 0 until qrMatrix.size) {
                            for (x in 0 until qrMatrix.size) {
                                qrBmp.setPixel(x, y, if (qrMatrix[y][x]) Color.BLACK else Color.WHITE)
                            }
                        }
                        iv.setImageBitmap(qrBmp)
                    } catch (_: Exception) {
                        iv.setBackgroundColor(Color.WHITE)
                    }
                    iv
                }
                OverlayType.IMAGE -> {
                    val iv = ImageView(context)
                    val path = elem.resourcePath
                    if (path != null && java.io.File(path).exists()) {
                        try {
                            val opts = android.graphics.BitmapFactory.Options().apply {
                                inSampleSize = 2
                            }
                            val bmp = android.graphics.BitmapFactory.decodeFile(path, opts)
                            if (bmp != null) {
                                iv.setImageBitmap(bmp)
                                iv.scaleType = ImageView.ScaleType.FIT_CENTER
                            } else {
                                iv.setImageResource(R.drawable.ic_stream)
                            }
                        } catch (_: Exception) {
                            iv.setImageResource(R.drawable.ic_stream)
                        }
                    } else {
                        iv.setImageResource(R.drawable.ic_stream)
                        iv.setBackgroundColor(Color.parseColor("#4000E5FF"))
                    }
                    if (elem.cornerRadiusPx > 0f) {
                        iv.outlineProvider = object : android.view.ViewOutlineProvider() {
                            override fun getOutline(view: View, outline: android.graphics.Outline) {
                                outline.setRoundRect(0, 0, view.width, view.height, elem.cornerRadiusPx)
                            }
                        }
                        iv.clipToOutline = true
                    }
                    iv
                }
                OverlayType.GIF -> {
                    val tv = TextView(context)
                    val speed = elem.gifConfig?.playbackSpeed ?: 1.0f
                    tv.text = "🎞️ GIF (${speed}x)"
                    tv.setTextColor(Color.parseColor("#00E5FF"))
                    tv.textSize = 11f
                    tv.gravity = android.view.Gravity.CENTER
                    tv.setBackgroundColor(Color.parseColor("#407C4DFF"))
                    tv
                }
                OverlayType.WIDGET -> {
                    val tv = TextView(context)
                    val cfg = elem.widgetConfig
                    tv.text = "⚡ ${cfg?.label ?: "STATS"}: ${cfg?.value ?: "LIVE"}"
                    tv.setTextColor(Color.parseColor("#00E5FF"))
                    tv.textSize = 10f
                    tv.setBackgroundColor(Color.parseColor("#CC0E0E14"))
                    tv.setPadding(6, 4, 6, 4)
                    tv
                }
                OverlayType.WEB -> {
                    val iv = ImageView(context)
                    val cfg = elem.webConfig ?: WebOverlayConfig()
                    val bmp = OffscreenWebRenderer.getInstance(context).getOrRenderBitmap(elem.id, cfg)
                    iv.setImageBitmap(bmp)
                    iv.scaleType = ImageView.ScaleType.FIT_CENTER
                    iv
                }
                OverlayType.YOUTUBE_SUBSCRIBER -> {
                    val iv = ImageView(context)
                    val cfg = elem.youtubeSubscriberConfig ?: YouTubeSubscriberConfig()
                    val bmp = com.fairylive.app.core.compositor.rasterizer.YouTubeSubscriberRasterizer.rasterize(cfg)
                    iv.setImageBitmap(bmp)
                    iv.scaleType = ImageView.ScaleType.FIT_CENTER
                    iv
                }
                OverlayType.ALERT -> {
                    val tv = TextView(context)
                    val cfg = elem.alertConfig
                    tv.text = "🔔 ${cfg?.headline ?: "ALERT"}"
                    tv.setTextColor(Color.parseColor("#FFD600"))
                    tv.textSize = 12f
                    tv.setBackgroundColor(Color.parseColor("#E0222234"))
                    tv.setPadding(10, 6, 10, 6)
                    tv
                }
                else -> {
                    val tv = TextView(context)
                    tv.text = elem.id
                    tv.setTextColor(Color.WHITE)
                    tv
                }
            }

            overlayView.alpha = elem.opacity
            overlayView.scaleX = elem.scale
            overlayView.scaleY = elem.scale
            overlayView.rotation = elem.rotationDegrees
            overlayView.layoutParams = LayoutParams(elemW, elemH).apply {
                leftMargin = elemX
                topMargin = elemY
            }
            addView(overlayView)
        }
    }

    var webInteractionMode: Boolean = false
        private set

    fun setWebInteractionMode(enabled: Boolean) {
        webInteractionMode = enabled
        invalidate()
    }

    fun getElementBaseBounds(scene: BroadcastScene, id: String, viewW: Float, viewH: Float): RectF? {
        if (id == "facecam") {
            val fc = scene.facecamConfig
            if (!fc.enabled) return null
            val l = fc.normalizedX * viewW
            val t = fc.normalizedY * viewH
            val r = l + (fc.normalizedWidth * viewW)
            val b = t + (fc.normalizedHeight * viewH)
            return RectF(l, t, r, b)
        }

        val elem = scene.overlayElements.firstOrNull { it.id == id } ?: return null
        val l = elem.normalizedX * viewW
        val t = elem.normalizedY * viewH
        val elemW = elem.normalizedWidth * viewW
        val elemH = if (elem.normalizedHeight > 0f) {
            elem.normalizedHeight * viewH
        } else {
            elem.normalizedWidth * 0.4f * viewH
        }
        return RectF(l, t, l + elemW, t + elemH)
    }

    fun getElementTransformedBounds(scene: BroadcastScene, id: String, viewW: Float, viewH: Float): RectF? {
        if (id == "facecam") {
            return getElementBaseBounds(scene, id, viewW, viewH)
        }
        val elem = scene.overlayElements.firstOrNull { it.id == id } ?: return null
        val base = getElementBaseBounds(scene, id, viewW, viewH) ?: return null
        val cx = base.centerX()
        val cy = base.centerY()
        val scaledW = base.width() * elem.scale
        val scaledH = base.height() * elem.scale
        return RectF(cx - scaledW / 2f, cy - scaledH / 2f, cx + scaledW / 2f, cy + scaledH / 2f)
    }

    private fun unrotatePoint(x: Float, y: Float, cx: Float, cy: Float, deg: Float): PointF {
        if (deg == 0f) return PointF(x, y)
        val rad = Math.toRadians(-deg.toDouble())
        val cos = Math.cos(rad).toFloat()
        val sin = Math.sin(rad).toFloat()
        val dx = x - cx
        val dy = y - cy
        return PointF(cx + (dx * cos - dy * sin), cy + (dx * sin + dy * cos))
    }

    private fun unrotateDelta(dx: Float, dy: Float, deg: Float): PointF {
        if (deg == 0f) return PointF(dx, dy)
        val rad = Math.toRadians(-deg.toDouble())
        val cos = Math.cos(rad).toFloat()
        val sin = Math.sin(rad).toFloat()
        return PointF(dx * cos - dy * sin, dx * sin + dy * cos)
    }

    override fun dispatchDraw(canvas: Canvas) {
        super.dispatchDraw(canvas)
        drawGuidelines(canvas)
        drawSelectionOverlay(canvas)
    }

    private fun drawGuidelines(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        snapGuideVerticalX?.let { gx ->
            canvas.drawLine(gx, 0f, gx, h, guidelinePaint)
        }
        snapGuideHorizontalY?.let { gy ->
            canvas.drawLine(0f, gy, w, gy, guidelinePaint)
        }
    }

    private fun drawSelectionOverlay(canvas: Canvas) {
        val scene = currentScene ?: return
        val selId = selectedId ?: return
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f) return

        val rect = getElementTransformedBounds(scene, selId, w, h) ?: return
        val elem = if (selId != "facecam") scene.overlayElements.firstOrNull { it.id == selId } else null
        val isLocked = elem?.locked == true
        val rotDegrees = elem?.rotationDegrees ?: (if (selId == "facecam") scene.facecamConfig.rotationDegrees else 0f)

        canvas.save()
        val centerX = rect.centerX()
        val centerY = rect.centerY()
        canvas.rotate(rotDegrees, centerX, centerY)

        // 1. Draw bounding rectangle
        canvas.drawRect(rect, selectionFillPaint)
        canvas.drawRect(rect, selectionPaint)

        // 2. Draw handles (only if unlocked)
        if (!isLocked) {
            val r = handleRadiusPx
            val corners = arrayOf(
                Pair(rect.left, rect.top),     // TL
                Pair(rect.right, rect.top),    // TR
                Pair(rect.right, rect.bottom), // BR
                Pair(rect.left, rect.bottom)   // BL
            )
            for ((cx, cy) in corners) {
                canvas.drawCircle(cx, cy, r, handlePaint)
                canvas.drawCircle(cx, cy, r, handleStrokePaint)
            }

            // 3. Draw rotation handle extending above the top edge
            val topCenterX = rect.centerX()
            val topCenterY = rect.top
            val rotHandleY = topCenterY - rotationStemHeightPx

            canvas.drawLine(topCenterX, topCenterY, topCenterX, rotHandleY, rotationStemPaint)
            canvas.drawCircle(topCenterX, rotHandleY, r * 1.2f, rotationHandlePaint)
            canvas.drawCircle(topCenterX, rotHandleY, r * 1.2f, handleStrokePaint)

            // Distinct 4-Handle Visual Crop Editor
            if (elem?.type == OverlayType.WEB) {
                val cropEdges = arrayOf(
                    Pair(rect.left, rect.centerY()),    // 0: Left
                    Pair(rect.centerX(), rect.top),       // 1: Top
                    Pair(rect.right, rect.centerY()),   // 2: Right
                    Pair(rect.centerX(), rect.bottom)    // 3: Bottom
                )
                val notchPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    color = Color.WHITE
                    strokeWidth = 2f * resources.displayMetrics.density
                    style = Paint.Style.STROKE
                }
                val nLen = r * 0.45f

                for (i in cropEdges.indices) {
                    val (ex, ey) = cropEdges[i]
                    canvas.drawCircle(ex, ey, r * 0.95f, cropHandlePaint)
                    canvas.drawCircle(ex, ey, r * 0.95f, cropHandleStrokePaint)

                    // Draw directional notch on crop handle
                    when (i) {
                        0, 2 -> canvas.drawLine(ex, ey - nLen, ex, ey + nLen, notchPaint)
                        1, 3 -> canvas.drawLine(ex - nLen, ey, ex + nLen, ey, notchPaint)
                    }
                }
            }
        }

        // 4. Element type badge with live gesture indicator
        val label = if (selId == "facecam") {
            "Camera"
        } else {
            val baseName = elem?.type?.name?.lowercase()?.replaceFirstChar { it.uppercase() } ?: "Overlay"
            val rotSuffix = if (rotDegrees != 0f) " • ${rotDegrees.roundToInt()}°" else ""
            val gestureSuffix = when (gestureState) {
                GestureState.DRAG -> " • ✋ Move"
                GestureState.ROTATE -> " • 🔄 Rotate"
                GestureState.RESIZE -> " • 📐 Resize"
                GestureState.CROP_LEFT, GestureState.CROP_TOP, GestureState.CROP_RIGHT, GestureState.CROP_BOTTOM -> " • ✂️ Crop"
                GestureState.SCALE_ROTATE -> " • 🤏 Pinch"
                GestureState.WEB_SCROLL -> " • 🖱️ Scroll"
                else -> if (elem?.type == OverlayType.WEB && webInteractionMode) " • 🖱️ Scroll" else ""
            }
            if (isLocked) "🔒 $baseName$rotSuffix" else "$baseName$rotSuffix$gestureSuffix"
        }

        val textWidth = labelPaint.measureText(label)
        val labelBg = RectF(rect.left, rect.top - 28f, rect.left + textWidth + 16f, rect.top - 4f)
        if (labelBg.top < 0f) {
            labelBg.offset(0f, 32f + rect.height())
        }
        canvas.drawRoundRect(labelBg, 4f, 4f, labelBgPaint)
        canvas.drawText(label, labelBg.left + 8f, labelBg.bottom - 6f, labelPaint)

        canvas.restore()
    }

    private fun getElementBounds(scene: BroadcastScene, id: String, viewW: Float, viewH: Float): RectF? =
        getElementTransformedBounds(scene, id, viewW, viewH)

    override fun onTouchEvent(event: MotionEvent): Boolean {
        val scene = currentScene ?: return super.onTouchEvent(event)
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f) return super.onTouchEvent(event)

        val pointerCount = event.pointerCount

        // P5 & P9: Two-finger arbitration for simultaneous pinch scale and rotation
        if (pointerCount == 2) {
            val selId = selectedId
            if (selId != null) {
                val elem = if (selId != "facecam") scene.overlayElements.firstOrNull { it.id == selId } else null
                if (elem?.locked != true) {
                    val p0x = event.getX(0)
                    val p0y = event.getY(0)
                    val p1x = event.getX(1)
                    val p1y = event.getY(1)

                    when (event.actionMasked) {
                        MotionEvent.ACTION_POINTER_DOWN -> {
                            gestureState = GestureState.SCALE_ROTATE
                            initialFingerDistance = hypot((p1x - p0x).toDouble(), (p1y - p0y).toDouble()).toFloat()
                            initialFingerAngle = Math.toDegrees(atan2((p1y - p0y).toDouble(), (p1x - p0x).toDouble())).toFloat()
                            initialElementScale = elem?.scale ?: 1f
                            initialElementRotation = elem?.rotationDegrees ?: (if (selId == "facecam") scene.facecamConfig.rotationDegrees else 0f)
                            parent?.requestDisallowInterceptTouchEvent(true)
                            return true
                        }
                        MotionEvent.ACTION_MOVE -> {
                            if (gestureState == GestureState.SCALE_ROTATE && initialFingerDistance > 10f) {
                                val currentDistance = hypot((p1x - p0x).toDouble(), (p1y - p0y).toDouble()).toFloat()
                                val currentAngle = Math.toDegrees(atan2((p1y - p0y).toDouble(), (p1x - p0x).toDouble())).toFloat()

                                val scaleFactor = currentDistance / initialFingerDistance
                                val newScale = (initialElementScale * scaleFactor).coerceIn(0.1f, 5.0f)

                                val deltaAngle = currentAngle - initialFingerAngle
                                val newRot = ((initialElementRotation + deltaAngle) % 360f + 360f) % 360f

                                if (selId != "facecam") {
                                    val overlay = elem ?: return true
                                    val updated = overlay.copy(scale = newScale, rotationDegrees = newRot)
                                    val updatedScene = scene.copy(
                                        overlayElements = scene.overlayElements.map { if (it.id == selId) updated else it }
                                    )
                                    setScene(updatedScene)
                                    interactionListener?.onElementRotated(selId, newRot)
                                }
                                invalidate()
                                return true
                            }
                        }
                        MotionEvent.ACTION_POINTER_UP -> {
                            gestureState = GestureState.IDLE
                        }
                    }
                }
            }
        }

        val touchX = event.x
        val touchY = event.y

        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                lastTouchX = touchX
                lastTouchY = touchY

                // Check hit on rotation handle or resize handles of selected element
                val selId = selectedId
                if (selId != null) {
                    val elem = if (selId != "facecam") scene.overlayElements.firstOrNull { it.id == selId } else null
                    val isLocked = elem?.locked == true
                    if (!isLocked) {
                        val rect = getElementTransformedBounds(scene, selId, w, h)
                        if (rect != null) {
                            val rot = elem?.rotationDegrees ?: (if (selId == "facecam") scene.facecamConfig.rotationDegrees else 0f)
                            val unrotated = unrotatePoint(touchX, touchY, rect.centerX(), rect.centerY(), rot)

                            // Check rotation handle hit in local element space
                            if (isHitRotationHandle(rect, unrotated.x, unrotated.y)) {
                                gestureState = GestureState.ROTATE
                                parent?.requestDisallowInterceptTouchEvent(true)
                                return true
                            }

                            // Check resize handle hit in local element space
                            val hitHandle = getHitHandle(rect, unrotated.x, unrotated.y)
                            if (hitHandle >= 0) {
                                activeCorner = hitHandle
                                gestureState = GestureState.RESIZE
                                parent?.requestDisallowInterceptTouchEvent(true)
                                return true
                            }

                            // Distinct Crop Edge Handle hits for Web Overlays
                            if (elem?.type == OverlayType.WEB) {
                                val hitCrop = getHitCropHandle(rect, unrotated.x, unrotated.y)
                                if (hitCrop >= 0) {
                                    gestureState = when (hitCrop) {
                                        0 -> GestureState.CROP_LEFT
                                        1 -> GestureState.CROP_TOP
                                        2 -> GestureState.CROP_RIGHT
                                        else -> GestureState.CROP_BOTTOM
                                    }
                                    parent?.requestDisallowInterceptTouchEvent(true)
                                    return true
                                }
                            }

                            // Check Web Overlay internal scroll hit only if webInteractionMode is enabled
                            if (elem?.type == OverlayType.WEB && webInteractionMode && rect.contains(unrotated.x, unrotated.y)) {
                                gestureState = GestureState.WEB_SCROLL
                                parent?.requestDisallowInterceptTouchEvent(true)
                                return true
                            }
                        }
                    }
                }

                // Hit-test elements
                val hitElement = hitTestElements(scene, touchX, touchY, w, h)
                if (hitElement != null) {
                    selectedId = hitElement
                    val hitElem = if (hitElement != "facecam") scene.overlayElements.firstOrNull { it.id == hitElement } else null
                    val isLocked = hitElem?.locked == true
                    gestureState = if (isLocked) GestureState.IDLE else GestureState.DRAG
                    interactionListener?.onElementSelected(hitElement)
                    parent?.requestDisallowInterceptTouchEvent(true)
                    invalidate()
                    return true
                } else {
                    selectedId = null
                    gestureState = GestureState.IDLE
                    interactionListener?.onElementSelected(null)
                    invalidate()
                    return false
                }
            }

            MotionEvent.ACTION_MOVE -> {
                val selId = selectedId ?: return false
                val elem = if (selId != "facecam") scene.overlayElements.firstOrNull { it.id == selId } else null
                if (elem?.locked == true) return false
                val dx = touchX - lastTouchX
                val dy = touchY - lastTouchY
                val deltaNormX = dx / w
                val deltaNormY = dy / h

                when (gestureState) {
                    GestureState.CROP_LEFT, GestureState.CROP_TOP, GestureState.CROP_RIGHT, GestureState.CROP_BOTTOM -> {
                        // Interactive Crop in local element coordinates honoring rotation
                        val rect = getElementTransformedBounds(scene, selId, w, h) ?: return false
                        val overlay = elem ?: return false
                        val cfg = overlay.webConfig ?: return false

                        val localDelta = unrotateDelta(dx, dy, overlay.rotationDegrees)
                        val rw = rect.width().coerceAtLeast(10f)
                        val rh = rect.height().coerceAtLeast(10f)

                        var cl = cfg.cropLeft
                        var ct = cfg.cropTop
                        var cr = cfg.cropRight
                        var cb = cfg.cropBottom

                        when (gestureState) {
                            GestureState.CROP_LEFT -> cl = (cl + localDelta.x / rw).coerceIn(0f, cr - 0.05f)
                            GestureState.CROP_TOP -> ct = (ct + localDelta.y / rh).coerceIn(0f, cb - 0.05f)
                            GestureState.CROP_RIGHT -> cr = (cr + localDelta.x / rw).coerceIn(cl + 0.05f, 1f)
                            GestureState.CROP_BOTTOM -> cb = (cb + localDelta.y / rh).coerceIn(ct + 0.05f, 1f)
                            else -> {}
                        }

                        val updatedCfg = cfg.copy(cropLeft = cl, cropTop = ct, cropRight = cr, cropBottom = cb)
                        val updatedElem = overlay.copy(webConfig = updatedCfg)
                        val updatedScene = scene.copy(
                            overlayElements = scene.overlayElements.map { if (it.id == selId) updatedElem else it }
                        )
                        setScene(updatedScene)
                        interactionListener?.onCropUpdated(selId, cl, ct, cr, cb)
                        lastTouchX = touchX
                        lastTouchY = touchY
                        invalidate()
                        return true
                    }

                    GestureState.ROTATE -> {
                        val rect = getElementBounds(scene, selId, w, h) ?: return false
                        val centerX = rect.centerX()
                        val centerY = rect.centerY()
                        val rad = atan2((touchY - centerY).toDouble(), (touchX - centerX).toDouble())
                        var deg = Math.toDegrees(rad).toFloat() + 90f // +90 because handle is at top
                        deg = ((deg % 360f) + 360f) % 360f

                        if (selId != "facecam") {
                            val overlay = elem ?: return false
                            val updated = overlay.copy(rotationDegrees = deg)
                            val updatedScene = scene.copy(
                                overlayElements = scene.overlayElements.map { if (it.id == selId) updated else it }
                            )
                            setScene(updatedScene)
                            interactionListener?.onElementRotated(selId, deg)
                        }
                        invalidate()
                        return true
                    }

                    GestureState.WEB_SCROLL -> {
                        // P1 & P10: Single finger drag inside webpage scrolls document, NOT the overlay
                        val overlay = elem ?: return false
                        val cfg = overlay.webConfig ?: WebOverlayConfig()
                        val scrollSpeedMultiplier = 2.0f
                        val newScrollX = (cfg.scrollX - (dx * scrollSpeedMultiplier).toInt()).coerceAtLeast(0)
                        val newScrollY = (cfg.scrollY - (dy * scrollSpeedMultiplier).toInt()).coerceAtLeast(0)

                        val updatedCfg = cfg.copy(scrollX = newScrollX, scrollY = newScrollY)
                        val updatedElem = overlay.copy(webConfig = updatedCfg)
                        val updatedScene = scene.copy(
                            overlayElements = scene.overlayElements.map { if (it.id == selId) updatedElem else it }
                        )
                        setScene(updatedScene)
                        OffscreenWebRenderer.getInstance(context).scrollWebPage(selId, updatedCfg, newScrollX, newScrollY)
                        interactionListener?.onWebScrolled(selId, newScrollX, newScrollY)
                        lastTouchX = touchX
                        lastTouchY = touchY
                        invalidate()
                        return true
                    }

                    GestureState.DRAG -> {
                        snapGuideVerticalX = null
                        snapGuideHorizontalY = null

                        val elemW: Float
                        val elemH: Float
                        val origX: Float
                        val origY: Float

                        if (selId == "facecam") {
                            val fc = scene.facecamConfig
                            elemW = fc.normalizedWidth
                            elemH = fc.normalizedHeight
                            origX = fc.normalizedX
                            origY = fc.normalizedY
                        } else {
                            val overlay = elem ?: return false
                            elemW = overlay.normalizedWidth
                            elemH = if (overlay.normalizedHeight > 0f) overlay.normalizedHeight else overlay.normalizedWidth * 0.4f
                            origX = overlay.normalizedX
                            origY = overlay.normalizedY
                        }

                        var newX = (origX + deltaNormX).coerceIn(0f, 1f - elemW)
                        var newY = (origY + deltaNormY).coerceIn(0f, 1f - elemH)

                        // Center & Margin Snapping
                        val centerX = newX + elemW / 2f
                        if (Math.abs(centerX - 0.5f) < 0.025f) {
                            newX = 0.5f - elemW / 2f
                            snapGuideVerticalX = 0.5f * w
                        } else if (Math.abs(newX - 0.05f) < 0.02f) {
                            newX = 0.05f
                            snapGuideVerticalX = 0.05f * w
                        } else if (Math.abs((newX + elemW) - 0.95f) < 0.02f) {
                            newX = 0.95f - elemW
                            snapGuideVerticalX = 0.95f * w
                        }

                        val centerY = newY + elemH / 2f
                        if (Math.abs(centerY - 0.5f) < 0.025f) {
                            newY = 0.5f - elemH / 2f
                            snapGuideHorizontalY = 0.5f * h
                        } else if (Math.abs(newY - 0.05f) < 0.02f) {
                            newY = 0.05f
                            snapGuideHorizontalY = 0.05f * h
                        } else if (Math.abs((newY + elemH) - 0.95f) < 0.02f) {
                            newY = 0.95f - elemH
                            snapGuideHorizontalY = 0.95f * h
                        }

                        if (selId == "facecam") {
                            interactionListener?.onFacecamTransformed(newX, newY, elemW, elemH)
                        } else {
                            interactionListener?.onElementTransformed(selId, newX, newY, elemW, elemH)
                        }
                        lastTouchX = touchX
                        lastTouchY = touchY
                        invalidate()
                        return true
                    }

                    GestureState.RESIZE -> {
                        if (selId == "facecam") {
                            val fc = scene.facecamConfig
                            val newW = (fc.normalizedWidth + deltaNormX).coerceIn(0.08f, 0.8f)
                            val newH = (fc.normalizedHeight + deltaNormY).coerceIn(0.08f, 0.8f)
                            interactionListener?.onFacecamTransformed(fc.normalizedX, fc.normalizedY, newW, newH)
                        } else {
                            val overlay = elem ?: return false
                            val currentH = if (overlay.normalizedHeight > 0f) overlay.normalizedHeight else overlay.normalizedWidth * 0.4f
                            val newW = (overlay.normalizedWidth + deltaNormX).coerceIn(0.05f, 0.95f)
                            val newH = (currentH + deltaNormY).coerceIn(0.03f, 0.95f)
                            interactionListener?.onElementTransformed(selId, overlay.normalizedX, overlay.normalizedY, newW, newH)
                        }
                        lastTouchX = touchX
                        lastTouchY = touchY
                        return true
                    }

                    else -> return false
                }
            }

            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                gestureState = GestureState.IDLE
                activeCorner = -1
                snapGuideVerticalX = null
                snapGuideHorizontalY = null
                parent?.requestDisallowInterceptTouchEvent(false)
                invalidate()
                return true
            }
        }

        return super.onTouchEvent(event)
    }

    private fun isHitRotationHandle(rect: RectF, x: Float, y: Float): Boolean {
        val topCenterX = rect.centerX()
        val rotHandleY = rect.top - rotationStemHeightPx
        val radius = handleTouchRadiusPx * 1.3f
        val distSq = (x - topCenterX) * (x - topCenterX) + (y - rotHandleY) * (y - rotHandleY)
        return distSq <= radius * radius
    }

    private fun getHitHandle(rect: RectF, x: Float, y: Float): Int {
        val radius = handleTouchRadiusPx
        val corners = arrayOf(
            Pair(rect.left, rect.top),     // 0: TL
            Pair(rect.right, rect.top),    // 1: TR
            Pair(rect.right, rect.bottom), // 2: BR
            Pair(rect.left, rect.bottom)   // 3: BL
        )
        for (i in corners.indices) {
            val (cx, cy) = corners[i]
            val distSq = (x - cx) * (x - cx) + (y - cy) * (y - cy)
            if (distSq <= radius * radius) {
                return i
            }
        }
        return -1
    }

    /**
     * Phase 7 §2: Interactive Crop Edge Handles.
     * 0: Left Edge, 1: Top Edge, 2: Right Edge, 3: Bottom Edge
     */
    private fun getHitCropHandle(rect: RectF, x: Float, y: Float): Int {
        val radius = handleTouchRadiusPx * 1.1f
        val edges = arrayOf(
            Pair(rect.left, rect.centerY()),    // 0: Left
            Pair(rect.centerX(), rect.top),       // 1: Top
            Pair(rect.right, rect.centerY()),   // 2: Right
            Pair(rect.centerX(), rect.bottom)    // 3: Bottom
        )
        for (i in edges.indices) {
            val (cx, cy) = edges[i]
            val distSq = (x - cx) * (x - cx) + (y - cy) * (y - cy)
            if (distSq <= radius * radius) {
                return i
            }
        }
        return -1
    }

    private fun hitTestElements(scene: BroadcastScene, x: Float, y: Float, viewW: Float, viewH: Float): String? {
        val sortedDesc = scene.overlayElements.sortedByDescending { it.zIndex }
        for (elem in sortedDesc) {
            if (!elem.visible) continue
            val rect = getElementTransformedBounds(scene, elem.id, viewW, viewH) ?: continue
            val unrotated = unrotatePoint(x, y, rect.centerX(), rect.centerY(), elem.rotationDegrees)
            if (rect.contains(unrotated.x, unrotated.y)) {
                return elem.id
            }
        }

        if (scene.facecamConfig.enabled) {
            val fcRect = getElementTransformedBounds(scene, "facecam", viewW, viewH)
            if (fcRect != null) {
                val unrotated = unrotatePoint(x, y, fcRect.centerX(), fcRect.centerY(), scene.facecamConfig.rotationDegrees)
                if (fcRect.contains(unrotated.x, unrotated.y)) {
                    return "facecam"
                }
            }
        }

        return null
    }
}
