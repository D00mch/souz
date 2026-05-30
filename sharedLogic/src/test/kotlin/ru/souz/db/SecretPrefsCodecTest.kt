package ru.souz.db

import ru.souz.service.mcp.OAUTH_STORE_PREFIX
import java.util.UUID
import java.util.prefs.Preferences
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class SecretPrefsCodecTest {

    private val masterKeyProperty = "SOUZ_MASTER_KEY"
    private val prefs = Preferences.userNodeForPackage(ConfigStore::class.java)
    private var previousMasterKeyProperty: String? = null

    @BeforeTest
    fun setUp() {
        previousMasterKeyProperty = System.getProperty(masterKeyProperty)
        System.setProperty(masterKeyProperty, "test-master-key")
    }

    @AfterTest
    fun tearDown() {
        if (previousMasterKeyProperty == null) {
            System.clearProperty(masterKeyProperty)
        } else {
            System.setProperty(masterKeyProperty, previousMasterKeyProperty!!)
        }
    }

    @Test
    fun `non-sensitive key values are stored without encryption`() = withStoredPreference("PUBLIC_VALUE_${UUID.randomUUID()}") { key ->
        val value = "plain-text"

        ConfigStore.put(key, value)
        val stored = prefs.get(key, null)
        val decoded = ConfigStore.get<String>(key)

        assertEquals(value, stored)
        assertEquals(value, decoded)
    }

    @Test
    fun `sensitive key values are encrypted and can be decrypted`() = withStoredPreference(ConfigStore.TG_BOT_TOKEN) { key ->
        val value = "super-secret-token"

        ConfigStore.put(key, value)
        val stored = prefs.get(key, null)
        val decoded = ConfigStore.get<String>(key)

        assertNotNull(stored)
        assertNotEquals(value, stored)
        assertEquals(value, decoded)
    }

    @Test
    fun `oauth store keys are treated as sensitive`() = withStoredPreference("${OAUTH_STORE_PREFIX}github") { key ->
        val value = """{"accessToken":"abc"}"""

        ConfigStore.put(key, value)
        val stored = prefs.get(key, null)
        val decoded = ConfigStore.get<String>(key)

        assertNotNull(stored)
        assertNotEquals(value, stored)
        assertEquals(value, decoded)
    }

    @Test
    fun `legacy plaintext sensitive values are returned and migrated`() = withStoredPreference(ConfigStore.TG_BOT_TOKEN) { key ->
        val value = "legacy-secret"
        prefs.put(key, value)

        val decoded = ConfigStore.get<String>(key)
        val migrated = prefs.get(key, null)

        assertEquals(value, decoded)
        assertNotNull(migrated)
        assertNotEquals(value, migrated)
        assertEquals(value, ConfigStore.get<String>(key))
    }

    @Test
    fun `malformed encrypted payload returns null`() = withStoredPreference(ConfigStore.TG_BOT_TOKEN) { key ->
        ConfigStore.put(key, "secret")
        val encoded = prefs.get(key, null)
        assertNotNull(encoded)
        val malformed = encoded.substringBeforeLast(':')
        assertNotEquals(encoded, malformed)
        prefs.put(key, malformed)

        val decoded = ConfigStore.get<String>(key)

        assertNull(decoded)
    }

    private fun withStoredPreference(key: String, block: (String) -> Unit) {
        val previousValue = prefs.get(key, null)
        try {
            block(key)
        } finally {
            runCatching {
                if (previousValue == null) {
                    prefs.remove(key)
                } else {
                    prefs.put(key, previousValue)
                }
                prefs.flush()
            }
        }
    }
}
