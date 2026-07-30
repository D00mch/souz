package ru.souz.backend.http

import ru.souz.backend.bootstrap.BackendBootstrapService
import ru.souz.backend.chat.service.ChatService
import ru.souz.backend.chat.service.MessageService
import ru.souz.backend.config.BackendFeatureFlags
import ru.souz.backend.events.service.AgentEventService
import ru.souz.backend.execution.service.AgentExecutionService
import ru.souz.backend.keys.service.UserProviderKeyService
import ru.souz.backend.onboarding.BackendOnboardingService
import ru.souz.backend.options.service.OptionService
import ru.souz.backend.settings.service.UserSettingsService
import ru.souz.backend.telegram.TelegramBotBindingService

internal data class BackendHttpDependencies(
    val bootstrapService: BackendBootstrapService,
    val selectedModel: () -> String,
    val onboardingService: BackendOnboardingService? = null,
    val userSettingsService: UserSettingsService? = null,
    val providerKeyService: UserProviderKeyService? = null,
    val chatService: ChatService? = null,
    val messageService: MessageService? = null,
    val executionService: AgentExecutionService? = null,
    val optionService: OptionService? = null,
    val eventService: AgentEventService? = null,
    val telegramBotBindingService: TelegramBotBindingService? = null,
    val featureFlags: BackendFeatureFlags = BackendFeatureFlags(),
    val trustedProxyToken: () -> String? = { null },
    val ensureTrustedUser: suspend (String) -> Unit = { _ -> },
)
