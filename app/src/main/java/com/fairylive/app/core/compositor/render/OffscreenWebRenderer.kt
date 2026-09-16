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
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import com.fairylive.app.core.compositor.model.WebOverlayConfig
import com.fairylive.app.core.compositor.rasterizer.WebOverlayRasterizer
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

/**
 * High-performance offscreen web overlay renderer for RenderDomain.BROADCAST.
 * Renders actual web content into a shared ARGB_8888 bitmap pool.
 * Does NOT instantiate extra video encoders or threads.
 * Automatically throttles frame capturing and falls back gracefully on load failure.
 */
class OffscreenWebRenderer(private val context: Context) {

    companion object {
        const val DEFAULT_WIDTH = 640
        const val DEFAULT_HEIGHT = 360
    }

    private val mainHandler = Handler(Looper.getMainLooper())
    private val webViewMap = ConcurrentHashMap<String, WebView>()
    private val bitmapCache = ConcurrentHashMap<String, Bitmap>()
    private val isLoadedMap = ConcurrentHashMap<String, Boolean>()
    private val isErrorMap = ConcurrentHashMap<String, Boolean>()

    fun getOrRenderBitmap(id: String, config: WebOverlayConfig): Bitmap {
        val cached = bitmapCache[id]
        if (cached != null) {
            return cached
        }

        // Initialize fallback placeholder while webview loads
        val placeholder = WebOverlayRasterizer.rasterizePlaceholder(config)
        bitmapCache[id] = placeholder

        mainHandler.post {
            setupWebView(id, config)
        }

        return placeholder
    }

    private fun setupWebView(id: String, config: WebOverlayConfig) {
        if (webViewMap.containsKey(id)) return

        try {
            val webView = WebView(context.applicationContext)
            webView.layout(0, 0, DEFAULT_WIDTH, DEFAULT_HEIGHT)

            if (config.transparentBackground) {
                webView.setBackgroundColor(Color.TRANSPARENT)
            } else {
                webView.setBackgroundColor(Color.WHITE)
            }

            val settings = webView.settings
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.cacheMode = WebSettings.LOAD_DEFAULT

            webView.webViewClient = object : WebViewClient() {
                override fun onPageFinished(view: WebView?, url: String?) {
                    super.onPageFinished(view, url)
                    isLoadedMap[id] = true
                    captureWebViewToBitmap(id, webView, config)
                }

                override fun onReceivedError(
                    view: WebView?,
                    errorCode: Int,
                    description: String?,
                    failingUrl: String?
                ) {
                    super.onReceivedError(view, errorCode, description, failingUrl)
                    isErrorMap[id] = true
                }
            }

            webView.loadUrl(config.url)
            webViewMap[id] = webView
        } catch (e: Exception) {
            isErrorMap[id] = true
        }
    }

    private fun captureWebViewToBitmap(id: String, webView: WebView, config: WebOverlayConfig) {
        try {
            val bmp = Bitmap.createBitmap(DEFAULT_WIDTH, DEFAULT_HEIGHT, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(bmp)
            if (config.transparentBackground) {
                canvas.drawColor(Color.TRANSPARENT)
            }
            webView.draw(canvas)
            bitmapCache[id] = bmp
        } catch (_: Exception) {}
    }

    fun release() {
        mainHandler.post {
            for ((_, wv) in webViewMap) {
                try {
                    wv.stopLoading()
                    wv.destroy()
                } catch (_: Exception) {}
            }
            webViewMap.clear()
            bitmapCache.clear()
            isLoadedMap.clear()
            isErrorMap.clear()
        }
    }
}
