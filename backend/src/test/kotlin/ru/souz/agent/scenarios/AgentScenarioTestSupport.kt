package ru.souz.agent.scenarios

import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import java.nio.file.Files
import java.nio.file.Path
import java.time.Clock
import java.util.UUID
import kotlin.time.Duration.Companion.minutes
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
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
import ru.souz.agent.spi.AgentRuntimeEnvironment
import ru.souz.agent.spi.AgentTelemetry
import ru.souz.backend.agent.model.BackendConversationTurnRequest
import ru.souz.backend.agent.runtime.BackendAgentErrorMessages
import ru.souz.backend.agent.runtime.BackendConversationSettingsProvider
import ru.souz.backend.agent.runtime.BackendNoopAgentDesktopInfoRepository
import ru.souz.backend.agent.runtime.BackendRequestRuntimeEnvironment
import ru.souz.backend.app.BackendRuntime
import ru.souz.backend.config.SystemBackendConfigSource
import ru.souz.backend.memory.hindsight.ToolForgetAllMemory
import ru.souz.backend.memory.hindsight.ToolForgetMemoryFact
import ru.souz.backend.personality.PersonalityTool
import ru.souz.backend.personality.personalityInstructions
import ru.souz.backend.settings.model.PersonalitySnapshot
import ru.souz.backend.settings.repository.UserSettingsRepository
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

internal suspend fun ScenarioLiveModel.runConversation(
    fixture: ScenarioFixture,
    skills: List<SkillBundle>,
    trace: ScenarioTrace,
    clock: Clock,
) = withContext(Dispatchers.Default) {
    val home = Files.createTempDirectory("souz-model-scenario-")
    try {
        val meta = ToolInvocationMeta("scenario-user", UUID.randomUUID().toString())
        val settings = conversationSettings(fixture.turns.first().input, clock.zone.id)
        val kernel = createKernel(this, settings, home, meta, fixture, skills, trace, clock)
        var history = fixture.seed
        for (turn in fixture.turns) {
            trace.start(turn)
            val result = try {
                withTimeoutOrNull(4.minutes) {
                    val context = kernel.contextFactory.create(
                        AgentId.SKILLS_GRAPH, history, settings.gigaModel, settings.contextSize, settings.temperature, meta,
                    )
                    kernel.executor.execute(AgentId.SKILLS_GRAPH, context, turn.input, trace)
                }.also { if (it == null) turn.error = "Turn timed out." }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                turn.error = failure.javaClass.simpleName
                null
            } catch (failure: AssertionError) {
                turn.error = failure.message ?: "AssertionError"
                null
            }
            trace.finish(result)
            history = result?.context?.history ?: (history + LLMRequest.Message(LLMMessageRole.user, turn.input))
        }
    } finally {
        home.toFile().deleteRecursively()
    }
}

private fun ScenarioLiveModel.conversationSettings(prompt: String, zone: String): BackendConversationSettingsProvider {
    val baseSettings = scenarioSettings(modelId)
    return BackendConversationSettingsProvider(
        delegate = baseSettings,
        defaultSystemPrompt = BackendRuntime.backendSystemPrompt(),
        policyPrompt = SystemBackendConfigSource.value("SOUZ_BACKEND_POLICY_PROMPT", "souz.backend.policyPrompt")
            ?.trim()?.takeIf { it.isNotEmpty() },
        useFewShotExamples = true,
    ).apply {
        applyRequest(BackendConversationTurnRequest(
            prompt, modelId, locale = "ru-RU", timeZone = zone, clientToolsEnabled = true,
        ), temperature = 0f)
        // Keep the scenario's selected provider, including when the model ID is a known alias.
        gigaModel = baseSettings.gigaModel
    }
}

private suspend fun ScenarioLiveModel.createKernel(
    scope: CoroutineScope,
    settings: SettingsProvider,
    home: Path,
    meta: ToolInvocationMeta,
    fixture: ScenarioFixture,
    skills: List<SkillBundle>,
    trace: ScenarioTrace,
    clock: Clock,
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
    skills.forEach { registry.saveSkillBundle(meta.userId, it) }
    val knowledge = SandboxConversationKnowledgeStore(resolver)
    val production = scenarioBackendDi(settings, resolver, registry, knowledge, fixture.channels)
    val catalog = scenarioProductionCatalog(production, settings, resolver, scope).mapTools { _, tool ->
        when (tool.fn.name) {
            "ListActiveChannels", "SendMessageToChannel" -> tool
            else -> scenarioTool(tool)
        }
    }
    val filter = RuntimePassThroughToolsFilter
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
        runtimeEnvironment = object : AgentRuntimeEnvironment by BackendRequestRuntimeEnvironment("ru-RU", clock.zone.id) {
            override val clock = clock
        },
        coreTools = AgentCoreTools(
            getSkillByName = ToolGetSkillByName({ catalog }, filter, registry),
            getSkillsByCategory = ToolGetSkillsByCategory({ catalog }, filter),
            getSkillsNamesByCategory = ToolGetSkillsNamesByCategory({ catalog }, filter),
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
        llmApi = trace.api,
        conversationInstructions = {
            userSettings.personalityInstructions(meta.userId,
                canUpdate = PersonalityTool.NAME in catalog.toolsByCategory[ToolCategory.CONFIG].orEmpty())
        },
        automaticMemoryRecall = false,
        captureScope = scope,
    ).create()
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
    every { subagentTaskTimeout } returns DEFAULT_SUBAGENT_TASK_TIMEOUT
    every { requestTimeoutMillis } returns 60_000L
}
