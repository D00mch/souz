package ru.souz.backend.bootstrap

import ru.souz.agent.spi.AgentToolCatalog
import ru.souz.backend.common.backendSafeToolNames
import ru.souz.backend.config.BackendFeatureFlags
import ru.souz.backend.keys.repository.UserProviderKeyRepository
import ru.souz.backend.security.RequestIdentity
import ru.souz.backend.settings.service.EffectiveSettingsResolver
import ru.souz.backend.storage.StorageMode
import ru.souz.db.SettingsProvider
import ru.souz.db.hasKey
import ru.souz.llms.LLMModel
import ru.souz.llms.LlmBuildProfile
import ru.souz.llms.LlmProvider
import ru.souz.llms.LocalModelAvailability

class BackendBootstrapService(
    private val settingsProvider: SettingsProvider,
    private val effectiveSettingsResolver: EffectiveSettingsResolver,
    private val toolCatalog: AgentToolCatalog,
    private val featureFlags: BackendFeatureFlags,
    private val storageMode: StorageMode,
    private val localModelAvailability: LocalModelAvailability,
    private val userProviderKeyRepository: UserProviderKeyRepository,
) {
    suspend fun response(identity: RequestIdentity): BootstrapResponse {
        val buildProfile = LlmBuildProfile(settingsProvider, localModelAvailability)
        val userManagedProviders = userProviderKeyRepository.list(identity.userId).map { it.provider }.toSet()
        val effectiveSettings = effectiveSettingsResolver.resolve(
            userId = identity.userId,
            userManagedProviders = userManagedProviders,
        )
        val capabilityProviders = buildSet {
            addAll(buildProfile.availableProviders)
            addAll(userManagedProviders)
            addAll(LlmProvider.entries.filter { provider ->
                provider != LlmProvider.LOCAL && settingsProvider.hasKey(provider)
            })
        }
        return BootstrapResponse(
            user = BootstrapUser(id = identity.userId),
            features = featureFlags,
            storage = BootstrapStorage(mode = storageMode.value),
            capabilities = BootstrapCapabilities(
                models = LLMModel.entries
                    .filter { model ->
                        when (model.provider) {
                            LlmProvider.LOCAL -> model in localModelAvailability.availableGigaModels()
                            else -> model.provider in capabilityProviders
                        }
                    }
                    .map { modelCapability(it, userManagedProviders) },
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
            userManagedKey = hasUserManagedAccess(model.provider, userManagedProviders),
        )

    private fun hasServerManagedAccess(model: LLMModel): Boolean =
        when (model.provider) {
            LlmProvider.LOCAL -> model in localModelAvailability.availableGigaModels()
            else -> settingsProvider.hasKey(model.provider)
        }

    private fun hasUserManagedAccess(
        provider: LlmProvider,
        userManagedProviders: Set<LlmProvider>,
    ): Boolean =
        provider != LlmProvider.LOCAL && provider in userManagedProviders
}
