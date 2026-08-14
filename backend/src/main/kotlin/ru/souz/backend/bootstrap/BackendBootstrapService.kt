package ru.souz.backend.bootstrap

import ru.souz.agent.spi.AgentToolCatalog
import ru.souz.backend.common.BackendAvailableToolNames
import ru.souz.backend.common.BackendLlmSupport
import ru.souz.backend.config.BackendFeatureFlags
import ru.souz.backend.keys.repository.UserProviderKeyRepository
import ru.souz.backend.llm.hasCompleteCodexOAuthCredentials
import ru.souz.backend.security.RequestIdentity
import ru.souz.backend.settings.service.EffectiveSettingsResolver
import ru.souz.db.SettingsProvider
import ru.souz.llms.LLMModel
import ru.souz.llms.LlmBuildProfile
import ru.souz.llms.LlmProvider
import ru.souz.llms.LocalModelAvailability

class BackendBootstrapService(
    private val settingsProvider: SettingsProvider,
    private val effectiveSettingsResolver: EffectiveSettingsResolver,
    private val availableToolNames: BackendAvailableToolNames,
    private val featureFlags: BackendFeatureFlags,
    private val localModelAvailability: LocalModelAvailability,
    private val userProviderKeyRepository: UserProviderKeyRepository,
) {
    constructor(
        settingsProvider: SettingsProvider,
        effectiveSettingsResolver: EffectiveSettingsResolver,
        toolCatalog: AgentToolCatalog,
        featureFlags: BackendFeatureFlags,
        localModelAvailability: LocalModelAvailability,
        userProviderKeyRepository: UserProviderKeyRepository,
    ) : this(
        settingsProvider = settingsProvider,
        effectiveSettingsResolver = effectiveSettingsResolver,
        availableToolNames = BackendAvailableToolNames.fromProcessCatalog(toolCatalog),
        featureFlags = featureFlags,
        localModelAvailability = localModelAvailability,
        userProviderKeyRepository = userProviderKeyRepository,
    )

    suspend fun response(identity: RequestIdentity): BootstrapResponse {
        val buildProfile = LlmBuildProfile(settingsProvider, localModelAvailability)
        val userManagedProviders = userProviderKeyRepository.list(identity.userId)
            .mapNotNullTo(linkedSetOf()) { key ->
                key.provider.takeIf { it in BackendLlmSupport.userManagedKeyProviders }
            }
        val effectiveSettings = effectiveSettingsResolver.resolve(
            userId = identity.userId,
            userManagedProviders = userManagedProviders,
        )
        val capabilityProviders = buildSet {
            addAll(buildProfile.availableProviders.filter { it in BackendLlmSupport.chatProviders })
            addAll(userManagedProviders)
            addAll(LlmProvider.entries.filter { provider ->
                provider in BackendLlmSupport.chatProviders &&
                    provider != LlmProvider.LOCAL &&
                    settingsProvider.hasKey(provider)
            })
            if (!settingsProvider.hasCompleteCodexOAuthCredentials()) {
                remove(LlmProvider.CODEX)
            }
        }
        return BootstrapResponse(
            user = BootstrapUser(id = identity.userId),
            features = featureFlags,
            capabilities = BootstrapCapabilities(
                models = BackendLlmSupport.chatModels
                    .filter { model ->
                        if (model == LLMModel.OpenAICompatibleCustom) {
                            return@filter hasConfiguredOpenAiCompatibleChatModel() &&
                                LlmProvider.OPENAI in capabilityProviders
                        }
                        when (model.provider) {
                            LlmProvider.LOCAL -> model in localModelAvailability.availableGigaModels()
                            else -> model.provider in capabilityProviders
                        }
                    }
                    .map { modelCapability(it, userManagedProviders) },
                tools = availableToolNames.values.sorted().map { toolName ->
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
    ): BootstrapModelCapability =
        BootstrapModelCapability(
            provider = model.provider.name.lowercase(),
            model = model.alias,
            serverManagedKey = hasServerManagedAccess(model),
            userManagedKey = hasUserManagedAccess(model, userManagedProviders),
        )

    private fun hasServerManagedAccess(model: LLMModel): Boolean =
        if (model == LLMModel.OpenAICompatibleCustom) {
            hasConfiguredOpenAiCompatibleChatModel() && settingsProvider.hasKey(LlmProvider.OPENAI)
        } else {
            when (model.provider) {
                LlmProvider.LOCAL -> model in localModelAvailability.availableGigaModels()
                LlmProvider.CODEX -> settingsProvider.hasCompleteCodexOAuthCredentials()
                else -> settingsProvider.hasKey(model.provider)
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
        !settingsProvider.openaiModel.isNullOrBlank()
}
