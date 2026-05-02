package ru.souz.backend.http

import ru.souz.backend.agent.service.BackendAgentService
import ru.souz.backend.bootstrap.BackendBootstrapService
import ru.souz.backend.chat.service.ChatService
import ru.souz.backend.chat.service.MessageService
import ru.souz.backend.config.BackendFeatureFlags
import ru.souz.backend.events.service.AgentEventService
import ru.souz.backend.execution.service.AgentExecutionService
import ru.souz.backend.keys.service.UserProviderKeyService
import ru.souz.backend.options.service.OptionService
import ru.souz.backend.settings.service.UserSettingsService

internal data class BackendHttpDependencies(
    val agentService: BackendAgentService,
    val bootstrapService: BackendBootstrapService,
    val userSettingsService: UserSettingsService?,
    val providerKeyService: UserProviderKeyService?,
    val chatService: ChatService?,
    val messageService: MessageService?,
    val executionService: AgentExecutionService?,
    val optionService: OptionService?,
    val eventService: AgentEventService?,
    val featureFlags: BackendFeatureFlags,
    val selectedModel: () -> String,
    val internalAgentToken: () -> String?,
    val trustedProxyToken: () -> String?,
    val ensureTrustedUser: suspend (String) -> Unit,
)
