package ru.souz.backend.settings.service

import java.time.Instant
import java.time.ZoneId
import java.util.Locale
import ru.souz.agent.spi.AgentToolCatalog
import ru.souz.backend.common.backendSafeToolNames
import ru.souz.backend.config.BackendFeatureFlags
import ru.souz.backend.keys.repository.UserProviderKeyRepository
import ru.souz.backend.settings.model.EffectiveUserSettings
import ru.souz.backend.settings.model.ToolPermission
import ru.souz.backend.settings.model.UserMcpServer
import ru.souz.backend.settings.model.UserSettings
import ru.souz.backend.settings.repository.UserSettingsRepository
import ru.souz.db.SettingsProvider
import ru.souz.db.hasKey
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
    val toolPermissions: Map<String, ToolPermission>? = null,
    val mcp: Map<String, UserMcpServer>? = null,
)

class EffectiveSettingsResolver(
    private val baseSettingsProvider: SettingsProvider,
    private val userSettingsRepository: UserSettingsRepository,
    private val userProviderKeyRepository: UserProviderKeyRepository,
    private val featureFlags: BackendFeatureFlags,
    private val toolCatalog: AgentToolCatalog,
    private val localModelAvailability: LocalModelAvailability,
) {
    suspend fun resolve(
        userId: String,
        requestOverrides: UserSettingsOverrides? = null,
        userManagedProviders: Set<LlmProvider>? = null,
    ): EffectiveUserSettings {
        val persisted = userSettingsRepository.get(userId) ?: userSettingsRepository.save(defaultsFor(userId))

        val locale = normalizeLocale(requestOverrides?.locale ?: persisted.locale ?: defaultLocale())
        val timeZone = requestOverrides?.timeZone ?: persisted.timeZone ?: ZoneId.systemDefault()
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
            ?: baseSettingsProvider.useStreaming

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
            toolPermissions = requestOverrides?.toolPermissions ?: persisted.toolPermissions,
            mcp = requestOverrides?.mcp ?: persisted.mcp,
        )
    }

    private fun defaultsFor(userId: String): UserSettings {
        val locale = defaultLocale()
        val now = Instant.now()
        return UserSettings(
            userId = userId,
            defaultModel = baseSettingsProvider.gigaModel,
            contextSize = baseSettingsProvider.contextSize,
            temperature = baseSettingsProvider.temperature,
            locale = locale,
            timeZone = ZoneId.systemDefault(),
            systemPrompt = null,
            enabledTools = normalizeEnabledTools(null),
            showToolEvents = true,
            streamingMessages = baseSettingsProvider.useStreaming,
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
        val candidate = model ?: fallback
        val supportedProviders = LlmBuildProfile.defaultsForLanguage(locale.languageOrRegion()).keys
        return when {
            candidate.provider == LlmProvider.LOCAL ->
                candidate.takeIf { it in localModelAvailability.availableGigaModels() } ?: fallback

            candidate.provider !in supportedProviders &&
                !hasConfiguredAccess(userId, candidate.provider, userManagedProviders) -> fallback
            !hasConfiguredAccess(userId, candidate.provider, userManagedProviders) -> fallback
            else -> candidate
        }
    }

    private suspend fun fallbackModel(
        userId: String,
        locale: Locale,
        userManagedProviders: Set<LlmProvider>?,
    ): LLMModel {
        val defaults = LlmBuildProfile.defaultsForLanguage(locale.languageOrRegion())
        val localDefault = localModelAvailability.defaultGigaModel()
        return LlmBuildProfile.providerPrioritiesForLanguage(locale.languageOrRegion())
            .firstNotNullOfOrNull { provider ->
                when (provider) {
                    LlmProvider.LOCAL -> localDefault
                    else -> defaults[provider]?.takeIf { hasConfiguredAccess(userId, provider, userManagedProviders) }
                }
            }
            ?: localDefault
            ?: defaults.values.first()
    }

    private suspend fun hasConfiguredAccess(
        userId: String,
        provider: LlmProvider,
        userManagedProviders: Set<LlmProvider>?,
    ): Boolean =
        when (provider) {
            LlmProvider.LOCAL -> localModelAvailability.isProviderAvailable()
            else -> baseSettingsProvider.hasKey(provider) || provider in (userManagedProviders ?: loadUserManagedProviders(userId))
        }

    private suspend fun loadUserManagedProviders(userId: String): Set<LlmProvider> =
        userProviderKeyRepository.list(userId)
            .mapTo(linkedSetOf()) { it.provider }

    private fun defaultLocale(): Locale =
        if (baseSettingsProvider.regionProfile.equals(REGION_EN, ignoreCase = true)) {
            Locale.forLanguageTag("en-US")
        } else {
            Locale.forLanguageTag("ru-RU")
        }

    private fun normalizeLocale(locale: Locale): Locale =
        locale.takeIf { it.language.isNotBlank() } ?: defaultLocale()

    private fun Locale.languageOrRegion(): String =
        language.takeIf { it.isNotBlank() } ?: baseSettingsProvider.regionProfile

    private companion object {
        const val REGION_EN = "en"
    }
}
