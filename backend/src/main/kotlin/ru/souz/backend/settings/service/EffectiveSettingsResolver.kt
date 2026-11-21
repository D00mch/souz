package ru.souz.backend.settings.service

import java.time.Instant
import java.time.ZoneId
import java.util.Locale
import ru.souz.agent.spi.AgentToolCatalog
import ru.souz.backend.common.BackendLlmSupport
import ru.souz.backend.common.BackendToolCapabilityPolicy
import ru.souz.backend.common.hasServerChatAccess
import ru.souz.backend.common.supportsChatModel
import ru.souz.backend.config.BackendFeatureFlags
import ru.souz.backend.keys.repository.UserProviderKeyRepository
import ru.souz.backend.settings.model.EffectiveUserSettings
import ru.souz.backend.settings.model.ToolPermission
import ru.souz.backend.settings.model.UserMcpServer
import ru.souz.backend.settings.model.UserSettings
import ru.souz.backend.settings.repository.UserSettingsRepository
import ru.souz.llms.findLLMModel
import ru.souz.backend.http.invalidV1Request
import ru.souz.llms.LLMModel
import ru.souz.llms.LlmProvider

data class UserSettingsOverrides(
    val defaultModel: String? = null,
    val temperature: Float? = null,
    val locale: Locale? = null,
    val timeZone: ZoneId? = null,
    val systemPrompt: String? = null,
    val enabledTools: Set<String>? = null,
    val showToolEvents: Boolean? = null,
    val streamingMessages: Boolean? = null,
    val narrateSteps: Boolean? = null,
    val intermediateMessages: Boolean? = null,
    val interfaceLanguage: String? = null,
    val requestTimeoutMillis: Long? = null,
    val useFewShotExamples: Boolean? = null,
    val toolPermissions: Map<String, ToolPermission>? = null,
    val mcp: Map<String, UserMcpServer>? = null,
)

class EffectiveSettingsResolver(
    private val baseSettingsProvider: BackendSettingsProvider,
    private val userSettingsRepository: UserSettingsRepository,
    private val userProviderKeyRepository: UserProviderKeyRepository,
    private val featureFlags: BackendFeatureFlags,
    private val toolCatalog: AgentToolCatalog,
    val namedModelNames: Set<String> = emptySet(),
    private val pluginToolCatalog: () -> AgentToolCatalog = { AgentToolCatalog() },
) {
    val advertisedToolNames: Set<String> get() = advertisedToolNames(pluginToolCatalog())

    fun advertisedToolNames(pluginCatalog: AgentToolCatalog): Set<String> =
        BackendToolCapabilityPolicy.advertisedToolNames(toolCatalog, pluginCatalog)

    internal suspend fun defaultModel(
        userManagedProviders: suspend () -> Set<LlmProvider> = { emptySet() },
    ): String = normalizeModel(baseSettingsProvider.configuredChatModel?.alias, userManagedProviders)

    suspend fun isSelectableDefaultModel(userId: String, model: String): Boolean =
        model in namedModelNames || findLLMModel(model)?.let { isSelectableModel(it) { loadUserManagedProviders(userId) } } == true

    suspend fun resolve(
        userId: String,
        requestOverrides: UserSettingsOverrides? = null,
        userManagedProviders: Set<LlmProvider>? = null,
        availableToolNames: Set<String> = advertisedToolNames,
    ): EffectiveUserSettings {
        var loadedProviders = userManagedProviders
        val providers: suspend () -> Set<LlmProvider> = {
            loadedProviders ?: loadUserManagedProviders(userId).also { loadedProviders = it }
        }
        val persisted = userSettingsRepository.get(userId)
            ?: userSettingsRepository.save(defaultsFor(userId, providers))

        val defaultLocale = baseSettingsProvider.defaultLocale
        val locale = (requestOverrides?.locale ?: persisted.locale)?.takeIf { it.language.isNotBlank() } ?: defaultLocale
        val timeZone = requestOverrides?.timeZone ?: persisted.timeZone ?: ZoneId.systemDefault()
        val interfaceLanguage = normalizeInterfaceLanguage(
            requestOverrides?.interfaceLanguage
                ?: persisted.interfaceLanguage
                ?: defaultLocale.language
        )
        val requestTimeoutMillis = normalizeRequestTimeoutMillis(
            requestOverrides?.requestTimeoutMillis
                ?: persisted.requestTimeoutMillis
                ?: baseSettingsProvider.requestTimeoutMillis
        )
        val defaultModel = normalizeModel(
            model = requestOverrides?.defaultModel ?: persisted.defaultModel ?: baseSettingsProvider.configuredChatModel?.alias,
            userManagedProviders = providers,
        )
        val enabledTools = normalizeEnabledTools(requestOverrides?.enabledTools ?: persisted.enabledTools, availableToolNames)
        val showToolEventsPreference = requestOverrides?.showToolEvents ?: persisted.showToolEvents ?: true
        val streamingPreference = requestOverrides?.streamingMessages
            ?: persisted.streamingMessages
            ?: baseSettingsProvider.useStreaming
        val useFewShotExamples = requestOverrides?.useFewShotExamples
            ?: persisted.useFewShotExamples
            ?: DEFAULT_BACKEND_USE_FEW_SHOT_EXAMPLES

        return EffectiveUserSettings(
            userId = userId,
            reasoningEffort = persisted.reasoningEffort,
            defaultModel = defaultModel,
            temperature = requestOverrides?.temperature ?: persisted.temperature ?: baseSettingsProvider.temperature,
            locale = locale,
            timeZone = timeZone,
            systemPrompt = requestOverrides?.systemPrompt ?: persisted.systemPrompt,
            enabledTools = enabledTools,
            showToolEvents = featureFlags.toolEvents && showToolEventsPreference,
            streamingMessages = featureFlags.streamingMessages && streamingPreference,
            narrateSteps = requestOverrides?.narrateSteps ?: persisted.narrateSteps ?: false,
            intermediateMessages = requestOverrides?.intermediateMessages ?: persisted.intermediateMessages ?: true,
            interfaceLanguage = interfaceLanguage,
            requestTimeoutMillis = requestTimeoutMillis,
            useFewShotExamples = useFewShotExamples,
            toolPermissions = requestOverrides?.toolPermissions ?: persisted.toolPermissions,
            mcp = requestOverrides?.mcp ?: persisted.mcp,
        )
    }

    private suspend fun defaultsFor(
        userId: String,
        userManagedProviders: suspend () -> Set<LlmProvider>,
    ): UserSettings {
        val locale = baseSettingsProvider.defaultLocale
        val now = Instant.now()
        return UserSettings(
            userId = userId,
            defaultModel = defaultModel(userManagedProviders),
            temperature = baseSettingsProvider.temperature,
            locale = locale,
            timeZone = ZoneId.systemDefault(),
            systemPrompt = null,
            showToolEvents = true,
            streamingMessages = baseSettingsProvider.useStreaming,
            interfaceLanguage = locale.language,
            requestTimeoutMillis = normalizeRequestTimeoutMillis(baseSettingsProvider.requestTimeoutMillis),
            useFewShotExamples = DEFAULT_BACKEND_USE_FEW_SHOT_EXAMPLES,
            toolPermissions = emptyMap(),
            mcp = emptyMap(),
            createdAt = now,
            updatedAt = now,
        )
    }

    private fun normalizeEnabledTools(enabledTools: Set<String>?, available: Set<String>): Set<String> =
        (enabledTools ?: available).filterTo(linkedSetOf()) {
            it in available || BackendToolCapabilityPolicy.isPluginToolName(it)
        }

    private suspend fun normalizeModel(
        model: String?,
        userManagedProviders: suspend () -> Set<LlmProvider>,
    ): String {
        if (model != null && model in namedModelNames) return model
        val builtin = model?.let { findLLMModel(it) ?: throw invalidV1Request("Unknown configured model: $it.") }
        if (builtin?.provider == LlmProvider.GIGA) return builtin.alias
        return builtin?.withConfiguredOpenAiCompatibleChatModel()
            ?.takeIf { isSelectableModel(it, userManagedProviders) }?.alias
            ?: BackendLlmSupport.defaultModelOrder.firstOrNull { isSelectableModel(it, userManagedProviders) }?.alias
            ?: namedModelNames.firstOrNull()
            ?: BackendLlmSupport.fallbackChatModel.alias
    }

    /** Models the user can access, in the default-model order; executions fall back along this list. */
    suspend fun fallbackModels(userId: String): List<String> {
        val providers = loadUserManagedProviders(userId)
        // Upstream IDs: inside an execution on a named model, the custom selector would resolve to that named model.
        return BackendLlmSupport.defaultModelOrder.filter { isSelectableModel(it) { providers } }
            .map { baseSettingsProvider.executionModelId(it.withConfiguredOpenAiCompatibleChatModel()) }
            .distinct() + listOfNotNull(namedModelNames.firstOrNull())
    }

    private suspend fun loadUserManagedProviders(userId: String): Set<LlmProvider> =
        userProviderKeyRepository.list(userId)
            .mapNotNullTo(linkedSetOf()) { key ->
                key.provider.takeIf { it in BackendLlmSupport.userManagedKeyProviders }
            }

    private suspend fun isSelectableModel(
        model: LLMModel,
        userManagedProviders: suspend () -> Set<LlmProvider>,
    ): Boolean = baseSettingsProvider.supportsChatModel(model) &&
        (baseSettingsProvider.hasServerChatAccess(model.provider) ||
            model.provider in BackendLlmSupport.userManagedKeyProviders && model.provider in userManagedProviders())

    private fun LLMModel.withConfiguredOpenAiCompatibleChatModel(): LLMModel =
        if (provider == LlmProvider.OPENAI && baseSettingsProvider.supportsChatModel(LLMModel.OpenAICompatibleCustom)) {
            LLMModel.OpenAICompatibleCustom
        } else {
            this
        }

    private fun normalizeInterfaceLanguage(interfaceLanguage: String): String =
        if (interfaceLanguage.trim().equals("en", ignoreCase = true)) "en" else "ru"

    private fun normalizeRequestTimeoutMillis(requestTimeoutMillis: Long): Long =
        requestTimeoutMillis.takeIf { it >= MIN_REQUEST_TIMEOUT_MILLIS }
            ?: baseSettingsProvider.requestTimeoutMillis.coerceAtLeast(MIN_REQUEST_TIMEOUT_MILLIS)

    private companion object {
        const val MIN_REQUEST_TIMEOUT_MILLIS = 1_000L
        const val DEFAULT_BACKEND_USE_FEW_SHOT_EXAMPLES = true
    }
}
