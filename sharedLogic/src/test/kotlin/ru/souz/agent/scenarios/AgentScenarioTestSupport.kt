package ru.souz.agent.scenarios

import io.mockk.every
import io.mockk.mockk
import java.nio.file.Files
import java.util.Locale
import java.time.ZoneId
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import ru.souz.agent.AgentCoreTools
import ru.souz.agent.AgentExecutionKernelFactory
import ru.souz.agent.AgentId
import ru.souz.agent.skills.SkillId
import ru.souz.agent.skills.bundle.SkillBundle
import ru.souz.agent.skills.bundle.SkillFile
import ru.souz.agent.spi.AgentErrorMessages
import ru.souz.agent.spi.AgentRuntimeEnvironment
import ru.souz.agent.spi.AgentTelemetry
import ru.souz.agent.spi.AgentToolCatalog
import ru.souz.db.SettingsProvider
import ru.souz.knowledge.SandboxConversationKnowledgeStore
import ru.souz.llms.LLMChatAPI
import ru.souz.llms.LLMModel
import ru.souz.llms.LLMToolSetup
import ru.souz.llms.ToolInvocationMeta
import ru.souz.llms.restJsonMapper
import ru.souz.memory.NoopConversationMemoryRuntime
import ru.souz.runtime.sandbox.SandboxScope
import ru.souz.runtime.sandbox.ToolInvocationRuntimeSandboxResolver
import ru.souz.runtime.sandbox.local.LocalRuntimeSandbox
import ru.souz.skills.registry.FileSystemSkillRegistryRepository
import ru.souz.tool.RuntimePassThroughToolsFilter
import ru.souz.tool.ToolCategory
import ru.souz.tool.knowledge.ToolGetKnowledge
import ru.souz.tool.knowledge.ToolSearchKnowledge
import ru.souz.tool.memory.ToolSearchMemory
import ru.souz.tool.skills.SkillCommandExecutor
import ru.souz.tool.skills.ToolGetSkillByName
import ru.souz.tool.skills.ToolGetSkillsByCategory
import ru.souz.tool.skills.ToolGetSkillsNamesByCategory
import ru.souz.tool.skills.ToolInvokeSkill
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.TimeSource

/** One selected model, fresh production agent wiring for every scenario. */
class AgentScenarioTestSupport(
    private val modelId: String,
    private val llmApi: LLMChatAPI,
    private val reasoningEffort: String? = null,
    private val maxModelCalls: Int = 24,
    private val scenarioTimeout: Duration = 4.minutes,
    private val report: (String) -> Unit = ::println,
) {
    suspend fun runScenario(
        userPrompt: String,
        skillResources: Map<String, String> = emptyMap(),
        mockedTools: List<LLMToolSetup> = emptyList(),
    ): ScenarioResult = withContext(Dispatchers.Default) {
        val trace = ScenarioTrace(modelId, reasoningEffort, maxModelCalls)
        val started = TimeSource.Monotonic.markNow()
        val home = Files.createTempDirectory("souz-model-scenario-")
        var completed = false
        try {
            val execution = withTimeout(scenarioTimeout) {
                coroutineScope {
                    val settings = scenarioSettings(modelId)
                    val sandbox = LocalRuntimeSandbox(
                        SandboxScope(SCENARIO_META.userId), settings,
                        homePath = home, stateRoot = home.resolve("state"), workspaceRoot = home,
                    )
                    val resolver = ToolInvocationRuntimeSandboxResolver.fixed(sandbox)
                    val registry = FileSystemSkillRegistryRepository(sandbox)
                    for ((id, resource) in skillResources) {
                        val bytes = requireNotNull(javaClass.getResourceAsStream(resource)) {
                            "Missing Skill resource: $resource"
                        }.use { it.readBytes() }
                        registry.saveSkillBundle(SCENARIO_META.userId, SkillBundle.fromFiles(
                            SkillId(id), listOf(SkillFile("SKILL.md", bytes)),
                        ))
                    }
                    val catalog = AgentToolCatalog(mapOf(ToolCategory.FILES to mockedTools))
                    val filter = RuntimePassThroughToolsFilter
                    val knowledge = SandboxConversationKnowledgeStore(resolver)
                    val lookup = ToolGetSkillByName({ catalog }, filter, registry)
                    val names = ToolGetSkillsNamesByCategory({ catalog }, filter)
                    val kernel = AgentExecutionKernelFactory(
                        logObjectMapper = restJsonMapper,
                        settingsProvider = settings,
                        toolCatalog = catalog,
                        toolsFilter = filter,
                        skillBundleProvider = registry,
                        runtimeEnvironment = ScenarioEnvironment,
                        coreTools = AgentCoreTools(
                            getSkillByName = lookup,
                            getSkillsByCategory = ToolGetSkillsByCategory(lookup, names),
                            getSkillsNamesByCategory = names,
                            getKnowledge = ToolGetKnowledge(knowledge),
                            searchKnowledge = ToolSearchKnowledge(knowledge),
                            searchMemory = ToolSearchMemory(NoopConversationMemoryRuntime),
                            runtimeCommand = ToolInvokeSkill(
                                { catalog }, filter, registry::loadSkillBundle, SkillCommandExecutor(resolver),
                            ),
                        ),
                        knowledgeStore = knowledge,
                        telemetry = AgentTelemetry.NONE,
                        errorMessages = ScenarioErrorMessages,
                        llmApi = trace.instrument(llmApi),
                        automaticMemoryRecall = false,
                        captureScope = this,
                    ).create()
                    val context = kernel.contextFactory.create(AgentId.SKILLS_GRAPH).copy(
                        toolInvocationMeta = SCENARIO_META,
                    )
                    kernel.executor.execute(AgentId.SKILLS_GRAPH, context, userPrompt, trace)
                }
            }
            trace.assertCompleted(execution)
            completed = true
            ScenarioResult(execution, trace.requests.toList(), trace.events.toList(), trace.metrics(started.elapsedNow()))
        } finally {
            try {
                report("Scenario execution=${if (completed) "completed" else "failed"}; ${trace.metrics(started.elapsedNow())}")
            } finally {
                home.toFile().deleteRecursively()
            }
        }
    }
}

internal fun scenarioSettings(modelId: String): SettingsProvider = mockk {
    every { gigaModel } returns LLMModel.OpenAICompatibleCustom
    every { executionModelId(any()) } returns modelId
    every { activeAgentId } returns AgentId.SKILLS_GRAPH
    every { getSystemPromptForAgentModel(any(), any()) } returns null
    every { regionProfile } returns "ru"
    every { useStreaming } returns false
    every { contextSize } returns 32_000
    every { temperature } returns 0f
    every { forbiddenFolders } returns emptyList()
    every { requestTimeoutMillis } returns 60_000L
}

private val SCENARIO_META = ToolInvocationMeta("scenario-user", "scenario-conversation")

private object ScenarioEnvironment : AgentRuntimeEnvironment {
    override val locale: Locale = Locale.forLanguageTag("ru-RU")
    override val zoneId: ZoneId = ZoneId.of("Europe/Moscow")
}

private object ScenarioErrorMessages : AgentErrorMessages {
    override suspend fun contextReset(): String = "Scenario context exceeded its limit."
    override suspend fun timeout(): String = "Scenario provider request timed out."
    override suspend fun noMoney(): String = "Scenario provider has insufficient credits."
}
