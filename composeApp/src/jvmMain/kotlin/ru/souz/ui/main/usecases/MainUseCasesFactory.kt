package ru.souz.ui.main.usecases

import kotlinx.coroutines.CoroutineDispatcher
import ru.souz.agent.GraphBasedAgent
import ru.souz.audio.InMemoryAudioRecorder
import ru.souz.audio.Say
import ru.souz.db.SettingsProvider
import ru.souz.tool.ToolPermissionBroker

data class MainUseCases(
    val chat: ChatUseCase,
    val voiceInput: VoiceInputUseCase,
    val speech: SpeechUseCase,
    val permissions: PermissionsUseCase,
    val attachments: ChatAttachmentsUseCase,
)

class MainUseCasesFactory(
    private val graphAgent: GraphBasedAgent,
    private val settingsProvider: SettingsProvider,
    private val speechRecognitionProvider: SpeechRecognitionProvider,
    private val audioRecorder: InMemoryAudioRecorder,
    private val say: Say,
    private val toolPermissionBroker: ToolPermissionBroker,
    private val finderPathExtractor: FinderPathExtractor,
) {

    fun create(ioDispatcher: CoroutineDispatcher): MainUseCases {
        val speechUseCase = SpeechUseCase(say)
        val attachmentsUseCase = ChatAttachmentsUseCase(ioDispatcher)
        val chatUseCase = ChatUseCase(
            graphAgent = graphAgent,
            settingsProvider = settingsProvider,
            speechUseCase = speechUseCase,
            finderPathExtractor = finderPathExtractor,
            chatAttachmentsUseCase = attachmentsUseCase,
            ioDispatcher = ioDispatcher,
        )
        val permissionsUseCase = PermissionsUseCase(
            settingsProvider = settingsProvider,
            toolPermissionBroker = toolPermissionBroker,
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
