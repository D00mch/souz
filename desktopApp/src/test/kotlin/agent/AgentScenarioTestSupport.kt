package agent

import giga.getHttpClient
import giga.getSessionTokenUsage
import io.ktor.client.plugins.*
import io.mockk.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.TestScope
import org.junit.jupiter.api.Assumptions
import org.kodein.di.DI
import org.kodein.di.bindProvider
import org.kodein.di.bindSingleton
import org.kodein.di.direct
import org.kodein.di.instance
import ru.souz.agent.AgentContextFactory
import ru.souz.agent.AgentExecutor
import ru.souz.agent.AgentId
import ru.souz.agent.skills.registry.SkillRegistryRepository
import ru.souz.agent.spi.AgentToolCatalog
import ru.souz.agent.spi.AgentToolsFilter
import ru.souz.agent.spi.DefaultBrowserProvider
import ru.souz.agent.spi.McpToolProvider
import ru.souz.db.ConfigStore
import ru.souz.db.DesktopInfoRepository
import ru.souz.db.SettingsProvider
import ru.souz.db.SettingsProviderImpl
import ru.souz.di.mainDiModule
import ru.souz.llms.LLMChatAPI
import ru.souz.llms.LLMModel
import ru.souz.llms.LLMRequest
import ru.souz.llms.LLMResponse
import ru.souz.llms.LLMToolSetup
import ru.souz.llms.ToolInvocationMeta
import ru.souz.llms.findLLMModel
import ru.souz.agent.runtime.AgentRuntimeEvent
import ru.souz.agent.runtime.AgentRuntimeEventSink
import ru.souz.llms.giga.GigaRestChatAPI
import ru.souz.llms.LlmProvider
import ru.souz.llms.tunnel.AiTunnelChatAPI
import ru.souz.llms.anthropic.AnthropicChatAPI
import ru.souz.llms.openai.OpenAIChatAPI
import ru.souz.llms.qwen.QwenChatAPI
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
import java.util.concurrent.atomic.AtomicLong
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes

class AgentScenarioTestSupport(
    private val selectedModel: LLMModel,
) {
    private val agentType: AgentId = parseScenarioAgentId(readEnvironment(SOUZ_AGENT_INTEGRATION_TEST_AGENT))
    val filesUtil: FilesToolUtil by lazy { FilesToolUtil(spySettings) }
    private var fewShotExamplesEnabled: Boolean = true

    private val spySettings: SettingsProviderImpl by lazy {
        spyk(SettingsProviderImpl(ConfigStore)) {
            every { contextSize } returns 32_000
            every { forbiddenFolders } returns emptyList()
            every { useStreaming } returns false
            every { useFewShotExamples } answers { fewShotExamplesEnabled }
            every { gigaModel } returns selectedModel
            every { requestTimeoutMillis } returns 60_000L
            every { temperature } returns 0f
            every { getSystemPromptForAgentModel(any(), any()) } answers {
                scenarioSystemPrompt(firstArg())
            }
        }
    }

    private var gigaRestChatAPI: GigaRestChatAPI? = null
    private var qwenChatAPI: QwenChatAPI? = null
    private var aiTunnelChatAPI: AiTunnelChatAPI? = null
    private var anthropicChatAPI: AnthropicChatAPI? = null
    private var openAiChatAPI: OpenAIChatAPI? = null
    private var localLlamaRuntime: LocalLlamaRuntime? = null
    private var localChatAPI: LocalChatAPI? = null
    private val httpRequestCount = AtomicLong(0)
    private val httpRequestTotalNanos = AtomicLong(0)

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
            bindSingleton<SkillRegistryRepository>(overrides = true) { emptySkillRegistryRepository() }
            bindSingleton<McpToolProvider>(overrides = true) { EmptyMcpToolProvider }
            bindSingleton<ConversationMemoryRuntime>(overrides = true) { NoopConversationMemoryRuntime }
            bindSingleton<DefaultBrowserProvider>(overrides = true) { DefaultBrowserProvider { null } }

            bindSingleton<GigaRestChatAPI>(overrides = true) {
                if (gigaRestChatAPI == null) {
                    gigaRestChatAPI = GigaRestChatAPI(instance(), instance(), instance()).apply {
                        getHttpClient().plugin(HttpSend).intercept { request ->
                            val startNanos = System.nanoTime()
                            try {
                                execute(request)
                            } finally {
                                httpRequestCount.incrementAndGet()
                                httpRequestTotalNanos.addAndGet(System.nanoTime() - startNanos)
                            }
                        }
                    }
                }
                gigaRestChatAPI!!
            }
            bindSingleton<QwenChatAPI>(overrides = true) {
                if (qwenChatAPI == null) {
                    qwenChatAPI = QwenChatAPI(instance(), instance()).apply {
                        getHttpClient().plugin(HttpSend).intercept { request ->
                            val startNanos = System.nanoTime()
                            try {
                                execute(request)
                            } finally {
                                httpRequestCount.incrementAndGet()
                                httpRequestTotalNanos.addAndGet(System.nanoTime() - startNanos)
                            }
                        }
                    }
                }
                qwenChatAPI!!
            }
            bindSingleton<AiTunnelChatAPI>(overrides = true) {
                if (aiTunnelChatAPI == null) {
                    aiTunnelChatAPI = AiTunnelChatAPI(instance(), instance()).apply {
                        getHttpClient().plugin(HttpSend).intercept { request ->
                            val startNanos = System.nanoTime()
                            try {
                                execute(request)
                            } finally {
                                httpRequestCount.incrementAndGet()
                                httpRequestTotalNanos.addAndGet(System.nanoTime() - startNanos)
                            }
                        }
                    }
                }
                aiTunnelChatAPI!!
            }
            bindSingleton<AnthropicChatAPI>(overrides = true) {
                if (anthropicChatAPI == null) {
                    anthropicChatAPI = AnthropicChatAPI(instance(), instance()).apply {
                        getHttpClient().plugin(HttpSend).intercept { request ->
                            val startNanos = System.nanoTime()
                            try {
                                execute(request)
                            } finally {
                                httpRequestCount.incrementAndGet()
                                httpRequestTotalNanos.addAndGet(System.nanoTime() - startNanos)
                            }
                        }
                    }
                }
                anthropicChatAPI!!
            }
            bindSingleton<OpenAIChatAPI>(overrides = true) {
                if (openAiChatAPI == null) {
                    openAiChatAPI = OpenAIChatAPI(instance(), instance()).apply {
                        getHttpClient().plugin(HttpSend).intercept { request ->
                            val startNanos = System.nanoTime()
                            try {
                                execute(request)
                            } finally {
                                httpRequestCount.incrementAndGet()
                                httpRequestTotalNanos.addAndGet(System.nanoTime() - startNanos)
                            }
                        }
                    }
                }
                openAiChatAPI!!
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
                when (selectedModel.provider) {
                    LlmProvider.GIGA -> instance<GigaRestChatAPI>()
                    LlmProvider.QWEN -> instance<QwenChatAPI>()
                    LlmProvider.AI_TUNNEL -> instance<AiTunnelChatAPI>()
                    LlmProvider.ANTHROPIC -> instance<AnthropicChatAPI>()
                    LlmProvider.OPENAI -> instance<OpenAIChatAPI>()
                    LlmProvider.LOCAL -> instance<LocalChatAPI>()
                    LlmProvider.CODEX -> error("Codex OAuth provider is not supported in integration tests.")
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
    ) {
        val previousFewShotExamplesEnabled = fewShotExamplesEnabled
        fewShotExamplesEnabled = useFewShotExamples
        try {
            runAgent(createScenarioDi(mockedTools), userPrompt)
        } finally {
            fewShotExamplesEnabled = previousFewShotExamplesEnabled
        }
    }

    internal fun createScenarioDi(mockedTools: List<LLMToolSetup>): DI =
        DI.invoke(allowSilentOverride = true) {
            import(mainDiModule, allowOverride = true)
            import(testOverrideModule, allowOverride = true)
            bindProvider<DI> { this.di }
            bindSingleton<AgentToolCatalog>(overrides = true) {
                ScenarioAgentToolCatalog(
                    productionCatalog = instance<ToolsFactory>(),
                    mockedTools = mockedTools,
                )
            }
    }

    fun finish() {
        val prefix = "[agent=${agentType.storageValue}]"
        when (selectedModel.provider) {
            LlmProvider.GIGA -> println("$prefix Spent: ${gigaRestChatAPI?.getSessionTokenUsage() ?: "n/a"}")
            LlmProvider.QWEN -> println("$prefix Spent: ${qwenChatAPI?.getSessionTokenUsage() ?: "n/a"}")
            LlmProvider.AI_TUNNEL -> println("$prefix Spent: ${aiTunnelChatAPI?.getSessionTokenUsage() ?: "n/a"}")
            LlmProvider.ANTHROPIC -> println("$prefix Spent: ${anthropicChatAPI?.getSessionTokenUsage() ?: "n/a"}")
            LlmProvider.OPENAI -> println("$prefix Spent: ${openAiChatAPI?.getSessionTokenUsage() ?: "n/a"}")
            LlmProvider.LOCAL -> {
                println("$prefix Spent: local provider")
                runCatching { localLlamaRuntime?.close() }
                localChatAPI = null
                localLlamaRuntime = null
            }
            LlmProvider.CODEX -> println("$prefix Spent: codex provider (no session tracking)")
        }
        val requestCount = httpRequestCount.get()
        if (requestCount == 0L) {
            println("$prefix HTTP requests: 0")
            return
        }
        val avgMs = httpRequestTotalNanos.get().toDouble() / requestCount / 1_000_000.0
        println("$prefix HTTP requests: $requestCount, avg/request: ${"%.2f".format(avgMs)} ms")
    }

    private suspend fun runAgent(di: DI, userPrompt: String) {
        val contextFactory: AgentContextFactory = di.direct.instance()
        val executor: AgentExecutor = di.direct.instance()
        val toolCalls = mutableListOf<ScenarioToolCall>()
        try {
            executor.execute(
                agentId = agentType,
                context = contextFactory.create(agentType),
                input = userPrompt,
                eventSink = object : AgentRuntimeEventSink {
                    override suspend fun emit(event: AgentRuntimeEvent) {
                        if (event is AgentRuntimeEvent.ToolCallStarted) {
                            toolCalls += ScenarioToolCall(event.name, event.arguments)
                        }
                    }
                },
            )
        } finally {
            println("[agent=${agentType.storageValue}] Tool calls: ${toolCalls.joinToString { it.traceLabel() }}")
        }
    }
}

private data class ScenarioToolCall(
    val name: String,
    val arguments: Map<String, Any?>,
)

private fun ScenarioToolCall.traceLabel(): String {
    val skillId = arguments["skillId"] as? String
    val delegatedArguments = arguments["arguments"] as? Map<*, *>
    val argumentKeys = (delegatedArguments?.keys ?: arguments.keys)
        .map(Any?::toString)
        .sorted()
    val target = skillId?.let { "->$it" }.orEmpty()
    return "$name$target(${argumentKeys.joinToString()})"
}

internal class ScenarioAgentToolCatalog(
    productionCatalog: AgentToolCatalog,
    mockedTools: List<LLMToolSetup>,
) : AgentToolCatalog {
    override val toolsByCategory: Map<ToolCategory, Map<String, LLMToolSetup>> = buildMap {
        val duplicateNames = mockedTools
            .groupingBy { it.fn.name }
            .eachCount()
            .filterValues { it > 1 }
            .keys
            .sorted()
        require(duplicateNames.isEmpty()) {
            "Scenario mocks contain duplicate tool names: ${duplicateNames.joinToString()}"
        }

        val mockedByName = mockedTools.associateBy { it.fn.name }
        val matchedNames = mutableSetOf<String>()
        productionCatalog.toolsByCategory.forEach { (category, productionTools) ->
            val scenarioTools = productionTools.mapNotNull { (name, productionTool) ->
                val mockedTool = mockedByName[name] ?: return@mapNotNull null
                matchedNames += name
                name to productionTool.withMockInvocation(mockedTool)
            }.toMap()
            if (scenarioTools.isNotEmpty()) put(category, scenarioTools)
        }

        val unknownNames = (mockedByName.keys - matchedNames).sorted()
        require(unknownNames.isEmpty()) {
            "Scenario mocks are not present in the production tool catalog: ${unknownNames.joinToString()}"
        }
    }
}

private fun LLMToolSetup.withMockInvocation(mockedTool: LLMToolSetup): LLMToolSetup {
    val productionTool = this
    return object : LLMToolSetup {
        override val fn: LLMRequest.Function = productionTool.fn

        override suspend fun invoke(functionCall: LLMResponse.FunctionCall): LLMRequest.Message =
            mockedTool.invoke(functionCall)

        override suspend fun invoke(
            functionCall: LLMResponse.FunctionCall,
            meta: ToolInvocationMeta,
        ): LLMRequest.Message = mockedTool.invoke(functionCall, meta)
    }
}

internal fun emptySkillRegistryRepository(): SkillRegistryRepository = mockk {
    coEvery { listSkills(any()) } returns emptyList()
    coEvery { getSkill(any(), any()) } returns null
    coEvery { getSkillByName(any(), any()) } returns null
    coEvery { loadSkillBundle(any(), any()) } returns null
    coEvery { getValidation(any(), any(), any(), any()) } returns null
}

internal object EmptyMcpToolProvider : McpToolProvider {
    override suspend fun tools(): List<LLMToolSetup> = emptyList()
}

const val SOUZ_AGENT_INTEGRATION_TESTS_ON = "SOUZ_AGENT_INTEGRATION_TESTS_ON"
const val SOUZ_AGENT_INTEGRATION_TEST_AGENT = "SOUZ_AGENT_INTEGRATION_TEST_AGENT"
const val SOUZ_AGENT_INTEGRATION_TEST_MODEL = "SOUZ_AGENT_INTEGRATION_TEST_MODEL"

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
