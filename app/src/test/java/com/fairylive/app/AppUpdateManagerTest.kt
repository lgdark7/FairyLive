package com.fairylive.app

import com.fairylive.app.core.updater.AppUpdateManager
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AppUpdateManagerTest {

    @Test
    fun testParseVersionJson_validPayload() {
        val json = """
            {
              "versionCode": 124,
              "versionName": "1.2.4",
              "releaseNotes": "• Fixed audio noise gate\n• Enhanced stability",
              "downloadUrl": "https://github.com/lgdark7/FairyLive/releases/download/v1.2.4/app-release.apk",
              "isMandatory": true,
              "sha256": "abc123456"
            }
        """.trimIndent()

        val info = AppUpdateManager.parseVersionJson(json)
        assertEquals(124, info.versionCode)
        assertEquals("1.2.4", info.versionName)
        assertTrue(info.releaseNotes.contains("Fixed audio noise gate"))
        assertEquals("https://github.com/lgdark7/FairyLive/releases/download/v1.2.4/app-release.apk", info.downloadUrl)
        assertTrue(info.isMandatory)
        assertEquals("abc123456", info.sha256)
    }

    @Test
    fun testParseVersionJson_defaultValues() {
        val json = """
            {
              "versionCode": 125,
              "versionName": "1.2.5",
              "downloadUrl": "https://example.com/app.apk"
            }
        """.trimIndent()

        val info = AppUpdateManager.parseVersionJson(json)
        assertEquals(125, info.versionCode)
        assertEquals("1.2.5", info.versionName)
        assertFalse(info.isMandatory)
        assertNotNull(info.releaseNotes)
    }
}
