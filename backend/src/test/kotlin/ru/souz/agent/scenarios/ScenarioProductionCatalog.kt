package ru.souz.agent.scenarios

import io.mockk.coEvery
import io.mockk.mockk
import java.nio.file.Files
import java.nio.file.Path
import kotlinx.coroutines.CoroutineScope
import org.kodein.di.DI
import org.kodein.di.bindSingleton
import org.kodein.di.direct
import org.kodein.di.instance
import ru.souz.agent.knowledge.ConversationKnowledgeStore
import ru.souz.agent.skills.SkillId
import ru.souz.agent.skills.bundle.SkillBundle
import ru.souz.agent.skills.bundle.SkillFile
import ru.souz.agent.skills.registry.SkillRegistryRepository
import ru.souz.agent.spi.AgentToolCatalog
import ru.souz.backend.agent.runtime.conversation.backendExecutionToolCatalog
import ru.souz.backend.app.BackendAppConfig
import ru.souz.backend.app.BackendDiTags
import ru.souz.backend.app.backendDiModule
import ru.souz.backend.channels.ChannelProviderRegistry
import ru.souz.backend.client.BackendClientSkills
import ru.souz.backend.config.BackendFeatureFlags
import ru.souz.backend.execution.repository.AgentExecutionRepository
import ru.souz.backend.personality.PersonalityTool
import ru.souz.db.SettingsProvider
import ru.souz.jobs.JobService
import ru.souz.llms.LLMToolSetup
import ru.souz.runtime.files.FilesToolUtil
import ru.souz.runtime.sandbox.ToolInvocationRuntimeSandboxResolver
import ru.souz.skilloauth.SkillOAuthGateway
import ru.souz.tool.LlmBackedToolCatalog
import ru.souz.tool.ToolCategory

internal fun scenarioBackendDi(
    settings: SettingsProvider,
    resolver: ToolInvocationRuntimeSandboxResolver,
    registry: SkillRegistryRepository,
    knowledge: ConversationKnowledgeStore,
): DI {
    val config = BackendAppConfig(BackendFeatureFlags(), server = mockk(), postgres = mockk())
    return DI {
        import(backendDiModule("", config, dataSourceFactory = { error("Scenario must not open a database.") }))
        bindSingleton<SettingsProvider>(overrides = true) { settings }
        bindSingleton<ToolInvocationRuntimeSandboxResolver>(overrides = true) { resolver }
        bindSingleton<SkillRegistryRepository>(overrides = true) { registry }
        bindSingleton<ConversationKnowledgeStore>(overrides = true) { knowledge }
        // Include optional OAuth tools without credentials, HTTP clients or token storage.
        bindSingleton<SkillOAuthGateway> { mockk() }
        bindSingleton<JobService>(overrides = true) { mockk() }
        bindSingleton<AgentExecutionRepository>(overrides = true) { mockk() }
        bindSingleton<ChannelProviderRegistry>(overrides = true) { mockk() }
    }
}

/** Reuses backend registrations and client Skill resources; only invocation is replaced. */
internal fun scenarioProductionCatalog(
    di: DI,
    settings: SettingsProvider,
    resolver: ToolInvocationRuntimeSandboxResolver,
    scope: CoroutineScope,
): AgentToolCatalog {
    val llmTools = LlmBackedToolCatalog(mockk(), settings, FilesToolUtil(resolver), mockk(), mockk(), mockk())
    val personality = PersonalityTool(mockk(), mockk(), mockk(), settings, scope)
    val clientSkills = BackendClientSkills(mockk(), mockk(), mockk(), mockk()).catalog(emptyList())
    return backendExecutionToolCatalog(
        compiledToolCatalog = di.direct.instance(tag = BackendDiTags.MERGED_TOOL_CATALOG),
        executionLlmToolCatalog = AgentToolCatalog.compose(
            llmTools.catalog,
            AgentToolCatalog(mapOf(ToolCategory.CONFIG to listOf(personality))),
        ),
        enabledCompiledToolNames = null,
        clientToolCatalog = clientSkills,
        includeFewShotExamples = true,
    )
}

internal fun scenarioTool(productionTool: LLMToolSetup, executionMock: LLMToolSetup? = null): LLMToolSetup {
    val execution = executionMock ?: mockk<LLMToolSetup>().also { tool ->
        coEvery { tool.invoke(any()) } throws AssertionError("No scenario reply configured for ${productionTool.fn.name}.")
        coEvery { tool.invoke(any(), any()) } throws AssertionError("No scenario reply configured for ${productionTool.fn.name}.")
    }
    return object : LLMToolSetup by execution {
        override val fn = productionTool.fn
    }
}

/** processTestResources copies the complete production sandbox Skill tree, including supporting files. */
internal suspend fun installProductionSkillBundles(registry: SkillRegistryRepository, userId: String) {
    val root = Path.of(requireNotNull(ScenarioLiveModel::class.java.getResource("/skills/runtime")).toURI())
    val bundles = Files.list(root).use { directories ->
        directories.filter { Files.isDirectory(it) }.sorted().map { directory ->
            val files = Files.walk(directory).use { paths ->
                paths.filter { Files.isRegularFile(it) }.sorted().map { path ->
                    SkillFile(directory.relativize(path).toString().replace('\\', '/'), Files.readAllBytes(path))
                }.toList()
            }
            SkillBundle.fromFiles(SkillId(directory.fileName.toString()), files)
        }.toList()
    }
    bundles.forEach { registry.saveSkillBundle(userId, it) }
}
