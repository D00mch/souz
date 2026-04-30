package ru.souz.backend.bootstrap

import java.time.ZoneId
import ru.souz.agent.spi.AgentToolCatalog
import ru.souz.backend.config.BackendFeatureFlags
import ru.souz.backend.security.RequestIdentity
import ru.souz.backend.storage.StorageMode
import ru.souz.db.SettingsProvider
import ru.souz.db.hasKey
import ru.souz.llms.LLMModel
import ru.souz.llms.LlmBuildProfile
import ru.souz.llms.LlmProvider
import ru.souz.llms.LocalModelAvailability
import ru.souz.tool.ToolCategory

class BackendBootstrapService(
    private val settingsProvider: SettingsProvider,
    private val toolCatalog: AgentToolCatalog,
    private val featureFlags: BackendFeatureFlags,
    private val storageMode: StorageMode,
    private val localModelAvailability: LocalModelAvailability,
) {
    fun response(identity: RequestIdentity): BootstrapResponse {
        val buildProfile = LlmBuildProfile(settingsProvider, localModelAvailability)
        return BootstrapResponse(
            user = BootstrapUser(id = identity.userId),
            features = featureFlags,
            storage = BootstrapStorage(mode = storageMode.value),
            capabilities = BootstrapCapabilities(
                models = buildProfile.availableModels.map(::modelCapability),
                tools = backendSafeToolNames().map { toolName ->
                    BootstrapToolCapability(name = toolName, enabled = true)
                },
            ),
            settings = BootstrapSettings(
                defaultModel = settingsProvider.gigaModel.alias,
                contextSize = settingsProvider.contextSize,
                temperature = settingsProvider.temperature,
                locale = localeForRegion(settingsProvider.regionProfile),
                timeZone = ZoneId.systemDefault().id,
                showToolEvents = featureFlags.toolEvents,
                streamingMessages = featureFlags.streamingMessages && settingsProvider.useStreaming,
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

    private fun backendSafeToolNames(): List<String> =
        toolCatalog.toolsByCategory
            .filterKeys { it in BACKEND_SAFE_TOOL_CATEGORIES }
            .values
            .asSequence()
            .flatMap { tools -> tools.values.asSequence() }
            .map { tool -> tool.fn.name }
            .distinct()
            .sorted()
            .toList()

    private fun localeForRegion(regionProfile: String): String =
        if (regionProfile.equals(REGION_EN, ignoreCase = true)) {
            "en-US"
        } else {
            "ru-RU"
        }

    private companion object {
        val BACKEND_SAFE_TOOL_CATEGORIES: Set<ToolCategory> = setOf(
            ToolCategory.FILES,
            ToolCategory.WEB_SEARCH,
            ToolCategory.CONFIG,
            ToolCategory.DATA_ANALYTICS,
            ToolCategory.CALCULATOR,
        )
        const val REGION_EN = "en"
    }
}
