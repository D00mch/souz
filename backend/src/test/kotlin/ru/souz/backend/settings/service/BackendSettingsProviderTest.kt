package ru.souz.backend.settings.service

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import ru.souz.backend.config.BackendConfigSource
import ru.souz.backend.llm.hasCompleteCodexOAuthCredentials
import ru.souz.backend.settings.repository.BackendServerPreferenceStore
import ru.souz.llms.LLMModel
import ru.souz.llms.LlmProvider
import ru.souz.llms.LocalModelAvailability

class BackendSettingsProviderTest {
    @Test
    fun `codex oauth values use postgres store before deploy config`() {
        val store = MapBackendServerPreferenceStore()
        val provider = provider(
            store = store,
            env = mapOf(
                "CODEX_ACCESS_TOKEN" to "env-access",
                "CODEX_REFRESH_TOKEN" to "env-refresh",
                "CODEX_ACCOUNT_ID" to "env-account",
                "CODEX_EXPIRES_AT" to "1800000000",
            ),
        )

        assertEquals("env-access", provider.codexAccessToken)
        assertTrue(provider.hasCompleteCodexOAuthCredentials())

        provider.codexAccessToken = "stored-access"

        assertEquals("stored-access", store.values.getValue("CODEX_ACCESS_TOKEN"))
        assertEquals("stored-access", provider.codexAccessToken)

        provider.codexAccessToken = null

        assertEquals("", store.values.getValue("CODEX_ACCESS_TOKEN"))
        assertNull(provider.codexAccessToken)
    }

    @Test
    fun `terminal disconnect tombstones survive restart and suppress deploy config`() {
        val store = MapBackendServerPreferenceStore()
        val env = mapOf(
            "CODEX_ACCESS_TOKEN" to "env-access",
            "CODEX_REFRESH_TOKEN" to "env-refresh",
            "CODEX_ACCOUNT_ID" to "env-account",
            "CODEX_EXPIRES_AT" to "1800000000",
        )
        val provider = provider(store = store, env = env)
        assertTrue(provider.hasCompleteCodexOAuthCredentials())

        // Terminal refresh failure clears every field.
        provider.codexAccessToken = null
        provider.codexRefreshToken = null
        provider.codexAccountId = null
        provider.codexExpiresAt = null

        // Rebuild from the same store to simulate a process restart.
        val restarted = provider(store = store, env = env)
        assertNull(restarted.codexAccessToken)
        assertNull(restarted.codexRefreshToken)
        assertNull(restarted.codexAccountId)
        assertNull(restarted.codexExpiresAt)
        assertFalse(restarted.hasCompleteCodexOAuthCredentials())

        // A fresh device-flow login overwrites the tombstones.
        restarted.codexAccessToken = "new-access"
        restarted.codexRefreshToken = "new-refresh"
        restarted.codexAccountId = "new-account"
        restarted.codexExpiresAt = 1_900_000_000L
        assertTrue(provider(store = store, env = env).hasCompleteCodexOAuthCredentials())
    }

    @Test
    fun `backend deploy config controls non codex provider settings`() {
        val provider = provider(
            env = mapOf(
                "APP_LANGUAGE" to "en",
                "GIGA_MODEL" to "gpt-5.2",
                "OPENAI_API_KEY" to "openai-key",
                "OPENAI_MODEL" to "custom-model",
                "OPENAI_SUMMARIZATION_API_KEY" to "  summary-key  ",
                "OPENAI_SUMMARIZATION_BASE_URL" to "  https://summary.test/v1/  ",
                "OPENAI_SUMMARIZATION_CONTEXT_SIZE" to "  1000000  ",
                "OPENAI_SUMMARIZATION_MODEL" to "  provider-summary-model  ",
                "OPENAI_SUMMARIZATION_PARAMETERS" to """{"reasoning_effort":"low","anotherOne":false}""",
                "REQUEST_TIMEOUT_MILLIS" to "45000",
            ),
        )

        assertEquals("en", provider.regionProfile)
        assertEquals(LLMModel.OpenAIGpt52, provider.gigaModel)
        assertEquals("openai-key", provider.openaiKey)
        assertEquals("custom-model", provider.openaiModel)
        assertEquals("summary-key", provider.openaiSummarizationApiKey)
        assertEquals("https://summary.test/v1/", provider.openaiSummarizationBaseUrl)
        assertEquals(1_000_000, provider.summarizationContextSize)
        assertEquals("provider-summary-model", provider.openaiSummarizationModel)
        assertEquals("""{"reasoning_effort":"low","anotherOne":false}""", provider.openaiSummarizationParameters)
        assertEquals(45_000L, provider.requestTimeoutMillis)
        assertTrue(provider.hasKey(LlmProvider.OPENAI))
        assertNull(provider(env = mapOf("OPENAI_SUMMARIZATION_API_KEY" to "  ")).openaiSummarizationApiKey)
        assertNull(provider(env = mapOf("OPENAI_SUMMARIZATION_BASE_URL" to "  ")).openaiSummarizationBaseUrl)
        assertNull(
            provider(env = mapOf("OPENAI_SUMMARIZATION_CONTEXT_SIZE" to "1000000")).summarizationContextSize
        )
        assertNull(provider(env = mapOf("OPENAI_SUMMARIZATION_MODEL" to "  ")).openaiSummarizationModel)
        assertNull(provider(env = mapOf("OPENAI_SUMMARIZATION_PARAMETERS" to "  ")).openaiSummarizationParameters)
    }

    private fun provider(
        store: MapBackendServerPreferenceStore = MapBackendServerPreferenceStore(),
        env: Map<String, String> = emptyMap(),
        properties: Map<String, String> = emptyMap(),
    ): BackendSettingsProvider =
        BackendSettingsProvider(
            preferenceStore = store,
            localProviderAvailability = NoLocalModels,
            source = MapBackendConfigSource(env, properties),
        )
}

private class MapBackendConfigSource(
    private val env: Map<String, String>,
    private val properties: Map<String, String>,
) : BackendConfigSource {
    override fun env(key: String): String? = env[key]

    override fun property(key: String): String? = properties[key]
}

private class MapBackendServerPreferenceStore : BackendServerPreferenceStore {
    val values = mutableMapOf<String, String>()

    override fun get(key: String): String? = values[key]

    override fun put(key: String, value: String) {
        values[key] = value
    }

    override fun remove(key: String) {
        values.remove(key)
    }
}

private object NoLocalModels : LocalModelAvailability {
    override fun availableGigaModels(): List<LLMModel> = emptyList()

    override fun defaultGigaModel(): LLMModel? = null

    override fun isProviderAvailable(): Boolean = false
}
