package ru.souz.backend.http

import ru.souz.backend.bootstrap.BackendBootstrapService
import ru.souz.backend.chat.service.ChatService
import ru.souz.backend.chat.service.MessageService
import ru.souz.backend.client.PublicClientService
import ru.souz.backend.config.BackendFeatureFlags
import ru.souz.backend.events.service.AgentEventService
import ru.souz.backend.execution.service.AgentExecutionService
import ru.souz.backend.keys.service.UserProviderKeyService
import ru.souz.backend.onboarding.BackendOnboardingService
import ru.souz.backend.options.service.OptionService
import ru.souz.backend.settings.service.UserSettingsService
import ru.souz.backend.telegram.TelegramBotBindingService
import ru.souz.skilloauth.impl.SkillOAuthGatewayImpl

internal data class BackendHttpDependencies(
    val bootstrapService: BackendBootstrapService,
    val selectedModel: () -> String,
    val onboardingService: BackendOnboardingService,
    val userSettingsService: UserSettingsService,
    val providerKeyService: UserProviderKeyService,
    val chatService: ChatService,
    val messageService: MessageService,
    val executionService: AgentExecutionService,
    val optionService: OptionService,
    val eventService: AgentEventService,
    val publicClientService: PublicClientService,
    val telegramBotBindingService: TelegramBotBindingService? = null,
    val skillOAuthGatewayImpl: SkillOAuthGatewayImpl? = null,
    val featureFlags: BackendFeatureFlags,
    val trustedProxyToken: () -> String?,
    val ensureTrustedUser: suspend (String) -> Unit,
)
