package com.fairylive.app

import com.fairylive.app.core.capability.model.StreamProfile
import com.fairylive.app.core.settings.StreamSettings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class StreamSettingsTest {

    @Test
    fun testClampingEnforcesEvenDimensions() {
        val oddSettings = StreamSettings(
            width = 855, // Odd width
            height = 479, // Odd height
            fps = 24,
            videoBitrateKbps = 1200
        )
        val clamped = oddSettings.clamped()

        assertEquals("Width must be clamped to even integer", 854, clamped.width)
        assertEquals("Height must be clamped to even integer", 478, clamped.height)
    }

    @Test
    fun testClampingEnforcesLimits() {
        val extremeSettings = StreamSettings(
            width = 4000,
            height = 3000,
            fps = 120,
            videoBitrateKbps = 50000,
            audioBitrateKbps = 1000
        )
        val clamped = extremeSettings.clamped()

        assertEquals(1920, clamped.width)
        assertEquals(1080, clamped.height)
        assertEquals(60, clamped.fps)
        assertEquals(8000, clamped.videoBitrateKbps)
        assertEquals(320, clamped.audioBitrateKbps)
    }

    @Test
    fun testFromProfileFactory() {
        val lowSettings = StreamSettings.fromProfile(
            StreamProfile.LOW,
            serverUrl = "rtmp://my.stream/live",
            streamKey = "live_key_123"
        )
        assertEquals("rtmp://my.stream/live", lowSettings.serverUrl)
        assertEquals("live_key_123", lowSettings.streamKey)
        assertEquals(StreamProfile.LOW, lowSettings.profile)
        assertEquals(854, lowSettings.width)
        assertEquals(480, lowSettings.height)
        assertEquals(24, lowSettings.fps)
        assertEquals(1200, lowSettings.videoBitrateKbps)
        assertEquals(96, lowSettings.audioBitrateKbps)
        assertTrue(lowSettings.micEnabled)
    }
}
