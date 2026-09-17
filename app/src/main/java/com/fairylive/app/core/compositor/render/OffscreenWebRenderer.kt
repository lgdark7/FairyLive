package com.fairylive.app.core.compositor.render

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.os.Handler
import android.os.Looper
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import com.fairylive.app.core.compositor.model.MaskShape
import com.fairylive.app.core.compositor.model.WebOverlayConfig
import java.util.concurrent.ConcurrentHashMap

/**
 * Real Offscreen Web Renderer for RenderDomain.BROADCAST.
 * Renders actual web content into ARGB_8888 Bitmaps using an offscreen WebView.
 * Supports document viewport scrolling, non-destructive cropping, and memory-safe caching.
 * Thread-safe: UI interaction happens on Looper.getMainLooper(), Bitmap cache accessed from GL/Render thread.
 */
class OffscreenWebRenderer private constructor(private val context: Context) {

    companion object {
        const val DEFAULT_WIDTH = 800
        const val DEFAULT_HEIGHT = 450
        @Volatile
        private var instance: OffscreenWebRenderer? = null

        fun getInstance(context: Context): OffscreenWebRenderer {
            return instance ?: synchronized(this) {
                instance ?: OffscreenWebRenderer(context.applicationContext).also { instance = it }
            }
        }
    }

    private val mainHandler = Handler(Looper.getMainLooper())
    private val webViews = ConcurrentHashMap<String, WebView>()
    private val rawBitmapCache = ConcurrentHashMap<String, Bitmap>()
    private val processedBitmapCache = ConcurrentHashMap<String, Bitmap>()
    private val activeUrls = ConcurrentHashMap<String, String>()
    private val activeScrolls = ConcurrentHashMap<String, Pair<Int, Int>>()
    private val activeCrops = ConcurrentHashMap<String, String>()
    private val isLoading = ConcurrentHashMap<String, Boolean>()
    private val loadError = ConcurrentHashMap<String, String>()

    /**
     * Called from GlesRenderer / BroadcastPreviewView to get the current rendered bitmap of the webpage.
     */
    fun getOrRenderBitmap(id: String, config: WebOverlayConfig): Bitmap {
        val targetUrl = config.url.trim()
        val cached = processedBitmapCache[id]
        val currentUrl = activeUrls[id]
        val currentScroll = activeScrolls[id] ?: Pair(0, 0)
        val targetScroll = Pair(config.scrollX, config.scrollY)
        val cropKey = "${config.cropLeft}_${config.cropTop}_${config.cropRight}_${config.cropBottom}"
        val currentCropKey = activeCrops[id]

        if (cached != null && currentUrl == targetUrl && currentScroll == targetScroll && currentCropKey == cropKey && loadError[id] == null) {
            return cached
        }

        if (currentUrl != targetUrl) {
            activeUrls[id] = targetUrl
            activeScrolls[id] = targetScroll
            activeCrops[id] = cropKey
            mainHandler.post {
                loadWebPage(id, config)
            }
        } else if (currentScroll != targetScroll) {
            activeScrolls[id] = targetScroll
            mainHandler.post {
                val webView = webViews[id]
                if (webView != null) {
                    webView.scrollTo(targetScroll.first, targetScroll.second)
                    captureWebView(id, webView, config)
                }
            }
        } else if (currentCropKey != cropKey) {
            activeCrops[id] = cropKey
            val raw = rawBitmapCache[id]
            if (raw != null) {
                val cropped = applyCrop(raw, config)
                processedBitmapCache[id] = cropped
                return cropped
            }
        }

        // Return current bitmap, or loading/fallback placeholder if still loading
        return cached ?: createStatusPlaceholder(config, isLoading = true)
    }

    /**
     * Internal scroll in the offscreen WebView document without moving overlay on canvas.
     */
    fun scrollWebPage(id: String, config: WebOverlayConfig, scrollX: Int, scrollY: Int) {
        val targetScroll = Pair(scrollX, scrollY)
        activeScrolls[id] = targetScroll
        mainHandler.post {
            val webView = webViews[id]
            if (webView != null) {
                webView.scrollTo(scrollX, scrollY)
                captureWebView(id, webView, config.copy(scrollX = scrollX, scrollY = scrollY))
            }
        }
    }

    /**
     * Manually triggers or forces a reload of the web overlay.
     */
    fun reload(id: String, config: WebOverlayConfig) {
        mainHandler.post {
            loadWebPage(id, config, force = true)
        }
    }

    private fun loadWebPage(id: String, config: WebOverlayConfig, force: Boolean = false) {
        val url = config.url.trim()
        if (url.isEmpty()) {
            val ph = createStatusPlaceholder(config, error = "Empty URL")
            rawBitmapCache[id] = ph
            processedBitmapCache[id] = ph
            return
        }

        // Phase 5 §2: URL validation — only accept http:// and https://
        if (!isValidUrl(url)) {
            val ph = createStatusPlaceholder(config, error = "Invalid URL (must start with http:// or https://)")
            loadError[id] = "Invalid URL"
            rawBitmapCache[id] = ph
            processedBitmapCache[id] = ph
            return
        }

        var webView = webViews[id]
        if (webView == null || force) {
            webView?.stopLoading()
            webView?.destroy()

            try {
                webView = WebView(context).apply {
                    val w = if (config.customWidth > 0) config.customWidth else DEFAULT_WIDTH
                    val h = if (config.customHeight > 0) config.customHeight else DEFAULT_HEIGHT
                    layout(0, 0, w, h)

                    if (config.transparentBackground) {
                        setBackgroundColor(Color.TRANSPARENT)
                    } else {
                        setBackgroundColor(Color.WHITE)
                    }

                    settings.javaScriptEnabled = true
                    settings.domStorageEnabled = true
                    settings.useWideViewPort = true
                    settings.loadWithOverviewMode = true
                    settings.cacheMode = WebSettings.LOAD_DEFAULT
                    // Phase 6 §4: Web zoom (0.25x–5.0x, independent of overlay scale)
                    val zoomPct = (config.zoomLevel.coerceIn(0.25f, 5.0f) * 100).toInt()
                    settings.textZoom = zoomPct
                    if (zoomPct != 100) setInitialScale(zoomPct)

                    webViewClient = object : WebViewClient() {
                        override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                            isLoading[id] = true
                            loadError.remove(id)
                        }

                        override fun onPageFinished(view: WebView?, url: String?) {
                            isLoading[id] = false
                            loadError.remove(id)
                            view?.scrollTo(config.scrollX, config.scrollY)
                            captureWebView(id, this@apply, config)
                        }

                        override fun onReceivedError(
                            view: WebView?,
                            request: WebResourceRequest?,
                            error: WebResourceError?
                        ) {
                            if (request?.isForMainFrame == true) {
                                isLoading[id] = false
                                val errMsg = error?.description?.toString() ?: "Network Error"
                                loadError[id] = errMsg
                                val errPh = createStatusPlaceholder(config, error = errMsg)
                                rawBitmapCache[id] = errPh
                                processedBitmapCache[id] = errPh
                            }
                        }
                    }
                }
                webViews[id] = webView
            } catch (e: Exception) {
                loadError[id] = e.message ?: "Failed to initialize WebView"
                val errPh = createStatusPlaceholder(config, error = loadError[id])
                rawBitmapCache[id] = errPh
                processedBitmapCache[id] = errPh
                return
            }
        }

        isLoading[id] = true
        webView.loadUrl(url)
    }

    private fun captureWebView(id: String, webView: WebView, config: WebOverlayConfig) {
        try {
            val w = if (config.customWidth > 0) config.customWidth else DEFAULT_WIDTH
            val h = if (config.customHeight > 0) config.customHeight else DEFAULT_HEIGHT
            val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(bmp)

            if (config.transparentBackground) {
                canvas.drawColor(Color.TRANSPARENT)
            } else {
                canvas.drawColor(Color.WHITE)
            }

            webView.draw(canvas)
            rawBitmapCache[id] = bmp
            processedBitmapCache[id] = applyCrop(bmp, config)
            activeCrops[id] = "${config.cropLeft}_${config.cropTop}_${config.cropRight}_${config.cropBottom}"
        } catch (e: Exception) {
            val errPh = createStatusPlaceholder(config, error = "Capture failed: ${e.message}")
            rawBitmapCache[id] = errPh
            processedBitmapCache[id] = errPh
        }
    }

    /**
     * Non-destructively crops the raw web bitmap according to normalized crop bounds.
     */
    private fun applyCrop(source: Bitmap, config: WebOverlayConfig): Bitmap {
        val cL = config.cropLeft.coerceIn(0f, 0.95f)
        val cT = config.cropTop.coerceIn(0f, 0.95f)
        val cR = config.cropRight.coerceIn(cL + 0.05f, 1f)
        val cB = config.cropBottom.coerceIn(cT + 0.05f, 1f)

        val srcW = source.width
        val srcH = source.height
        val cropX = (cL * srcW).toInt().coerceIn(0, srcW - 1)
        val cropY = (cT * srcH).toInt().coerceIn(0, srcH - 1)
        val cropW = ((cR - cL) * srcW).toInt().coerceIn(1, srcW - cropX)
        val cropH = ((cB - cT) * srcH).toInt().coerceIn(1, srcH - cropY)

        val cropped = try {
            Bitmap.createBitmap(source, cropX, cropY, cropW, cropH)
        } catch (_: Exception) {
            source
        }

        return applyMask(cropped, config)
    }

    private fun applyMask(source: Bitmap, config: WebOverlayConfig): Bitmap {
        if (config.maskShape == MaskShape.RECTANGLE && config.cornerRadiusPx <= 0f) {
            return source
        }

        val out = Bitmap.createBitmap(source.width, source.height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(out)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        val rect = RectF(0f, 0f, source.width.toFloat(), source.height.toFloat())

        when (config.maskShape) {
            MaskShape.CIRCLE -> {
                val radius = Math.min(source.width, source.height) / 2f
                canvas.drawCircle(source.width / 2f, source.height / 2f, radius, paint)
            }
            MaskShape.ROUNDED_RECT -> {
                val radius = if (config.cornerRadiusPx > 0f) config.cornerRadiusPx else 24f
                canvas.drawRoundRect(rect, radius, radius, paint)
            }
            MaskShape.RECTANGLE -> {
                if (config.cornerRadiusPx > 0f) {
                    canvas.drawRoundRect(rect, config.cornerRadiusPx, config.cornerRadiusPx, paint)
                } else {
                    canvas.drawRect(rect, paint)
                }
            }
        }

        paint.xfermode = android.graphics.PorterDuffXfermode(android.graphics.PorterDuff.Mode.SRC_IN)
        canvas.drawBitmap(source, 0f, 0f, paint)
        return out
    }

    private fun createStatusPlaceholder(
        config: WebOverlayConfig,
        isLoading: Boolean = false,
        error: String? = null
    ): Bitmap {
        val w = if (config.customWidth > 0) config.customWidth else DEFAULT_WIDTH
        val h = if (config.customHeight > 0) config.customHeight else DEFAULT_HEIGHT
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)

        val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = if (error != null) Color.parseColor("#3A1515") else Color.parseColor("#1C1917")
            style = Paint.Style.FILL
        }
        val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = if (error != null) Color.parseColor("#EF4444") else Color.parseColor("#38BDF8")
            style = Paint.Style.STROKE
            strokeWidth = 4f
        }
        val rect = RectF(6f, 6f, w - 6f, h - 6f)
        canvas.drawRoundRect(rect, 24f, 24f, bgPaint)
        canvas.drawRoundRect(rect, 24f, 24f, borderPaint)

        val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            textSize = 28f
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            textAlign = Paint.Align.CENTER
        }
        val subPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#A8A29E")
            textSize = 20f
            typeface = Typeface.DEFAULT
            textAlign = Paint.Align.CENTER
        }

        val centerX = w / 2f
        val centerY = h / 2f

        if (error != null) {
            canvas.drawText("Web Overlay Error", centerX, centerY - 15f, textPaint)
            canvas.drawText(error.take(40), centerX, centerY + 25f, subPaint)
        } else if (isLoading) {
            canvas.drawText("Loading Web Overlay...", centerX, centerY - 15f, textPaint)
            canvas.drawText(config.url.take(45), centerX, centerY + 25f, subPaint)
        } else {
            canvas.drawText("Live Web Overlay", centerX, centerY - 15f, textPaint)
            canvas.drawText(config.url.take(45), centerX, centerY + 25f, subPaint)
        }

        return bmp
    }

    // ── Phase 5: Web Navigation & Production Hardening ─────────────────

    /**
     * Navigate the offscreen WebView back in history.
     */
    fun goBack(id: String) {
        mainHandler.post {
            webViews[id]?.let { if (it.canGoBack()) it.goBack() }
        }
    }

    /**
     * Navigate the offscreen WebView forward in history.
     */
    fun goForward(id: String) {
        mainHandler.post {
            webViews[id]?.let { if (it.canGoForward()) it.goForward() }
        }
    }

    /**
     * Stop loading the current page.
     */
    fun stopLoading(id: String) {
        mainHandler.post {
            webViews[id]?.stopLoading()
            isLoading[id] = false
        }
    }

    /**
     * Query whether the WebView for a given overlay ID can navigate back.
     */
    fun canGoBack(id: String): Boolean = webViews[id]?.canGoBack() == true

    /**
     * Query whether the WebView for a given overlay ID can navigate forward.
     */
    fun canGoForward(id: String): Boolean = webViews[id]?.canGoForward() == true

    /**
     * Return the current loading state.
     */
    fun isPageLoading(id: String): Boolean = isLoading[id] == true

    /**
     * Return the current error message, if any.
     */
    fun getError(id: String): String? = loadError[id]

    /**
     * Validate a URL before loading. Must be http:// or https://.
     */
    fun isValidUrl(url: String): Boolean {
        val trimmed = url.trim()
        return trimmed.startsWith("http://") || trimmed.startsWith("https://")
    }

    /**
     * Release a specific overlay's WebView and cached bitmaps (Phase 5 §15: memory management).
     */
    fun releaseOverlay(id: String) {
        mainHandler.post {
            webViews.remove(id)?.let {
                try {
                    it.stopLoading()
                    it.destroy()
                } catch (_: Exception) {}
            }
            rawBitmapCache.remove(id)
            processedBitmapCache.remove(id)
            activeUrls.remove(id)
            activeScrolls.remove(id)
            activeCrops.remove(id)
            isLoading.remove(id)
            loadError.remove(id)
        }
    }

    /**
     * Return the count of active WebView instances (for diagnostics / leak detection).
     */
    fun activeWebViewCount(): Int = webViews.size

    fun release() {
        mainHandler.post {
            webViews.values.forEach {
                try {
                    it.stopLoading()
                    it.destroy()
                } catch (_: Exception) {}
            }
            webViews.clear()
            rawBitmapCache.clear()
            processedBitmapCache.clear()
            activeUrls.clear()
            activeScrolls.clear()
            activeCrops.clear()
            isLoading.clear()
            loadError.clear()
        }
    }
}
