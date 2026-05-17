package ru.souz.di

import org.kodein.di.DI
import org.kodein.di.bindSingleton
import org.kodein.di.instance
import ru.souz.agent.spi.AgentErrorMessages
import ru.souz.tool.SelectionApprovalSource
import ru.souz.ui.approval.TelegramChatSelectionApprovalSource
import ru.souz.ui.approval.TelegramContactSelectionApprovalSource
import ru.souz.ui.common.ComposeAgentErrorMessages
import ru.souz.ui.common.usecases.ApiKeyAvailabilityUseCase
import ru.souz.ui.main.usecases.FinderPathExtractor
import ru.souz.ui.main.usecases.MainUseCasesFactory

fun sharedUiDiModule(): DI.Module = DI.Module("sharedUi") {
    bindSingleton<AgentErrorMessages> { ComposeAgentErrorMessages() }
    bindSingleton { ApiKeyAvailabilityUseCase(instance()) }
    bindSingleton { FinderPathExtractor(instance()) }

    bindSingleton { TelegramContactSelectionApprovalSource(instance()) }
    bindSingleton { TelegramChatSelectionApprovalSource(instance()) }
    bindSingleton<Set<SelectionApprovalSource>> {
        setOf(
            instance<TelegramContactSelectionApprovalSource>(),
            instance<TelegramChatSelectionApprovalSource>(),
        )
    }

    bindSingleton {
        MainUseCasesFactory(
            agentFacade = instance(),
            settingsProvider = instance(),
            speechRecognitionProvider = instance(),
            audioRecorder = instance(),
            speechPlayer = instance(),
            toolPermissionBroker = instance(),
            deferredToolModifyPermissionBroker = instance(),
            selectionApprovalSources = instance(),
            finderPathExtractor = instance(),
            tokenLogging = instance(),
            log = instance(),
            desktopPermissionService = instance(),
            voiceInputHotkeyRegistrar = instance(),
        )
    }
}
