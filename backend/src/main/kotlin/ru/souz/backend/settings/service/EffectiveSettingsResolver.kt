package ru.souz.backend.settings.service

import java.time.Instant
import java.time.ZoneId
import java.util.Locale
import ru.souz.agent.spi.AgentToolCatalog
import ru.souz.backend.common.BackendLlmSupport
import ru.souz.backend.common.BackendToolCapabilityPolicy
import ru.souz.backend.config.BackendFeatureFlags
import ru.souz.backend.keys.repository.UserProviderKeyRepository
import ru.souz.backend.llm.hasCompleteCodexOAuthCredentials
import ru.souz.backend.settings.model.EffectiveUserSettings
import ru.souz.backend.settings.model.ToolPermission
import ru.souz.backend.settings.model.UserMcpServer
import ru.souz.backend.settings.model.UserSettings
import ru.souz.backend.settings.repository.UserSettingsRepository
import ru.souz.db.SettingsProvider
import ru.souz.llms.LLMModel
import ru.souz.llms.LlmBuildProfile
import ru.souz.llms.LlmProvider
import ru.souz.llms.LocalModelAvailability

data class UserSettingsOverrides(
    val defaultModel: LLMModel? = null,
    val contextSize: Int? = null,
    val temperature: Float? = null,
    val locale: Locale? = null,
    val timeZone: ZoneId? = null,
    val systemPrompt: String? = null,
    val enabledTools: Set<String>? = null,
    val showToolEvents: Boolean? = null,
    val streamingMessages: Boolean? = null,
    val interfaceLanguage: String? = null,
    val requestTimeoutMillis: Long? = null,
    val useFewShotExamples: Boolean? = null,
    val toolPermissions: Map<String, ToolPermission>? = null,
    val mcp: Map<String, UserMcpServer>? = null,
)

class EffectiveSettingsResolver(
    private val baseSettingsProvider: SettingsProvider,
    private val userSettingsRepository: UserSettingsRepository,
    private val userProviderKeyRepository: UserProviderKeyRepository,
    private val featureFlags: BackendFeatureFlags,
    toolCatalog: AgentToolCatalog,
    private val localModelAvailability: LocalModelAvailability,
) {
    private val advertisedToolNames: Set<String> =
        BackendToolCapabilityPolicy.advertisedToolNames(toolCatalog)

    suspend fun isSelectableDefaultModel(userId: String, model: LLMModel): Boolean =
        isSelectableModel(model) { loadUserManagedProviders(userId) }

    suspend fun resolve(
        userId: String,
        requestOverrides: UserSettingsOverrides? = null,
        userManagedProviders: Set<LlmProvider>? = null,
    ): EffectiveUserSettings {
        var loadedProviders = userManagedProviders
        val providers: suspend () -> Set<LlmProvider> = {
            loadedProviders ?: loadUserManagedProviders(userId).also { loadedProviders = it }
        }
        val persisted = userSettingsRepository.get(userId)
            ?: userSettingsRepository.save(defaultsFor(userId, providers))

        val locale = normalizeLocale(requestOverrides?.locale ?: persisted.locale ?: defaultLocale())
        val timeZone = requestOverrides?.timeZone ?: persisted.timeZone ?: ZoneId.systemDefault()
        val interfaceLanguage = normalizeInterfaceLanguage(
            requestOverrides?.interfaceLanguage
                ?: persisted.interfaceLanguage
                ?: defaultInterfaceLanguage()
        )
        val requestTimeoutMillis = normalizeRequestTimeoutMillis(
            requestOverrides?.requestTimeoutMillis
                ?: persisted.requestTimeoutMillis
                ?: baseSettingsProvider.requestTimeoutMillis
        )
        val defaultModel = normalizeModel(
            model = requestOverrides?.defaultModel ?: persisted.defaultModel,
            locale = locale,
            userManagedProviders = providers,
        )
        val enabledTools = normalizeEnabledTools(requestOverrides?.enabledTools ?: persisted.enabledTools)
        val showToolEventsPreference = requestOverrides?.showToolEvents ?: persisted.showToolEvents ?: true
        val streamingPreference = requestOverrides?.streamingMessages
            ?: persisted.streamingMessages
            ?: baseSettingsProvider.useStreaming
        val useFewShotExamples = requestOverrides?.useFewShotExamples
            ?: persisted.useFewShotExamples
            ?: DEFAULT_BACKEND_USE_FEW_SHOT_EXAMPLES

        return EffectiveUserSettings(
            userId = userId,
            defaultModel = defaultModel,
            contextSize = requestOverrides?.contextSize ?: persisted.contextSize ?: baseSettingsProvider.contextSize,
            temperature = requestOverrides?.temperature ?: persisted.temperature ?: baseSettingsProvider.temperature,
            locale = locale,
            timeZone = timeZone,
            systemPrompt = requestOverrides?.systemPrompt ?: persisted.systemPrompt,
            enabledTools = enabledTools,
            showToolEvents = featureFlags.toolEvents && showToolEventsPreference,
            streamingMessages = featureFlags.streamingMessages && streamingPreference,
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
        val locale = defaultLocale()
        val now = Instant.now()
        return UserSettings(
            userId = userId,
            defaultModel = defaultModelForNewSettings(locale, userManagedProviders),
            contextSize = baseSettingsProvider.contextSize,
            temperature = baseSettingsProvider.temperature,
            locale = locale,
            timeZone = ZoneId.systemDefault(),
            systemPrompt = null,
            enabledTools = normalizeEnabledTools(null),
            showToolEvents = true,
            streamingMessages = baseSettingsProvider.useStreaming,
            interfaceLanguage = defaultInterfaceLanguage(),
            requestTimeoutMillis = normalizeRequestTimeoutMillis(baseSettingsProvider.requestTimeoutMillis),
            useFewShotExamples = DEFAULT_BACKEND_USE_FEW_SHOT_EXAMPLES,
            toolPermissions = emptyMap(),
            mcp = emptyMap(),
            createdAt = now,
            updatedAt = now,
        )
    }

    private fun normalizeEnabledTools(enabledTools: Set<String>?): Set<String> =
        (enabledTools ?: advertisedToolNames).filterTo(linkedSetOf()) { it in advertisedToolNames }

    private suspend fun normalizeModel(
        model: LLMModel?,
        locale: Locale,
        userManagedProviders: suspend () -> Set<LlmProvider>,
    ): LLMModel {
        if (model?.provider == LlmProvider.GIGA) {
            return model
        }
        return model?.withConfiguredOpenAiCompatibleChatModel()
            ?.takeIf { isSelectableModel(it, userManagedProviders) }
            ?: fallbackModel(locale, userManagedProviders)
    }

    private suspend fun defaultModelForNewSettings(
        locale: Locale,
        userManagedProviders: suspend () -> Set<LlmProvider>,
    ): LLMModel {
        val candidate = baseSettingsProvider.gigaModel.withConfiguredOpenAiCompatibleChatModel()
        return candidate.takeIf { isSelectableModel(it, userManagedProviders) }
            ?: fallbackModel(locale, userManagedProviders)
    }

    private suspend fun fallbackModel(
        locale: Locale,
        userManagedProviders: suspend () -> Set<LlmProvider>,
    ): LLMModel {
        if (isSelectableModel(LLMModel.OpenAICompatibleCustom, userManagedProviders)) {
            return LLMModel.OpenAICompatibleCustom
        }
        val defaults = LlmBuildProfile.defaultsForLanguage(locale.languageOrRegion())
        val localDefault = localModelAvailability.defaultGigaModel()
        return LlmBuildProfile.providerPrioritiesForLanguage(locale.languageOrRegion())
            .firstNotNullOfOrNull { provider ->
                when (provider) {
                    LlmProvider.LOCAL -> localDefault
                    else -> defaults[provider]?.takeIf { isSelectableModel(it, userManagedProviders) }
                }
            }
            ?: localDefault
            ?: defaults.values.first { it in BackendLlmSupport.chatModels }
    }

    private suspend fun loadUserManagedProviders(userId: String): Set<LlmProvider> =
        userProviderKeyRepository.list(userId)
            .mapNotNullTo(linkedSetOf()) { key ->
                key.provider.takeIf { it in BackendLlmSupport.userManagedKeyProviders }
            }

    private suspend fun isSelectableModel(
        model: LLMModel,
        userManagedProviders: suspend () -> Set<LlmProvider>,
    ): Boolean =
        when {
            model !in BackendLlmSupport.chatModels -> false
            model == LLMModel.OpenAICompatibleCustom && !hasConfiguredOpenAiCompatibleChatModel() -> false
            model.provider == LlmProvider.LOCAL -> model in localModelAvailability.availableGigaModels()
            model.provider == LlmProvider.CODEX -> baseSettingsProvider.hasCompleteCodexOAuthCredentials()
            else -> baseSettingsProvider.hasKey(model.provider) || model.provider in userManagedProviders()
        }

    private fun hasConfiguredOpenAiCompatibleChatModel(): Boolean =
        !baseSettingsProvider.openaiModel.isNullOrBlank()

    private fun LLMModel.withConfiguredOpenAiCompatibleChatModel(): LLMModel =
        if (provider == LlmProvider.OPENAI && hasConfiguredOpenAiCompatibleChatModel()) {
            LLMModel.OpenAICompatibleCustom
        } else {
            this
        }

    private fun defaultLocale(): Locale =
        if (baseSettingsProvider.regionProfile.equals(REGION_EN, ignoreCase = true)) {
            Locale.forLanguageTag("en-US")
        } else {
            Locale.forLanguageTag("ru-RU")
        }

    private fun normalizeLocale(locale: Locale): Locale =
        locale.takeIf { it.language.isNotBlank() } ?: defaultLocale()

    private fun normalizeInterfaceLanguage(interfaceLanguage: String): String =
        when (interfaceLanguage.trim().lowercase()) {
            REGION_EN -> REGION_EN
            else -> REGION_RU
        }

    private fun defaultInterfaceLanguage(): String =
        if (baseSettingsProvider.regionProfile.equals(REGION_EN, ignoreCase = true)) {
            REGION_EN
        } else {
            REGION_RU
        }

    private fun normalizeRequestTimeoutMillis(requestTimeoutMillis: Long): Long =
        requestTimeoutMillis.takeIf { it >= MIN_REQUEST_TIMEOUT_MILLIS }
            ?: baseSettingsProvider.requestTimeoutMillis.coerceAtLeast(MIN_REQUEST_TIMEOUT_MILLIS)

    private fun Locale.languageOrRegion(): String =
        language.takeIf { it.isNotBlank() } ?: baseSettingsProvider.regionProfile

    private companion object {
        const val REGION_EN = "en"
        const val REGION_RU = "ru"
        const val MIN_REQUEST_TIMEOUT_MILLIS = 1_000L
        const val DEFAULT_BACKEND_USE_FEW_SHOT_EXAMPLES = true
    }
}
