package com.fairylive.app.core.compositor.model

/**
 * Overlay element model — the declarative, serializable description of a single
 * broadcast overlay. Pure data; no rendering logic. Coordinates are normalized
 * (0.0..1.0 relative to the *broadcast/stream* resolution, NOT the device UI
 * resolution) so overlays survive resolution/profile changes.
 */
data class OverlayElement(
    val id: String,
    val type: OverlayType,
    val enabled: Boolean = true,
    val normalizedX: Float = 0f,      // 0.0 = left, 1.0 = right
    val normalizedY: Float = 0f,      // 0.0 = top, 1.0 = bottom
    val normalizedWidth: Float = 0.2f, // fraction of stream width
    val normalizedHeight: Float = 0f,  // 0 = auto (derived from aspect + width)
    val scale: Float = 1f,
    val rotationDegrees: Float = 0f,
    val opacity: Float = 1f,          // 0..1
    val zIndex: Int = 0,              // higher = on top
    val visible: Boolean = true,
    val locked: Boolean = false,
    val cornerRadiusPx: Float = 0f,
    val resourcePath: String? = null,  // image/asset resource reference
    val textConfig: TextOverlayConfig? = null,
    val goalConfig: GoalOverlayConfig? = null,
    val lowerThirdConfig: LowerThirdOverlayConfig? = null,
    val alertConfig: AlertOverlayConfig? = null,
    val qrConfig: QrOverlayConfig? = null,
    val gifConfig: GifOverlayConfig? = null,
    val widgetConfig: WidgetOverlayConfig? = null,
    val webConfig: WebOverlayConfig? = null,
    val youtubeSubscriberConfig: YouTubeSubscriberWidgetConfig? = null
) {
    val isVisible: Boolean get() = enabled && visible && opacity > 0f

    /** Clamped copy — never lets callers violate the invariants. */
    fun sanitized(): OverlayElement = copy(
        opacity = opacity.coerceIn(0f, 1f),
        normalizedX = normalizedX.coerceIn(0f, 1f),
        normalizedY = normalizedY.coerceIn(0f, 1f),
        normalizedWidth = normalizedWidth.coerceIn(0f, 1f),
        scale = scale.coerceAtLeast(0.01f)
    )

    companion object {
        fun createImage(
            id: String,
            resourcePath: String,
            x: Float,
            y: Float,
            width: Float,
            opacity: Float = 1f,
            zIndex: Int = 0
        ) = OverlayElement(
            id = id,
            type = OverlayType.IMAGE,
            resourcePath = resourcePath,
            normalizedX = x,
            normalizedY = y,
            normalizedWidth = width,
            opacity = opacity,
            zIndex = zIndex
        )

        fun createImageWithBounds(
            id: String,
            resourcePath: String,
            x: Float,
            y: Float,
            width: Float,
            height: Float,
            opacity: Float = 1f,
            zIndex: Int = 0
        ) = OverlayElement(
            id = id,
            type = OverlayType.IMAGE,
            resourcePath = resourcePath,
            normalizedX = x,
            normalizedY = y,
            normalizedWidth = width,
            normalizedHeight = height,
            opacity = opacity,
            zIndex = zIndex
        )

        fun createText(
            id: String,
            config: TextOverlayConfig,
            x: Float,
            y: Float,
            width: Float = 0.4f,
            opacity: Float = 1f,
            zIndex: Int = 10
        ) = OverlayElement(
            id = id,
            type = OverlayType.TEXT,
            textConfig = config,
            normalizedX = x,
            normalizedY = y,
            normalizedWidth = width,
            opacity = opacity,
            zIndex = zIndex
        )

        fun createGoal(
            id: String,
            config: GoalOverlayConfig,
            x: Float,
            y: Float,
            width: Float = 0.6f,
            opacity: Float = 1f,
            zIndex: Int = 10
        ) = OverlayElement(
            id = id,
            type = OverlayType.GOAL,
            goalConfig = config,
            normalizedX = x,
            normalizedY = y,
            normalizedWidth = width,
            opacity = opacity,
            zIndex = zIndex
        )

        fun createLowerThird(
            id: String,
            config: LowerThirdOverlayConfig,
            x: Float = 0.05f,
            y: Float = 0.82f,
            width: Float = 0.75f,
            opacity: Float = 1f,
            zIndex: Int = 20
        ) = OverlayElement(
            id = id,
            type = OverlayType.LOWER_THIRD,
            lowerThirdConfig = config,
            normalizedX = x,
            normalizedY = y,
            normalizedWidth = width,
            opacity = opacity,
            zIndex = zIndex
        )

        fun createAlert(
            id: String,
            config: AlertOverlayConfig,
            x: Float = 0.15f,
            y: Float = 0.12f,
            width: Float = 0.70f,
            opacity: Float = 1f,
            zIndex: Int = 30
        ) = OverlayElement(
            id = id,
            type = OverlayType.ALERT,
            alertConfig = config,
            normalizedX = x,
            normalizedY = y,
            normalizedWidth = width,
            opacity = opacity,
            zIndex = zIndex
        )

        fun createQr(
            id: String,
            config: QrOverlayConfig,
            x: Float,
            y: Float,
            width: Float = 0.22f,
            opacity: Float = 1f,
            zIndex: Int = 10
        ) = OverlayElement(
            id = id,
            type = OverlayType.QR,
            qrConfig = config,
            normalizedX = x,
            normalizedY = y,
            normalizedWidth = width,
            opacity = opacity,
            zIndex = zIndex
        )

        fun createGif(
            id: String,
            config: GifOverlayConfig,
            x: Float,
            y: Float,
            width: Float = 0.25f,
            opacity: Float = 1f,
            zIndex: Int = 10
        ) = OverlayElement(
            id = id,
            type = OverlayType.GIF,
            gifConfig = config,
            normalizedX = x,
            normalizedY = y,
            normalizedWidth = width,
            opacity = opacity,
            zIndex = zIndex
        )

        fun createWidget(
            id: String,
            config: WidgetOverlayConfig,
            x: Float,
            y: Float,
            width: Float = 0.35f,
            opacity: Float = 1f,
            zIndex: Int = 15
        ) = OverlayElement(
            id = id,
            type = OverlayType.WIDGET,
            widgetConfig = config,
            normalizedX = x,
            normalizedY = y,
            normalizedWidth = width,
            opacity = opacity,
            zIndex = zIndex
        )

        fun createWeb(
            id: String,
            config: WebOverlayConfig,
            x: Float = 0.05f,
            y: Float = 0.05f,
            width: Float = 0.40f,
            opacity: Float = 1f,
            zIndex: Int = 15
        ) = OverlayElement(
            id = id,
            type = OverlayType.WEB,
            webConfig = config,
            normalizedX = x,
            normalizedY = y,
            normalizedWidth = width,
            opacity = opacity,
            zIndex = zIndex
        )

        fun createYouTubeSubscriber(
            id: String,
            config: YouTubeSubscriberWidgetConfig,
            x: Float = 0.05f,
            y: Float = 0.05f,
            width: Float = 0.35f,
            opacity: Float = 1f,
            zIndex: Int = 20
        ) = OverlayElement(
            id = id,
            type = OverlayType.YOUTUBE_SUBSCRIBER,
            youtubeSubscriberConfig = config,
            normalizedX = x,
            normalizedY = y,
            normalizedWidth = width,
            opacity = opacity,
            zIndex = zIndex
        )
    }
}

enum class OverlayType {
    IMAGE,
    GIF,
    TEXT,
    GOAL,
    LOWER_THIRD,
    ALERT,
    QR,
    WIDGET,
    WEB,
    YOUTUBE_SUBSCRIBER,
    PNG // Backward compatibility alias for IMAGE
}
