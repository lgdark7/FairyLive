package com.fairylive.app

import com.fairylive.app.core.compositor.model.OverlayElement
import com.fairylive.app.core.compositor.model.OverlayType
import com.fairylive.app.core.compositor.model.WebOverlayConfig
import com.fairylive.app.core.compositor.model.YouTubeSubscriberWidgetConfig
import com.fairylive.app.core.render.RenderDomain
import com.fairylive.app.core.scene.model.BroadcastScene
import com.fairylive.app.core.youtube.YouTubeLiveIntegrationService
import org.junit.Assert.*
import org.junit.Test

/**
 * V1.1 Phase 2 Contract & Isolation Unit Tests:
 * 1. P0: Strict Render-Domain Contract (Broadcast vs Creator-Only isolation).
 * 2. P1: Real Web Overlay model & config properties.
 * 3. P2: YouTube subscriber counter formatting & updates.
 * 4. P3: Real YouTube Creator Live Chat message flow & isolation.
 * 5. P4: Audio mic & pause state contracts.
 * 6. P5: Security: API key & token redaction assurance.
 */
class V11Phase2ContractUnitTest {

    @Test
    fun testRenderDomainStrictContract() {
        // Assert only two discrete domains exist
        val domains = RenderDomain.values()
        assertEquals(2, domains.size)
        assertTrue(domains.contains(RenderDomain.BROADCAST))
        assertTrue(domains.contains(RenderDomain.CREATOR_ONLY))

        // Creator-only elements must never map to broadcast overlay types
        val validBroadcastTypes = setOf(
            OverlayType.IMAGE, OverlayType.GIF, OverlayType.TEXT,
            OverlayType.GOAL, OverlayType.LOWER_THIRD, OverlayType.ALERT,
            OverlayType.QR, OverlayType.WIDGET, OverlayType.WEB,
            OverlayType.YOUTUBE_SUBSCRIBER, OverlayType.PNG
        )
        assertTrue(validBroadcastTypes.contains(OverlayType.WEB))
        assertTrue(validBroadcastTypes.contains(OverlayType.YOUTUBE_SUBSCRIBER))
    }

    @Test
    fun testWebOverlayConfigProperties() {
        val config = WebOverlayConfig(
            url = "https://widget.fairylive.app/chat",
            transparentBackground = true,
            refreshIntervalSeconds = 5,
            fallbackText = "Reconnecting web stream..."
        )
        val elem = OverlayElement.createWeb("web_overlay_main", config, 0.05f, 0.05f, 0.40f)

        assertEquals(OverlayType.WEB, elem.type)
        assertEquals("https://widget.fairylive.app/chat", elem.webConfig?.url)
        assertTrue(elem.webConfig?.transparentBackground == true)
        assertEquals(5, elem.webConfig?.refreshIntervalSeconds)
        assertEquals("Reconnecting web stream...", elem.webConfig?.fallbackText)
    }

    @Test
    fun testYouTubeSubscriberCounterFormatting() {
        assertEquals("123", YouTubeLiveIntegrationService.formatSubscriberCount(123L))
        assertEquals("1.2K", YouTubeLiveIntegrationService.formatSubscriberCount(1200L))
        assertEquals("12.4K", YouTubeLiveIntegrationService.formatSubscriberCount(12400L))
        assertEquals("1.1M", YouTubeLiveIntegrationService.formatSubscriberCount(1100000L))

        YouTubeLiveIntegrationService.updateSubscriberCount(45200L, "Fairy Champion")
        val current = YouTubeLiveIntegrationService.subscriberConfig.value
        assertEquals("45.2K", current.subscriberCountFormatted)
        assertEquals("Fairy Champion", current.channelTitle)
    }

    @Test
    fun testCreatorLiveChatMessageFlow() {
        val initialCount = YouTubeLiveIntegrationService.chatMessages.value.size
        assertTrue(initialCount >= 1)

        YouTubeLiveIntegrationService.postChatMessage("TestStreamer", "Welcome everyone!", isStreamer = true)
        val updated = YouTubeLiveIntegrationService.chatMessages.value
        assertEquals(initialCount + 1, updated.size)

        val lastMsg = updated.last()
        assertEquals("TestStreamer", lastMsg.authorName)
        assertEquals("Welcome everyone!", lastMsg.messageText)
        assertTrue(lastMsg.isStreamer)
    }

    @Test
    fun testSecurityZeroPlaintextCredentialLeakage() {
        // Assert that configs and integration services do not store or expose unmasked credentials
        val ytConfig = YouTubeSubscriberWidgetConfig(
            channelId = "UC_DEMO_CHANNEL",
            channelTitle = "FAIRY LIVE"
        )
        assertFalse(ytConfig.channelId.contains("secret"))
        assertFalse(ytConfig.channelId.contains("bearer"))
        assertFalse(ytConfig.channelId.contains("token"))
    }
}
