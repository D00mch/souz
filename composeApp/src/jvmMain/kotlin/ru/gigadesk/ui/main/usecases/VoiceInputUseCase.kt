package ru.gigadesk.ui.main.usecases

import com.github.kwhat.jnativehook.GlobalScreen
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.retryWhen
import kotlinx.coroutines.launch
import org.slf4j.LoggerFactory
import ru.gigadesk.audio.InMemoryAudioRecorder
import ru.gigadesk.audio.rawToOpusOgg
import ru.gigadesk.giga.GigaVoiceAPI
import ru.gigadesk.keys.HotkeyListener
import ru.gigadesk.ui.main.MainState

@OptIn(ExperimentalCoroutinesApi::class)
class VoiceInputUseCase(
    val audioRecorder: InMemoryAudioRecorder,
    private val gigaVoiceAPI: GigaVoiceAPI,
    private val chatUseCase: ChatUseCase,
    private val speechUseCase: SpeechUseCase,
    private val permissionsUseCase: PermissionsUseCase,
) {
    private val l = LoggerFactory.getLogger(VoiceInputUseCase::class.java)
    private var lastRecognizedText: String? = null
    private var lastRecognizedAtMs: Long = 0L

    private val _outputs = MutableSharedFlow<MainUseCaseOutput>(replay = 1, extraBufferCapacity = 64)
    val outputs: Flow<MainUseCaseOutput> = _outputs.asSharedFlow()

    suspend fun initialize(
        scope: CoroutineScope,
        stateProvider: () -> MainState,
        onRecognizedText: suspend (String) -> Unit,
    ) = coroutineScope {
        val hotkeyListener = HotkeyListener(
            onPressed = { pressed ->
                l.info(if (pressed) "onStart" else "onStop")
                scope.launch {
                    when {
                        pressed -> startRecording(stateProvider().isListening)
                        else -> stopRecording(stateProvider().isListening)
                    }
                }
            },
            onDoubleClick = { chatUseCase.cancelActiveJob() },
        )

        launch { audioRecorder.logState() }

        if (!permissionsUseCase.registerNativeHook()) {
            permissionsUseCase.handleMissingInputMonitoringPermission(scope)
            return@coroutineScope
        }

        try {
            GlobalScreen.addNativeKeyListener(hotkeyListener)

            val userInputFlow = audioRecorder.audioFlow
                .onEach { l.debug("[Received audio data: ${it.size} bytes]") }
                .catch { l.error("Error in audio flow: ${it.message}") }
                .mapLatest { audioData ->
                    val encodedAudio = rawToOpusOgg(rawData = audioData)
                    l.debug("[Sending audio data: ${encodedAudio.size} bytes]")
                    val resp = gigaVoiceAPI.recognize(encodedAudio)
                    l.info("Recognition response: {}", resp)
                    resp.result.joinToString("\n").trim()
                }
                .onEach(::onTextRecognizeSideEffects)
                .filter { it.isNotBlank() }

            userInputFlow.retryWhen { cause, attempt ->
                if (cause is CancellationException) return@retryWhen false

                l.error("Agent flow failed, attempt {}, cause: {}", attempt, cause.message, cause)
                emitState { copy(isProcessing = false, statusMessage = "Ошибка: ${cause.message}") }
                delay(1000L)
                true
            }.collect { userInput ->
                if (isDuplicateRecognition(userInput)) return@collect
                onRecognizedText(userInput)
            }
        } finally {
            GlobalScreen.unregisterNativeHook()
        }
    }

    suspend fun startRecording(isListening: Boolean) {
        if (isListening) return

        chatUseCase.stopSpeechAndSideEffects()
        chatUseCase.cancelActiveJob()
        speechUseCase.playMacPing()

        emitState {
            copy(
                isListening = true,
                statusMessage = "Запись запущена",
            )
        }

        audioRecorder.start()
    }

    suspend fun stopRecording(isListening: Boolean) {
        if (!isListening) return

        audioRecorder.stop()
        emitState {
            copy(
                isListening = false,
                statusMessage = "Обработка входа",
            )
        }

        delay(300)
        speechUseCase.playInputConfirmation()
    }

    private suspend fun onTextRecognizeSideEffects(recognizedText: String) {
        if (recognizedText.isNotBlank()) return

        val msg = "Речь не распознана"
        speechUseCase.queue(msg)
        emitState { copy(statusMessage = msg, isProcessing = false) }
    }

    private suspend fun emitState(reduce: MainState.() -> MainState) {
        _outputs.emit(MainUseCaseOutput.State(reduce))
    }

    private fun isDuplicateRecognition(text: String): Boolean {
        val now = System.currentTimeMillis()
        val isDuplicate = text == lastRecognizedText && now - lastRecognizedAtMs < DUPLICATE_RECOGNITION_WINDOW_MS
        if (!isDuplicate) {
            lastRecognizedText = text
            lastRecognizedAtMs = now
        }
        return isDuplicate
    }

    private companion object {
        const val DUPLICATE_RECOGNITION_WINDOW_MS = 800L
    }
}
