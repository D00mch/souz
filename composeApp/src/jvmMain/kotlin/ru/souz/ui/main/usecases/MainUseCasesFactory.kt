package ru.souz.ui.main.usecases

import kotlinx.coroutines.CoroutineDispatcher
import ru.souz.agent.AgentFacade
import ru.souz.service.audio.InMemoryAudioRecorder
import ru.souz.service.audio.Say
import ru.souz.db.SettingsProvider
import ru.souz.llms.TokenLogging
import ru.souz.service.telemetry.TelemetryService
import ru.souz.tool.SelectionApprovalSource
import ru.souz.tool.ToolPermissionBroker
import ru.souz.tool.files.DeferredToolModifyPermissionBroker

data class MainUseCases(
    val chat: ChatUseCase,
    val voiceInput: VoiceInputUseCase,
    val speech: SpeechUseCase,
    val permissions: PermissionsUseCase,
    val attachments: ChatAttachmentsUseCase,
)

class MainUseCasesFactory(
    private val agentFacade: AgentFacade,
    private val settingsProvider: SettingsProvider,
    private val speechRecognitionProvider: SpeechRecognitionProvider,
    private val audioRecorder: InMemoryAudioRecorder,
    private val say: Say,
    private val toolPermissionBroker: ToolPermissionBroker,
    private val deferredToolModifyPermissionBroker: DeferredToolModifyPermissionBroker,
    private val selectionApprovalSources: Set<SelectionApprovalSource>,
    private val finderPathExtractor: FinderPathExtractor,
    private val tokenLogging: TokenLogging,
    private val telemetryService: TelemetryService,
) {

    fun create(ioDispatcher: CoroutineDispatcher): MainUseCases {
        val speechUseCase = SpeechUseCase(say)
        val attachmentsUseCase = ChatAttachmentsUseCase(ioDispatcher)
        val chatUseCase = ChatUseCase(
            agentFacade = agentFacade,
            settingsProvider = settingsProvider,
            speechUseCase = speechUseCase,
            finderPathExtractor = finderPathExtractor,
            chatAttachmentsUseCase = attachmentsUseCase,
            deferredToolModifyPermissionBroker = deferredToolModifyPermissionBroker,
            tokenLogging = tokenLogging,
            telemetryService = telemetryService,
            ioDispatcher = ioDispatcher,
        )
        val permissionsUseCase = PermissionsUseCase(
            settingsProvider = settingsProvider,
            toolPermissionBroker = toolPermissionBroker,
            selectionApprovalSources = selectionApprovalSources,
            speechUseCase = speechUseCase,
        )
        val voiceInputUseCase = VoiceInputUseCase(
            audioRecorder = audioRecorder,
            speechRecognitionProvider = speechRecognitionProvider,
            chatUseCase = chatUseCase,
            speechUseCase = speechUseCase,
            permissionsUseCase = permissionsUseCase,
        )

        return MainUseCases(
            chat = chatUseCase,
            voiceInput = voiceInputUseCase,
            speech = speechUseCase,
            permissions = permissionsUseCase,
            attachments = attachmentsUseCase,
        )
    }
}
