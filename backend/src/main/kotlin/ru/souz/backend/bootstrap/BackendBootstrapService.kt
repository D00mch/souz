package ru.souz.backend.bootstrap

import ru.souz.backend.common.BackendLlmSupport
import ru.souz.backend.common.supportsChatModel
import ru.souz.backend.config.BackendFeatureFlags
import ru.souz.backend.http.toDto
import ru.souz.backend.keys.repository.UserProviderKeyRepository
import ru.souz.backend.llm.hasCompleteCodexOAuthCredentials
import ru.souz.backend.security.RequestIdentity
import ru.souz.backend.settings.service.UserSettingsService
import ru.souz.db.SettingsProvider
import ru.souz.llms.LlmProvider

class BackendBootstrapService(
    private val settingsProvider: SettingsProvider,
    private val userSettingsService: UserSettingsService,
    private val featureFlags: BackendFeatureFlags,
    private val userProviderKeyRepository: UserProviderKeyRepository,
) {
    suspend fun response(identity: RequestIdentity): BootstrapResponse {
        val advertisedToolNames = userSettingsService.advertisedToolNames
        val userManagedProviders = userProviderKeyRepository.list(identity.userId)
            .mapNotNullTo(linkedSetOf()) { key ->
                key.provider.takeIf { it in BackendLlmSupport.userManagedKeyProviders }
            }
        val settings = userSettingsService.get(identity.userId, userManagedProviders, advertisedToolNames)
        val codexAvailable = settingsProvider.hasCompleteCodexOAuthCredentials()
        return BootstrapResponse(
            user = BootstrapUser(id = identity.userId),
            features = featureFlags,
            capabilities = BootstrapCapabilities(
                models = BackendLlmSupport.chatModels
                    .filter { model ->
                        settingsProvider.supportsChatModel(model) && (model.provider != LlmProvider.CODEX || codexAvailable)
                    }
                    .map { model ->
                        BootstrapModelCapability(
                            provider = model.provider.name.lowercase(),
                            model = model.alias,
                            serverManagedKey = model.provider == LlmProvider.CODEX || settingsProvider.hasKey(model.provider),
                            userManagedKey = model.provider in userManagedProviders,
                        )
                    } + userSettingsService.namedModelNames.map {
                        BootstrapModelCapability(provider = "openai", model = it, serverManagedKey = true, userManagedKey = false)
                    },
                tools = advertisedToolNames.map { toolName ->
                    BootstrapToolCapability(name = toolName, enabled = true)
                },
            ),
            settings = settings.toDto(),
        )
    }
}
