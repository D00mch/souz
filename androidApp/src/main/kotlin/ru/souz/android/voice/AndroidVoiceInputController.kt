package ru.souz.android.voice

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.consumeAsFlow
import org.slf4j.LoggerFactory
import ru.souz.android.assistant.captureFailureMessage
import ru.souz.llms.tunnel.MissingAiTunnelVoiceKeyException
import ru.souz.service.speech.SpeechRecognitionProvider
import ru.souz.service.speech.VoiceRecognitionUnavailableException
import ru.souz.ui.main.MainState
import ru.souz.ui.main.usecases.MainUseCaseOutput
import ru.souz.ui.main.usecases.VoiceInputController

class AndroidVoiceInputController(
    private val audioRecorder: AndroidPcmAudioRecorder,
    private val speechRecognitionProvider: SpeechRecognitionProvider,
    private val speechPlayer: CloudSpeechPlayer,
    private val micPermissionGate: MicPermissionGate,
) : VoiceInputController {
    private val l = LoggerFactory.getLogger(AndroidVoiceInputController::class.java)

    private val _outputs = Channel<MainUseCaseOutput>(capacity = Channel.BUFFERED)
    override val outputs: Flow<MainUseCaseOutput> = _outputs.consumeAsFlow()

    override suspend fun initialize(
        scope: CoroutineScope,
        stateProvider: () -> MainState,
        onRecognizedText: suspend (String) -> Unit,
        voiceInputStartBlocker: suspend () -> String?,
    ) {
        audioRecorder.audioFlow.collect { audio ->
            emitState { copy(isListening = false) }
            if (audio.isEmpty()) {
                emitStatus(captureFailureMessage(audioRecorder.lastStats))
                return@collect
            }
            emitStatus("Распознаю…")
            val text = try {
                speechRecognitionProvider.recognize(audio)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                l.error("Speech recognition failed", e)
                emitStatus(recognitionErrorMessage(e))
                return@collect
            }
            l.info("Recognized {} chars", text.length)
            if (text.isBlank()) {
                emitStatus("Речь не распознана")
                return@collect
            }
            emitState { copy(statusMessage = "") }
            onRecognizedText(text)
        }
    }

    override suspend fun startRecording(scope: CoroutineScope, isListening: Boolean) {
        if (isListening) return
        if (!speechRecognitionProvider.enabled) {
            emitStatus("Распознавание речи недоступно в этой сборке")
            return
        }
        if (!speechRecognitionProvider.hasRequiredKey) {
            emitStatus(MISSING_KEY_MESSAGE)
            return
        }
        if (!micPermissionGate.ensureMicrophonePermission()) {
            emitStatus("Нет разрешения на доступ к микрофону")
            return
        }

        speechPlayer.clearQueue()
        if (!audioRecorder.start()) {
            emitStatus("Микрофон занят")
            return
        }
        speechPlayer.playMacPing()
        emitState { copy(isListening = true, statusMessage = "Слушаю…") }
    }

    override suspend fun stopRecording(isListening: Boolean) {
        if (!isListening) return
        audioRecorder.stop()
        emitState { copy(isListening = false, statusMessage = "Распознаю…") }
    }

    private fun recognitionErrorMessage(e: Exception): String = when (e) {
        is MissingAiTunnelVoiceKeyException -> MISSING_KEY_MESSAGE
        is VoiceRecognitionUnavailableException -> "Распознавание речи недоступно в этой сборке"
        else -> "Ошибка распознавания: ${e.message ?: e::class.java.simpleName}"
    }

    private suspend fun emitStatus(message: String) {
        emitState { copy(isListening = false, isProcessing = false, statusMessage = message) }
    }

    private suspend fun emitState(reduce: MainState.() -> MainState) {
        _outputs.send(MainUseCaseOutput.State(reduce))
    }
}

private const val MISSING_KEY_MESSAGE = "Не задан ключ AiTunnel в настройках"

fun interface MicPermissionGate {
    suspend fun ensureMicrophonePermission(): Boolean
}
