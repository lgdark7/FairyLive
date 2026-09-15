package com.fairylive.app.ui.studio

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
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
import com.fairylive.app.core.compositor.model.OverlayElement
import com.fairylive.app.core.compositor.model.OverlayType
import com.fairylive.app.core.compositor.rasterizer.QrMatrixEncoder
import com.fairylive.app.core.scene.model.BroadcastScene
import com.fairylive.app.core.scene.model.SceneBackgroundMode
import kotlin.math.max
import kotlin.math.min

/**
 * High-fidelity, touch-interactive Live Preview View for the Creator Studio.
 * Renders the shared scene model WYSIWYG without burning studio UI controls into the broadcast.
 * Handles hit-testing, dragging, and resizing with normalized coordinates [0.0..1.0].
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
    }

    var interactionListener: InteractionListener? = null

    private var currentScene: BroadcastScene? = null
    private var selectedId: String? = null

    // Transform touch tracking
    private var touchMode = TouchMode.NONE
    private var lastTouchX = 0f
    private var lastTouchY = 0f
    private var activeCorner = -1 // 0: TL, 1: TR, 2: BR, 3: BL

    private val selectionPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 3f * resources.displayMetrics.density
        color = Color.parseColor("#00E5FF")
    }

    private val selectionFillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Color.parseColor("#1A00E5FF")
    }

    private val handlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Color.parseColor("#00E5FF")
    }

    private val handleStrokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 2f * resources.displayMetrics.density
        color = Color.WHITE
    }

    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textSize = 12f * resources.displayMetrics.scaledDensity
        typeface = Typeface.DEFAULT_BOLD
    }

    private val labelBgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#CC0E0E14")
        style = Paint.Style.FILL
    }

    private val HANDLE_RADIUS_DP = 10f
    private val handleRadiusPx: Float
        get() = HANDLE_RADIUS_DP * resources.displayMetrics.density

    private enum class TouchMode {
        NONE, DRAG, RESIZE
    }

    init {
        setWillNotDraw(false)
        setBackgroundColor(Color.parseColor("#0E0E14"))
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val width = MeasureSpec.getSize(widthMeasureSpec)
        val height = MeasureSpec.getSize(heightMeasureSpec)

        // Strict 16:9 aspect ratio letterbox/pillarbox
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
                layoutParams = LayoutParams(max(10, fcW), max(10, fcH)).apply {
                    leftMargin = fcX
                    topMargin = fcY
                }
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
                    tv.textSize = 12f
                    tv.setBackgroundColor(cfg?.backgroundColorRgba ?: Color.parseColor("#B4101420"))
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
                    iv.setImageResource(R.drawable.ic_stream)
                    iv.setBackgroundColor(Color.parseColor("#4000E5FF"))
                    iv
                }
                OverlayType.GIF -> {
                    val tv = TextView(context)
                    tv.text = "🎞️ GIF"
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
            overlayView.layoutParams = LayoutParams(elemW, elemH).apply {
                leftMargin = elemX
                topMargin = elemY
            }
            addView(overlayView)
        }
    }

    override fun dispatchDraw(canvas: Canvas) {
        super.dispatchDraw(canvas)
        drawSelectionOverlay(canvas)
    }

    private fun drawSelectionOverlay(canvas: Canvas) {
        val scene = currentScene ?: return
        val selId = selectedId ?: return
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f) return

        val rect = getElementBounds(scene, selId, w, h) ?: return

        // 1. Draw bounding rectangle
        canvas.drawRect(rect, selectionFillPaint)
        canvas.drawRect(rect, selectionPaint)

        // 2. Draw 4 corner resize handles
        val r = handleRadiusPx
        val corners = arrayOf(
            Pair(rect.left, rect.top),
            Pair(rect.right, rect.top),
            Pair(rect.right, rect.bottom),
            Pair(rect.left, rect.bottom)
        )
        for ((cx, cy) in corners) {
            canvas.drawCircle(cx, cy, r, handlePaint)
            canvas.drawCircle(cx, cy, r, handleStrokePaint)
        }

        // 3. Draw coordinate and name label
        val label = if (selId == "facecam") {
            "📷 FACECAM (x: ${(rect.left / w * 100).toInt()}%, y: ${(rect.top / h * 100).toInt()}%)"
        } else {
            val elem = scene.overlayElements.firstOrNull { it.id == selId }
            val typeName = elem?.type?.name ?: "OVERLAY"
            "$typeName (w: ${(rect.width() / w * 100).toInt()}%, h: ${(rect.height() / h * 100).toInt()}%)"
        }

        val textWidth = labelPaint.measureText(label)
        val labelBg = RectF(rect.left, rect.top - 36f, rect.left + textWidth + 24f, rect.top - 4f)
        if (labelBg.top < 0f) {
            labelBg.offset(0f, 40f + rect.height())
        }
        canvas.drawRoundRect(labelBg, 6f, 6f, labelBgPaint)
        canvas.drawText(label, labelBg.left + 12f, labelBg.bottom - 8f, labelPaint)
    }

    private fun getElementBounds(scene: BroadcastScene, id: String, viewW: Float, viewH: Float): RectF? {
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

    override fun onTouchEvent(event: MotionEvent): Boolean {
        val scene = currentScene ?: return super.onTouchEvent(event)
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f) return super.onTouchEvent(event)

        val touchX = event.x
        val touchY = event.y

        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                lastTouchX = touchX
                lastTouchY = touchY

                // Check if touching a resize handle of current selection
                val selId = selectedId
                if (selId != null) {
                    val rect = getElementBounds(scene, selId, w, h)
                    if (rect != null) {
                        val hitHandle = getHitHandle(rect, touchX, touchY)
                        if (hitHandle >= 0) {
                            activeCorner = hitHandle
                            touchMode = TouchMode.RESIZE
                            parent?.requestDisallowInterceptTouchEvent(true)
                            return true
                        }
                    }
                }

                // Hit-test elements (top-most z-index first)
                val hitElement = hitTestElements(scene, touchX, touchY, w, h)
                if (hitElement != null) {
                    selectedId = hitElement
                    touchMode = TouchMode.DRAG
                    interactionListener?.onElementSelected(hitElement)
                    parent?.requestDisallowInterceptTouchEvent(true)
                    invalidate()
                    return true
                } else {
                    // Tap outside deselects
                    selectedId = null
                    touchMode = TouchMode.NONE
                    interactionListener?.onElementSelected(null)
                    invalidate()
                    return false
                }
            }

            MotionEvent.ACTION_MOVE -> {
                val selId = selectedId ?: return false
                val dx = touchX - lastTouchX
                val dy = touchY - lastTouchY
                val deltaNormX = dx / w
                val deltaNormY = dy / h

                when (touchMode) {
                    TouchMode.DRAG -> {
                        if (selId == "facecam") {
                            val fc = scene.facecamConfig
                            val newX = (fc.normalizedX + deltaNormX).coerceIn(0f, 1f - fc.normalizedWidth)
                            val newY = (fc.normalizedY + deltaNormY).coerceIn(0f, 1f - fc.normalizedHeight)
                            interactionListener?.onFacecamTransformed(newX, newY, fc.normalizedWidth, fc.normalizedHeight)
                        } else {
                            val elem = scene.overlayElements.firstOrNull { it.id == selId } ?: return false
                            val newX = (elem.normalizedX + deltaNormX).coerceIn(0f, 1f - elem.normalizedWidth)
                            val elemHNorm = if (elem.normalizedHeight > 0f) elem.normalizedHeight else elem.normalizedWidth * 0.4f
                            val newY = (elem.normalizedY + deltaNormY).coerceIn(0f, 1f - elemHNorm)
                            interactionListener?.onElementTransformed(selId, newX, newY, elem.normalizedWidth, elemHNorm)
                        }
                        lastTouchX = touchX
                        lastTouchY = touchY
                        return true
                    }

                    TouchMode.RESIZE -> {
                        if (selId == "facecam") {
                            val fc = scene.facecamConfig
                            val newW = (fc.normalizedWidth + deltaNormX).coerceIn(0.08f, 0.8f)
                            val newH = (fc.normalizedHeight + deltaNormY).coerceIn(0.08f, 0.8f)
                            interactionListener?.onFacecamTransformed(fc.normalizedX, fc.normalizedY, newW, newH)
                        } else {
                            val elem = scene.overlayElements.firstOrNull { it.id == selId } ?: return false
                            val currentH = if (elem.normalizedHeight > 0f) elem.normalizedHeight else elem.normalizedWidth * 0.4f
                            val newW = (elem.normalizedWidth + deltaNormX).coerceIn(0.05f, 0.95f)
                            val newH = (currentH + deltaNormY).coerceIn(0.03f, 0.95f)
                            interactionListener?.onElementTransformed(selId, elem.normalizedX, elem.normalizedY, newW, newH)
                        }
                        lastTouchX = touchX
                        lastTouchY = touchY
                        return true
                    }

                    else -> return false
                }
            }

            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                touchMode = TouchMode.NONE
                activeCorner = -1
                parent?.requestDisallowInterceptTouchEvent(false)
                return true
            }
        }

        return super.onTouchEvent(event)
    }

    private fun getHitHandle(rect: RectF, x: Float, y: Float): Int {
        val radius = handleRadiusPx * 1.5f
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

    private fun hitTestElements(scene: BroadcastScene, x: Float, y: Float, viewW: Float, viewH: Float): String? {
        // 1. Check overlays in reverse z-index order (top-most first)
        val sortedDesc = scene.overlayElements.sortedByDescending { it.zIndex }
        for (elem in sortedDesc) {
            if (!elem.visible) continue
            val rect = getElementBounds(scene, elem.id, viewW, viewH) ?: continue
            if (rect.contains(x, y)) {
                return elem.id
            }
        }

        // 2. Check facecam
        if (scene.facecamConfig.enabled) {
            val fcRect = getElementBounds(scene, "facecam", viewW, viewH)
            if (fcRect != null && fcRect.contains(x, y)) {
                return "facecam"
            }
        }

        return null
    }
}
