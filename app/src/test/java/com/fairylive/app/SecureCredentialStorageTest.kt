package com.fairylive.app

import android.content.Context
import android.content.SharedPreferences
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
import java.util.concurrent.ConcurrentHashMap

/**
 * Unit test suite verifying secure credential storage, hardware KeyStore fallback encryption,
 * tampering resilience, and automatic legacy preference migration.
 */
class SecureCredentialStorageTest {

    private lateinit var fakeContext: FakeTestContext
    private lateinit var secureStorage: SecureCredentialStorage
    private lateinit var settingsRepo: StreamSettingsRepository

    @Before
    fun setUp() {
        fakeContext = FakeTestContext()
        secureStorage = SecureCredentialStorage(fakeContext, prefsName = "test_secure_creds", keyAlias = "test_key")
        settingsRepo = StreamSettingsRepository(fakeContext, secureStorage)
    }

    @Test
    fun testStoreAndRetrieveStreamKey() {
        val testKey = "live_secret_token_abcdef123456"
        val stored = secureStorage.storeStreamKey(testKey)
        assertTrue("Storage should succeed", stored)
        assertTrue("Storage should report key exists", secureStorage.hasStreamKey())

        val retrieved = secureStorage.getStreamKey()
        assertEquals("Decrypted key must match original", testKey, retrieved)

        // Verify underlying SharedPreferences does NOT contain plaintext
        val rawPrefs = fakeContext.getSharedPreferences("test_secure_creds", Context.MODE_PRIVATE)
        val allValues = rawPrefs.all.values.joinToString()
        assertFalse("Ciphertext storage must never contain plaintext secret", allValues.contains(testKey))
    }

    @Test
    fun testClearStreamKey() {
        secureStorage.storeStreamKey("temporary_secret_key")
        assertTrue(secureStorage.hasStreamKey())

        secureStorage.clearStreamKey()
        assertFalse(secureStorage.hasStreamKey())
        assertNull(secureStorage.getStreamKey())
    }

    @Test
    fun testNullOrBlankClearsStreamKey() {
        secureStorage.storeStreamKey("test_key")
        assertTrue(secureStorage.hasStreamKey())

        secureStorage.storeStreamKey("")
        assertFalse(secureStorage.hasStreamKey())
        assertNull(secureStorage.getStreamKey())

        secureStorage.storeStreamKey("another_key")
        assertTrue(secureStorage.hasStreamKey())

        secureStorage.storeStreamKey(null)
        assertFalse(secureStorage.hasStreamKey())
        assertNull(secureStorage.getStreamKey())
    }

    @Test
    fun testCorruptedPayloadReturnsNullSafelyWithoutCrashing() {
        val rawPrefs = fakeContext.getSharedPreferences("test_secure_creds", Context.MODE_PRIVATE)
        rawPrefs.edit().putString("enc_stream_key", "CorruptedBase64Payload==!@#").commit()

        val retrieved = secureStorage.getStreamKey()
        assertNull("Corrupted payload must return null rather than throwing", retrieved)
    }

    @Test
    fun testTruncatedPayloadReturnsNullSafely() {
        val rawPrefs = fakeContext.getSharedPreferences("test_secure_creds", Context.MODE_PRIVATE)
        // Truncated payload smaller than 12-byte IV
        rawPrefs.edit().putString("enc_stream_key", "AQIDBA==").commit()

        val retrieved = secureStorage.getStreamKey()
        assertNull("Truncated IV payload must return null rather than throwing", retrieved)
    }

    @Test
    fun testLegacySettingsMigration() {
        val legacyPrefs = fakeContext.getSharedPreferences("fairy_live_settings", Context.MODE_PRIVATE)
        val legacySecret = "legacy_unencrypted_key_9999"

        // Simulate existing pre-M14 installation with plaintext key
        legacyPrefs.edit().putString("stream_key", legacySecret).commit()
        assertTrue("Legacy key exists before migration", legacyPrefs.contains("stream_key"))
        assertFalse("Secure storage is initially empty", secureStorage.hasStreamKey())

        // Load settings — triggers automatic migration
        val settings = settingsRepo.loadSettings(StreamProfile.LOW)

        assertEquals("Settings should load legacy key", legacySecret, settings.streamKey)
        assertTrue("Key should now be persisted in secure storage", secureStorage.hasStreamKey())
        assertFalse("Legacy plaintext stream_key must be purged from fairy_live_settings", legacyPrefs.contains("stream_key"))
    }

    @Test
    fun testSaveSettingsPersistsToSecureStorageAndPurgesLegacyKey() {
        val legacyPrefs = fakeContext.getSharedPreferences("fairy_live_settings", Context.MODE_PRIVATE)
        legacyPrefs.edit().putString("stream_key", "old_plaintext").commit()

        val newSettings = StreamSettings(
            serverUrl = "rtmp://live.myplatform.tv/app",
            streamKey = "new_secure_key_888",
            profile = StreamProfile.HIGH
        )

        settingsRepo.saveSettings(newSettings)

        // Verify secure storage has new key
        assertEquals("new_secure_key_888", secureStorage.getStreamKey())
        // Verify legacy prefs do not contain stream_key
        assertFalse(legacyPrefs.contains("stream_key"))
    }

    // ---------------------------------------------------------------------------------------------
    // In-memory Test Context & SharedPreferences Implementation (Zero Mockito Dependency)
    // ---------------------------------------------------------------------------------------------

    class FakeTestContext : android.content.ContextWrapper(null) {
        private val sharedPreferencesMap = ConcurrentHashMap<String, SharedPreferences>()

        override fun getSharedPreferences(name: String, mode: Int): SharedPreferences {
            return sharedPreferencesMap.computeIfAbsent(name) { FakeSharedPreferences() }
        }

        override fun getApplicationContext(): Context = this
        override fun getPackageName(): String = "com.fairylive.app.test"
    }

    class FakeSharedPreferences : SharedPreferences {
        private val data = ConcurrentHashMap<String, Any>()

        override fun getAll(): MutableMap<String, *> = HashMap(data)
        override fun getString(key: String?, defValue: String?): String? = data[key] as? String ?: defValue
        override fun getStringSet(key: String?, defValues: MutableSet<String>?): MutableSet<String>? = (data[key] as? Set<*>)?.mapNotNull { it as? String }?.toMutableSet() ?: defValues
        override fun getInt(key: String?, defValue: Int): Int = data[key] as? Int ?: defValue
        override fun getLong(key: String?, defValue: Long): Long = data[key] as? Long ?: defValue
        override fun getFloat(key: String?, defValue: Float): Float = data[key] as? Float ?: defValue
        override fun getBoolean(key: String?, defValue: Boolean): Boolean = data[key] as? Boolean ?: defValue
        override fun contains(key: String?): Boolean = data.containsKey(key)
        override fun edit(): SharedPreferences.Editor = FakeEditor(data)
        override fun registerOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener?) {}
        override fun unregisterOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener?) {}

        class FakeEditor(private val target: ConcurrentHashMap<String, Any>) : SharedPreferences.Editor {
            private val pending = HashMap<String, Any?>()
            private var clearAll = false

            override fun putString(key: String?, value: String?): SharedPreferences.Editor {
                if (key != null) {
                    if (value != null) pending[key] = value else pending[key] = null
                }
                return this
            }
            override fun putStringSet(key: String?, values: MutableSet<String>?): SharedPreferences.Editor {
                if (key != null) {
                    if (values != null) pending[key] = HashSet(values) else pending[key] = null
                }
                return this
            }
            override fun putInt(key: String?, value: Int): SharedPreferences.Editor {
                if (key != null) pending[key] = value
                return this
            }
            override fun putLong(key: String?, value: Long): SharedPreferences.Editor {
                if (key != null) pending[key] = value
                return this
            }
            override fun putFloat(key: String?, value: Float): SharedPreferences.Editor {
                if (key != null) pending[key] = value
                return this
            }
            override fun putBoolean(key: String?, value: Boolean): SharedPreferences.Editor {
                if (key != null) pending[key] = value
                return this
            }
            override fun remove(key: String?): SharedPreferences.Editor {
                if (key != null) pending[key] = null
                return this
            }
            override fun clear(): SharedPreferences.Editor {
                clearAll = true
                return this
            }
            override fun commit(): Boolean {
                apply()
                return true
            }
            override fun apply() {
                if (clearAll) target.clear()
                for ((k, v) in pending) {
                    if (v == null) target.remove(k) else target[k] = v
                }
                pending.clear()
            }
        }
    }
}
