package ru.souz.android.agent

import android.content.Context
import android.content.Intent
import android.net.Uri
import com.fasterxml.jackson.annotation.JsonInclude
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.SerializationFeature
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import org.kodein.di.DI
import org.kodein.di.bindSingleton
import org.kodein.di.direct
import org.kodein.di.instance
import ru.souz.agent.AgentFacade
import ru.souz.agent.agentDiModule
import ru.souz.agent.session.GraphSessionRepository
import ru.souz.agent.skills.activation.SkillId
import ru.souz.agent.skills.bundle.SkillBundle
import ru.souz.agent.skills.registry.SkillRegistryRepository
import ru.souz.agent.skills.registry.StoredSkill
import ru.souz.agent.skills.validation.SkillValidationRecord
import ru.souz.agent.skills.validation.SkillValidationStatus
import ru.souz.agent.spi.AgentDesktopInfoRepository
import ru.souz.agent.spi.AgentErrorMessages
import ru.souz.agent.spi.AgentTelemetry
import ru.souz.agent.spi.DefaultBrowserProvider
import ru.souz.agent.spi.McpToolProvider
import ru.souz.android.sandbox.AndroidRuntimeSandboxFactory
import ru.souz.android.settings.AndroidSettingsProvider
import ru.souz.db.SettingsProvider
import ru.souz.di.sharedUiCommonJvmDiModule
import ru.souz.llms.LLMChatAPI
import ru.souz.llms.LLMToolSetup
import ru.souz.llms.LlmBuildProfile
import ru.souz.llms.LlmProvider
import ru.souz.llms.SessionTokenLogging
import ru.souz.llms.TokenLogging
import ru.souz.llms.anthropic.AnthropicChatAPI
import ru.souz.llms.anthropic.AnthropicVisionGateway
import ru.souz.llms.codex.CodexChatAPI
import ru.souz.llms.codex.CodexOAuthService
import ru.souz.llms.giga.GigaAuth
import ru.souz.llms.giga.GigaRestChatAPI
import ru.souz.llms.openai.OpenAIChatAPI
import ru.souz.llms.openai.OpenAIImageGenerationGateway
import ru.souz.llms.openai.OpenAIVisionGateway
import ru.souz.llms.qwen.QwenChatAPI
import ru.souz.llms.runtime.CapabilityBasedImageGenerationGateway
import ru.souz.llms.runtime.ImageGenerationGateway
import ru.souz.llms.runtime.LLMCapabilityResolver
import ru.souz.llms.runtime.LLMFactory
import ru.souz.llms.runtime.VisionGateway
import ru.souz.llms.tunnel.AiTunnelChatAPI
import ru.souz.memory.ConversationMemoryRuntime
import ru.souz.memory.NoopConversationMemoryRuntime
import ru.souz.paths.SouzPaths
import ru.souz.runtime.sandbox.RuntimeSandboxFactory
import ru.souz.llms.runtime.ApiClassifier
import ru.souz.runtime.files.FilesToolUtil
import ru.souz.service.observability.DesktopStructuredLogger
import ru.souz.tool.ImmediateToolPermissionBroker
import ru.souz.tool.ToolPermissionBroker
import ru.souz.tool.ToolsSettings
import ru.souz.tool.ToolsSettingsState
import ru.souz.tool.ToolsSettingsStore
import ru.souz.tool.UserMessageClassifier
import ru.souz.tool.files.DeferredToolModifyPermissionBroker
import ru.souz.tool.portableRuntimeToolsDiModule
import ru.souz.ui.host.ExternalLinkOpener
import java.nio.file.Path

class AndroidAgentRuntime(
    context: Context,
    settings: AndroidSettingsProvider,
) {
    val di: DI
    val agentFacade: AgentFacade

    init {
        val appContext = context.applicationContext
        val paths = AndroidSouzPaths(
            stateRoot = appContext.filesDir.toPath().resolve("souz-state"),
        )
        di = DI {
            bindSingleton<ObjectMapper>(tag = DiTags.LOG_OBJECT_MAPPER) {
                jacksonObjectMapper()
                    .setSerializationInclusion(JsonInclude.Include.NON_NULL)
                    .enable(SerializationFeature.INDENT_OUTPUT)
            }
            bindSingleton<SettingsProvider> { settings }
            bindSingleton { LlmBuildProfile(instance<SettingsProvider>()) }
            bindSingleton { DesktopStructuredLogger() }
            bindSingleton<TokenLogging> {
                SessionTokenLogging(instance(tag = DiTags.LOG_OBJECT_MAPPER))
            }
            bindSingleton { GigaAuth(instance()) }
            bindSingleton<GigaRestChatAPI> { GigaRestChatAPI(instance(), instance(), instance()) }
            bindSingleton<QwenChatAPI> { QwenChatAPI(instance(), instance()) }
            bindSingleton<AiTunnelChatAPI> { AiTunnelChatAPI(instance(), instance()) }
            bindSingleton<AnthropicChatAPI> { AnthropicChatAPI(instance(), instance()) }
            bindSingleton { OpenAIChatAPI(instance(), instance()) }
            bindSingleton { CodexOAuthService(instance()) }
            bindSingleton<CodexChatAPI> { CodexChatAPI(instance(), instance(), instance()) }
            bindSingleton {
                LLMFactory(
                    settingsProvider = instance(),
                    apisByProvider = mapOf(
                        LlmProvider.GIGA to instance<GigaRestChatAPI>(),
                        LlmProvider.QWEN to instance<QwenChatAPI>(),
                        LlmProvider.AI_TUNNEL to instance<AiTunnelChatAPI>(),
                        LlmProvider.ANTHROPIC to instance<AnthropicChatAPI>(),
                        LlmProvider.OPENAI to instance<OpenAIChatAPI>(),
                        LlmProvider.CODEX to instance<CodexChatAPI>(),
                    ),
                )
            }
            bindSingleton<LLMChatAPI> { instance<LLMFactory>() }
            bindSingleton { OpenAIImageGenerationGateway(instance()) }
            bindSingleton { OpenAIVisionGateway(instance(), instance()) }
            bindSingleton { AnthropicVisionGateway(instance(), instance()) }
            bindSingleton { LLMCapabilityResolver(instance(), instance(), instance()) }
            bindSingleton { CapabilityBasedImageGenerationGateway(instance(), instance()) }
            bindSingleton<VisionGateway> { instance<LLMCapabilityResolver>() }
            bindSingleton<ImageGenerationGateway> { instance<CapabilityBasedImageGenerationGateway>() }
            bindSingleton<RuntimeSandboxFactory> { AndroidRuntimeSandboxFactory(appContext) }
            bindSingleton<GraphSessionRepository>(tag = DiTags.GRAPH_SESSION_REPOSITORY) {
                GraphSessionRepository(paths)
            }

            bindSingleton<AgentDesktopInfoRepository> { AndroidNoopDesktopInfoRepository }
            bindSingleton<DefaultBrowserProvider> { DefaultBrowserProvider { null } }
            bindSingleton<McpToolProvider> { AndroidNoopMcpToolProvider }
            bindSingleton<AgentTelemetry> { AgentTelemetry.NONE }
            bindSingleton<AgentErrorMessages> { AndroidAgentErrorMessages }
            bindSingleton<ConversationMemoryRuntime> { NoopConversationMemoryRuntime }
            bindSingleton<CoroutineScope> { CoroutineScope(SupervisorJob() + Dispatchers.Default) }
            bindSingleton<SkillRegistryRepository> { AndroidNoopSkillRegistryRepository }
            bindSingleton<UserMessageClassifier>(tag = DiTags.API_CLASSIFIER) { ApiClassifier(instance<LLMChatAPI>()) }
            bindSingleton<UserMessageClassifier>(tag = DiTags.LOCAL_CLASSIFIER) { AndroidNoopClassifier }
            bindSingleton<ToolPermissionBroker> { ImmediateToolPermissionBroker(instance<SettingsProvider>()) }
            bindSingleton { DeferredToolModifyPermissionBroker(instance<SettingsProvider>(), instance<FilesToolUtil>()) }
            bindSingleton<ToolsSettingsStore> {
                AndroidToolsSettingsStore(
                    context = appContext,
                    objectMapper = instance(tag = DiTags.LOG_OBJECT_MAPPER),
                )
            }
            bindSingleton { ToolsSettings(instance(), instance()) }

            import(portableRuntimeToolsDiModule())
            import(
                agentDiModule(
                    logObjectMapperTag = DiTags.LOG_OBJECT_MAPPER,
                    apiClassifierTag = DiTags.API_CLASSIFIER,
                    localClassifierTag = DiTags.LOCAL_CLASSIFIER,
                    graphSessionRepositoryTag = DiTags.GRAPH_SESSION_REPOSITORY,
                )
            )
            import(sharedUiCommonJvmDiModule(), allowOverride = true)
            bindSingleton<ExternalLinkOpener>(overrides = true) {
                ExternalLinkOpener { url ->
                    runCatching {
                        appContext.startActivity(
                            Intent(Intent.ACTION_VIEW, Uri.parse(url))
                                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                        )
                    }
                }
            }
        }
        agentFacade = di.direct.instance()
    }

    private object DiTags {
        const val LOG_OBJECT_MAPPER = "androidLogObjectMapper"
        const val API_CLASSIFIER = "androidApiClassifier"
        const val LOCAL_CLASSIFIER = "androidLocalClassifier"
        const val GRAPH_SESSION_REPOSITORY = "androidGraphSessionRepository"
    }
}

private class AndroidToolsSettingsStore(
    context: Context,
    private val objectMapper: ObjectMapper,
) : ToolsSettingsStore {
    private val prefs = context.getSharedPreferences("souz_android_tool_settings", Context.MODE_PRIVATE)

    override fun loadToolsSettings(key: String): ToolsSettingsState? =
        prefs.getString(key, null)?.let { json ->
            runCatching { objectMapper.readValue(json, ToolsSettingsState::class.java) }.getOrNull()
        }

    override fun saveToolsSettings(key: String, state: ToolsSettingsState) {
        prefs.edit().putString(key, objectMapper.writeValueAsString(state)).apply()
    }
}

private class AndroidSouzPaths(
    override val stateRoot: Path,
) : SouzPaths {
    override val sessionsDir: Path = stateRoot.resolve("sessions")
    override val vectorIndexDir: Path = stateRoot.resolve("vector-index")
    override val logsDir: Path = stateRoot.resolve("logs")
    override val modelsDir: Path = stateRoot.resolve("models")
    override val nativeLibsDir: Path = stateRoot.resolve("native")
    override val skillsDir: Path = stateRoot.resolve("skills")
    override val skillValidationsDir: Path = stateRoot.resolve("skill-validations")
}

private object AndroidNoopClassifier : UserMessageClassifier {
    override suspend fun classify(body: String): UserMessageClassifier.Reply =
        UserMessageClassifier.Reply(categories = emptyList(), confidence = 100.0)
}

private object AndroidNoopDesktopInfoRepository : AgentDesktopInfoRepository {
    override suspend fun search(query: String, limit: Int): List<ru.souz.db.StorredData> = emptyList()
}

private object AndroidNoopMcpToolProvider : McpToolProvider {
    override suspend fun tools(): List<LLMToolSetup> = emptyList()
}

private object AndroidAgentErrorMessages : AgentErrorMessages {
    override suspend fun contextReset(): String =
        "Conversation context was reset because it became too large."

    override suspend fun timeout(): String =
        "The model request timed out."

    override suspend fun noMoney(): String =
        "The configured provider has no available balance."
}

private object AndroidNoopSkillRegistryRepository : SkillRegistryRepository {
    override suspend fun listSkills(userId: String): List<StoredSkill> = emptyList()

    override suspend fun getSkill(userId: String, skillId: SkillId): StoredSkill? = null

    override suspend fun getSkillByName(userId: String, name: String): StoredSkill? = null

    override suspend fun saveSkillBundle(userId: String, bundle: SkillBundle): StoredSkill =
        error("Skills are not available in the Android chat-agent runtime yet.")

    override suspend fun loadSkillBundle(userId: String, skillId: SkillId): SkillBundle? = null

    override suspend fun getValidation(
        userId: String,
        skillId: SkillId,
        bundleHash: String,
        policyVersion: String,
    ): SkillValidationRecord? = null

    override suspend fun saveValidation(record: SkillValidationRecord) = Unit

    override suspend fun markValidationStatus(
        userId: String,
        skillId: SkillId,
        bundleHash: String,
        policyVersion: String,
        status: SkillValidationStatus,
        reason: String?,
    ) = Unit

    override suspend fun invalidateOtherValidations(
        userId: String,
        skillId: SkillId,
        activeBundleHash: String,
        policyVersion: String,
        reason: String?,
    ) = Unit
}
