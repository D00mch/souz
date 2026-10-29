package ru.souz.agent.scenarios

import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import java.nio.file.Files
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.kodein.di.direct
import org.kodein.di.instance
import ru.souz.ToolLoopGraphBasedAgent
import ru.souz.agent.AgentCoreTools
import ru.souz.agent.AgentExecutionKernelFactory
import ru.souz.agent.AgentId
import ru.souz.agent.skills.SkillId
import ru.souz.agent.skills.bundle.SkillBundle
import ru.souz.agent.skills.bundle.SkillFile
import ru.souz.agent.spi.AgentTelemetry
import ru.souz.agent.spi.AgentToolCatalog
import ru.souz.backend.agent.model.BackendConversationTurnRequest
import ru.souz.backend.agent.runtime.BackendAgentErrorMessages
import ru.souz.backend.agent.runtime.BackendConversationSettingsProvider
import ru.souz.backend.agent.runtime.BackendRequestRuntimeEnvironment
import ru.souz.backend.app.BackendRuntime
import ru.souz.backend.config.SystemBackendConfigSource
import ru.souz.backend.personality.PersonalityTool
import ru.souz.backend.personality.personalityInstructions
import ru.souz.backend.settings.model.PersonalitySnapshot
import ru.souz.backend.settings.repository.UserSettingsRepository
import ru.souz.backend.memory.hindsight.ToolForgetAllMemory
import ru.souz.backend.memory.hindsight.ToolForgetMemoryFact
import ru.souz.db.SettingsProvider
import ru.souz.knowledge.SandboxConversationKnowledgeStore
import ru.souz.llms.LLMModel
import ru.souz.llms.LlmProvider
import ru.souz.llms.LLMToolSetup
import ru.souz.llms.ToolInvocationMeta
import ru.souz.llms.restJsonMapper
import ru.souz.memory.NoopConversationMemoryRuntime
import ru.souz.runtime.sandbox.RuntimeSandbox
import ru.souz.runtime.sandbox.SandboxCommandExecutor
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
import ru.souz.tool.subagent.SubagentToolFactory
import kotlin.time.Duration.Companion.minutes
import kotlin.time.TimeSource

/** One selected model, fresh production agent wiring for every scenario. */
suspend fun ScenarioLiveModel.runScenario(
    userPrompt: String,
    skillResources: Map<String, String> = emptyMap(),
    mockedTools: List<LLMToolSetup> = emptyList(),
): ScenarioResult = withContext(Dispatchers.Default) {
    val trace = ScenarioTrace(modelId, reasoningEffort)
    val started = TimeSource.Monotonic.markNow()
    val home = Files.createTempDirectory("souz-model-scenario-")
    var completed = false
    try {
        val execution = withTimeout(4.minutes) {
            val baseSettings = scenarioSettings(modelId, provider)
            val settings = BackendConversationSettingsProvider(
                delegate = baseSettings,
                defaultSystemPrompt = BackendRuntime.backendSystemPrompt(),
                policyPrompt = SystemBackendConfigSource.value("SOUZ_BACKEND_POLICY_PROMPT", "souz.backend.policyPrompt")
                    ?.trim()?.takeIf { it.isNotEmpty() },
                locale = "ru-RU",
                useFewShotExamples = true,
            ).apply {
                applyRequest(BackendConversationTurnRequest(
                    userPrompt, modelId, locale = "ru-RU", timeZone = "Europe/Moscow", clientToolsEnabled = true,
                ), temperature = 0f)
                // Keep the scenario's selected provider, including when the model ID is a known alias.
                gigaModel = baseSettings.gigaModel
            }
            val userSettings = mockk<UserSettingsRepository> {
                coEvery { personality(any()) } returns PersonalitySnapshot()
            }
            val localSandbox = LocalRuntimeSandbox(
                SandboxScope(SCENARIO_META.userId), settings,
                homePath = home, stateRoot = home.resolve("state"), workspaceRoot = home,
            )
            val sandbox = object : RuntimeSandbox by localSandbox {
                override val commandExecutor = mockk<SandboxCommandExecutor> {
                    coEvery { execute(any()) } throws AssertionError("Scenario must not execute a process.")
                }
            }
            val resolver = ToolInvocationRuntimeSandboxResolver.fixed(sandbox)
            val registry = FileSystemSkillRegistryRepository(sandbox)
            installProductionSkillBundles(registry, SCENARIO_META.userId)
            for ((id, resource) in skillResources) {
                val bytes = requireNotNull(ScenarioLiveModel::class.java.getResourceAsStream(resource)) {
                    "Missing Skill resource: $resource"
                }.use { it.readBytes() }
                registry.saveSkillBundle(SCENARIO_META.userId, SkillBundle.fromFiles(
                    SkillId(id), listOf(SkillFile("SKILL.md", bytes)),
                ))
            }
            val knowledge = SandboxConversationKnowledgeStore(resolver)
            val production = scenarioBackendDi(settings, resolver, registry, knowledge)
            val productionCatalog = scenarioProductionCatalog(production, settings, resolver, this)
            val fixtureCatalog = AgentToolCatalog(mapOf(ToolCategory.FILES to mockedTools))
            val executionMocks = fixtureCatalog.toolsByCategory.values.flatMap { it.values }.associateBy { it.fn.name }
            val productionNames = productionCatalog.toolsByCategory.values.flatMap { it.keys }.toSet()
            val catalog = AgentToolCatalog.compose(
                productionCatalog.mapTools { _, tool -> scenarioTool(tool, executionMocks[tool.fn.name]) },
                fixtureCatalog.filter { _, tool -> tool.fn.name !in productionNames },
            )
            val filter = RuntimePassThroughToolsFilter
            val lookup = ToolGetSkillByName({ catalog }, filter, registry)
            val names = ToolGetSkillsNamesByCategory({ catalog }, filter)
            val commandExecutor = SkillCommandExecutor(resolver)
            val subagents = SubagentToolFactory(
                createAgent = { maxTurns -> ToolLoopGraphBasedAgent(api, settings, maxTurns) },
                catalogProvider = { catalog },
                toolsFilter = filter,
                skillBundleProvider = registry,
                commandExecutor = commandExecutor,
            )
            val kernel = AgentExecutionKernelFactory(
                logObjectMapper = restJsonMapper,
                settingsProvider = settings,
                toolCatalog = catalog,
                toolsFilter = filter,
                skillBundleProvider = registry,
                runtimeEnvironment = BackendRequestRuntimeEnvironment("ru-RU", "Europe/Moscow"),
                coreTools = AgentCoreTools(
                    getSkillByName = lookup,
                    getSkillsByCategory = ToolGetSkillsByCategory(lookup, names),
                    getSkillsNamesByCategory = names,
                    getKnowledge = production.direct.instance<ToolGetKnowledge>(),
                    searchKnowledge = production.direct.instance<ToolSearchKnowledge>(),
                    searchMemory = ToolSearchMemory(NoopConversationMemoryRuntime),
                    runtimeCommand = ToolInvokeSkill(
                        { catalog }, filter, registry::loadSkillBundle, commandExecutor,
                    ),
                    spawnSubagent = { scenarioTool(subagents.create(it)) },
                    forgetMemory = listOf(
                        scenarioTool(ToolForgetMemoryFact(mockk(), mockk())),
                        scenarioTool(ToolForgetAllMemory(mockk(), mockk(), mockk(), mockk())),
                    ),
                ),
                knowledgeStore = knowledge,
                telemetry = AgentTelemetry.NONE,
                errorMessages = BackendAgentErrorMessages,
                llmApi = trace.instrument(api),
                conversationInstructions = {
                    userSettings.personalityInstructions(SCENARIO_META.userId,
                        canUpdate = PersonalityTool.NAME in catalog.toolsByCategory[ToolCategory.CONFIG].orEmpty())
                },
                automaticMemoryRecall = false,
                captureScope = this,
            ).create()
            val context = kernel.contextFactory.create(AgentId.SKILLS_GRAPH).copy(
                toolInvocationMeta = SCENARIO_META,
            )
            kernel.executor.execute(AgentId.SKILLS_GRAPH, context, userPrompt, trace)
        }
        trace.assertCompleted(execution)
        completed = true
        ScenarioResult(execution, trace.requests.toList(), trace.events.toList(), trace.metrics(started.elapsedNow()))
    } finally {
        println("Scenario execution=${if (completed) "completed" else "failed"}; ${trace.metrics(started.elapsedNow())}")
        home.toFile().deleteRecursively()
    }
}

internal fun scenarioSettings(modelId: String, provider: LlmProvider): SettingsProvider = mockk {
    every { gigaModel } returns if (provider == LlmProvider.CODEX) LLMModel.CodexGpt6Astra else LLMModel.OpenAICompatibleCustom
    every { executionModelId(any()) } returns modelId
    every { activeAgentId } returns AgentId.SKILLS_GRAPH
    every { regionProfile } returns "ru"
    every { useStreaming } returns false
    every { contextSize } returns 32_000
    every { temperature } returns 0f
    every { forbiddenFolders } returns emptyList()
    every { requestTimeoutMillis } returns 60_000L
}

private val SCENARIO_META = ToolInvocationMeta("scenario-user", "scenario-conversation")
