package ru.souz.db

import io.mockk.mockk
import ru.souz.llms.LlmProvider
import ru.souz.llms.VoiceRecognitionProvider
import ru.souz.llms.VoiceRecognitionModel
import ru.souz.service.mcp.OAUTH_STORE_PREFIX
import java.util.UUID
import java.util.prefs.Preferences
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

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
    fun `Polza key is encrypted and available to desktop speech settings`() =
        withStoredPreference(SettingsProviderImpl.POLZA_API_KEY) { key ->
            val provider = SettingsProviderImpl(ConfigStore, mockk(relaxed = true))
            provider.polzaApiKey = "pza_fixture_secret"

            assertNotEquals("pza_fixture_secret", prefs.get(key, null))
            assertEquals("pza_fixture_secret", provider.polzaApiKey)
            assertTrue(provider.hasKey(VoiceRecognitionProvider.POLZA))
            provider.polzaApiKey = null
            assertFalse(ConfigStore.contains(key))
        }

    @Test
    fun `saved Salute model migrates to GigaAM`() =
        withStoredPreference("VOICE_RECOGNITION_MODEL") { key ->
            val provider = SettingsProviderImpl(ConfigStore, mockk(relaxed = true))
            for (legacy in listOf("SaluteSpeech", "salute-speech")) {
                prefs.put(key, legacy)
                assertEquals(VoiceRecognitionModel.PolzaGigaAm, provider.voiceRecognitionModel)
            }
            provider.voiceRecognitionModel = VoiceRecognitionModel.PolzaGigaAm
            assertEquals("PolzaGigaAm", prefs.get(key, null))
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

    @Test
    fun `raw presence check does not migrate or decrypt a sensitive value`() =
        withStoredPreference(ConfigStore.TG_BOT_TOKEN) { key ->
            val legacy = "legacy-secret"
            prefs.put(key, legacy)

            assertTrue(ConfigStore.contains(key))
            assertEquals(legacy, prefs.get(key, null))

            ConfigStore.rm(key)
            assertFalse(ConfigStore.contains(key))
        }

    @Test
    fun `desktop provider presence accepts malformed encrypted payload without decrypting it`() =
        withStoredPreference(SettingsProviderImpl.AI_TUNNEL_KEY) { key ->
            prefs.put(key, "enc:v1:malformed")
            val provider = SettingsProviderImpl(ConfigStore, mockk(relaxed = true))

            assertTrue(provider.hasKey(LlmProvider.AI_TUNNEL))
            assertEquals("enc:v1:malformed", prefs.get(key, null))
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
