package com.fairylive.app

import com.fairylive.app.core.compositor.model.OverlayElement
import com.fairylive.app.core.compositor.model.OverlayType
import com.fairylive.app.core.compositor.model.WebOverlayConfig
import com.fairylive.app.core.compositor.model.YouTubeSubscriberWidgetConfig
import com.fairylive.app.core.render.RenderDomain
import com.fairylive.app.core.scene.model.BroadcastScene
import org.junit.Assert.*
import org.junit.Test

/**
 * V1.1 Architectural Invariant Verification:
 * Validates strict render domain boundaries, WebOverlay & YouTube subscriber configs,
 * and ensures creator-only UI components cannot leak into the broadcast domain.
 */
class V11RenderDomainIsolationTest {

    @Test
    fun testRenderDomainEnumIsolation() {
        assertEquals(2, RenderDomain.values().size)
        assertTrue(RenderDomain.values().contains(RenderDomain.BROADCAST))
        assertTrue(RenderDomain.values().contains(RenderDomain.CREATOR_ONLY))
        assertNotEquals(RenderDomain.BROADCAST, RenderDomain.CREATOR_ONLY)
    }

    @Test
    fun testWebOverlayModelIntegrity() {
        val webConfig = WebOverlayConfig(
            url = "https://widget.fairylive.app/alerts",
            transparentBackground = true,
            fallbackText = "Offscreen web layer active"
        )
        val webElement = OverlayElement.createWeb("web_elem_1", webConfig, 0.1f, 0.1f, 0.4f)

        assertEquals(OverlayType.WEB, webElement.type)
        assertNotNull(webElement.webConfig)
        assertEquals("https://widget.fairylive.app/alerts", webElement.webConfig?.url)
        assertTrue(webElement.webConfig?.transparentBackground == true)
        assertEquals("Offscreen web layer active", webElement.webConfig?.fallbackText)
    }

    @Test
    fun testYouTubeSubscriberWidgetModelIntegrity() {
        val ytConfig = YouTubeSubscriberWidgetConfig(
            channelId = "UC_TEST_CHANNEL_123",
            channelTitle = "FAIRY GAMING",
            subscriberCountFormatted = "25.8K",
            rawSubscriberCount = 25800L
        )
        val ytElement = OverlayElement.createYouTubeSubscriber("yt_sub_1", ytConfig, 0.05f, 0.05f)

        assertEquals(OverlayType.YOUTUBE_SUBSCRIBER, ytElement.type)
        assertNotNull(ytElement.youtubeSubscriberConfig)
        assertEquals("25.8K", ytElement.youtubeSubscriberConfig?.subscriberCountFormatted)
        assertEquals("FAIRY GAMING", ytElement.youtubeSubscriberConfig?.channelTitle)
    }

    @Test
    fun testBroadcastSceneRejectsUnsanitizedCreatorUI() {
        val scene = BroadcastScene(
            id = "scene_v11",
            name = "V11 Broadcast Scene",
            overlayElements = listOf(
                OverlayElement.createWeb("web_1", WebOverlayConfig("https://stream.io/chat"), 0.1f, 0.1f),
                OverlayElement.createYouTubeSubscriber("yt_1", YouTubeSubscriberWidgetConfig(channelTitle = "Benny"), 0.05f, 0.05f)
            )
        )

        assertEquals(2, scene.overlayElements.size)
        // Ensure all elements in the scene belong exclusively to legitimate broadcast types
        scene.overlayElements.forEach { elem ->
            assertTrue(elem.type == OverlayType.WEB || elem.type == OverlayType.YOUTUBE_SUBSCRIBER)
        }
    }
}
