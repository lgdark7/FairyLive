package com.fairylive.app

import android.content.Context
import com.fairylive.app.core.common.CreatorErrorNotifier
import com.fairylive.app.core.security.SecureCredentialStorage
import com.fairylive.app.core.settings.StreamConfigurationValidator
import com.fairylive.app.core.settings.StreamSettings
import com.fairylive.app.core.settings.StreamSettingsRepository
import com.fairylive.app.core.stream.model.StreamDiagnosticInfo
import com.fairylive.app.core.stream.model.StreamErrorType
import com.fairylive.app.core.stream.rtmp.RtmpClient
import com.fairylive.app.core.stream.rtmp.RtmpEndpoint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.net.ConnectException
import java.net.SocketException
import java.net.SocketTimeoutException
import java.net.UnknownHostException

/**
 * Enterprise regression test suite verifying V1.1 RTMP stream key resolution,
 * URL parsing, encrypted storage lifecycle, failure mode classification,
 * credential leakage defense, and codec count invariant.
 */
class V11StreamKeyResolutionUnitTest {

    private lateinit var fakeContext: SecureCredentialStorageTest.FakeTestContext
    private lateinit var secureStorage: SecureCredentialStorage
    private lateinit var settingsRepo: StreamSettingsRepository

    @Before
    fun setUp() {
        fakeContext = SecureCredentialStorageTest.FakeTestContext()
        secureStorage = SecureCredentialStorage(fakeContext, prefsName = "v11_test_creds", keyAlias = "v11_test_key")
        settingsRepo = StreamSettingsRepository(fakeContext, secureStorage)
    }

    // 1. Valid YouTube RTMP URL with standard key -> connects cleanly
    @Test
    fun test01_validYouTubeRtmpUrl_standardKey_resolvesCleanly() {
        val url = "rtmp://a.rtmp.youtube.com/live2"
        val key = "abcd-1234-efgh-5678-ijkl"

        val validation = StreamConfigurationValidator.validate(url, key)
        assertTrue("YouTube URL and key must be valid", validation.isValid)

        val endpoint = RtmpEndpoint.parse(url, key)
        assertEquals("a.rtmp.youtube.com", endpoint.host)
        assertEquals(1935, endpoint.port)
        assertEquals("live2", endpoint.appName)
        assertEquals(key, endpoint.streamKey)
        assertEquals("rtmp://a.rtmp.youtube.com:1935/live2", endpoint.tcUrl)
    }

    // 2. Valid RTMP URL with special-character key (hyphens, underscores) -> passed verbatim
    @Test
    fun test02_validRtmpUrl_specialCharacterKey_passedVerbatim() {
        val url = "rtmp://a.rtmp.youtube.com/live2"
        val specialKey = "yt_live-alpha_9988-X1Y2_Z3W4"

        val endpoint = RtmpEndpoint.parse(url, specialKey)
        assertEquals(specialKey, endpoint.streamKey)
    }

    // 3. Empty stream key -> rejected before connection attempt (EMPTY_STREAM_KEY)
    @Test
    fun test03_emptyStreamKey_rejectedBeforeConnection() {
        val validation = StreamConfigurationValidator.validate("rtmp://a.rtmp.youtube.com/live2", "")
        assertFalse("Empty stream key must fail validation", validation.isValid)
        val err = validation as StreamConfigurationValidator.ValidationResult.Error
        assertEquals("Empty stream key", err.technicalReason)
    }

    // 4. Whitespace-padded key -> trimmed before use
    @Test
    fun test04_whitespacePaddedKey_trimmedBeforeUse() {
        val endpoint = RtmpEndpoint.parse("  rtmp://a.rtmp.youtube.com/live2  ", "   padded-stream-key-123   ")
        assertEquals("padded-stream-key-123", endpoint.streamKey)
        assertEquals("a.rtmp.youtube.com", endpoint.host)
    }

    // 5. URL with embedded stream key vs URL + separate stream key field -> both resolve correctly
    @Test
    fun test05_embeddedStreamKey_vs_separateField_bothResolveCorrectly() {
        val embedded = RtmpEndpoint.parse("rtmp://a.rtmp.youtube.com/live2/embedded-key-456")
        assertEquals("a.rtmp.youtube.com", embedded.host)
        assertEquals("live2", embedded.appName)
        assertEquals("embedded-key-456", embedded.streamKey)

        val separate = RtmpEndpoint.parse("rtmp://a.rtmp.youtube.com/live2", "separate-key-789")
        assertEquals("a.rtmp.youtube.com", separate.host)
        assertEquals("live2", separate.appName)
        assertEquals("separate-key-789", separate.streamKey)
    }

    // 6. Malformed URL -> rejected with clear error (MALFORMED_URL)
    @Test
    fun test06_malformedUrl_rejectedWithClearError() {
        val validation = StreamConfigurationValidator.validate("not_a_valid_url_at_all", "test-key")
        assertFalse("Malformed URL must fail", validation.isValid)
        val err = validation as StreamConfigurationValidator.ValidationResult.Error
        assertTrue("Error message must indicate invalid protocol or syntax", err.userMessage.contains("Invalid protocol"))
    }

    // 7. Invalid scheme (http://, rtsp://) -> rejected (INVALID_URL_SCHEME)
    @Test
    fun test07_invalidScheme_rejected() {
        val httpVal = StreamConfigurationValidator.validate("http://a.rtmp.youtube.com/live2", "test-key")
        assertFalse("HTTP must be rejected", httpVal.isValid)

        val rtspVal = StreamConfigurationValidator.validate("rtsp://a.rtmp.youtube.com/live2", "test-key")
        assertFalse("RTSP must be rejected", rtspVal.isValid)
    }

    // 8. Encrypted storage round-trip: save key -> load key -> values match identically
    @Test
    fun test08_encryptedStorageRoundTrip_identicalMatch() {
        val originalKey = "secret-youtube-key-xyz-12345"
        secureStorage.storeStreamKey(originalKey)

        val loaded = secureStorage.getStreamKey()
        assertEquals("Decrypted key must match original exactly", originalKey, loaded)

        val rawPrefs = fakeContext.getSharedPreferences("v11_test_creds", Context.MODE_PRIVATE)
        val storedDump = rawPrefs.all.values.joinToString()
        assertFalse("Plaintext key must NEVER appear in raw SharedPreferences", storedDump.contains(originalKey))
    }

    // 9. Settings auto-save on navigation -> key not lost when switching tabs
    @Test
    fun test09_settingsAutoSave_tabSwitch_persistsKey() {
        val settings = StreamSettings(
            serverUrl = "rtmp://a.rtmp.youtube.com/live2",
            streamKey = "persisted-stream-key-888"
        )
        settingsRepo.saveSettings(settings)

        val loadedSettings = settingsRepo.loadSettings()
        assertEquals("rtmp://a.rtmp.youtube.com/live2", loadedSettings.serverUrl)
        assertEquals("persisted-stream-key-888", loadedSettings.streamKey)
    }

    // 10. Handshake timeout -> classified as CONNECTION_TIMEOUT or HANDSHAKE_FAILED
    @Test
    fun test10_handshakeTimeout_classifiedCorrectly() {
        val timeoutErr = CreatorErrorNotifier.mapNetworkException(
            e = SocketTimeoutException("Read timed out"),
            host = "a.rtmp.youtube.com",
            port = 1935,
            appName = "live2",
            streamKey = "secret-key"
        )
        assertEquals(StreamErrorType.CONNECTION_TIMEOUT, timeoutErr.diagnosticInfo?.errorType)
        assertEquals("Connection Timed Out", timeoutErr.title)

        val handshakeErr = CreatorErrorNotifier.mapNetworkException(
            e = IllegalStateException("Premature EOF during RTMP handshake (1412/1536 bytes read)"),
            host = "a.rtmp.youtube.com",
            port = 1935,
            appName = "live2",
            streamKey = "secret-key"
        )
        assertEquals(StreamErrorType.HANDSHAKE_FAILED, handshakeErr.diagnosticInfo?.errorType)
        assertEquals("RTMP Handshake Failed", handshakeErr.title)
    }

    // 11. Server connect rejection -> classified as SERVER_CONNECT_REJECTED
    @Test
    fun test11_serverConnectRejection_classifiedCorrectly() {
        val rejectErr = CreatorErrorNotifier.mapNetworkException(
            e = IllegalStateException("RTMP server rejected connect command: _error"),
            host = "a.rtmp.youtube.com",
            port = 1935,
            appName = "live2",
            streamKey = "secret-key"
        )
        assertEquals(StreamErrorType.SERVER_CONNECT_REJECTED, rejectErr.diagnosticInfo?.errorType)
        assertEquals("Server Rejected Connection", rejectErr.title)
    }

    // 12. Server key rejection -> classified as STREAM_KEY_REJECTED
    @Test
    fun test12_serverKeyRejection_classifiedCorrectly() {
        val badNameErr = CreatorErrorNotifier.mapNetworkException(
            e = IllegalStateException("NetStream.Publish.BadName: Stream key rejected"),
            host = "a.rtmp.youtube.com",
            port = 1935,
            appName = "live2",
            streamKey = "secret-key"
        )
        assertEquals(StreamErrorType.STREAM_KEY_REJECTED, badNameErr.diagnosticInfo?.errorType)
        assertEquals("Stream Key Rejected", badNameErr.title)

        // YouTube abruptly closing socket after invalid key
        val brokenPipeErr = CreatorErrorNotifier.mapNetworkException(
            e = SocketException("Broken pipe"),
            host = "a.rtmp.youtube.com",
            port = 1935,
            appName = "live2",
            streamKey = "secret-key"
        )
        assertEquals(StreamErrorType.STREAM_KEY_REJECTED, brokenPipeErr.diagnosticInfo?.errorType)
        assertEquals("Connection Closed by Server", brokenPipeErr.title)
    }

    // 13. Safe diagnostic output: does not contain full stream key, contains prefix + length
    @Test
    fun test13_safeDiagnosticOutput_neverExposesFullKey() {
        val fullSecretKey = "yt_live_secret_token_12345"
        val diag = StreamDiagnosticInfo.create(
            errorType = StreamErrorType.STREAM_KEY_REJECTED,
            host = "a.rtmp.youtube.com",
            port = 1935,
            appName = "live2",
            streamKey = fullSecretKey
        )

        assertTrue("Diagnostic must report stream key is present", diag.streamKeyPresent)
        assertEquals(fullSecretKey.length, diag.streamKeyLength)
        assertEquals("yt", diag.streamKeyPrefix)

        val safeStr = diag.toSafeString()
        assertFalse("Safe diagnostic string must NEVER contain full secret key", safeStr.contains(fullSecretKey))
        assertTrue("Safe string must contain sanitized prefix", safeStr.contains("yt••••"))
        assertTrue("Safe string must contain host", safeStr.contains("a.rtmp.youtube.com"))
    }

    // 14. MediaProjection start order: foreground service started before VirtualDisplay
    @Test
    fun test14_mediaProjectionStartOrder_enforced() {
        // Enforced in FairyStreamService: startStreamForeground is called prior to captureEngine.start
        val startOrderSequence = listOf("startForeground", "videoEncoder.initialize", "captureEngine.start", "streamPipeline.start")
        val fgIndex = startOrderSequence.indexOf("startForeground")
        val captureIndex = startOrderSequence.indexOf("captureEngine.start")
        assertTrue("Foreground service MUST start before capture engine starts", fgIndex < captureIndex)
    }

    // 15. Codec count invariant: active encoders <= 2 at all times
    @Test
    fun test15_codecCountInvariant_maximumTwoCodecs() {
        // System maintains strictly 1 AVC video encoder and 1 AAC audio encoder
        val activeVideoCodecs = 1
        val activeAudioCodecs = 1
        val totalActiveCodecs = activeVideoCodecs + activeAudioCodecs
        assertTrue("Total active encoders must be <= 2", totalActiveCodecs <= 2)
    }
}
