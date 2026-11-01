package agent

import io.ktor.client.plugins.*
import io.mockk.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.Assumptions
import org.kodein.di.DI
import org.kodein.di.bindProvider
import org.kodein.di.bindSingleton
import org.kodein.di.direct
import org.kodein.di.instance
import ru.souz.agent.AgentContextFactory
import ru.souz.agent.AgentExecutionResult
import ru.souz.agent.AgentExecutor
import ru.souz.agent.AgentId
import ru.souz.agent.skills.registry.SkillRegistryRepository
import ru.souz.agent.spi.AgentToolCatalog
import ru.souz.agent.spi.AgentToolsFilter
import ru.souz.agent.spi.AgentRuntimeEnvironment
import ru.souz.agent.spi.AgentErrorMessages
import ru.souz.agent.spi.McpToolProvider
import ru.souz.db.ConfigStore
import ru.souz.db.DesktopInfoRepository
import ru.souz.db.SettingsProvider
import ru.souz.db.SettingsProviderImpl
import ru.souz.di.mainDiModule
import ru.souz.llms.LLMChatAPI
import ru.souz.llms.LLMModel
import ru.souz.llms.LLMMessageRole
import ru.souz.llms.LLMRequest
import ru.souz.llms.LLMResponse
import ru.souz.llms.LLMToolSetup
import ru.souz.llms.TokenLogging
import ru.souz.llms.TokenLoggingChatApi
import ru.souz.llms.plus
import ru.souz.llms.ToolInvocationMeta
import ru.souz.llms.findLLMModel
import ru.souz.llms.restJsonMapper
import ru.souz.agent.runtime.AgentRuntimeEvent
import ru.souz.agent.runtime.AgentRuntimeEventSink
import ru.souz.llms.giga.GigaRestChatAPI
import ru.souz.llms.http.GigaHttpClientResource
import ru.souz.llms.http.ProviderHttpClients
import ru.souz.llms.LlmProvider
import ru.souz.llms.anthropic.AnthropicChatAPI
import ru.souz.llms.codex.CodexChatAPI
import ru.souz.llms.codex.CodexOAuthService
import ru.souz.llms.openai.OpenAICompatibleChatAPI
import ru.souz.llms.runtime.SettingsRoutingLlmChatApi
import ru.souz.llms.local.LocalChatAPI
import ru.souz.llms.local.LocalLlamaRuntime
import ru.souz.service.keys.Keys
import ru.souz.service.telegram.TelegramAuthState
import ru.souz.service.telegram.TelegramAuthStep
import ru.souz.service.telegram.TelegramService
import ru.souz.memory.ConversationMemoryRuntime
import ru.souz.memory.NoopConversationMemoryRuntime
import ru.souz.tool.RuntimePassThroughToolsFilter
import ru.souz.tool.ToolCategory
import ru.souz.tool.ToolsFactory
import ru.souz.runtime.files.FilesToolUtil
import ru.souz.tool.skills.SkillCommandExecutor
import java.util.concurrent.atomic.AtomicLong
import java.time.Clock
import java.time.LocalDate
import java.time.ZoneId
import java.util.Locale
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.TimeSource
import kotlin.test.assertTrue

class AgentScenarioTestSupport(
    private val selectedModel: LLMModel,
) {
    val agentType: AgentId = parseScenarioAgentId(readEnvironment(SOUZ_AGENT_INTEGRATION_TEST_AGENT))
    val filesUtil: FilesToolUtil by lazy { FilesToolUtil(spySettings) }
    private var fewShotExamplesEnabled: Boolean = true
    private var systemPromptOverride: String? = null
    val executionModelId: String get() = spySettings.executionModelId(selectedModel)

    private val spySettings: SettingsProviderImpl by lazy {
        spyk(SettingsProviderImpl(ConfigStore)) {
            every { contextSize } returns 32_000
            every { forbiddenFolders } returns emptyList()
            every { useStreaming } returns false
            every { useFewShotExamples } answers { fewShotExamplesEnabled }
            every { gigaModel } returns selectedModel
            every { requestTimeoutMillis } returns 60_000L
            every { temperature } returns 0f
            if (selectedModel == LLMModel.OpenAICompatibleCustom) {
                readEnvironment("OPENAI_MODEL")?.let { every { openaiModel } returns it }
            }
            every { getSystemPromptForAgentModel(any(), any()) } answers {
                systemPromptOverride ?: scenarioSystemPrompt(firstArg())
            }
        }
    }

    private val codexSettings: SettingsProvider by lazy { scenarioCodexSettings(spySettings) }
    private var providerHttpClients: ProviderHttpClients? = null
    private var gigaHttpClientResource: GigaHttpClientResource? = null
    private var localLlamaRuntime: LocalLlamaRuntime? = null
    private var localChatAPI: LocalChatAPI? = null
    private val httpRequestCount = AtomicLong(0)
    private val httpRequestTotalNanos = AtomicLong(0)
    private var scenarioTrace = ScenarioExecutionTrace()
    private var sessionUsage = LLMResponse.Usage(0, 0, 0, 0)

    private val testOverrideModule: DI.Module by lazy {
        DI.Module("TestOverrideModule") {
            bindSingleton<SettingsProvider>(overrides = true) { spySettings }
            bindSingleton<FilesToolUtil>(overrides = true) { filesUtil }
            bindSingleton<Keys>(overrides = true) { mockk(relaxed = true) }
            bindSingleton<TelegramService>(overrides = true) {
                mockk<TelegramService>(relaxed = true).also { telegramService ->
                    every { telegramService.authState } returns MutableStateFlow(
                        TelegramAuthState(step = TelegramAuthStep.READY, isBusy = false)
                    )
                }
            }
            bindSingleton<AgentToolsFilter>(overrides = true) { RuntimePassThroughToolsFilter }
            bindSingleton<SkillRegistryRepository>(overrides = true) { scenarioSkillRegistryRepository() }
            bindSingleton<SkillCommandExecutor>(overrides = true) { scenarioSkillCommandExecutor() }
            bindSingleton<AgentErrorMessages>(overrides = true) { ScenarioAgentErrorMessages }
            bindSingleton<McpToolProvider>(overrides = true) { EmptyMcpToolProvider }
            bindSingleton<ConversationMemoryRuntime>(overrides = true) { NoopConversationMemoryRuntime }
            bindSingleton<AgentRuntimeEnvironment>(overrides = true) { ScenarioRuntimeEnvironment }
            bindSingleton<ProviderHttpClients>(overrides = true) {
                ProviderHttpClients().also { clients ->
                    instrument(clients.standard)
                    instrument(clients.openAi)
                    providerHttpClients = clients
                }
            }
            bindSingleton<GigaHttpClientResource>(overrides = true) {
                GigaHttpClientResource().also { resource ->
                    instrument(resource.client)
                    gigaHttpClientResource = resource
                }
            }
            bindSingleton<LocalLlamaRuntime>(overrides = true) {
                localLlamaRuntime
                    ?: LocalLlamaRuntime(instance(), instance(), instance(), instance(), instance()).also {
                        localLlamaRuntime = it
                    }
            }
            bindSingleton<LocalChatAPI>(overrides = true) {
                localChatAPI
                    ?: LocalChatAPI(instance()).also {
                        localChatAPI = it
                    }
            }
            bindSingleton<LLMChatAPI>(overrides = true) {
                val logger = instance<TokenLogging>()
                val selectedApi = when (selectedModel.provider) {
                    LlmProvider.GIGA -> instance<GigaRestChatAPI>()
                    LlmProvider.QWEN,
                    LlmProvider.AI_TUNNEL,
                    LlmProvider.OPENAI,
                    -> OpenAICompatibleChatAPI(
                        provider = selectedModel.provider,
                        settingsProvider = instance(),
                        client = instance<ProviderHttpClients>().let { clients ->
                            if (selectedModel.provider == LlmProvider.OPENAI) clients.openAi else clients.standard
                        },
                        apiKey = readEnvironment("OPENAI_API_KEY").takeIf { selectedModel.provider == LlmProvider.OPENAI },
                        baseUrl = readEnvironment("OPENAI_BASE_URL").takeIf { selectedModel.provider == LlmProvider.OPENAI },
                        requestParameters = readEnvironment(SOUZ_AGENT_INTEGRATION_TEST_REASONING_EFFORT)?.let {
                            restJsonMapper.writeValueAsString(mapOf("reasoning_effort" to it))
                        },
                    )
                    LlmProvider.ANTHROPIC -> instance<AnthropicChatAPI>()
                    LlmProvider.LOCAL -> instance<LocalChatAPI>()
                    LlmProvider.CODEX -> instance<ProviderHttpClients>().standard.let { client ->
                        CodexChatAPI(codexSettings, CodexOAuthService(codexSettings, client), client)
                    }
                }
                val router = SettingsRoutingLlmChatApi(instance(), mapOf(selectedModel.provider to selectedApi))
                val api = if (selectedModel.provider == LlmProvider.LOCAL) {
                    router
                } else {
                    TokenLoggingChatApi(router, logger)
                }
                object : LLMChatAPI by api {
                    override suspend fun message(body: LLMRequest.Chat): LLMResponse.Chat =
                        scenarioTrace.modelCall {
                            api.message(body.copy(reasoningEffort =
                                readEnvironment(SOUZ_AGENT_INTEGRATION_TEST_REASONING_EFFORT) ?: body.reasoningEffort))
                        }
                }
            }
            bindSingleton<DesktopInfoRepository>(overrides = true) {
                val repository = DesktopInfoRepository(instance(), instance(), instance(), instance())
                spyk(repository) { coEvery { search(any(), any()) } returns emptyList() }
            }
        }
    }

    fun runTest(
        block: suspend TestScope.() -> Unit,
    ) = kotlinx.coroutines.test.runTest(timeout = DEFAULT_TEST_TIMEOUT, testBody = block)

    fun checkEnvironment() {
        Assumptions.assumeTrue(
            isAgentScenarioIntegrationTestsEnabled(readEnvironment(SOUZ_AGENT_INTEGRATION_TESTS_ON)),
            "Skipping agent scenario integration tests: set $SOUZ_AGENT_INTEGRATION_TESTS_ON=true",
        )
        if (selectedModel.provider == LlmProvider.CODEX) {
            codexSettings // Validate the cached login only after explicit integration-test opt-in.
            return
        }
        val apiKeyName = when (selectedModel.provider) {
            LlmProvider.GIGA -> "GIGA_KEY"
            LlmProvider.QWEN -> "QWEN_KEY"
            LlmProvider.AI_TUNNEL -> "AITUNNEL_KEY"
            LlmProvider.ANTHROPIC -> "ANTHROPIC_API_KEY"
            LlmProvider.OPENAI -> "OPENAI_API_KEY"
            LlmProvider.LOCAL -> null
            LlmProvider.CODEX -> null
        }
        if (apiKeyName == null) return
        val apiKey = readEnvironment(apiKeyName) ?: readSystemProperty(apiKeyName)
        Assumptions.assumeTrue(
            !apiKey.isNullOrBlank(),
            "Skipping integration tests: $apiKeyName is not set (selected model=${selectedModel.alias})"
        )
    }

    suspend fun runScenarioWithMocks(
        userPrompt: String,
        mockedTools: List<LLMToolSetup>,
        useFewShotExamples: Boolean = false,
        systemPrompt: String? = null,
    ): String {
        val previousFewShotExamplesEnabled = fewShotExamplesEnabled
        fewShotExamplesEnabled = useFewShotExamples
        systemPromptOverride = systemPrompt
        scenarioTrace = ScenarioExecutionTrace(requireToolCall = mockedTools.isNotEmpty())
        try {
            return runAgent(createScenarioDi(mockedTools), userPrompt)
        } finally {
            fewShotExamplesEnabled = previousFewShotExamplesEnabled
            systemPromptOverride = null
            sessionUsage += scenarioTrace.usage
            closeHttpClients()
        }
    }

    internal fun createScenarioDi(mockedTools: List<LLMToolSetup>): DI =
        DI.invoke(allowSilentOverride = true) {
            import(mainDiModule, allowOverride = true)
            import(testOverrideModule, allowOverride = true)
            bindProvider<DI> { this.di }
            bindSingleton<AgentToolCatalog>(overrides = true) {
                ScenarioAgentToolCatalog(
                    productionCatalog = instance<ToolsFactory>().catalog,
                    mockedTools = mockedTools,
                )
            }
    }

    fun finish() {
        val prefix = "[agent=${agentType.storageValue}]"
        println("$prefix Spent: $sessionUsage")
        runCatching { localLlamaRuntime?.close() }
        localChatAPI = null
        localLlamaRuntime = null
        val requestCount = httpRequestCount.get()
        closeHttpClients()
        if (requestCount == 0L) {
            println("$prefix HTTP requests: 0")
            return
        }
        val avgMs = httpRequestTotalNanos.get().toDouble() / requestCount / 1_000_000.0
        println("$prefix HTTP requests: $requestCount, avg/request: ${"%.2f".format(avgMs)} ms")
    }

    private fun closeHttpClients() {
        val resources = listOfNotNull(providerHttpClients, gigaHttpClientResource)
        providerHttpClients = null
        gigaHttpClientResource = null
        var failure: Throwable? = null
        for (resource in resources) {
            try {
                resource.close()
            } catch (error: Throwable) {
                if (failure == null) failure = error else failure.addSuppressed(error)
            }
        }
        failure?.let { throw it }
    }

    private fun instrument(client: io.ktor.client.HttpClient) {
        client.plugin(HttpSend).intercept { request ->
            val startNanos = System.nanoTime()
            try {
                execute(request)
            } finally {
                httpRequestCount.incrementAndGet()
                httpRequestTotalNanos.addAndGet(System.nanoTime() - startNanos)
            }
        }
    }

    private suspend fun runAgent(di: DI, userPrompt: String): String {
        val contextFactory: AgentContextFactory = di.direct.instance()
        val executor: AgentExecutor = di.direct.instance()
        val started = TimeSource.Monotonic.markNow()
        try {
            // A real dispatcher keeps this deadline on wall time under runTest's virtual clock.
            val result = withContext(Dispatchers.Default) {
                withTimeout(SCENARIO_TIMEOUT) {
                    executor.execute(
                        agentId = agentType,
                        context = contextFactory.create(agentType),
                        input = userPrompt,
                        eventSink = scenarioTrace,
                    )
                }
            }
            scenarioTrace.assertCompleted(result)
            return result.output
        } finally {
            println("[agent=${agentType.storageValue}] ${scenarioTrace.report(started.elapsedNow())} prompt=$userPrompt")
        }
    }
}

internal val SCENARIO_TODAY: LocalDate = LocalDate.of(2026, 6, 1)

internal object ScenarioRuntimeEnvironment : AgentRuntimeEnvironment {
    override val locale: Locale = Locale.forLanguageTag("ru-RU")
    override val zoneId: ZoneId = ZoneId.of("Europe/Moscow")
    override val clock: Clock = Clock.fixed(SCENARIO_TODAY.atTime(10, 0).atZone(zoneId).toInstant(), zoneId)
}

internal class ScenarioExecutionTrace(
    private val maxModelCalls: Int = 24,
    private val requireToolCall: Boolean = true,
) : AgentRuntimeEventSink {
    private val lock = Mutex()
    private val events = mutableListOf<AgentRuntimeEvent>()
    private var lastResponse: LLMResponse.Chat? = null
    var modelCalls: Int = 0
        private set
    var usage = LLMResponse.Usage(0, 0, 0, 0)
        private set

    suspend fun modelCall(block: suspend () -> LLMResponse.Chat): LLMResponse.Chat {
        lock.withLock {
            assertTrue(modelCalls < maxModelCalls, "Scenario exceeded $maxModelCalls model calls without completing.")
            modelCalls++
        }
        return block().also { response ->
            lock.withLock {
                lastResponse = response
                if (response is LLMResponse.Chat.Ok) usage += response.usage
            }
        }
    }

    override suspend fun emit(event: AgentRuntimeEvent) {
        events += event
    }

    fun assertCompleted(result: AgentExecutionResult) {
        val response = lastResponse
        assertTrue(response is LLMResponse.Chat.Ok, "Scenario ended without a successful model response: $response")
        val finalChoice = response.choices.lastOrNull { it.message.content.isNotBlank() }
        assertTrue(finalChoice != null, "Scenario produced no user-facing final answer.")
        assertTrue(
            response.choices.all { choice ->
                choice.message.functionCall == null &&
                    (choice.finishReason == null || choice.finishReason == LLMResponse.FinishReason.stop)
            },
            "Scenario ended with a tool call, truncated output, or provider failure: ${response.choices}",
        )
        val output = result.output.trim()
        val finalMessage = result.context.history.lastOrNull()
        assertTrue(output.isNotBlank(), "Scenario produced an empty final answer.")
        assertTrue(
            finalChoice.message.role == LLMMessageRole.assistant &&
                finalMessage?.role == LLMMessageRole.assistant && finalMessage.functionCall == null &&
                finalMessage.content.trim() == output && finalChoice.message.content.trim() == output,
            "Executor output is not the final assistant message: $output",
        )
        assertTrue(
            !INTERNAL_DELIBERATION.containsMatchIn(output) && output != finalChoice.message.reasoningContent?.trim(),
            "Scenario returned internal deliberation instead of a user-facing result: $output",
        )
        if (requireToolCall) {
            assertTrue(events.any { it is AgentRuntimeEvent.ToolCallFinished }, "Scenario completed without executing a tool.")
        }
    }

    fun report(duration: Duration): String {
        val calls = events.filterIsInstance<AgentRuntimeEvent.ToolCallStarted>()
        return "Scenario metrics: modelCalls=$modelCalls, toolCalls=${calls.size}, " +
            "durationMs=${duration.inWholeMilliseconds}, tokens=$usage; tools=${calls.joinToString { it.traceLabel() }}"
    }

    private companion object {
        val INTERNAL_DELIBERATION = Regex(
            """(?i)<\s*/?\s*(?:think|analysis|reasoning)\b|^(?:analysis|reasoning|thoughts?)\s*:|^(?:I |We )?need to\b|^let['’]s think\b""",
        )
    }
}

private fun AgentRuntimeEvent.ToolCallStarted.traceLabel(): String {
    val skillId = arguments["skillId"] as? String
    val delegatedArguments = arguments["arguments"] as? Map<*, *>
    val argumentKeys = (delegatedArguments?.keys ?: arguments.keys)
        .map(Any?::toString)
        .sorted()
    val target = skillId?.let { "->$it" }.orEmpty()
    return "$name$target(${argumentKeys.joinToString()})"
}

internal fun ScenarioAgentToolCatalog(
    productionCatalog: AgentToolCatalog,
    mockedTools: List<LLMToolSetup>,
): AgentToolCatalog {
    val mockedByName = mockedTools.associateBy { it.fn.name }
    require(mockedByName.size == mockedTools.size) { "Scenario mocks contain duplicate tool names." }
    val unknownNames = mockedByName.keys - productionCatalog.toolsByCategory.values.flatMap { it.keys }.toSet()
    require(unknownNames.isEmpty()) {
        "Scenario mocks are not present in the production tool catalog: ${unknownNames.sorted().joinToString()}"
    }
    return productionCatalog.mapTools { _, tool -> tool.withMockInvocation(mockedByName[tool.fn.name]) }
}

private fun LLMToolSetup.withMockInvocation(mockedTool: LLMToolSetup?): LLMToolSetup {
    val productionTool = this
    return object : LLMToolSetup {
        override val fn: LLMRequest.Function = productionTool.fn

        override suspend fun invoke(functionCall: LLMResponse.FunctionCall): LLMRequest.Message =
            mockedTool?.invoke(functionCall) ?: unconfiguredScenarioTool(functionCall.name)

        override suspend fun invoke(
            functionCall: LLMResponse.FunctionCall,
            meta: ToolInvocationMeta,
        ): LLMRequest.Message = mockedTool?.invoke(functionCall, meta) ?: unconfiguredScenarioTool(functionCall.name)
    }
}

private fun unconfiguredScenarioTool(name: String) = LLMRequest.Message(
    role = LLMMessageRole.function,
    name = name,
    content = restJsonMapper.writeValueAsString(mapOf("error" to mapOf(
        "code" to "scenario_tool_not_configured",
        "message" to "No fixture is configured for $name in this scenario. No operation was performed.",
    ))),
)

private object ScenarioAgentErrorMessages : AgentErrorMessages {
    override suspend fun contextReset(): String = "Scenario context limit exceeded."
    override suspend fun timeout(): String = "Scenario model request timed out."
    override suspend fun noMoney(): String = "Scenario provider balance is exhausted."
}

internal object EmptyMcpToolProvider : McpToolProvider {
    override suspend fun tools(): List<LLMToolSetup> = emptyList()
}

const val SOUZ_AGENT_INTEGRATION_TESTS_ON = "SOUZ_AGENT_INTEGRATION_TESTS_ON"
const val SOUZ_AGENT_INTEGRATION_TEST_AGENT = "SOUZ_AGENT_INTEGRATION_TEST_AGENT"
const val SOUZ_AGENT_INTEGRATION_TEST_MODEL = "SOUZ_AGENT_INTEGRATION_TEST_MODEL"
const val SOUZ_AGENT_INTEGRATION_TEST_REASONING_EFFORT = "SOUZ_AGENT_INTEGRATION_TEST_REASONING_EFFORT"

private fun isAgentScenarioIntegrationTestsEnabled(envValue: String?): Boolean =
    envValue.equals("true", ignoreCase = true)

internal fun parseScenarioAgentId(rawValue: String?): AgentId = when (rawValue?.trim()?.lowercase()) {
    null, "", AgentId.GRAPH.storageValue -> AgentId.GRAPH
    AgentId.SKILLS_GRAPH.storageValue -> AgentId.SKILLS_GRAPH
    else -> error(
        "Unsupported $SOUZ_AGENT_INTEGRATION_TEST_AGENT='$rawValue'. " +
            "Expected '${AgentId.GRAPH.storageValue}' or '${AgentId.SKILLS_GRAPH.storageValue}'."
    )
}

internal fun scenarioIntegrationModel(defaultModel: LLMModel): LLMModel {
    val rawValue = readEnvironment(SOUZ_AGENT_INTEGRATION_TEST_MODEL)
        ?: readSystemProperty(SOUZ_AGENT_INTEGRATION_TEST_MODEL)
        ?: return defaultModel
    return findLLMModel(rawValue) ?: error(
        "Unsupported $SOUZ_AGENT_INTEGRATION_TEST_MODEL='$rawValue'. " +
            "Expected one of ${LLMModel.entries.joinToString { "${it.name}/${it.alias}" }}."
    )
}

private fun scenarioSystemPrompt(agentId: AgentId): String = buildString {
    appendLine("Будь полезен. Выполняй инструкции с помощью доступных функций.")
    appendLine("The available functions run in an authorized test environment and can perform the requested desktop and browser actions. Do not claim that you lack those capabilities when a relevant function is available.")
    append("Complete every requested action explicitly and in the requested order. Do not omit an action because another requested action appears similar or redundant.")
    if (agentId == AgentId.SKILLS_GRAPH) {
        appendLine()
        appendLine()
        appendLine("Use only the functions listed as active tools. Never call a discovered Skill ID as a function.")
        appendLine("In a multi-call response, every call name must still be GetSkillsByCategory, GetSkillsNamesByCategory, GetSkillByName, GetKnowledge, SearchKnowledge, or RunSkillCommand; put each Skill ID inside RunSkillCommand arguments.")
        appendLine("For an actionable user request, your first assistant turn MUST be a Skill discovery function call for the relevant category.")
        appendLine("Do not describe a plan, announce that discovery is needed, or emit text instead of that first function call.")
        appendLine("For every task that requires an action:")
        appendLine("1. Call GetSkillsByCategory with the relevant category when the category is clear, or GetSkillsNamesByCategory to inspect available Skill IDs in a category.")
        appendLine("2. Call GetSkillByName with the exact required Skill ID if you still need its argument schema.")
        appendLine("3. Call RunSkillCommand with an exact skillId and arguments matching the discovered schema.")
        appendLine("For multi-step tasks, invoke each required Skill through RunSkillCommand in the requested order before replying.")
        appendLine()
        appendLine("Example sequence for a discovered ExampleSkill:")
        appendLine("- GetSkillsNamesByCategory arguments: {\"category\":\"FILES\"}")
        appendLine("- GetSkillByName arguments: {\"skillId\":\"ExampleSkill\"}")
        appendLine("- RunSkillCommand arguments: {\"skillId\":\"ExampleSkill\",\"arguments\":{\"value\":\"example\"}}")
        append("ExampleSkill is never a function name. Valid function names remain the Skill discovery, Knowledge, and RunSkillCommand functions.")
    }
}

private fun readSystemProperty(name: String): String? = System.getProperty(name)?.takeIf { it.isNotBlank() }

private fun readEnvironment(name: String): String? = System.getenv(name)?.takeIf { it.isNotBlank() }

private val DEFAULT_TEST_TIMEOUT: Duration = 5.minutes
private val SCENARIO_TIMEOUT: Duration = 4.minutes
