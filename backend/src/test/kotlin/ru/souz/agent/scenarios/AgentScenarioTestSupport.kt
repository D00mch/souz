package ru.souz.agent.scenarios

import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import org.kodein.di.direct
import org.kodein.di.instance
import ru.souz.ToolLoopGraphBasedAgent
import ru.souz.agent.AgentCoreTools
import ru.souz.agent.AgentExecutionKernel
import ru.souz.agent.AgentExecutionKernelFactory
import ru.souz.agent.AgentId
import ru.souz.agent.runtime.DEFAULT_SUBAGENT_TASK_TIMEOUT
import ru.souz.agent.skills.bundle.SkillBundle
import ru.souz.agent.spi.AgentTelemetry
import ru.souz.backend.agent.model.BackendConversationTurnRequest
import ru.souz.backend.agent.runtime.BackendAgentErrorMessages
import ru.souz.backend.agent.runtime.BackendConversationSettingsProvider
import ru.souz.backend.agent.runtime.BackendNoopAgentDesktopInfoRepository
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
import ru.souz.llms.LLMMessageRole
import ru.souz.llms.LLMModel
import ru.souz.llms.LLMRequest
import ru.souz.llms.ToolInvocationMeta
import ru.souz.llms.restJsonMapper
import ru.souz.memory.NoopConversationMemoryRuntime
import ru.souz.runtime.sandbox.RuntimeSandbox
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

private val SCENARIO_TIMEOUT = 4.minutes

internal suspend fun ScenarioLiveModel.runConversation(
    fixture: ScenarioFixture,
    skillBundles: List<SkillBundle>,
    trace: ScenarioTrace,
): Unit = withContext(Dispatchers.Default) {
    require(fixture.turns.isNotEmpty())
    val home = Files.createTempDirectory("souz-model-scenario-")
    val meta = ToolInvocationMeta("scenario-user", UUID.randomUUID().toString())
    try {
        withTimeout(SCENARIO_TIMEOUT * (fixture.turns.size + 1)) {
            val settings = scenarioConversationSettings(fixture.turns.first())
            val kernel = createScenarioKernel(this, fixture, skillBundles, trace, settings, home, meta)
            runTurns(kernel, settings, fixture, trace, meta)
        }
    } finally {
        home.toFile().deleteRecursively()
    }
}

private fun ScenarioLiveModel.scenarioConversationSettings(prompt: String): BackendConversationSettingsProvider {
    val baseSettings = scenarioSettings(modelId)
    return BackendConversationSettingsProvider(
        delegate = baseSettings,
        defaultSystemPrompt = BackendRuntime.backendSystemPrompt(),
        policyPrompt = SystemBackendConfigSource.value("SOUZ_BACKEND_POLICY_PROMPT", "souz.backend.policyPrompt")
            ?.trim()?.takeIf { it.isNotEmpty() },
        useFewShotExamples = true,
    ).apply {
        applyRequest(BackendConversationTurnRequest(
            prompt, modelId, locale = "ru-RU", timeZone = SCENARIO_ZONE, clientToolsEnabled = true,
        ), temperature = baseSettings.temperature)
        // Keep the scenario's selected provider, including when the model ID is a known alias.
        gigaModel = baseSettings.gigaModel
    }
}

private suspend fun ScenarioLiveModel.createScenarioKernel(
    scope: CoroutineScope,
    fixture: ScenarioFixture,
    skillBundles: List<SkillBundle>,
    trace: ScenarioTrace,
    settings: SettingsProvider,
    home: Path,
    meta: ToolInvocationMeta,
): AgentExecutionKernel {
    val userSettings = mockk<UserSettingsRepository> {
        coEvery { personality(any()) } returns PersonalitySnapshot()
    }
    val localSandbox = LocalRuntimeSandbox(
        SandboxScope(meta.userId), settings,
        homePath = home, stateRoot = home.resolve("state"), workspaceRoot = home,
    )
    val sandbox = object : RuntimeSandbox by localSandbox {
        override val commandExecutor = fixture.commands
    }
    val resolver = ToolInvocationRuntimeSandboxResolver.fixed(sandbox)
    val registry = FileSystemSkillRegistryRepository(sandbox)
    skillBundles.forEach { registry.saveSkillBundle(meta.userId, it) }
    val knowledge = SandboxConversationKnowledgeStore(resolver)
    val production = scenarioBackendDi(settings, resolver, registry, knowledge, fixture.channels)
    val catalog = scenarioProductionCatalog(production, settings, resolver, scope).mapTools { _, tool ->
        if (tool.fn.name in SCENARIO_CHANNEL_TOOLS) tool else scenarioTool(tool)
    }
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
    return AgentExecutionKernelFactory(
        logObjectMapper = restJsonMapper,
        settingsProvider = settings,
        desktopInfoRepository = BackendNoopAgentDesktopInfoRepository,
        toolCatalog = catalog,
        toolsFilter = filter,
        skillBundleProvider = registry,
        runtimeEnvironment = BackendRequestRuntimeEnvironment("ru-RU", SCENARIO_ZONE),
        coreTools = AgentCoreTools(
            getSkillByName = lookup,
            getSkillsByCategory = ToolGetSkillsByCategory({ catalog }, RuntimePassThroughToolsFilter),
            getSkillsNamesByCategory = names,
            getKnowledge = production.direct.instance<ToolGetKnowledge>(),
            searchKnowledge = production.direct.instance<ToolSearchKnowledge>(),
            searchMemory = ToolSearchMemory(NoopConversationMemoryRuntime),
            runtimeCommand = ToolInvokeSkill(
                { catalog }, filter, registry::loadSkillBundle, commandExecutor,
            ),
            subagentTools = { subagents.create(it).tools.map(::scenarioTool) },
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
            userSettings.personalityInstructions(meta.userId,
                canUpdate = PersonalityTool.NAME in catalog.toolsByCategory[ToolCategory.CONFIG].orEmpty())
        },
        automaticMemoryRecall = false,
        captureScope = scope,
    ).create()
}

private suspend fun runTurns(
    kernel: AgentExecutionKernel,
    settings: SettingsProvider,
    fixture: ScenarioFixture,
    trace: ScenarioTrace,
    meta: ToolInvocationMeta,
) {
    var history = fixture.initialHistory
    for ((index, prompt) in fixture.turns.withIndex()) {
        trace.startTurn()
        val result = try {
            withTimeoutOrNull(SCENARIO_TIMEOUT) {
                val context = kernel.contextFactory.create(
                    AgentId.SKILLS_GRAPH, history, settings.gigaModel, settings.contextSize, settings.temperature, meta,
                )
                kernel.executor.execute(AgentId.SKILLS_GRAPH, context, prompt, trace)
            }.also {
                if (it == null) trace.recordTurnError("Execution timed out.")
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Throwable) {
            currentCoroutineContext().ensureActive()
            if (failure !is Exception && failure !is AssertionError) throw failure
            trace.recordTurnError("Execution failed with ${failure.javaClass.simpleName}.")
            null
        }
        val answer = trace.assertCompleted(result)
        history = result?.context?.history ?: (history + LLMRequest.Message(LLMMessageRole.user, prompt))
        fixture.afterTurn(index + 1, answer)
    }
}

internal fun scenarioSettings(modelId: String): SettingsProvider = mockk {
    every { gigaModel } returns LLMModel.OpenAICompatibleCustom
    every { executionModelId(any()) } returns modelId
    every { activeAgentId } returns AgentId.SKILLS_GRAPH
    every { regionProfile } returns "ru"
    every { useStreaming } returns false
    every { contextSize } returns 32_000
    every { temperature } returns 0f
    every { forbiddenFolders } returns emptyList()
    every { requestTimeoutMillis } returns 60_000L
    every { subagentTaskTimeout } returns DEFAULT_SUBAGENT_TASK_TIMEOUT
}

internal const val SCENARIO_ZONE = "Europe/Moscow"
private val SCENARIO_CHANNEL_TOOLS = setOf("ListActiveChannels", "SendMessageToChannel")
