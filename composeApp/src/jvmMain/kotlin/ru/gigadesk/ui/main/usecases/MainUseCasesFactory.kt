package ru.gigadesk.ui.main.usecases

import kotlinx.coroutines.CoroutineDispatcher
import ru.gigadesk.agent.GraphBasedAgent
import ru.gigadesk.audio.InMemoryAudioRecorder
import ru.gigadesk.audio.Say
import ru.gigadesk.db.SettingsProvider
import ru.gigadesk.tool.ToolPermissionBroker

data class MainUseCases(
    val chat: ChatUseCase,
    val voiceInput: VoiceInputUseCase,
    val speech: SpeechUseCase,
    val permissions: OnboardingUseCase,
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
        val chatUseCase = ChatUseCase(
            graphAgent = graphAgent,
            settingsProvider = settingsProvider,
            speechUseCase = speechUseCase,
            finderPathExtractor = finderPathExtractor,
            ioDispatcher = ioDispatcher,
        )
        val permissionsUseCase = OnboardingUseCase(
            settingsProvider = settingsProvider,
            toolPermissionBroker = toolPermissionBroker,
            speechUseCase = speechUseCase,
        )
        val voiceInputUseCase = VoiceInputUseCase(
            audioRecorder = audioRecorder,
            speechRecognitionProvider = speechRecognitionProvider,
            settingsProvider = settingsProvider,
            chatUseCase = chatUseCase,
            speechUseCase = speechUseCase,
            permissionsUseCase = permissionsUseCase,
        )

        return MainUseCases(
            chat = chatUseCase,
            voiceInput = voiceInputUseCase,
            speech = speechUseCase,
            permissions = permissionsUseCase,
        )
    }
}
