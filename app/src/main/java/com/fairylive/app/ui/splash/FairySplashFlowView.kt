package com.fairylive.app.ui.splash

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.*
import android.util.AttributeSet
import android.view.View
import android.view.animation.LinearInterpolator
import androidx.core.content.ContextCompat
import com.fairylive.app.R
import kotlin.math.*

/**
 * FairySplashFlowView
 * High-performance, hardware-accelerated 60/120 FPS native splash animation view.
 * Eliminates all SurfaceView/VideoView decoder black flashes and frame-by-frame stutter.
 * Follows the Fairy Live 9-second logo drawing storyboard with silky-smooth parametric flow.
 */
class FairySplashFlowView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private var animator: ValueAnimator? = null
    var animationProgress: Float = 0f // 0.0f to 1.0f (0 to 9 seconds)
        private set

    var onAnimationCompleteListener: (() -> Unit)? = null

    // Master Bitmaps
    private var chromeBitmap: Bitmap? = null
    private var wireframeBitmap: Bitmap? = null

    // Offscreen rendering layer
    private var layerBitmap: Bitmap? = null
    private var layerCanvas: Canvas? = null

    // Paints
    private val backgroundPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFF130E22.toInt() // Band 4 Abyss Violet Canvas
        style = Paint.Style.FILL
    }

    private val arcPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFFF1EDF8.toInt()
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }

    private val arcGlowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0x808E7EB0.toInt()
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }

    private val penGlowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }

    private val sparklePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFFFFFFFF.toInt()
        style = Paint.Style.FILL
    }

    private val bitmapPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val maskPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        xfermode = PorterDuffXfermode(PorterDuff.Mode.DST_IN)
    }

    private val sweepBeamPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }

    // Geometry Cache
    private val logoRect = RectF()
    private val arcRect = RectF()
    private val pathMeasure = PathMeasure()
    private val tempPath = Path()

    init {
        // Load bitmaps
        loadEmblemBitmaps()
    }

    private fun loadEmblemBitmaps() {
        try {
            val opts = BitmapFactory.Options().apply {
                inPreferredConfig = Bitmap.Config.ARGB_8888
                inScaled = false
            }
            chromeBitmap = BitmapFactory.decodeResource(resources, R.drawable.fairy_logo_chrome, opts)
            wireframeBitmap = BitmapFactory.decodeResource(resources, R.drawable.fairy_logo_wireframe, opts)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        if (w <= 0 || h <= 0) return

        // Allocate offscreen double-buffer layer for ultra-crisp GPU masking
        layerBitmap?.recycle()
        layerBitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        layerCanvas = Canvas(layerBitmap!!)

        // Calculate responsive emblem destination rect (fit centered, preserving 1:1 ratio)
        val emblemSize = min(w * 0.78f, h * 0.78f)
        val left = (w - emblemSize) / 2f
        val top = (h - emblemSize) / 2f
        logoRect.set(left, top, left + emblemSize, top + emblemSize)

        // Arc geometry matching storyboard:
        // Center of arc is near (0.50, 0.49), radius is ~0.37 * emblemSize
        val arcCenter = PointF(logoRect.centerX(), logoRect.centerY())
        val arcR = emblemSize * 0.365f
        arcRect.set(arcCenter.x - arcR, arcCenter.y - arcR, arcCenter.x + arcR, arcCenter.y + arcR)

        arcPaint.strokeWidth = max(2.5f, emblemSize * 0.007f)
        arcGlowPaint.strokeWidth = max(7f, emblemSize * 0.022f)
    }

    fun startAnimation() {
        animator?.cancel()
        animator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 9000L // Exactly 9.0 seconds matching storyboard
            interpolator = LinearInterpolator()
            addUpdateListener { va ->
                animationProgress = va.animatedValue as Float
                invalidate()
            }
            addListener(object : android.animation.AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: android.animation.Animator) {
                    onAnimationCompleteListener?.invoke()
                }
            })
            start()
        }
    }

    fun pauseAnimation() {
        animator?.pause()
    }

    fun resumeAnimation() {
        animator?.resume()
    }

    fun stopAnimation() {
        animator?.cancel()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f) return

        // 1. Draw Deep Nocturnal Violet Base (#130E22)
        canvas.drawRect(0f, 0f, w, h, backgroundPaint)

        val t = animationProgress * 9.0f // Current timeline in seconds (0.0 to 9.0)
        val chrome = chromeBitmap ?: return
        val wire = wireframeBitmap ?: return

        val emblemCenter = PointF(logoRect.centerX(), logoRect.centerY())
        val emblemSize = logoRect.width()

        // Sparkle position relative to emblem (near top-right arc apex)
        val sparkleX = logoRect.left + emblemSize * 0.819f
        val sparkleY = logoRect.top + emblemSize * 0.225f

        when {
            // ====================================================================
            // 0.0 to 1.2 SECONDS: Arc Drawing & 4-Point Sparkle Ignition
            // ====================================================================
            t < 1.2f -> {
                val p = (t / 1.2f).coerceIn(0f, 1f)
                val easeP = smoothStep(p)

                // Arc sweep: starts around -40 degrees (right side of top arc) and sweeps clockwise to +95 degrees
                val startAngle = -40f
                val sweepAngle = 135f * easeP

                // Draw outer glow arc
                canvas.drawArc(arcRect, startAngle, sweepAngle, false, arcGlowPaint)
                // Draw sharp luminous arc
                canvas.drawArc(arcRect, startAngle, sweepAngle, false, arcPaint)

                // Current pen position at tip of arc
                val currentAngleRad = Math.toRadians((startAngle + sweepAngle).toDouble())
                val rx = (arcRect.width() / 2f)
                val ry = (arcRect.height() / 2f)
                val penX = (arcRect.centerX() + rx * cos(currentAngleRad)).toFloat()
                val penY = (arcRect.centerY() + ry * sin(currentAngleRad)).toFloat()

                // Draw luminous pen tip
                drawPenTip(canvas, penX, penY, emblemSize * 0.024f, 1f)

                // Sparkle ignition (triggers when stroke reaches apex, ~0.6s to 1.2s)
                if (t >= 0.55f) {
                    val sparkleProgress = ((t - 0.55f) / 0.65f).coerceIn(0f, 1f)
                    val sparkleScale = sin(sparkleProgress * Math.PI.toFloat()).coerceAtLeast(0f) * 1.2f
                    drawFourPointSparkle(canvas, sparkleX, sparkleY, emblemSize * 0.055f * sparkleScale, sparkleProgress)
                }
            }

            // ====================================================================
            // 1.2 to 3.0 SECONDS: Fairy Silhouette & Head Formation
            // ====================================================================
            t < 3.0f -> {
                val p = ((t - 1.2f) / 1.8f).coerceIn(0f, 1f)
                val easeP = smoothStep(p)

                // Draw base full arc
                canvas.drawArc(arcRect, -40f, 135f, false, arcGlowPaint)
                canvas.drawArc(arcRect, -40f, 135f, false, arcPaint)

                // Sparkle resting at apex
                drawFourPointSparkle(canvas, sparkleX, sparkleY, emblemSize * 0.045f, 1f)

                // Progressively reveal wireframe head and body top-to-bottom
                val lCanvas = layerCanvas
                val lBmp = layerBitmap
                if (lCanvas != null && lBmp != null) {
                    lBmp.eraseColor(Color.TRANSPARENT)

                    // Draw wireframe
                    bitmapPaint.alpha = 255
                    lCanvas.drawBitmap(wire, null, logoRect, bitmapPaint)

                    // Reveal mask (sweeping downward from top arc into body)
                    val maskTop = logoRect.top + emblemSize * 0.10f
                    val maskBottom = maskTop + (emblemSize * 0.65f) * easeP

                    val maskGradient = LinearGradient(
                        0f, maskBottom - emblemSize * 0.08f,
                        0f, maskBottom,
                        intArrayOf(Color.BLACK, Color.TRANSPARENT),
                        floatArrayOf(0f, 1f),
                        Shader.TileMode.CLAMP
                    )
                    maskPaint.shader = maskGradient
                    lCanvas.drawRect(logoRect.left, 0f, logoRect.right, maskBottom, maskPaint)
                    maskPaint.shader = null

                    canvas.drawBitmap(lBmp, 0f, 0f, null)

                    // Pen tip tracing head/body contour
                    val penY = maskBottom
                    val penX = emblemCenter.x - emblemSize * 0.04f * sin(easeP * Math.PI.toFloat())
                    drawPenTip(canvas, penX, penY, emblemSize * 0.020f, (1f - p * 0.2f))
                }
            }

            // ====================================================================
            // 3.0 to 4.8 SECONDS: Unfolding Majestic Wings & Wireframe Completion
            // ====================================================================
            t < 4.8f -> {
                val p = ((t - 3.0f) / 1.8f).coerceIn(0f, 1f)
                val easeP = smoothStep(p)

                // Sparkle at apex
                drawFourPointSparkle(canvas, sparkleX, sparkleY, emblemSize * 0.045f, 1f)

                val lCanvas = layerCanvas
                val lBmp = layerBitmap
                if (lCanvas != null && lBmp != null) {
                    lBmp.eraseColor(Color.TRANSPARENT)

                    // Draw wireframe with luminous intensity
                    bitmapPaint.alpha = 255
                    lCanvas.drawBitmap(wire, null, logoRect, bitmapPaint)

                    // Wing reveal: radiates outwards and downwards from center spine
                    val revealRadius = emblemSize * (0.25f + 0.65f * easeP)
                    val radialMask = RadialGradient(
                        emblemCenter.x - emblemSize * 0.05f, emblemCenter.y - emblemSize * 0.05f,
                        revealRadius,
                        intArrayOf(Color.BLACK, Color.BLACK, Color.TRANSPARENT),
                        floatArrayOf(0f, 0.75f, 1.0f),
                        Shader.TileMode.CLAMP
                    )
                    maskPaint.shader = radialMask
                    lCanvas.drawRect(0f, 0f, w, h, maskPaint)
                    maskPaint.shader = null

                    canvas.drawBitmap(lBmp, 0f, 0f, null)

                    // Pen tip at outer wing blade
                    val wingTipX = emblemCenter.x - revealRadius * 0.65f
                    val wingTipY = emblemCenter.y - revealRadius * 0.55f
                    if (p < 0.92f) {
                        drawPenTip(canvas, wingTipX, wingTipY, emblemSize * 0.022f, 1f)
                    }
                }
            }

            // ====================================================================
            // 4.8 to 6.2 SECONDS: Specular Liquid Chrome Metallization Wave
            // ====================================================================
            t < 6.2f -> {
                val p = ((t - 4.8f) / 1.4f).coerceIn(0f, 1f)
                val easeP = smoothStep(p)

                val lCanvas = layerCanvas
                val lBmp = layerBitmap
                if (lCanvas != null && lBmp != null) {
                    // Step 1: Draw wireframe base
                    bitmapPaint.alpha = (255 * (1f - easeP * 0.85f)).toInt()
                    canvas.drawBitmap(wire, null, logoRect, bitmapPaint)

                    // Step 2: Render Chrome emblem revealed by diagonal sweep wave
                    lBmp.eraseColor(Color.TRANSPARENT)
                    bitmapPaint.alpha = 255
                    lCanvas.drawBitmap(chrome, null, logoRect, bitmapPaint)

                    // Diagonal sweep line from top-left to bottom-right
                    val sweepSpan = emblemSize * 1.6f
                    val sweepProgress = -emblemSize * 0.3f + sweepSpan * easeP
                    val sweepStartX = logoRect.left + sweepProgress
                    val sweepStartY = logoRect.top

                    val diagonalMask = LinearGradient(
                        sweepStartX - emblemSize * 0.15f, sweepStartY - emblemSize * 0.15f,
                        sweepStartX, sweepStartY,
                        intArrayOf(Color.BLACK, Color.TRANSPARENT),
                        floatArrayOf(0f, 1f),
                        Shader.TileMode.CLAMP
                    )
                    maskPaint.shader = diagonalMask
                    lCanvas.drawRect(0f, 0f, w, h, maskPaint)
                    maskPaint.shader = null

                    canvas.drawBitmap(lBmp, 0f, 0f, null)

                    // Step 3: Draw bright specular light wave beam across the transition line
                    val beamX = sweepStartX
                    val beamY = logoRect.centerY()
                    drawSpecularSweepBeam(canvas, beamX, beamY, emblemSize * 0.12f, emblemSize * 0.9f)
                }

                // Sparkle at apex
                drawFourPointSparkle(canvas, sparkleX, sparkleY, emblemSize * 0.050f, 1f)
            }

            // ====================================================================
            // 6.2 to 7.5 SECONDS: Polished Chrome Centered Resting State & Shimmer
            // ====================================================================
            t < 7.5f -> {
                val p = ((t - 6.2f) / 1.3f).coerceIn(0f, 1f)

                // Subtle breathing scale (1.000 -> 1.015 -> 1.000)
                val breathScale = 1.0f + 0.014f * sin(p * Math.PI.toFloat())

                canvas.save()
                canvas.scale(breathScale, breathScale, emblemCenter.x, emblemCenter.y)

                bitmapPaint.alpha = 255
                canvas.drawBitmap(chrome, null, logoRect, bitmapPaint)

                // Gentle twinkle on sparkle
                val twinkle = 1.0f + 0.35f * sin(p * 3.0f * Math.PI.toFloat())
                drawFourPointSparkle(canvas, sparkleX, sparkleY, emblemSize * 0.052f * twinkle, 1f)

                canvas.restore()
            }

            // ====================================================================
            // 7.5 to 9.0 SECONDS: Ethereal Luminous Dissolution / Seamless Fade
            // ====================================================================
            else -> {
                val p = ((t - 7.5f) / 1.5f).coerceIn(0f, 1f)
                val easeP = smoothStep(p)

                // Chrome dissolves into wireframe, then wireframe softly fades to #130E22
                if (easeP < 0.5f) {
                    val subP = easeP / 0.5f
                    // Cross fade chrome to wireframe
                    bitmapPaint.alpha = (255 * (1f - subP)).toInt()
                    canvas.drawBitmap(chrome, null, logoRect, bitmapPaint)

                    bitmapPaint.alpha = 255
                    canvas.drawBitmap(wire, null, logoRect, bitmapPaint)

                    drawFourPointSparkle(canvas, sparkleX, sparkleY, emblemSize * 0.045f * (1f - subP * 0.5f), 1f - subP * 0.3f)
                } else {
                    val subP = (easeP - 0.5f) / 0.5f
                    val wireAlpha = (255 * (1f - subP)).toInt().coerceIn(0, 255)

                    bitmapPaint.alpha = wireAlpha
                    canvas.drawBitmap(wire, null, logoRect, bitmapPaint)

                    // Arc contracts and dissolves to pure canvas
                    val remainingAngle = 135f * (1f - subP)
                    arcPaint.alpha = wireAlpha
                    arcGlowPaint.alpha = (wireAlpha * 0.5f).toInt()
                    canvas.drawArc(arcRect, -40f, remainingAngle, false, arcGlowPaint)
                    canvas.drawArc(arcRect, -40f, remainingAngle, false, arcPaint)

                    drawFourPointSparkle(canvas, sparkleX, sparkleY, emblemSize * 0.035f * (1f - subP), 1f - subP)
                }
            }
        }
    }

    /**
     * Draws the magical stylus light pen tip with soft concentric radiant bloom.
     */
    private fun drawPenTip(canvas: Canvas, x: Float, y: Float, radius: Float, alphaFactor: Float) {
        val a = (255 * alphaFactor).toInt().coerceIn(0, 255)
        if (a <= 0) return

        // Outer violet atmospheric halo (#685B82 / #8E7EB0)
        penGlowPaint.color = (a shl 24) or (0x008E7EB0 and 0x00FFFFFF)
        canvas.drawCircle(x, y, radius * 2.8f, penGlowPaint)

        // Middle soft lavender corona
        penGlowPaint.color = (min(255, (a * 1.2f).toInt()) shl 24) or (0x00F1EDF8 and 0x00FFFFFF)
        canvas.drawCircle(x, y, radius * 1.5f, penGlowPaint)

        // Core bright white light point
        penGlowPaint.color = (a shl 24) or 0x00FFFFFF
        canvas.drawCircle(x, y, radius * 0.7f, penGlowPaint)
    }

    /**
     * Draws the iconic four-pointed sparkle with specular cross-rays and radiant center.
     */
    private fun drawFourPointSparkle(canvas: Canvas, cx: Float, cy: Float, size: Float, alphaFactor: Float) {
        val a = (255 * alphaFactor).toInt().coerceIn(0, 255)
        if (a <= 0 || size <= 0f) return

        sparklePaint.color = (a shl 24) or 0x00FFFFFF

        tempPath.reset()
        // Horizontal and vertical sharp diamond rays
        val rayLong = size * 1.2f
        val rayThin = size * 0.18f

        // 4-pointed star polygon
        tempPath.moveTo(cx, cy - rayLong)
        tempPath.lineTo(cx + rayThin, cy - rayThin)
        tempPath.lineTo(cx + rayLong, cy)
        tempPath.lineTo(cx + rayThin, cy + rayThin)
        tempPath.lineTo(cx, cy + rayLong)
        tempPath.lineTo(cx - rayThin, cy + rayThin)
        tempPath.lineTo(cx - rayLong, cy)
        tempPath.lineTo(cx - rayThin, cy - rayThin)
        tempPath.close()

        canvas.drawPath(tempPath, sparklePaint)

        // Soft center glow
        penGlowPaint.color = ((a * 0.4f).toInt() shl 24) or (0x009F8EC7 and 0x00FFFFFF)
        canvas.drawCircle(cx, cy, size * 0.8f, penGlowPaint)
    }

    /**
     * Draws the sweeping specular chrome reflection beam across the logo.
     */
    private fun drawSpecularSweepBeam(canvas: Canvas, cx: Float, cy: Float, width: Float, height: Float) {
        canvas.save()
        canvas.rotate(45f, cx, cy)

        val sweepShader = LinearGradient(
            cx - width / 2f, cy,
            cx + width / 2f, cy,
            intArrayOf(0x00FFFFFF, 0xD9FFFFFF.toInt(), 0x00FFFFFF),
            floatArrayOf(0f, 0.5f, 1f),
            Shader.TileMode.CLAMP
        )
        sweepBeamPaint.shader = sweepShader
        canvas.drawRect(cx - width / 2f, cy - height / 2f, cx + width / 2f, cy + height / 2f, sweepBeamPaint)
        sweepBeamPaint.shader = null

        canvas.restore()
    }

    private fun smoothStep(x: Float): Float {
        val clamped = x.coerceIn(0f, 1f)
        return clamped * clamped * (3f - 2f * clamped)
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        stopAnimation()
        layerBitmap?.recycle()
        layerBitmap = null
        layerCanvas = null
    }
}
