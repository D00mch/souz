package ru.souz.di

import org.kodein.di.DI
import org.kodein.di.bind
import org.kodein.di.bindSingleton
import org.kodein.di.instance
import org.kodein.di.scoped
import org.kodein.di.singleton
import org.kodein.di.with
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import ru.souz.ambient.AmbientBlockAnalyzer
import ru.souz.ambient.AmbientSemanticBlock
import ru.souz.ambient.AmbientSpeechAvailability
import ru.souz.ambient.AmbientTaskCandidate
import ru.souz.ambient.AmbientTranscriptEvent
import ru.souz.ambient.AmbientTranscriptionService
import ru.souz.ambient.AmbientTranscriptionState
import ru.souz.ambient.SemanticBlockBuilder
import ru.souz.service.observability.ChatObservabilityTracker
import ru.souz.tool.SelectionApprovalSource
import ru.souz.ui.common.FileSystemPathMetadataProvider
import ru.souz.ui.common.PathMetadataProvider
import ru.souz.ui.common.usecases.ApiKeyAvailabilityUseCase
import ru.souz.ui.host.BackgroundIndexRefresher
import ru.souz.ui.host.ExternalLinkOpener
import ru.souz.ui.host.InMemorySettingsHostPreferences
import ru.souz.ui.host.NoopBackgroundIndexRefresher
import ru.souz.ui.host.NoopCalendarListProvider
import ru.souz.ui.host.NoopChatCommandInputSource
import ru.souz.ui.host.NoopLocalModelUiHost
import ru.souz.ui.host.NoopPathOpener
import ru.souz.ui.host.NoopPermissionPromptService
import ru.souz.ui.host.NoopPrivacyPolicyOpener
import ru.souz.ui.host.NoopSupportLogService
import ru.souz.ui.host.NoopTelegramSettingsHost
import ru.souz.ui.host.NoopUiAudioRecorder
import ru.souz.ui.host.NoopUiSpeechPlayer
import ru.souz.ui.host.CalendarListProvider
import ru.souz.ui.host.ChatCommandInputSource
import ru.souz.ui.host.LocalModelUiHost
import ru.souz.ui.host.PathOpener
import ru.souz.ui.host.PermissionPromptService
import ru.souz.ui.host.PrivacyPolicyOpener
import ru.souz.ui.host.SupportLogService
import ru.souz.ui.host.TelegramSettingsHost
import ru.souz.ui.host.UiAudioRecorder
import ru.souz.ui.host.UiSpeechPlayer
import ru.souz.ui.main.usecases.AttachmentMetadataProvider
import ru.souz.ui.main.usecases.ChatAttachmentsUseCase
import ru.souz.ui.main.usecases.ChatUseCase
import ru.souz.ui.main.usecases.FileSystemAttachmentMetadataProvider
import ru.souz.ui.main.usecases.DroppedFilePathExtractor
import ru.souz.ui.main.usecases.FinderPathExtractor
import ru.souz.ui.main.usecases.NoopVoiceInputController
import ru.souz.ui.main.usecases.NoopDroppedFilePathExtractor
import ru.souz.ui.main.usecases.NoopPathPicker
import ru.souz.ui.main.usecases.PathPicker
import ru.souz.ui.main.mainViewModelDiScope
import ru.souz.ui.main.usecases.PermissionsUseCase
import ru.souz.ui.main.usecases.SpeechUseCase
import ru.souz.ui.main.usecases.ToolModifyReviewUseCase
import ru.souz.ui.main.usecases.VoiceInputController
import ru.souz.ui.host.SettingsHostPreferences

fun sharedUiCommonJvmDiModule(): DI.Module = DI.Module("sharedUiCommonJvm") {
    import(sharedUiMainViewModelUseCasesDiModule())

    bindSingleton<PathMetadataProvider> { FileSystemPathMetadataProvider() }
    bindSingleton<ExternalLinkOpener> {
        ExternalLinkOpener {
            Result.failure(UnsupportedOperationException("Opening external links is not available on this host."))
        }
    }
    bindSingleton<BackgroundIndexRefresher> { NoopBackgroundIndexRefresher }
    bindSingleton<CalendarListProvider> { NoopCalendarListProvider }
    bindSingleton<PermissionPromptService> { NoopPermissionPromptService }
    bindSingleton<UiAudioRecorder> { NoopUiAudioRecorder }
    bindSingleton<UiSpeechPlayer> { NoopUiSpeechPlayer }
    bindSingleton<PathPicker> { NoopPathPicker }
    bindSingleton<DroppedFilePathExtractor> { NoopDroppedFilePathExtractor }
    bindSingleton<AttachmentMetadataProvider> { FileSystemAttachmentMetadataProvider(instance()) }
    bindSingleton<PathOpener> { NoopPathOpener }
    bindSingleton<LocalModelUiHost> { NoopLocalModelUiHost }
    bindSingleton<ChatCommandInputSource> { NoopChatCommandInputSource }
    bindSingleton<TelegramSettingsHost> { NoopTelegramSettingsHost }
    bindSingleton<SupportLogService> { NoopSupportLogService }
    bindSingleton<PrivacyPolicyOpener> { NoopPrivacyPolicyOpener }
    bindSingleton<SettingsHostPreferences> { InMemorySettingsHostPreferences() }
    bindSingleton<AmbientTranscriptionService> { NoopAmbientTranscriptionService }
    bindSingleton<AmbientBlockAnalyzer> { NoopAmbientBlockAnalyzer }
    bindSingleton { SemanticBlockBuilder() }
    bindSingleton<Set<SelectionApprovalSource>> { emptySet() }
    bindSingleton { ApiKeyAvailabilityUseCase(instance()) }
    bindSingleton { FinderPathExtractor(instance(), instance()) }
}

fun sharedUiMainViewModelUseCasesDiModule(): DI.Module = DI.Module("sharedUiMainViewModelUseCases") {
    bind<SpeechUseCase>() with scoped(mainViewModelDiScope).singleton {
        SpeechUseCase(instance())
    }
    bind<ToolModifyReviewUseCase>() with scoped(mainViewModelDiScope).singleton {
        ToolModifyReviewUseCase(instance())
    }
    bind<ChatAttachmentsUseCase>() with scoped(mainViewModelDiScope).singleton {
        ChatAttachmentsUseCase(
            ioDispatcher = context.ioDispatcher,
            pathPicker = instance(),
            droppedFilePathExtractor = instance(),
            attachmentMetadataProvider = instance(),
        )
    }
    bind<ChatUseCase>() with scoped(mainViewModelDiScope).singleton {
        ChatUseCase(
            agentFacade = instance(),
            settingsProvider = instance(),
            speechUseCase = instance(),
            finderPathExtractor = instance(),
            chatAttachmentsUseCase = instance(),
            toolModifyReviewUseCase = instance(),
            observabilityTracker = ChatObservabilityTracker(log = instance()),
            log = instance(),
            tokenLogging = instance(),
            ioDispatcher = context.ioDispatcher,
        )
    }
    bind<PermissionsUseCase>() with scoped(mainViewModelDiScope).singleton {
        PermissionsUseCase(
            settingsProvider = instance(),
            toolPermissionBroker = instance(),
            selectionApprovalSources = instance(),
            speechUseCase = instance(),
            permissionPromptService = instance(),
        )
    }
    bind<VoiceInputController>() with scoped(mainViewModelDiScope).singleton {
        NoopVoiceInputController
    }
}

private object NoopAmbientTranscriptionService : AmbientTranscriptionService {
    override val transcriptEvents = MutableSharedFlow<AmbientTranscriptEvent>()
    override val state = MutableStateFlow<AmbientTranscriptionState>(AmbientTranscriptionState.Stopped)

    override suspend fun availability(locale: String): AmbientSpeechAvailability =
        AmbientSpeechAvailability.LiveBackendUnavailable

    override suspend fun start(locale: String): AmbientSpeechAvailability =
        AmbientSpeechAvailability.LiveBackendUnavailable

    override suspend fun stop() = Unit
    override suspend fun clearTranscript() = Unit
}

private object NoopAmbientBlockAnalyzer : AmbientBlockAnalyzer {
    override suspend fun analyze(block: AmbientSemanticBlock): AmbientTaskCandidate? = null
}
