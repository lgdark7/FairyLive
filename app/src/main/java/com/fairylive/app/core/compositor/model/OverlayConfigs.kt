package com.fairylive.app.core.compositor.model

import android.graphics.Color
import android.graphics.Typeface

/** Configuration for styled text overlays. */
data class TextOverlayConfig(
    val text: String,
    val fontSizeSp: Float = 24f,
    val textColorRgba: Int = Color.WHITE,
    val backgroundColorRgba: Int = Color.argb(180, 16, 20, 32),
    val fontStyle: Int = Typeface.BOLD,
    val paddingPx: Int = 16,
    val cornerRadiusPx: Float = 8f,
    val alignment: String = "LEFT"
)

/** Configuration for goal progress overlays (followers, subs, donations). */
data class GoalOverlayConfig(
    val title: String,
    val currentVal: Int,
    val targetVal: Int,
    val unitPrefix: String = "",
    val barColorRgba: Int = Color.rgb(0, 229, 255), // Cyan
    val trackColorRgba: Int = Color.rgb(40, 48, 64),
    val backgroundColorRgba: Int = Color.argb(200, 16, 20, 32)
) {
    val progressFraction: Float
        get() = if (targetVal > 0) (currentVal.toFloat() / targetVal).coerceIn(0f, 1f) else 0f
}

/** Configuration for broadcast lower-third banners. */
data class LowerThirdOverlayConfig(
    val title: String,
    val subtitle: String,
    val accentColorRgba: Int = Color.rgb(124, 77, 255), // Purple
    val backgroundColorRgba: Int = Color.argb(210, 12, 16, 26),
    val durationMs: Long = 0L // 0 = permanent until removed
)

/** Configuration for animated stream alerts. */
data class AlertOverlayConfig(
    val headline: String,
    val subtext: String,
    val durationMs: Long = 4000L,
    val badgeColorRgba: Int = Color.rgb(255, 193, 7), // Gold/Amber
    val soundAsset: String? = null,
    val createdAtMs: Long = 0L
)

/** Configuration for on-screen QR codes. */
data class QrOverlayConfig(
    val payloadUrl: String,
    val foregroundColorRgba: Int = Color.BLACK,
    val backgroundColorRgba: Int = Color.WHITE,
    val borderPaddingModules: Int = 2
)

/** Configuration for animated GIF overlays. */
data class GifOverlayConfig(
    val resourcePath: String,
    val playbackSpeed: Float = 1.0f,
    val isLooping: Boolean = true
)

/** Configuration for real-time diagnostic / telemetry widgets. */
data class WidgetOverlayConfig(
    val widgetType: String, // "STATS", "VIEWERS", "CLOCK"
    val label: String,
    val value: String,
    val accentColorRgba: Int = Color.rgb(0, 229, 255)
)

enum class MaskShape {
    RECTANGLE,
    ROUNDED_RECT,
    CIRCLE
}

/** Configuration for live browser / HTML web overlays. */
data class WebOverlayConfig(
    val url: String = "https://example.com",
    val transparentBackground: Boolean = true,
    val refreshIntervalSeconds: Int = 0, // 0 = no periodic refresh
    val cssInjection: String? = null,
    val jsInjection: String? = null,
    val isInteractive: Boolean = false,
    val allowTouchInteraction: Boolean = true,
    val fallbackText: String = "Web overlay unavailable",
    val customWidth: Int = 800,
    val customHeight: Int = 450,
    val scrollX: Int = 0,
    val scrollY: Int = 0,
    val zoomLevel: Float = 1.0f,     // Webpage zoom (independent of overlay scale)
    val cropLeft: Float = 0f,
    val cropTop: Float = 0f,
    val cropRight: Float = 1f,
    val cropBottom: Float = 1f,
    val maskShape: MaskShape = MaskShape.RECTANGLE,
    val cornerRadiusPx: Float = 0f
)

/** Typealias for YouTube subscriber widget config consistency */
typealias YouTubeSubscriberConfig = YouTubeSubscriberWidgetConfig

/** Configuration for YouTube live subscriber count widget. */
data class YouTubeSubscriberWidgetConfig(
    val channelId: String = "",
    val channelTitle: String = "FAIRY LIVE",
    val subscriberCountFormatted: String = "12.4K",
    val rawSubscriberCount: Long = 12400L,
    val labelText: String = "SUBSCRIBERS",
    val accentColorRgba: Int = 0xFFFF0000.toInt(), // YouTube Red
    val backgroundColorRgba: Int = 0xCC1A1A1A.toInt(),
    val showIcon: Boolean = true
)
