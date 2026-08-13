package ru.souz.backend.bootstrap

import ru.souz.agent.spi.AgentToolCatalog
import ru.souz.backend.common.backendSafeToolNames
import ru.souz.backend.config.BackendFeatureFlags
import ru.souz.backend.config.BackendSettingsConfig
import ru.souz.backend.keys.repository.UserProviderKeyRepository
import ru.souz.backend.security.RequestIdentity
import ru.souz.backend.settings.service.EffectiveSettingsResolver
import ru.souz.llms.LLMModel
import ru.souz.llms.LlmBuildProfile
import ru.souz.llms.LlmProvider
import ru.souz.llms.codex.CodexOAuthCredentialStore

class BackendBootstrapService(
    private val settingsConfig: BackendSettingsConfig,
    private val effectiveSettingsResolver: EffectiveSettingsResolver,
    private val toolCatalog: AgentToolCatalog,
    private val featureFlags: BackendFeatureFlags,
    private val userProviderKeyRepository: UserProviderKeyRepository,
    private val codexOAuthCredentialStore: CodexOAuthCredentialStore? = null,
) {
    suspend fun response(identity: RequestIdentity): BootstrapResponse {
        val buildProfile = LlmBuildProfile(settingsConfig)
        val hasCodexOAuthCredentials = hasCodexOAuthCredentials()
        val userManagedProviders = userProviderKeyRepository.list(identity.userId)
            .mapNotNullTo(linkedSetOf()) { key -> key.provider.takeUnless { it == LlmProvider.CODEX } }
        val effectiveSettings = effectiveSettingsResolver.resolve(
            userId = identity.userId,
            userManagedProviders = userManagedProviders,
        )
        val capabilityProviders = buildSet {
            addAll(buildProfile.availableProviders)
            addAll(userManagedProviders)
            addAll(LlmProvider.entries.filter { provider ->
                settingsConfig.hasKey(provider)
            })
            if (!hasCodexOAuthCredentials) {
                remove(LlmProvider.CODEX)
            }
        }
        return BootstrapResponse(
            user = BootstrapUser(id = identity.userId),
            features = featureFlags,
            capabilities = BootstrapCapabilities(
                models = LLMModel.entries
                    .filter { model ->
                        if (model == LLMModel.OpenAICompatibleCustom) {
                            return@filter hasConfiguredOpenAiCompatibleChatModel() &&
                                LlmProvider.OPENAI in capabilityProviders
                        }
                        when (model.provider) {
                            LlmProvider.LOCAL -> false
                            else -> model.provider in capabilityProviders
                        }
                    }
                    .map { modelCapability(it, userManagedProviders, hasCodexOAuthCredentials) },
                tools = backendSafeToolNames(toolCatalog).map { toolName ->
                    BootstrapToolCapability(name = toolName, enabled = true)
                },
            ),
            settings = BootstrapSettings(
                defaultModel = effectiveSettings.defaultModel.alias,
                contextSize = effectiveSettings.contextSize,
                temperature = effectiveSettings.temperature,
                locale = effectiveSettings.locale.toLanguageTag(),
                timeZone = effectiveSettings.timeZone.id,
                systemPrompt = effectiveSettings.systemPrompt,
                enabledTools = effectiveSettings.enabledTools.toList(),
                showToolEvents = effectiveSettings.showToolEvents,
                streamingMessages = effectiveSettings.streamingMessages,
                interfaceLanguage = effectiveSettings.interfaceLanguage,
                requestTimeoutMillis = effectiveSettings.requestTimeoutMillis,
                useFewShotExamples = effectiveSettings.useFewShotExamples,
            ),
        )
    }

    private fun modelCapability(
        model: LLMModel,
        userManagedProviders: Set<LlmProvider>,
        hasCodexOAuthCredentials: Boolean,
    ): BootstrapModelCapability =
        BootstrapModelCapability(
            provider = model.provider.name.lowercase(),
            model = model.alias,
            serverManagedKey = hasServerManagedAccess(model, hasCodexOAuthCredentials),
            userManagedKey = hasUserManagedAccess(model, userManagedProviders),
        )

    private fun hasServerManagedAccess(
        model: LLMModel,
        hasCodexOAuthCredentials: Boolean,
    ): Boolean =
        if (model == LLMModel.OpenAICompatibleCustom) {
            hasConfiguredOpenAiCompatibleChatModel() && settingsConfig.hasKey(LlmProvider.OPENAI)
        } else {
            when (model.provider) {
                LlmProvider.LOCAL -> false
                LlmProvider.CODEX -> hasCodexOAuthCredentials
                else -> settingsConfig.hasKey(model.provider)
            }
        }

    private fun hasUserManagedAccess(
        model: LLMModel,
        userManagedProviders: Set<LlmProvider>,
    ): Boolean =
        if (model == LLMModel.OpenAICompatibleCustom) {
            hasConfiguredOpenAiCompatibleChatModel() && LlmProvider.OPENAI in userManagedProviders
        } else {
            hasUserManagedAccess(model.provider, userManagedProviders)
        }

    private fun hasUserManagedAccess(
        provider: LlmProvider,
        userManagedProviders: Set<LlmProvider>,
    ): Boolean =
        provider != LlmProvider.LOCAL && provider in userManagedProviders

    private fun hasConfiguredOpenAiCompatibleChatModel(): Boolean =
        !settingsConfig.openaiModel.isNullOrBlank()

    private suspend fun hasCodexOAuthCredentials(): Boolean =
        codexOAuthCredentialStore?.load()?.isCompleteRotatingSet == true
}
