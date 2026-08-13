package ru.souz.backend.settings.service

import java.time.Instant
import java.time.ZoneId
import java.util.Locale
import ru.souz.agent.spi.AgentToolCatalog
import ru.souz.backend.common.backendSafeToolNames
import ru.souz.backend.config.BackendFeatureFlags
import ru.souz.backend.config.BackendSettingsConfig
import ru.souz.backend.keys.repository.UserProviderKeyRepository
import ru.souz.backend.settings.model.EffectiveUserSettings
import ru.souz.backend.settings.model.ToolPermission
import ru.souz.backend.settings.model.UserMcpServer
import ru.souz.backend.settings.model.UserSettings
import ru.souz.backend.settings.repository.UserSettingsRepository
import ru.souz.llms.LLMModel
import ru.souz.llms.LlmBuildProfile
import ru.souz.llms.LlmProvider
import ru.souz.llms.codex.CodexOAuthCredentialStore

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
    private val baseSettings: BackendSettingsConfig,
    private val userSettingsRepository: UserSettingsRepository,
    private val userProviderKeyRepository: UserProviderKeyRepository,
    private val featureFlags: BackendFeatureFlags,
    private val toolCatalog: AgentToolCatalog,
    private val codexOAuthCredentialStore: CodexOAuthCredentialStore? = null,
) {
    suspend fun isSelectableDefaultModel(
        userId: String,
        model: LLMModel,
        userManagedProviders: Set<LlmProvider>? = null,
    ): Boolean =
        isSelectableModel(userId, model, userManagedProviders)

    suspend fun resolve(
        userId: String,
        requestOverrides: UserSettingsOverrides? = null,
        userManagedProviders: Set<LlmProvider>? = null,
    ): EffectiveUserSettings {
        val persisted = userSettingsRepository.get(userId)
            ?: userSettingsRepository.save(defaultsFor(userId, userManagedProviders))

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
                ?: baseSettings.requestTimeoutMillis
        )
        val defaultModel = normalizeModel(
            userId = userId,
            model = requestOverrides?.defaultModel ?: persisted.defaultModel,
            locale = locale,
            userManagedProviders = userManagedProviders,
        )
        val enabledTools = normalizeEnabledTools(requestOverrides?.enabledTools ?: persisted.enabledTools)
        val showToolEventsPreference = requestOverrides?.showToolEvents ?: persisted.showToolEvents ?: true
        val streamingPreference = requestOverrides?.streamingMessages
            ?: persisted.streamingMessages
            ?: baseSettings.useStreaming
        val useFewShotExamples = requestOverrides?.useFewShotExamples
            ?: persisted.useFewShotExamples
            ?: DEFAULT_BACKEND_USE_FEW_SHOT_EXAMPLES

        return EffectiveUserSettings(
            userId = userId,
            defaultModel = defaultModel,
            contextSize = requestOverrides?.contextSize ?: persisted.contextSize ?: baseSettings.contextSize,
            temperature = requestOverrides?.temperature ?: persisted.temperature ?: baseSettings.temperature,
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
        userManagedProviders: Set<LlmProvider>?,
    ): UserSettings {
        val locale = defaultLocale()
        val now = Instant.now()
        return UserSettings(
            userId = userId,
            defaultModel = defaultModelForNewSettings(userId, locale, userManagedProviders),
            contextSize = baseSettings.contextSize,
            temperature = baseSettings.temperature,
            locale = locale,
            timeZone = ZoneId.systemDefault(),
            systemPrompt = null,
            enabledTools = normalizeEnabledTools(null),
            showToolEvents = true,
            streamingMessages = baseSettings.useStreaming,
            interfaceLanguage = defaultInterfaceLanguage(),
            requestTimeoutMillis = normalizeRequestTimeoutMillis(baseSettings.requestTimeoutMillis),
            useFewShotExamples = DEFAULT_BACKEND_USE_FEW_SHOT_EXAMPLES,
            toolPermissions = emptyMap(),
            mcp = emptyMap(),
            createdAt = now,
            updatedAt = now,
        )
    }

    private fun normalizeEnabledTools(enabledTools: Set<String>?): Set<String> {
        val supportedTools = backendSafeToolNames(toolCatalog).toSet()
        val requested = enabledTools ?: supportedTools
        return requested.filterTo(linkedSetOf()) { it in supportedTools }
    }

    private suspend fun normalizeModel(
        userId: String,
        model: LLMModel?,
        locale: Locale,
        userManagedProviders: Set<LlmProvider>?,
    ): LLMModel {
        val fallback = fallbackModel(userId, locale, userManagedProviders)
        val candidate = (model ?: fallback).withConfiguredOpenAiCompatibleChatModel()
        return candidate.takeIf { isSelectableModel(userId, it, userManagedProviders) } ?: fallback
    }

    private suspend fun defaultModelForNewSettings(
        userId: String,
        locale: Locale,
        userManagedProviders: Set<LlmProvider>?,
    ): LLMModel {
        val candidate = baseSettings.gigaModel.withConfiguredOpenAiCompatibleChatModel()
        return candidate.takeIf { isSelectableModel(userId, it, userManagedProviders) }
            ?: fallbackModel(userId, locale, userManagedProviders)
    }

    private suspend fun fallbackModel(
        userId: String,
        locale: Locale,
        userManagedProviders: Set<LlmProvider>?,
    ): LLMModel {
        if (
            hasConfiguredOpenAiCompatibleChatModel() &&
            hasConfiguredAccess(userId, LlmProvider.OPENAI, userManagedProviders)
        ) {
            return LLMModel.OpenAICompatibleCustom
        }
        val defaults = LlmBuildProfile.defaultsForLanguage(locale.languageOrRegion())
        return LlmBuildProfile.providerPrioritiesForLanguage(locale.languageOrRegion())
            .filterNot { it == LlmProvider.LOCAL }
            .firstNotNullOfOrNull { provider ->
                defaults[provider]?.takeIf { hasConfiguredAccess(userId, provider, userManagedProviders) }
            }
            ?: defaults.values.first()
    }

    private suspend fun hasConfiguredAccess(
        userId: String,
        provider: LlmProvider,
        userManagedProviders: Set<LlmProvider>?,
    ): Boolean =
        when (provider) {
            LlmProvider.LOCAL -> false
            LlmProvider.CODEX -> hasCodexOAuthCredentials()
            else -> baseSettings.hasKey(provider) || provider in (userManagedProviders ?: loadUserManagedProviders(userId))
        }

    private suspend fun loadUserManagedProviders(userId: String): Set<LlmProvider> =
        userProviderKeyRepository.list(userId)
            .mapTo(linkedSetOf()) { it.provider }

    private suspend fun isSelectableModel(
        userId: String,
        model: LLMModel,
        userManagedProviders: Set<LlmProvider>?,
    ): Boolean =
        if (model == LLMModel.OpenAICompatibleCustom) {
            hasConfiguredOpenAiCompatibleChatModel() &&
                hasConfiguredAccess(userId, LlmProvider.OPENAI, userManagedProviders)
        } else {
            when (model.provider) {
                LlmProvider.LOCAL -> false
                else -> hasConfiguredAccess(userId, model.provider, userManagedProviders)
            }
        }

    private fun hasConfiguredOpenAiCompatibleChatModel(): Boolean =
        !baseSettings.openaiModel.isNullOrBlank()

    private suspend fun hasCodexOAuthCredentials(): Boolean =
        codexOAuthCredentialStore?.load()?.isCompleteRotatingSet == true

    private fun LLMModel.withConfiguredOpenAiCompatibleChatModel(): LLMModel =
        if (provider == LlmProvider.OPENAI && hasConfiguredOpenAiCompatibleChatModel()) {
            LLMModel.OpenAICompatibleCustom
        } else {
            this
        }

    private fun defaultLocale(): Locale =
        if (baseSettings.regionProfile.equals(REGION_EN, ignoreCase = true)) {
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
        if (baseSettings.regionProfile.equals(REGION_EN, ignoreCase = true)) {
            REGION_EN
        } else {
            REGION_RU
        }

    private fun normalizeRequestTimeoutMillis(requestTimeoutMillis: Long): Long =
        requestTimeoutMillis.takeIf { it >= MIN_REQUEST_TIMEOUT_MILLIS }
            ?: baseSettings.requestTimeoutMillis.coerceAtLeast(MIN_REQUEST_TIMEOUT_MILLIS)

    private fun Locale.languageOrRegion(): String =
        language.takeIf { it.isNotBlank() } ?: baseSettings.regionProfile

    private companion object {
        const val REGION_EN = "en"
        const val REGION_RU = "ru"
        const val MIN_REQUEST_TIMEOUT_MILLIS = 1_000L
        const val DEFAULT_BACKEND_USE_FEW_SHOT_EXAMPLES = true
    }
}
