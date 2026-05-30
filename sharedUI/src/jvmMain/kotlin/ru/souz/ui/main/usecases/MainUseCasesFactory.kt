package ru.souz.ui.main.usecases

import kotlinx.coroutines.CoroutineDispatcher
import ru.souz.agent.AgentFacade
import ru.souz.db.SettingsProvider
import ru.souz.llms.TokenLogging
import ru.souz.service.observability.ChatObservabilityTracker
import ru.souz.service.observability.DesktopStructuredLogger
import ru.souz.service.speech.SpeechRecognitionProvider
import ru.souz.tool.SelectionApprovalSource
import ru.souz.tool.ToolPermissionBroker
import ru.souz.tool.files.DeferredToolModifyPermissionBroker
import ru.souz.ui.host.DesktopPermissionService
import ru.souz.ui.host.UiAudioRecorder
import ru.souz.ui.host.UiSpeechPlayer

data class MainUseCases(
    val chat: ChatUseCase,
    val toolModifyReview: ToolModifyReviewUseCase,
    val voiceInput: VoiceInputUseCase,
    val speech: SpeechUseCase,
    val permissions: PermissionsUseCase,
    val attachments: ChatAttachmentsUseCase,
)

class MainUseCasesFactory(
    private val agentFacade: AgentFacade,
    private val settingsProvider: SettingsProvider,
    private val speechRecognitionProvider: SpeechRecognitionProvider,
    private val audioRecorder: UiAudioRecorder,
    private val speechPlayer: UiSpeechPlayer,
    private val toolPermissionBroker: ToolPermissionBroker,
    private val deferredToolModifyPermissionBroker: DeferredToolModifyPermissionBroker,
    private val selectionApprovalSources: Set<SelectionApprovalSource>,
    private val finderPathExtractor: FinderPathExtractor,
    private val tokenLogging: TokenLogging,
    private val log: DesktopStructuredLogger,
    private val desktopPermissionService: DesktopPermissionService,
) {

    fun create(ioDispatcher: CoroutineDispatcher): MainUseCases {
        val speechUseCase = SpeechUseCase(speechPlayer)
        val attachmentsUseCase = ChatAttachmentsUseCase(ioDispatcher)
        val toolModifyReviewUseCase = ToolModifyReviewUseCase(
            deferredToolModifyPermissionBroker = deferredToolModifyPermissionBroker,
        )
        val chatUseCase = ChatUseCase(
            agentFacade = agentFacade,
            settingsProvider = settingsProvider,
            speechUseCase = speechUseCase,
            finderPathExtractor = finderPathExtractor,
            chatAttachmentsUseCase = attachmentsUseCase,
            toolModifyReviewUseCase = toolModifyReviewUseCase,
            observabilityTracker = ChatObservabilityTracker(log = log),
            log = log,
            tokenLogging = tokenLogging,
            ioDispatcher = ioDispatcher,
        )
        val permissionsUseCase = PermissionsUseCase(
            settingsProvider = settingsProvider,
            toolPermissionBroker = toolPermissionBroker,
            selectionApprovalSources = selectionApprovalSources,
            speechUseCase = speechUseCase,
            desktopPermissionService = desktopPermissionService,
        )
        val voiceInputUseCase = VoiceInputUseCase(
            audioRecorder = audioRecorder,
            speechRecognitionProvider = speechRecognitionProvider,
            chatUseCase = chatUseCase,
            speechUseCase = speechUseCase,
            permissionsUseCase = permissionsUseCase,
            desktopPermissionService = desktopPermissionService,
        )

        return MainUseCases(
            chat = chatUseCase,
            toolModifyReview = toolModifyReviewUseCase,
            voiceInput = voiceInputUseCase,
            speech = speechUseCase,
            permissions = permissionsUseCase,
            attachments = attachmentsUseCase,
        )
    }
}
