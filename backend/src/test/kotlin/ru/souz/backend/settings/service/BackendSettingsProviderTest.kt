package ru.souz.backend.settings.service

import ru.souz.backend.config.loadNamedModels
import ru.souz.backend.llm.withNamedLlmConfig
import ru.souz.backend.llm.NAMED_LLM_CONFIG
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue
import ru.souz.backend.config.BackendConfigSource
import ru.souz.backend.llm.hasCompleteCodexOAuthCredentials
import ru.souz.backend.settings.repository.BackendServerPreferenceStore
import ru.souz.llms.LLMModel
import ru.souz.llms.LlmProvider
import ru.souz.llms.LocalModelAvailability
import ru.souz.db.SUBAGENT_MODELS_JSON

class BackendSettingsProviderTest {
    @Test
    fun `one server context window is loaded and validated at startup`() {
        assertEquals(125_000, provider().contextSize)
        assertEquals(64_000, provider(properties = mapOf("CONTEXT_SIZE" to "64000")).contextSize)
        assertEquals(125_000, provider(env = mapOf("CONTEXT_SIZE" to " 125000 "),
            properties = mapOf("CONTEXT_SIZE" to "64000")).contextSize)
        for (value in listOf("0", "-1", "1.5", "invalid", "", " ", "2147483648")) {
            assertFailsWith<IllegalArgumentException> { provider(env = mapOf("CONTEXT_SIZE" to value)) }
        }
        // The removed summary setting cannot change the server window.
        assertEquals(125_000, provider(env = mapOf("OPENAI_SUMMARIZATION_CONTEXT_SIZE" to "1")).contextSize)
    }

    @Test
    fun `named models load independent connections and reject broken configuration`() {
        withNamedLlmConfig { source ->
            val settings = BackendSettingsProvider(MapBackendServerPreferenceStore(), NoLocalModels, source)
            assertEquals(setOf("test-chat", "tv-agent"), source.loadNamedModels().models.keys)
            assertEquals(mapOf("tv-agent" to LlmProvider.OPENAI), settings.subagentModels)
            assertEquals("https://router.test/v1", settings.openaiSummarizationBaseUrl)
            assertEquals("router-secret", settings.openaiSummarizationApiKey)
            assertEquals("shared-model", settings.openaiSummarizationModel)
        }
        val valid = NAMED_LLM_CONFIG
        for (port in listOf(1, 65535)) {
            withNamedLlmConfig(valid.replace("router.test/v1", "router.test:$port/v1")) { source ->
                val settings = BackendSettingsProvider(MapBackendServerPreferenceStore(), NoLocalModels, source)
                assertEquals("https://router.test:$port/v1", settings.openaiSummarizationBaseUrl)
            }
        }
        listOf(
            valid.replace("EXPERIMENT_KEY", "MISSING_KEY"),
            valid.replace("\"summarization\": {", "\"summarization\": {\"contextSize\":32000,"),
            valid.replace("\"connection\":\"experiment\"", "\"connection\":\"missing\""),
            valid.replace("test-chat", "QwenMax"),
            valid.replace("\"model\":\"tv-agent\"", "\"model\":\"missing\""),
            valid.replace("\"models\":", "\"unknownField\":{},\"models\":"),
            valid + " {}",
            valid.replace("\"subagentModels\": [\"tv-agent\"]", "\"subagentModels\": [\"missing\"]"),
            valid.replace("https://router.test/v1", "file:///tmp/model"),
            valid.replace("https://router.test/v1", "https://router.test:99999/v1"),
            valid.replace("https://router.test/v1", "https://router.test:0/v1"),
            valid.replace("https://router.test/v1", "https://user:secret@router.test/v1"),
            valid.replace("\"models\":", "\"models\":{},\"models\":"),
        ).forEach { json ->
            withNamedLlmConfig(json) { source ->
                assertFailsWith<Exception> { BackendSettingsProvider(MapBackendServerPreferenceStore(), NoLocalModels, source) }
            }
        }
    }

    @Test
    fun `named model names cannot collide with legacy routing`() {
        for ((key, value) in mapOf("OPENAI_MODEL" to " test-chat ", SUBAGENT_MODELS_JSON to """{"OPENAI":["tv-agent"]}""")) {
            withNamedLlmConfig(environment = mapOf(key to value)) { source ->
                assertFailsWith<IllegalArgumentException> { BackendSettingsProvider(MapBackendServerPreferenceStore(), NoLocalModels, source) }
            }
        }
    }

    @Test
    fun `subagent configuration uses deploy precedence and rejects unsupported or invalid values`() {
        val property = mapOf(SUBAGENT_MODELS_JSON to """{"ANTHROPIC":["property-model"]}""")
        assertTrue(provider().subagentModels.isEmpty())
        assertEquals(mapOf("property-model" to LlmProvider.ANTHROPIC), provider(properties = property).subagentModels)
        assertEquals(
            mapOf("env-model" to LlmProvider.OPENAI),
            provider(env = mapOf(SUBAGENT_MODELS_JSON to """{"OPENAI":["env-model"]}"""), properties = property).subagentModels,
        )
        listOf("broken", " ", """{"GIGA":["model"]}""", """{"OPENAI":["x"],"ANTHROPIC":["x"]}""").forEach {
            assertFailsWith<IllegalArgumentException> { provider(env = mapOf(SUBAGENT_MODELS_JSON to it)) }
        }
    }

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
    fun `backend deploy config controls non codex provider settings`() {
        val provider = provider(
            env = mapOf(
                "APP_LANGUAGE" to "en",
                "GIGA_MODEL" to "gpt-5.2",
                "OPENAI_API_KEY" to "openai-key",
                "OPENAI_MODEL" to "custom-model",
                "OPENAI_SUMMARIZATION_API_KEY" to "  summary-key  ",
                "OPENAI_SUMMARIZATION_BASE_URL" to "  https://summary.test/v1/  ",
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
        assertEquals("provider-summary-model", provider.openaiSummarizationModel)
        assertEquals("""{"reasoning_effort":"low","anotherOne":false}""", provider.openaiSummarizationParameters)
        assertEquals(45_000L, provider.requestTimeoutMillis)
        assertTrue(provider.hasKey(LlmProvider.OPENAI))
        assertNull(provider(env = mapOf("OPENAI_SUMMARIZATION_API_KEY" to "  ")).openaiSummarizationApiKey)
        assertNull(provider(env = mapOf("OPENAI_SUMMARIZATION_BASE_URL" to "  ")).openaiSummarizationBaseUrl)
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

internal class MapBackendServerPreferenceStore : BackendServerPreferenceStore {
    val values = mutableMapOf<String, String>()

    override fun get(key: String): String? = values[key]

    override fun put(key: String, value: String) {
        values[key] = value
    }

    override fun remove(key: String) {
        values.remove(key)
    }
}

internal object NoLocalModels : LocalModelAvailability {
    override fun availableGigaModels(): List<LLMModel> = emptyList()

    override fun defaultGigaModel(): LLMModel? = null

    override fun isProviderAvailable(): Boolean = false
}
