package ru.souz.backend.http

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.testApplication
import java.io.File
import java.time.ZoneId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.coroutines.flow.Flow
import ru.souz.agent.AgentId
import ru.souz.agent.spi.AgentToolCatalog
import ru.souz.agent.spi.AgentToolsFilter
import ru.souz.backend.agent.runtime.BackendConversationRuntimeFactory
import ru.souz.backend.agent.runtime.BackendNoopAgentToolsFilter
import ru.souz.backend.agent.service.BackendAgentService
import ru.souz.backend.agent.session.InMemoryAgentSessionRepository
import ru.souz.backend.config.BackendFeatureFlags
import ru.souz.backend.bootstrap.BackendBootstrapService
import ru.souz.db.SettingsProvider
import ru.souz.llms.EmbeddingsModel
import ru.souz.llms.LLMChatAPI
import ru.souz.llms.LLMMessageRole
import ru.souz.llms.LLMModel
import ru.souz.llms.LLMRequest
import ru.souz.llms.LLMResponse
import ru.souz.llms.LLMToolSetup
import ru.souz.llms.LocalModelAvailability
import ru.souz.llms.VoiceRecognitionModel
import ru.souz.backend.storage.StorageMode
import ru.souz.tool.FewShotExample
import ru.souz.tool.InputParamDescription
import ru.souz.tool.ReturnParameters
import ru.souz.tool.ReturnProperty
import ru.souz.tool.ToolCategory
import ru.souz.tool.ToolSetup

class BackendBootstrapRouteTest {
    private val json = jacksonObjectMapper()

    @Test
    fun `bootstrap rejects requests without trusted headers`() = testApplication {
        application {
            backendApplication(
                agentService = unusedAgentService(),
                selectedModel = { LLMModel.Max.alias },
                internalAgentToken = { "legacy-token" },
                bootstrapService = bootstrapService(),
                trustedProxyToken = { "proxy-secret" },
            )
        }

        val response = client.get("/v1/bootstrap")
        val payload = json.readTree(response.bodyAsText())

        assertEquals(HttpStatusCode.Unauthorized, response.status)
        assertEquals("untrusted_proxy", payload["error"]["code"].asText())
    }

    @Test
    fun `bootstrap rejects missing or invalid proxy auth`() = testApplication {
        application {
            backendApplication(
                agentService = unusedAgentService(),
                selectedModel = { LLMModel.Max.alias },
                internalAgentToken = { "legacy-token" },
                bootstrapService = bootstrapService(),
                trustedProxyToken = { "proxy-secret" },
            )
        }

        val missing = client.get("/v1/bootstrap") {
            header("X-User-Id", "opaque-user")
        }
        val invalid = client.get("/v1/bootstrap") {
            header("X-User-Id", "opaque-user")
            header("X-Souz-Proxy-Auth", "wrong-secret")
        }

        assertEquals(HttpStatusCode.Unauthorized, missing.status)
        assertEquals("untrusted_proxy", json.readTree(missing.bodyAsText())["error"]["code"].asText())
        assertEquals(HttpStatusCode.Unauthorized, invalid.status)
        assertEquals("untrusted_proxy", json.readTree(invalid.bodyAsText())["error"]["code"].asText())
    }

    @Test
    fun `bootstrap rejects requests when proxy token is not configured`() = testApplication {
        application {
            backendApplication(
                agentService = unusedAgentService(),
                selectedModel = { LLMModel.Max.alias },
                internalAgentToken = { "legacy-token" },
                bootstrapService = bootstrapService(),
                trustedProxyToken = { null },
            )
        }

        val response = client.get("/v1/bootstrap") {
            header("X-User-Id", "opaque-user")
            header("X-Souz-Proxy-Auth", "proxy-secret")
        }
        val payload = json.readTree(response.bodyAsText())

        assertEquals(HttpStatusCode.InternalServerError, response.status)
        assertEquals("backend_misconfigured", payload["error"]["code"].asText())
    }

    @Test
    fun `bootstrap requires user identity header but does not require uuid format`() = testApplication {
        application {
            backendApplication(
                agentService = unusedAgentService(),
                selectedModel = { LLMModel.Max.alias },
                internalAgentToken = { "legacy-token" },
                bootstrapService = bootstrapService(),
                trustedProxyToken = { "proxy-secret" },
            )
        }

        val missingUser = client.get("/v1/bootstrap") {
            header("X-Souz-Proxy-Auth", "proxy-secret")
        }
        val success = client.get("/v1/bootstrap") {
            header("X-User-Id", "user-opaque-42")
            header("X-Souz-Proxy-Auth", "proxy-secret")
        }

        assertEquals(HttpStatusCode.Unauthorized, missingUser.status)
        assertEquals("missing_user_identity", json.readTree(missingUser.bodyAsText())["error"]["code"].asText())
        assertEquals(HttpStatusCode.OK, success.status)
        assertEquals("user-opaque-42", json.readTree(success.bodyAsText())["user"]["id"].asText())
    }

    @Test
    fun `bootstrap response contains user features storage capabilities and settings`() = testApplication {
        val settingsProvider = FakeSettingsProvider().apply {
            gigaModel = LLMModel.Max
            contextSize = 48_000
            temperature = 0.25f
            regionProfile = "ru"
            useStreaming = true
            gigaChatKey = "giga-key"
        }
        application {
            backendApplication(
                agentService = unusedAgentService(settingsProvider),
                selectedModel = { settingsProvider.gigaModel.alias },
                internalAgentToken = { "legacy-token" },
                bootstrapService = bootstrapService(
                    settingsProvider = settingsProvider,
                    featureFlags = BackendFeatureFlags(
                        wsEvents = false,
                        streamingMessages = true,
                        toolEvents = true,
                        choices = false,
                        durableEventReplay = false,
                    ),
                ),
                trustedProxyToken = { "proxy-secret" },
            )
        }

        val response = client.get("/v1/bootstrap") {
            header("X-User-Id", "user-123")
            header("X-Souz-Proxy-Auth", "proxy-secret")
        }
        val payload = json.readTree(response.bodyAsText())

        assertEquals(HttpStatusCode.OK, response.status)
        assertEquals("user-123", payload["user"]["id"].asText())
        assertEquals("memory", payload["storage"]["mode"].asText())
        assertEquals(true, payload["features"]["streamingMessages"].asBoolean())
        assertEquals(true, payload["settings"]["showToolEvents"].asBoolean())
        assertEquals(true, payload["settings"]["streamingMessages"].asBoolean())
        assertEquals(LLMModel.Max.alias, payload["settings"]["defaultModel"].asText())
        assertEquals(48_000, payload["settings"]["contextSize"].asInt())
        assertEquals(0.25, payload["settings"]["temperature"].asDouble())
        assertEquals("ru-RU", payload["settings"]["locale"].asText())
        assertEquals(ZoneId.systemDefault().id, payload["settings"]["timeZone"].asText())
        assertTrue(payload["capabilities"]["models"].isArray)
        assertTrue(payload["capabilities"]["tools"].isArray)
        assertNotNull(payload["capabilities"]["models"].firstOrNull())
    }

    @Test
    fun `bootstrap capabilities hide desktop only tools and reflect current settings provider`() = testApplication {
        val settingsProvider = FakeSettingsProvider().apply {
            gigaModel = LLMModel.Max
            contextSize = 24_000
            temperature = 0.4f
            regionProfile = "ru"
            useStreaming = false
            gigaChatKey = "giga-key"
        }
        application {
            backendApplication(
                agentService = unusedAgentService(settingsProvider),
                selectedModel = { settingsProvider.gigaModel.alias },
                internalAgentToken = { "legacy-token" },
                bootstrapService = bootstrapService(
                    settingsProvider = settingsProvider,
                    toolCatalog = toolCatalog(
                        ToolCategory.FILES to fakeTool("ListFiles"),
                        ToolCategory.BROWSER to fakeTool("OpenBrowser"),
                        ToolCategory.TELEGRAM to fakeTool("SendTelegramMessage"),
                    ),
                    featureFlags = BackendFeatureFlags(
                        wsEvents = false,
                        streamingMessages = false,
                        toolEvents = false,
                        choices = false,
                        durableEventReplay = false,
                    ),
                ),
                trustedProxyToken = { "proxy-secret" },
            )
        }

        val response = client.get("/v1/bootstrap") {
            header("X-User-Id", "user-123")
            header("X-Souz-Proxy-Auth", "proxy-secret")
        }
        val payload = json.readTree(response.bodyAsText())
        val tools = payload["capabilities"]["tools"].map { it["name"].asText() }
        val models = payload["capabilities"]["models"]

        assertEquals(HttpStatusCode.OK, response.status)
        assertEquals(listOf("ListFiles"), tools)
        assertFalse(tools.contains("OpenBrowser"))
        assertFalse(tools.contains("SendTelegramMessage"))
        assertTrue(models.any { it["model"].asText() == LLMModel.Max.alias && it["serverManagedKey"].asBoolean() })
        assertTrue(models.any { it["model"].asText() == LLMModel.QwenMax.alias && !it["serverManagedKey"].asBoolean() })
        assertFalse(models.any { it["model"].asText() == LLMModel.OpenAIGpt52.alias })
        assertEquals(false, payload["settings"]["streamingMessages"].asBoolean())
        assertEquals(false, payload["settings"]["showToolEvents"].asBoolean())
    }

    @Test
    fun `legacy agent route remains mounted`() = testApplication {
        application {
            backendApplication(
                agentService = unusedAgentService(),
                selectedModel = { LLMModel.Max.alias },
                internalAgentToken = { "legacy-token" },
                bootstrapService = bootstrapService(),
                trustedProxyToken = { "proxy-secret" },
            )
        }

        val response = client.post("/agent")

        assertTrue(response.status != HttpStatusCode.NotFound)
    }
}

private fun bootstrapService(
    settingsProvider: SettingsProvider = FakeSettingsProvider().apply { gigaChatKey = "giga-key" },
    toolCatalog: AgentToolCatalog = toolCatalog(
        ToolCategory.FILES to fakeTool("ListFiles"),
        ToolCategory.CALCULATOR to fakeTool("Calculator"),
    ),
    featureFlags: BackendFeatureFlags = BackendFeatureFlags(),
    localModelAvailability: LocalModelAvailability = unavailableLocalModels(),
): BackendBootstrapService =
    BackendBootstrapService(
        settingsProvider = settingsProvider,
        toolCatalog = toolCatalog,
        featureFlags = featureFlags,
        storageMode = StorageMode.MEMORY,
        localModelAvailability = localModelAvailability,
    )

private fun unusedAgentService(
    settingsProvider: SettingsProvider = FakeSettingsProvider(),
    toolCatalog: AgentToolCatalog = emptyToolCatalog(),
    toolsFilter: AgentToolsFilter = BackendNoopAgentToolsFilter,
): BackendAgentService =
    BackendAgentService(
        baseSettingsProvider = settingsProvider,
        runtimeFactory = BackendConversationRuntimeFactory(
            baseSettingsProvider = settingsProvider,
            llmApiFactory = { UnusedChatApi() },
            sessionRepository = InMemoryAgentSessionRepository(),
            logObjectMapper = jacksonObjectMapper(),
            systemPrompt = "test system prompt",
            toolCatalog = toolCatalog,
            toolsFilter = toolsFilter,
        ),
    )

private fun toolCatalog(vararg tools: Pair<ToolCategory, LLMToolSetup>): AgentToolCatalog =
    object : AgentToolCatalog {
        override val toolsByCategory: Map<ToolCategory, Map<String, LLMToolSetup>> =
            tools.groupBy(keySelector = { it.first }, valueTransform = { it.second })
                .mapValues { (_, setups) -> setups.associateBy { it.fn.name } }
    }

private fun emptyToolCatalog(): AgentToolCatalog =
    object : AgentToolCatalog {
        override val toolsByCategory: Map<ToolCategory, Map<String, LLMToolSetup>> = emptyMap()
    }

private fun fakeTool(name: String): LLMToolSetup =
    object : LLMToolSetup {
        override val fn: LLMRequest.Function = LLMRequest.Function(
            name = name,
            description = "test",
            parameters = LLMRequest.Parameters(type = "object", properties = emptyMap()),
        )

        override suspend fun invoke(functionCall: LLMResponse.FunctionCall): LLMRequest.Message =
            LLMRequest.Message(role = LLMMessageRole.function, content = "ok", name = functionCall.name)
    }

private fun unavailableLocalModels(): LocalModelAvailability =
    object : LocalModelAvailability {
        override fun availableGigaModels(): List<LLMModel> = emptyList()

        override fun defaultGigaModel(): LLMModel? = null

        override fun isProviderAvailable(): Boolean = false
    }

private class UnusedChatApi : LLMChatAPI {
    override suspend fun message(body: LLMRequest.Chat): LLMResponse.Chat =
        error("Legacy smoke tests should not execute agent runtime.")

    override suspend fun messageStream(body: LLMRequest.Chat): Flow<LLMResponse.Chat> =
        error("Streaming is not used in backend route tests.")

    override suspend fun embeddings(body: LLMRequest.Embeddings): LLMResponse.Embeddings =
        error("Embeddings are not used in backend route tests.")

    override suspend fun uploadFile(file: File): LLMResponse.UploadFile =
        error("File upload is not used in backend route tests.")

    override suspend fun downloadFile(fileId: String): String? =
        error("File download is not used in backend route tests.")

    override suspend fun balance(): LLMResponse.Balance =
        error("Balance is not used in backend route tests.")
}

private class FakeSettingsProvider : SettingsProvider {
    private val promptOverrides = HashMap<Pair<AgentId, LLMModel>, String>()

    override var gigaChatKey: String? = null
    override var qwenChatKey: String? = null
    override var aiTunnelKey: String? = null
    override var anthropicKey: String? = null
    override var openaiKey: String? = null
    override var saluteSpeechKey: String? = null
    override var supportEmail: String? = null
    override var defaultCalendar: String? = null
    override var regionProfile: String = "ru"
    override var activeAgentId: AgentId = AgentId.default
    override var gigaModel: LLMModel = LLMModel.Max
    override var useFewShotExamples: Boolean = false
    override var useStreaming: Boolean = false
    override var notificationSoundEnabled: Boolean = true
    override var voiceInputReviewEnabled: Boolean = false
    override var safeModeEnabled: Boolean = true
    override var needsOnboarding: Boolean = false
    override var onboardingCompleted: Boolean = false
    override var requestTimeoutMillis: Long = 30_000
    override var contextSize: Int = 16_000
    override var initialWindowWidthDp: Int = 580
    override var initialWindowHeightDp: Int = 780
    override var temperature: Float = 0.7f
    override var forbiddenFolders: List<String> = emptyList()
    override var embeddingsModel: EmbeddingsModel = EmbeddingsModel.GigaEmbeddings
    override var voiceRecognitionModel: VoiceRecognitionModel = VoiceRecognitionModel.SaluteSpeech
    override var mcpServersJson: String? = null
    override var mcpServersFile: String? = null

    override fun getSystemPromptForAgentModel(agentId: AgentId, model: LLMModel): String? =
        promptOverrides[agentId to model]

    override fun setSystemPromptForAgentModel(agentId: AgentId, model: LLMModel, prompt: String?) {
        val key = agentId to model
        if (prompt.isNullOrBlank()) {
            promptOverrides.remove(key)
        } else {
            promptOverrides[key] = prompt
        }
    }
}
