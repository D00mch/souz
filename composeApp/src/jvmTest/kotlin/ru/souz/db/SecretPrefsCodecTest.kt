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
    fun `non-sensitive key values are stored without encryption`() = withTestPrefs { prefs ->
        val key = "PUBLIC_VALUE"
        val value = "plain-text"

        val encoded = SecretPrefsCodec.encodeForStorage(key, value)
        val decoded = SecretPrefsCodec.decodeForRead(key, encoded, prefs)

        assertEquals(value, encoded)
        assertEquals(value, decoded)
    }

    @Test
    fun `sensitive key values are encrypted and can be decrypted`() = withTestPrefs { prefs ->
        val value = "super-secret-token"

        val encoded = SecretPrefsCodec.encodeForStorage(ConfigStore.TG_BOT_TOKEN, value)
        val decoded = SecretPrefsCodec.decodeForRead(ConfigStore.TG_BOT_TOKEN, encoded, prefs)

        assertNotEquals(value, encoded)
        assertEquals(value, decoded)
    }

    @Test
    fun `oauth store keys are treated as sensitive`() = withTestPrefs { prefs ->
        val key = "${OAUTH_STORE_PREFIX}github"
        val value = """{"accessToken":"abc"}"""

        val encoded = SecretPrefsCodec.encodeForStorage(key, value)
        val decoded = SecretPrefsCodec.decodeForRead(key, encoded, prefs)

        assertNotEquals(value, encoded)
        assertEquals(value, decoded)
    }

    @Test
    fun `legacy plaintext sensitive values are returned and migrated`() = withTestPrefs { prefs ->
        val key = ConfigStore.TG_BOT_TOKEN
        val value = "legacy-secret"
        prefs.put(key, value)

        val decoded = SecretPrefsCodec.decodeForRead(key, value, prefs)
        val migrated = prefs.get(key, null)

        assertEquals(value, decoded)
        assertNotNull(migrated)
        assertNotEquals(value, migrated)
        assertEquals(value, SecretPrefsCodec.decodeForRead(key, migrated, prefs))
    }

    @Test
    fun `malformed encrypted payload returns null`() = withTestPrefs { prefs ->
        val key = ConfigStore.TG_BOT_TOKEN
        val encoded = SecretPrefsCodec.encodeForStorage(key, "secret")
        val malformed = encoded.substringBeforeLast(':')

        val decoded = SecretPrefsCodec.decodeForRead(key, malformed, prefs)

        assertNull(decoded)
    }

    private fun withTestPrefs(block: (Preferences) -> Unit) {
        val node = Preferences.userRoot().node("/souz-tests/secret-prefs-codec/${UUID.randomUUID()}")
        try {
            block(node)
        } finally {
            runCatching {
                node.clear()
                node.removeNode()
                node.flush()
            }
        }
    }
}
