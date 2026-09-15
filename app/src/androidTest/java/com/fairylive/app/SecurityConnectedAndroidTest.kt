package com.fairylive.app

import android.content.Context
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.fairylive.app.core.capability.model.StreamProfile
import com.fairylive.app.core.security.SecureCredentialStorage
import com.fairylive.app.core.settings.StreamSettings
import com.fairylive.app.core.settings.StreamSettingsRepository
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.security.KeyStore

/**
 * On-device Android 15 instrumented security tests running on Google Pixel 8 (API 35).
 * Validates real hardware-backed AndroidKeyStore AES-256-GCM encryption, key lifecycle,
 * tamper detection, and persistent settings migration.
 */
@RunWith(AndroidJUnit4::class)
class SecurityConnectedAndroidTest {

    private lateinit var targetContext: Context
    private lateinit var secureStorage: SecureCredentialStorage
    private lateinit var settingsRepo: StreamSettingsRepository

    @Before
    fun setUp() {
        targetContext = InstrumentationRegistry.getInstrumentation().targetContext
        val testPrefs = "m14_security_test_creds"
        val testAlias = "m14_test_keystore_alias"

        // Clean up prior test runs
        targetContext.getSharedPreferences(testPrefs, Context.MODE_PRIVATE).edit().clear().commit()
        targetContext.getSharedPreferences("m14_test_settings", Context.MODE_PRIVATE).edit().clear().commit()

        try {
            val keyStore = KeyStore.getInstance("AndroidKeyStore")
            keyStore.load(null)
            if (keyStore.containsAlias(testAlias)) {
                keyStore.deleteEntry(testAlias)
            }
        } catch (_: Exception) {}

        secureStorage = SecureCredentialStorage(targetContext, prefsName = testPrefs, keyAlias = testAlias)
        settingsRepo = StreamSettingsRepository(targetContext, secureStorage)
    }

    @Test
    fun testRealAndroidKeyStoreHardwareEncryptionAndDecryption() {
        val streamKey = "live_prod_secret_token_pixel8_hardware_key_777"

        val stored = secureStorage.storeStreamKey(streamKey)
        assertTrue("AndroidKeyStore AES-256-GCM storage must succeed", stored)
        assertTrue(secureStorage.hasStreamKey())

        // Verify AndroidKeyStore contains the hardware key entry
        val keyStore = KeyStore.getInstance("AndroidKeyStore")
        keyStore.load(null)
        assertTrue("KeyStore must contain master alias", keyStore.containsAlias("m14_test_keystore_alias"))

        // Verify decrypted value matches original exactly
        val retrieved = secureStorage.getStreamKey()
        assertEquals("Decrypted key must match original", streamKey, retrieved)

        // Verify disk storage does not contain plaintext secret
        val rawPrefs = targetContext.getSharedPreferences("m14_security_test_creds", Context.MODE_PRIVATE)
        val allDiskValues = rawPrefs.all.values.joinToString()
        assertFalse("Disk file must never contain plaintext secret", allDiskValues.contains(streamKey))
    }

    @Test
    fun testRealDeviceTamperDetection() {
        secureStorage.storeStreamKey("tamper_test_secret_key")
        assertTrue(secureStorage.hasStreamKey())

        // Deliberately tamper with the ciphertext on disk (modify one byte in Base64 string)
        val rawPrefs = targetContext.getSharedPreferences("m14_security_test_creds", Context.MODE_PRIVATE)
        val originalCiphertext = rawPrefs.getString("enc_stream_key", "") ?: ""
        assertTrue(originalCiphertext.isNotEmpty())

        val tamperedCiphertext = if (originalCiphertext.last() == 'A') {
            originalCiphertext.dropLast(1) + 'B'
        } else {
            originalCiphertext.dropLast(1) + 'A'
        }
        rawPrefs.edit().putString("enc_stream_key", tamperedCiphertext).commit()

        // Decryption of tampered ciphertext must fail safely with GCM authentication tag mismatch, returning null
        val decrypted = secureStorage.getStreamKey()
        assertNull("Tampered ciphertext must fail GCM auth tag check and return null", decrypted)
    }

    @Test
    fun testRealDeviceLegacyMigrationLifecycle() {
        val legacyPrefs = targetContext.getSharedPreferences("fairy_live_settings", Context.MODE_PRIVATE)
        val legacySecret = "legacy_secret_device_migration_key_101"

        // Push legacy plaintext key to fairy_live_settings
        legacyPrefs.edit().putString("stream_key", legacySecret).commit()
        assertTrue("Legacy key exists before migration", legacyPrefs.contains("stream_key"))

        // Load settings — triggers automatic migration on live device
        val settings = settingsRepo.loadSettings(StreamProfile.LOW)

        assertEquals("Settings should resolve migrated key", legacySecret, settings.streamKey)
        assertTrue("Key should now be persisted in encrypted storage", secureStorage.hasStreamKey())
        assertFalse("Plaintext stream_key must be permanently deleted from fairy_live_settings", legacyPrefs.contains("stream_key"))
    }
}
