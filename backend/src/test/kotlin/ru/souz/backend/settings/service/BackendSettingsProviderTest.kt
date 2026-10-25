package ru.souz.backend.settings.service

import ru.souz.agent.spi.ModelRouting
import ru.souz.agent.spi.RoutedModel
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
import ru.souz.llms.restJsonMapper
import ru.souz.db.SUBAGENT_MODELS_JSON

class BackendSettingsProviderTest {
    @Test
    fun `one server context window is loaded and validated at startup`() {
        assertEquals(125_000, provider().contextSize)
        assertEquals(24_000, provider(env = mapOf("CONTEXT_SIZE" to "24000")).contextSize)
        assertEquals(64_000, provider(properties = mapOf("CONTEXT_SIZE" to "64000")).contextSize)
        assertEquals(125_000, provider(env = mapOf("CONTEXT_SIZE" to " 125000 "),
            properties = mapOf("CONTEXT_SIZE" to "64000")).contextSize)
        for (value in listOf("0", "-1", "23999", "1.5", "invalid", "", " ", "2147483648")) {
            assertFailsWith<IllegalArgumentException> { provider(env = mapOf("CONTEXT_SIZE" to value)) }
        }
        // The removed summary setting cannot change the server window.
        assertEquals(125_000, provider(env = mapOf("OPENAI_SUMMARIZATION_CONTEXT_SIZE" to "1")).contextSize)
    }

    @Test
    fun `named models load independent connections and reject broken configuration`() {
        withNamedLlmConfig { source ->
            val settings = BackendSettingsProvider(MapBackendServerPreferenceStore(), source)
            assertEquals(setOf("test-chat", "tv-agent"), source.loadNamedModels().models.keys)
            assertEquals(mapOf("tv-agent" to LlmProvider.OPENAI), settings.subagentModels)
            assertEquals("https://router.test/v1", settings.openaiSummarizationBaseUrl)
            assertEquals("router-secret", settings.openaiSummarizationApiKey)
            assertEquals("shared-model", settings.openaiSummarizationModel)
        }
        val valid = NAMED_LLM_CONFIG
        for (port in listOf(1, 65535)) {
            withNamedLlmConfig(valid.replace("router.test/v1", "router.test:$port/v1")) { source ->
                val settings = BackendSettingsProvider(MapBackendServerPreferenceStore(), source)
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
                assertFailsWith<Exception> { BackendSettingsProvider(MapBackendServerPreferenceStore(), source) }
            }
        }
    }

    @Test
    fun `named model parameters validate objects and merge summary defaults with model precedence`() {
        val configuredModel = "\"modelId\":\"shared-model\""
        withNamedLlmConfig(
            NAMED_LLM_CONFIG.replace(configuredModel,
                """$configuredModel,"parameters":{"thinking":{"type":"disabled"},"reasoning_effort":"low"}"""),
            environment = mapOf("OPENAI_SUMMARIZATION_PARAMETERS" to
                """{"thinking":{"type":"enabled"},"reasoning_effort":"high","top_p":0.9}"""),
        ) { source ->
            val models = source.loadNamedModels()
            assertEquals("disabled", restJsonMapper.readTree(models.models.getValue("test-chat").parameters)["thinking"]["type"].asText())
            val settings = BackendSettingsProvider(MapBackendServerPreferenceStore(), source, models)
            val summary = restJsonMapper.readTree(settings.openaiSummarizationParameters)
            assertEquals("disabled", summary["thinking"]["type"].asText())
            assertEquals("low", summary["reasoning_effort"].asText())
            assertEquals(0.9, summary["top_p"].asDouble())
        }
        withNamedLlmConfig { source -> assertNull(source.loadNamedModels().models.getValue("test-chat").parameters) }
        for (parameters in listOf("null", "[]", "true", "1", "\"disabled\"",
            """{"thinking":{"type":"disabled","type":"enabled"}}""") +
            listOf("model", "messages", "stream", "tools", "tool_choice", "max_tokens", "max_completion_tokens", "context_size")
                .map { """{"$it":null}""" }) {
            withNamedLlmConfig(NAMED_LLM_CONFIG.replace(configuredModel, "$configuredModel,\"parameters\":$parameters")) { source ->
                assertFailsWith<Exception> { source.loadNamedModels() }
            }
        }
    }

    @Test
    fun `builtin model effort accepts parameter-only entries without changing named selections`() {
        val model = ru.souz.llms.findLLMModel("gpt-6.1-sol")!!
        fun configuration(entry: String) = NAMED_LLM_CONFIG.replace("\"models\": {", "\"models\": {$entry,")
        val entry = """"${model.alias}":{"parameters":{"reasoning_effort":"low"}}"""
        withNamedLlmConfig(configuration(entry)) { source ->
            val config = source.loadNamedModels()
            assertEquals(mapOf(model.alias to "low"), config.builtinReasoningEfforts)
            assertEquals(setOf("test-chat", "tv-agent"), config.models.keys)
        }
        for (invalid in listOf(
            """"${model.alias}":{}""",
            """"${model.alias}":{"parameters":{"reasoning_effort":false}}""",
            """"${model.alias}":{"parameters":{"reasoning_effort":" "}}""",
            """"${model.alias}":{"parameters":{"thinking":{"type":"disabled"}}}""",
            """$entry,"${model.name}":{"parameters":{"reasoning_effort":"high"}}""",
        )) {
            withNamedLlmConfig(configuration(invalid)) { source -> assertFailsWith<IllegalArgumentException> { source.loadNamedModels() } }
        }
    }

    @Test
    fun `routing resolves named and built-in models and rejects broken blocks`() {
        val complex = """, "complex": {"model":"${LLMModel.Max.name}"}"""
        val valid = NAMED_LLM_CONFIG.trimEnd().removeSuffix("}") +
            """, "routing": {"simple": {"model":"tv-agent"}$complex}}"""
        withNamedLlmConfig(valid) { source ->
            assertEquals(
                ModelRouting(RoutedModel("tv-agent", LlmProvider.OPENAI), RoutedModel(LLMModel.Max.alias, LLMModel.Max.provider)),
                source.loadNamedModels().routing,
            )
        }
        withNamedLlmConfig { source -> assertNull(source.loadNamedModels().routing) }
        (listOf("missing", LLMModel.OpenAICompatibleCustom.alias).map {
            valid.replace("\"simple\": {\"model\":\"tv-agent\"}", "\"simple\": {\"model\":\"$it\"}")
        } + valid.replace(complex, "")).forEach { json ->
            withNamedLlmConfig(json) { source -> assertFailsWith<IllegalArgumentException> { source.loadNamedModels() } }
        }
    }

    @Test
    fun `named model names cannot collide with legacy routing`() {
        for ((key, value) in mapOf("OPENAI_MODEL" to " test-chat ", SUBAGENT_MODELS_JSON to """{"OPENAI":["tv-agent"]}""")) {
            withNamedLlmConfig(environment = mapOf(key to value)) { source ->
                assertFailsWith<IllegalArgumentException> { BackendSettingsProvider(MapBackendServerPreferenceStore(), source) }
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
