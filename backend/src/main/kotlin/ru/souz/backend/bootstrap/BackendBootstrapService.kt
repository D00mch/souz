package ru.souz.backend.bootstrap

import ru.souz.agent.spi.AgentToolCatalog
import ru.souz.backend.common.backendSafeToolNames
import ru.souz.backend.config.BackendFeatureFlags
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
) {
    suspend fun response(identity: RequestIdentity): BootstrapResponse {
        val buildProfile = LlmBuildProfile(settingsProvider, localModelAvailability)
        val effectiveSettings = effectiveSettingsResolver.resolve(identity.userId)
        return BootstrapResponse(
            user = BootstrapUser(id = identity.userId),
            features = featureFlags,
            storage = BootstrapStorage(mode = storageMode.value),
            capabilities = BootstrapCapabilities(
                models = buildProfile.availableModels.map(::modelCapability),
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
                showToolEvents = effectiveSettings.showToolEvents,
                streamingMessages = effectiveSettings.streamingMessages,
            ),
        )
    }

    private fun modelCapability(model: LLMModel): BootstrapModelCapability =
        BootstrapModelCapability(
            provider = model.provider.name.lowercase(),
            model = model.alias,
            serverManagedKey = hasServerManagedAccess(model),
            userManagedKey = false,
        )

    private fun hasServerManagedAccess(model: LLMModel): Boolean =
        when (model.provider) {
            LlmProvider.LOCAL -> model in localModelAvailability.availableGigaModels()
            else -> settingsProvider.hasKey(model.provider)
        }
}
