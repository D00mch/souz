package ru.gigadesk.ui.main.interactors

import kotlinx.coroutines.CoroutineDispatcher
import ru.gigadesk.agent.GraphBasedAgent
import ru.gigadesk.audio.InMemoryAudioRecorder
import ru.gigadesk.audio.Say
import ru.gigadesk.db.SettingsProvider
import ru.gigadesk.giga.GigaVoiceAPI
import ru.gigadesk.tool.ToolPermissionBroker

data class MainInteractors(
    val chat: ChatInteractor,
    val voiceInput: VoiceInputInteractor,
    val speech: SpeechInteractor,
    val permissions: PermissionsInteractor,
)

class MainInteractorsFactory(
    private val graphAgent: GraphBasedAgent,
    private val settingsProvider: SettingsProvider,
    private val gigaVoiceAPI: GigaVoiceAPI,
    private val audioRecorder: InMemoryAudioRecorder,
    private val say: Say,
    private val toolPermissionBroker: ToolPermissionBroker,
) {

    fun create(ioDispatcher: CoroutineDispatcher): MainInteractors {
        val speechInteractor = SpeechInteractor(say)
        val chatInteractor = ChatInteractor(
            graphAgent = graphAgent,
            settingsProvider = settingsProvider,
            speechInteractor = speechInteractor,
            ioDispatcher = ioDispatcher,
        )
        val permissionsInteractor = PermissionsInteractor(
            settingsProvider = settingsProvider,
            toolPermissionBroker = toolPermissionBroker,
            speechInteractor = speechInteractor,
        )
        val voiceInputInteractor = VoiceInputInteractor(
            audioRecorder = audioRecorder,
            gigaVoiceAPI = gigaVoiceAPI,
            chatInteractor = chatInteractor,
            speechInteractor = speechInteractor,
            permissionsInteractor = permissionsInteractor,
        )

        return MainInteractors(
            chat = chatInteractor,
            voiceInput = voiceInputInteractor,
            speech = speechInteractor,
            permissions = permissionsInteractor,
        )
    }
}
