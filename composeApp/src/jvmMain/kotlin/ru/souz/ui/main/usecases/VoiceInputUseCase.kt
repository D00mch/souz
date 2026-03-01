package ru.souz.ui.main.usecases

import com.github.kwhat.jnativehook.GlobalScreen
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.consumeAsFlow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.retryWhen
import kotlinx.coroutines.launch
import org.slf4j.LoggerFactory
import ru.souz.audio.InMemoryAudioRecorder
import ru.souz.giga.MissingVoiceKeyException
import ru.souz.keys.HotkeyListener
import ru.souz.llms.MissingAiTunnelVoiceKeyException
import ru.souz.llms.MissingOpenAiVoiceKeyException
import ru.souz.ui.main.MainState
import souz.composeapp.generated.resources.Res
import souz.composeapp.generated.resources.*
import org.jetbrains.compose.resources.getString

@OptIn(ExperimentalCoroutinesApi::class)
class VoiceInputUseCase(
    val audioRecorder: InMemoryAudioRecorder,
    private val speechRecognitionProvider: SpeechRecognitionProvider,
    private val chatUseCase: ChatUseCase,
    private val speechUseCase: SpeechUseCase,
    private val permissionsUseCase: OnboardingUseCase,
) {
    private val l = LoggerFactory.getLogger(VoiceInputUseCase::class.java)
    private var lastRecognizedText: String? = null
    private var lastRecognizedAtMs: Long = 0L

    private val _outputs = Channel<MainUseCaseOutput>()
    val outputs: Flow<MainUseCaseOutput> = _outputs.consumeAsFlow()

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
                        pressed -> startRecording(scope, stateProvider().isListening)
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
                    l.debug("[Sending PCM audio data: ${audioData.size} bytes]")
                    speechRecognitionProvider.recognize(audioData)
                }

                .catch { l.error("Error in recognition: ${it.message}") }
                .onEach(::onTextRecognizeSideEffects)
                .filter { it.isNotBlank() }

            userInputFlow.retryWhen { cause, attempt ->
                if (cause is CancellationException) return@retryWhen false
                if (
                    cause is MissingVoiceKeyException ||
                    cause is MissingOpenAiVoiceKeyException ||
                    cause is MissingAiTunnelVoiceKeyException
                ) {
                    emitVoiceKeyMissing()
                    return@retryWhen true
                }
                if (cause is VoiceRecognitionUnavailableException) {
                    emitVoiceRecognitionUnavailable()
                    return@retryWhen false
                }

                l.error("Agent flow failed, attempt {}, cause: {}", attempt, cause.message, cause)
                val errorMsg = getString(Res.string.error_prefix).format(cause.message ?: "")
                emitState { copy(isProcessing = false, statusMessage = errorMsg) }
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

    suspend fun startRecording(scope: CoroutineScope, isListening: Boolean) {
        if (isListening) return
        if (!speechRecognitionProvider.enabled) {
            emitVoiceRecognitionUnavailable()
            return
        }
        if (!speechRecognitionProvider.hasRequiredKey) {
            emitVoiceKeyMissing()
            return
        }

        chatUseCase.stopSpeechAndSideEffects()
        chatUseCase.cancelActiveJob()
        speechUseCase.playMacPingSafely(scope)

        val statusMsg = getString(Res.string.voice_status_recording_started)
        emitState {
            copy(
                isListening = true,
                statusMessage = statusMsg,
            )
        }

        audioRecorder.start()
    }

    suspend fun stopRecording(isListening: Boolean) {
        if (!isListening) return

        audioRecorder.stop()
        val statusMsg = getString(Res.string.voice_status_processing_input)
        emitState {
            copy(
                isListening = false,
                statusMessage = statusMsg,
            )
        }

        delay(300)
        speechUseCase.playInputConfirmation()
    }

    private suspend fun onTextRecognizeSideEffects(recognizedText: String) {
        if (recognizedText.isNotBlank()) return

        val msg = getString(Res.string.voice_status_speech_not_recognized)
        speechUseCase.queue(msg)
        emitState { copy(statusMessage = msg, isProcessing = false) }
    }

    private suspend fun emitVoiceKeyMissing() {
        val msg = getString(Res.string.voice_error_missing_key)
        speechUseCase.queue(msg)
        emitState { copy(isListening = false, isProcessing = false, statusMessage = msg) }
    }

    private suspend fun emitVoiceRecognitionUnavailable() {
        val msg = getString(Res.string.voice_error_recognition_unavailable)
        speechUseCase.queue(msg)
        emitState { copy(isListening = false, isProcessing = false, statusMessage = msg) }
    }

    private suspend fun emitState(reduce: MainState.() -> MainState) {
        _outputs.send(MainUseCaseOutput.State(reduce))
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
