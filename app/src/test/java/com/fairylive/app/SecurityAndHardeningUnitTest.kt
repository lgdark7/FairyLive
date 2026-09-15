package com.fairylive.app

import com.fairylive.app.core.settings.StreamConfigurationValidator
import com.fairylive.app.service.StreamControlReceiver
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Enterprise security and input validation test suite for Milestone 14 (Security + Hardening).
 * Verifies strict boundary protection, injection defense (CRLF, path traversal, buffer overflows),
 * scheme whitelisting, credential masking, and component exception isolation.
 */
class SecurityAndHardeningUnitTest {

    // ---------------------------------------------------------------------------------------------
    // RTMP Endpoint & URL Security Validation
    // ---------------------------------------------------------------------------------------------

    @Test
    fun testValidRtmpEndpointsPassValidation() {
        val validCases = listOf(
            "rtmp://live.fairylive.tv/live" to "stream_key_12345",
            "rtmps://ingest.global.live:443/app" to "sk_live_9876543210",
            "rtmp://192.168.1.100:1935/stream" to "my_channel_key",
            "rtmps://sub.domain.co.uk:19350/live/sub" to "auth_token_abc"
        )

        for ((url, key) in validCases) {
            val result = StreamConfigurationValidator.validate(url, key)
            assertTrue("Expected valid for $url: ${(result as? StreamConfigurationValidator.ValidationResult.Error)?.technicalReason}", result.isValid)
        }
    }

    @Test
    fun testForbiddenSchemesAreStrictlyRejected() {
        val dangerousSchemes = listOf(
            "http://live.fairylive.tv/live",
            "https://live.fairylive.tv/live",
            "ftp://files.fairylive.tv/stream",
            "file:///sdcard/malicious.flv",
            "javascript://alert(1)",
            "content://media/external/audio",
            "data:text/plain;base64,SGVsbG8=",
            "blob:http://example.com/uuid"
        )

        for (url in dangerousSchemes) {
            val result = StreamConfigurationValidator.validate(url, "any_key")
            assertFalse("Dangerous scheme must be rejected: $url", result.isValid)
            val error = result as StreamConfigurationValidator.ValidationResult.Error
            assertTrue(error.technicalReason.contains("does not start with rtmp:// or rtmps://"))
        }
    }

    @Test
    fun testOversizedUrlAndKeyAreRejected() {
        val oversizedUrl = "rtmp://live.fairylive.tv/app/" + "a".repeat(2100)
        val oversizedKey = "k".repeat(600)
        val oversizedHostUrl = "rtmp://" + "h".repeat(300) + ".com/live"

        // URL > 2048 chars
        val urlResult = StreamConfigurationValidator.validate(oversizedUrl, "valid_key")
        assertFalse("Oversized URL must be rejected", urlResult.isValid)
        assertTrue((urlResult as StreamConfigurationValidator.ValidationResult.Error).technicalReason.contains("exceeds 2048"))

        // Host > 253 chars
        val hostResult = StreamConfigurationValidator.validate(oversizedHostUrl, "valid_key")
        assertFalse("Oversized host must be rejected", hostResult.isValid)
        assertTrue((hostResult as StreamConfigurationValidator.ValidationResult.Error).technicalReason.contains("exceeds 253"))

        // Stream Key > 512 chars
        val keyResult = StreamConfigurationValidator.validate("rtmp://live.tv/app", oversizedKey)
        assertFalse("Oversized stream key must be rejected", keyResult.isValid)
        assertTrue((keyResult as StreamConfigurationValidator.ValidationResult.Error).technicalReason.contains("exceeds 512"))
    }

    @Test
    fun testCrlfAndControlCharacterInjectionAreRejected() {
        val injectionUrls = listOf(
            "rtmp://live.tv/app\r\nInject-Header: evil",
            "rtmp://live.tv/app\nDROP TABLE streams;",
            "rtmp://live.tv/app\u0000admin",
            "rtmp://live.tv/app\rSET-COOKIE: malicious"
        )

        for (url in injectionUrls) {
            val result = StreamConfigurationValidator.validate(url, "valid_key")
            assertFalse("URL with control characters/CRLF must be rejected: $url", result.isValid)
            val error = result as StreamConfigurationValidator.ValidationResult.Error
            assertTrue(error.technicalReason.contains("Control character or CRLF injection"))
        }

        val injectionKeys = listOf(
            "key\r\nmalicious",
            "key\u0000admin",
            "key\ninjection"
        )

        for (key in injectionKeys) {
            val result = StreamConfigurationValidator.validate("rtmp://live.tv/app", key)
            assertFalse("Key with control characters/CRLF must be rejected", result.isValid)
            val error = result as StreamConfigurationValidator.ValidationResult.Error
            assertTrue(error.technicalReason.contains("Control character or CRLF injection"))
        }
    }

    @Test
    fun testPathTraversalIsRejected() {
        val traversalUrls = listOf(
            "rtmp://live.tv/../../etc/passwd",
            "rtmp://live.tv/live/..;/admin",
            "rtmp://live.tv/app/../root"
        )

        for (url in traversalUrls) {
            val result = StreamConfigurationValidator.validate(url, "valid_key")
            assertFalse("Path traversal must be rejected: $url", result.isValid)
            val error = result as StreamConfigurationValidator.ValidationResult.Error
            assertTrue(error.technicalReason.contains("Path traversal sequence detected"))
        }
    }

    @Test
    fun testInvalidPortRangesAreRejected() {
        val invalidPorts = listOf(
            "rtmp://live.tv:0/app",
            "rtmp://live.tv:65536/app",
            "rtmp://live.tv:99999/app"
        )

        for (url in invalidPorts) {
            val result = StreamConfigurationValidator.validate(url, "valid_key")
            assertFalse("Invalid port must be rejected: $url", result.isValid)
        }
    }

    // ---------------------------------------------------------------------------------------------
    // Credential Masking & UI Privacy Protection
    // ---------------------------------------------------------------------------------------------

    @Test
    fun testStreamKeyMaskingNeverLeaksPlaintext() {
        val secretKey = "live_secret_token_1234567890"
        val masked = StreamConfigurationValidator.maskStreamKey(secretKey)

        assertFalse("Masked output must not contain full secret", masked.contains(secretKey))
        assertEquals("li••••••••••••••••••••••••90", masked)
        assertEquals(secretKey.length, masked.length)

        // Short keys
        assertEquals("••••", StreamConfigurationValidator.maskStreamKey("abc"))
        assertEquals("a••d", StreamConfigurationValidator.maskStreamKey("abcd"))
        assertEquals("none", StreamConfigurationValidator.maskStreamKey(""))
        assertEquals("none", StreamConfigurationValidator.maskStreamKey(null))
    }

    @Test
    fun testSafeEndpointFormattingDoesNotLeakKey() {
        val secretKey = "super_private_broadcast_key_999"
        val formatted = StreamConfigurationValidator.formatSafeEndpoint("rtmp://live.fairylive.tv/app", secretKey)

        assertTrue(formatted.startsWith("rtmp://live.fairylive.tv/app"))
        assertFalse(formatted.contains(secretKey))
        val expectedMasked = StreamConfigurationValidator.maskStreamKey(secretKey)
        assertTrue(formatted.contains("Key: $expectedMasked"))
    }

    // ---------------------------------------------------------------------------------------------
    // Resource & Overlay Input Sanitization
    // ---------------------------------------------------------------------------------------------

    @Test
    fun testOverlayTextSanitizationClampsAndStripsControlCharacters() {
        // Normal text unchanged
        assertEquals("Live Stream Title", StreamConfigurationValidator.sanitizeOverlayText("Live Stream Title"))

        // Strips control characters (\x00, \x07 bell, \x1B escape)
        val maliciousText = "Hello\u0000World\u001B[31mRed\u0007Bell"
        assertEquals("HelloWorld[31mRedBell", StreamConfigurationValidator.sanitizeOverlayText(maliciousText))

        // Clamps oversized text to 500 characters
        val massiveText = "X".repeat(1500)
        val sanitized = StreamConfigurationValidator.sanitizeOverlayText(massiveText)
        assertEquals(500, sanitized.length)

        // Null returns empty string
        assertEquals("", StreamConfigurationValidator.sanitizeOverlayText(null))
    }

    // ---------------------------------------------------------------------------------------------
    // Component & IPC Boundary Exception Isolation
    // ---------------------------------------------------------------------------------------------

    @Test
    fun testStreamControlReceiverHandlesNullAndMalformedIntentsSafely() {
        val receiver = StreamControlReceiver()
        val fakeContext = SecureCredentialStorageTest.FakeTestContext()

        // Null intent - must not throw
        receiver.onReceive(fakeContext, null)

        // Empty intent without action - must not throw
        val emptyIntent = android.content.Intent()
        receiver.onReceive(fakeContext, emptyIntent)

        // Unrecognized malicious action - must not throw
        val unknownIntent = android.content.Intent("com.malicious.app.EXPLOIT")
        receiver.onReceive(fakeContext, unknownIntent)
    }
}
