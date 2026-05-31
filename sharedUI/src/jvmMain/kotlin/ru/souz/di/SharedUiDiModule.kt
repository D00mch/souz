package ru.souz.di

import org.kodein.di.DI
import org.kodein.di.bind
import org.kodein.di.bindSingleton
import org.kodein.di.instance
import org.kodein.di.scoped
import org.kodein.di.singleton
import org.kodein.di.with
import ru.souz.agent.spi.AgentErrorMessages
import ru.souz.tool.SelectionApprovalSource
import ru.souz.ui.approval.TelegramChatSelectionApprovalSource
import ru.souz.ui.approval.TelegramContactSelectionApprovalSource
import ru.souz.ui.common.ComposeAgentErrorMessages
import ru.souz.ui.common.DesktopExternalLinkOpener
import ru.souz.ui.host.ChatCommandInputSource
import ru.souz.ui.host.DesktopChatCommandInputSource
import ru.souz.ui.host.DesktopLocalModelUiHost
import ru.souz.ui.host.DesktopPathOpener
import ru.souz.ui.host.DesktopPrivacyPolicyOpener
import ru.souz.ui.host.DesktopSettingsHostPreferences
import ru.souz.ui.host.DesktopSupportLogService
import ru.souz.ui.host.DesktopTelegramSettingsHost
import ru.souz.ui.host.ExternalLinkOpener
import ru.souz.ui.host.LocalModelUiHost
import ru.souz.ui.host.PathOpener
import ru.souz.ui.host.PrivacyPolicyOpener
import ru.souz.ui.host.SettingsHostPreferences
import ru.souz.ui.host.SupportLogService
import ru.souz.ui.host.TelegramControlBot
import ru.souz.ui.host.TelegramSettingsHost
import ru.souz.ui.host.TelegramUiService
import ru.souz.ui.main.usecases.AttachmentMetadataProvider
import ru.souz.ui.main.usecases.DesktopAttachmentMetadataProvider
import ru.souz.ui.main.usecases.DesktopDroppedFilePathExtractor
import ru.souz.ui.main.usecases.DesktopPathPicker
import ru.souz.ui.main.usecases.DroppedFilePathExtractor
import ru.souz.ui.main.usecases.PathPicker
import ru.souz.ui.main.mainViewModelDiScope
import ru.souz.ui.main.usecases.VoiceInputController
import ru.souz.ui.main.usecases.VoiceInputUseCase

fun sharedUiDesktopDiModule(): DI.Module = DI.Module("sharedUiDesktop") {
    import(sharedUiCommonJvmDiModule())

    bindSingleton<AgentErrorMessages> { ComposeAgentErrorMessages() }
    bindSingleton<ExternalLinkOpener>(overrides = true) { DesktopExternalLinkOpener() }
    bindSingleton<PathPicker>(overrides = true) { DesktopPathPicker() }
    bindSingleton<DroppedFilePathExtractor>(overrides = true) { DesktopDroppedFilePathExtractor() }
    bindSingleton<AttachmentMetadataProvider>(overrides = true) { DesktopAttachmentMetadataProvider() }
    bindSingleton<PathOpener>(overrides = true) { DesktopPathOpener() }
    bindSingleton<LocalModelUiHost>(overrides = true) {
        DesktopLocalModelUiHost(
            modelStore = instance(),
            localLlamaRuntime = instance(),
            desktopIndexRepository = instance(),
        )
    }
    bindSingleton<ChatCommandInputSource>(overrides = true) {
        DesktopChatCommandInputSource(instance<TelegramControlBot>())
    }
    bindSingleton<TelegramSettingsHost>(overrides = true) {
        DesktopTelegramSettingsHost(
            telegramService = instance<TelegramUiService>(),
            telegramControlBot = instance<TelegramControlBot>(),
        )
    }
    bindSingleton<SupportLogService>(overrides = true) { DesktopSupportLogService() }
    bindSingleton<PrivacyPolicyOpener>(overrides = true) { DesktopPrivacyPolicyOpener() }
    bindSingleton<SettingsHostPreferences>(overrides = true) { DesktopSettingsHostPreferences() }

    bindSingleton { TelegramContactSelectionApprovalSource(instance()) }
    bindSingleton { TelegramChatSelectionApprovalSource(instance()) }
    bindSingleton<Set<SelectionApprovalSource>>(overrides = true) {
        setOf(
            instance<TelegramContactSelectionApprovalSource>(),
            instance<TelegramChatSelectionApprovalSource>(),
        )
    }

    bind<VoiceInputController>(overrides = true) with scoped(mainViewModelDiScope).singleton {
        VoiceInputUseCase(
            audioRecorder = instance(),
            speechRecognitionProvider = instance(),
            chatUseCase = instance(),
            speechUseCase = instance(),
            permissionsUseCase = instance(),
            permissionPromptService = instance(),
        )
    }
}

fun sharedUiDiModule(): DI.Module = sharedUiDesktopDiModule()
