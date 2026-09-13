package com.fairylive.app

import com.fairylive.app.core.common.CreatorError
import com.fairylive.app.core.common.CreatorErrorNotifier
import com.fairylive.app.core.lifecycle.StreamState
import com.fairylive.app.core.settings.StreamConfigurationValidator
import com.fairylive.app.core.stream.model.RtmpConnectionState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.atomic.AtomicReference

/**
 * Unit tests for Milestone 6 (Basic Live Creator Experience).
 * Validates stream destination verification, credential masking, error mapping, and state machine transitions.
 */
class CreatorExperienceTest {

    // --- 1. STREAM CONFIGURATION VALIDATOR TESTS ---

    @Test
    fun testValidRtmpUrlAndKey() {
        val result = StreamConfigurationValidator.validate(
            serverUrl = "rtmp://live.fairylive.tv:1935/live",
            streamKey = "live_secret_key_12345"
        )
        assertTrue(result.isValid)
    }

    @Test
    fun testValidRtmpsUrl() {
        val result = StreamConfigurationValidator.validate(
            serverUrl = "rtmps://a.rtmp.youtube.com/live2",
            streamKey = "xxxx-yyyy-zzzz"
        )
        assertTrue(result.isValid)
    }

    @Test
    fun testEmptyServerUrl() {
        val result = StreamConfigurationValidator.validate(
            serverUrl = "",
            streamKey = "secret"
        )
        assertFalse(result.isValid)
        val err = result as StreamConfigurationValidator.ValidationResult.Error
        assertTrue(err.userMessage.contains("cannot be empty", ignoreCase = true))
    }

    @Test
    fun testInvalidScheme() {
        val resultHttp = StreamConfigurationValidator.validate(
            serverUrl = "http://live.fairylive.tv/live",
            streamKey = "secret"
        )
        assertFalse(resultHttp.isValid)

        val resultWs = StreamConfigurationValidator.validate(
            serverUrl = "ws://live.fairylive.tv/live",
            streamKey = "secret"
        )
        assertFalse(resultWs.isValid)
    }

    @Test
    fun testMissingHost() {
        val result = StreamConfigurationValidator.validate(
            serverUrl = "rtmp:///live",
            streamKey = "secret"
        )
        assertFalse(result.isValid)
        val err = result as StreamConfigurationValidator.ValidationResult.Error
        assertTrue(err.userMessage.contains("missing", ignoreCase = true))
    }

    @Test
    fun testMissingPath() {
        val result = StreamConfigurationValidator.validate(
            serverUrl = "rtmp://live.fairylive.tv:1935",
            streamKey = "secret"
        )
        assertFalse(result.isValid)
        val err = result as StreamConfigurationValidator.ValidationResult.Error
        assertTrue(err.userMessage.contains("path missing", ignoreCase = true))
    }

    @Test
    fun testInvalidPort() {
        val result = StreamConfigurationValidator.validate(
            serverUrl = "rtmp://live.fairylive.tv:99999/live",
            streamKey = "secret"
        )
        assertFalse(result.isValid)
    }

    @Test
    fun testEmptyStreamKey() {
        val result = StreamConfigurationValidator.validate(
            serverUrl = "rtmp://live.fairylive.tv/live",
            streamKey = "   "
        )
        assertFalse(result.isValid)
        val err = result as StreamConfigurationValidator.ValidationResult.Error
        assertTrue(err.userMessage.contains("key cannot be empty", ignoreCase = true))
    }

    // --- 2. CREDENTIAL MASKING & SECURITY TESTS ---

    @Test
    fun testStreamKeyMasking() {
        assertEquals("none", StreamConfigurationValidator.maskStreamKey(null))
        assertEquals("none", StreamConfigurationValidator.maskStreamKey(""))
        assertEquals("••••", StreamConfigurationValidator.maskStreamKey("abc"))
        assertEquals("a••d", StreamConfigurationValidator.maskStreamKey("abcd"))
        assertEquals("li•••••••••••••56", StreamConfigurationValidator.maskStreamKey("live_abcdef123456"))
    }

    @Test
    fun testSafeEndpointFormattingDoesNotLeakKey() {
        val safe = StreamConfigurationValidator.formatSafeEndpoint(
            serverUrl = "rtmp://live.twitch.tv/app",
            streamKey = "live_secret_token_super_private"
        )
        assertTrue(safe.contains("rtmp://live.twitch.tv/app"))
        assertFalse("Stream key must never appear unmasked", safe.contains("live_secret_token_super_private"))
        // Masked form keeps only first 2 and last 2 chars of the key
        assertTrue(safe.contains("li") && safe.contains("te"))
        assertFalse(safe.contains("live_secret_token_super_private".substring(2, 28)))
    }

    // --- 3. CREATOR ERROR MAPPING & NOTIFICATION TESTS ---

    @Test
    fun testErrorMappingForConnectionRefused() {
        val ex = java.net.ConnectException("failed to connect to 127.0.0.1 (port 1935): connect failed: ECONNREFUSED (Connection refused)")
        val error = CreatorErrorNotifier.mapNetworkException(ex)

        assertEquals("Connection Refused", error.title)
        assertTrue(error.message.contains("offline", ignoreCase = true) || error.message.contains("reach", ignoreCase = true))
        assertTrue(error.resolution.contains("Settings", ignoreCase = true))
    }

    @Test
    fun testErrorMappingForConnectionTimeout() {
        val ex = java.net.SocketTimeoutException("SSL or socket timeout")
        val error = CreatorErrorNotifier.mapNetworkException(ex)

        assertEquals("Connection Timed Out", error.title)
        assertTrue(error.resolution.contains("internet connection", ignoreCase = true))
    }

    @Test
    fun testErrorMappingForHandshakeFailure() {
        val ex = IllegalStateException("Premature EOF during RTMP handshake")
        val error = CreatorErrorNotifier.mapNetworkException(ex)

        assertEquals("RTMP Handshake Failed", error.title)
        assertTrue(error.resolution.contains("RTMP", ignoreCase = true))
    }

    @Test
    fun testCreatorErrorNotifierListener() {
        val receivedError = AtomicReference<CreatorError?>(null)
        val listener = CreatorErrorNotifier.ErrorListener { error ->
            receivedError.set(error)
        }

        CreatorErrorNotifier.addListener(listener)
        CreatorErrorNotifier.postError("Test Title", "Test Message", "Test Resolution")

        assertNotNull(receivedError.get())
        assertEquals("Test Title", receivedError.get()?.title)
        assertEquals("Test Message", receivedError.get()?.message)
        assertEquals("Test Resolution", receivedError.get()?.resolution)

        CreatorErrorNotifier.removeListener(listener)
    }

    // --- 4. STATE MACHINE & DETERMINISTIC WORKFLOW TESTS ---

    @Test
    fun testStreamStateActiveAndIdlePredicates() {
        assertFalse(StreamState.IDLE.isActive)
        assertTrue(StreamState.IDLE.isIdle)

        assertTrue(StreamState.STARTING.isActive)
        assertFalse(StreamState.STARTING.isIdle)

        assertTrue(StreamState.STREAMING.isActive)
        assertFalse(StreamState.STREAMING.isIdle)

        assertFalse(StreamState.ERROR.isActive)
        assertTrue(StreamState.ERROR.isIdle)
    }

    @Test
    fun testRtmpConnectionStateStreamingPredicate() {
        assertFalse(RtmpConnectionState.IDLE.isStreaming)
        assertFalse(RtmpConnectionState.CONNECTING.isStreaming)
        assertFalse(RtmpConnectionState.HANDSHAKING.isStreaming)
        assertFalse(RtmpConnectionState.CONNECTED.isStreaming)
        assertFalse(RtmpConnectionState.CREATING_STREAM.isStreaming)
        assertTrue(RtmpConnectionState.PUBLISHING.isStreaming)
        assertFalse(RtmpConnectionState.RECONNECTING.isStreaming)
        assertFalse(RtmpConnectionState.ERROR.isStreaming)
    }
}
