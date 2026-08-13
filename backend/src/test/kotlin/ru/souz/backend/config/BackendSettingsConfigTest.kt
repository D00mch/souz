package ru.souz.backend.config

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import ru.souz.agent.AgentId
import ru.souz.backend.agent.model.BackendConversationTurnRequest
import ru.souz.backend.common.BackendConfigurationException
import ru.souz.llms.EmbeddingsModel
import ru.souz.llms.LLMModel
import ru.souz.llms.LlmProvider

class BackendSettingsConfigTest {
    @Test
    fun `loads one immutable deployment snapshot`() {
        val environment = mutableMapOf(
            "APP_LANGUAGE" to " en ",
            "GIGA_MODEL" to "gpt-5.2",
            "OPENAI_API_KEY" to " server-openai-key ",
            "OPENAI_BASE_URL" to " https://llm.example/v1 ",
            "OPENAI_MODEL" to "custom-chat-model",
            "USE_FEW_SHOTS" to "true",
            "USE_STREAMING" to "true",
            "REQUEST_TIMEOUT_MILLIS" to "45000",
            "CONTEXT_SIZE" to "32000",
            "TEMPERATURE" to "0.25",
            "EMBEDDINGS_MODEL" to "OpenAITextEmbedding3Small",
        )

        val config = BackendSettingsConfig.load(MapBackendSettingsConfigSource(environment))
        environment.replaceAll { _, _ -> "changed-after-construction" }

        assertEquals("en", config.regionProfile)
        assertEquals(LLMModel.OpenAIGpt52, config.gigaModel)
        assertEquals("server-openai-key", config.openaiKey)
        assertEquals("https://llm.example/v1", config.openaiBaseUrl)
        assertEquals("custom-chat-model", config.openaiModel)
        assertTrue(config.useFewShotExamples)
        assertTrue(config.useStreaming)
        assertEquals(45_000L, config.requestTimeoutMillis)
        assertEquals(32_000, config.contextSize)
        assertEquals(0.25f, config.temperature)
        assertEquals(EmbeddingsModel.OpenAITextEmbedding3Small, config.embeddingsModel)
    }

    @Test
    fun `does not expose environment-seeded Codex credentials`() {
        val config = BackendSettingsConfig.load(
            MapBackendSettingsConfigSource(
                environment = mapOf(
                    "CODEX_ACCESS_TOKEN" to "seed-access",
                    "CODEX_REFRESH_TOKEN" to "seed-refresh",
                    "CODEX_ACCOUNT_ID" to "seed-account",
                    "CODEX_EXPIRES_AT" to "2000000000",
                )
            )
        )

        assertFalse(config.hasKey(LlmProvider.CODEX))
        assertFalse(config.hasKey(LlmProvider.LOCAL))
    }

    @Test
    fun `execution snapshot owns request mutations without changing deployment settings`() {
        val deployment = BackendSettingsConfig(
            gigaModel = LLMModel.Max,
            requestTimeoutMillis = 30_000,
            contextSize = 16_000,
            temperature = 0.7f,
        )
        val settings = BackendExecutionSettings(
            deployment = deployment,
            defaultSystemPrompt = "deployment prompt",
            locale = "ru-RU",
        )

        settings.applyRequest(
            request = BackendConversationTurnRequest(
                prompt = "hello",
                model = LLMModel.OpenAIGpt52.alias,
                contextSize = 8_000,
                temperature = 0.25f,
                locale = "en-US",
                timeZone = "UTC",
                systemPrompt = "request prompt",
                useFewShotExamples = true,
                streamingMessages = true,
                requestTimeoutMillis = 45_000,
            ),
            temperature = deployment.temperature,
        )

        assertEquals(AgentId.SKILLS_GRAPH, settings.activeAgentId)
        assertEquals(LLMModel.OpenAIGpt52, settings.gigaModel)
        assertEquals("en", settings.regionProfile)
        assertEquals(8_000, settings.contextSize)
        assertEquals(0.25f, settings.temperature)
        assertTrue(settings.useFewShotExamples)
        assertTrue(settings.useStreaming)
        assertEquals(45_000, settings.requestTimeoutMillis)
        assertEquals(
            "request prompt",
            settings.getSystemPromptForAgentModel(AgentId.SKILLS_GRAPH, LLMModel.OpenAIGpt52),
        )
        assertEquals(LLMModel.Max, deployment.gigaModel)
        assertEquals(30_000, deployment.requestTimeoutMillis)
    }

    @Test
    fun `rejects filesystem and local model configuration`() {
        val fileError = assertFailsWith<BackendConfigurationException> {
            BackendSettingsConfig.load(
                MapBackendSettingsConfigSource(environment = mapOf("MCP_SERVERS_FILE" to "/state/mcp.json"))
            )
        }
        val jsonError = assertFailsWith<BackendConfigurationException> {
            BackendSettingsConfig.load(
                MapBackendSettingsConfigSource(environment = mapOf("MCP_SERVERS_JSON" to "{\"mcpServers\":{}}"))
            )
        }
        val modelError = assertFailsWith<BackendConfigurationException> {
            BackendSettingsConfig.load(
                MapBackendSettingsConfigSource(
                    environment = mapOf("GIGA_MODEL" to LLMModel.LocalQwen3_4B_Instruct_2507.alias)
                )
            )
        }
        val embeddingsError = assertFailsWith<BackendConfigurationException> {
            BackendSettingsConfig.load(
                MapBackendSettingsConfigSource(
                    environment = mapOf(
                        "EMBEDDINGS_MODEL" to EmbeddingsModel.LocalEmbeddingGemma300M.alias
                    )
                )
            )
        }

        assertTrue(fileError.message.orEmpty().contains("unavailable"))
        assertTrue(jsonError.message.orEmpty().contains("disabled"))
        assertTrue(modelError.message.orEmpty().contains("Local model"))
        assertTrue(embeddingsError.message.orEmpty().contains("Local embeddings"))
    }

    @Test
    fun `rejects malformed scalar deployment values`() {
        val error = assertFailsWith<BackendConfigurationException> {
            BackendSettingsConfig.load(
                MapBackendSettingsConfigSource(environment = mapOf("CONTEXT_SIZE" to "many"))
            )
        }

        assertTrue(error.message.orEmpty().contains("CONTEXT_SIZE"))
    }
}

private class MapBackendSettingsConfigSource(
    private val environment: Map<String, String> = emptyMap(),
    private val properties: Map<String, String> = emptyMap(),
) : BackendConfigSource {
    override fun env(key: String): String? = environment[key]
    override fun property(key: String): String? = properties[key]
}
