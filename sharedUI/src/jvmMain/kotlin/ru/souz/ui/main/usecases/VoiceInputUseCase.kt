package ru.souz.ui.main.usecases

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.consumeAsFlow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.retryWhen
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.slf4j.LoggerFactory
import ru.souz.llms.giga.MissingVoiceKeyException
import ru.souz.llms.tunnel.MissingAiTunnelVoiceKeyException
import ru.souz.llms.openai.MissingOpenAiVoiceKeyException
import ru.souz.service.speech.LocalMacOsSpeechAppBundleMissingUsageDescriptionException
import ru.souz.service.speech.LocalMacOsSpeechAudioTooLongException
import ru.souz.service.speech.LocalMacOsSpeechLocaleUnsupportedException
import ru.souz.service.speech.LocalMacOsSpeechOnDeviceUnsupportedException
import ru.souz.service.speech.LocalMacOsSpeechPermissionDeniedException
import ru.souz.service.speech.LocalMacOsSpeechUnavailableException
import ru.souz.service.speech.SpeechRecognitionProvider
import ru.souz.service.speech.VoiceRecognitionUnavailableException
import ru.souz.ui.host.PermissionPromptService
import ru.souz.ui.host.UiAudioRecorder
import ru.souz.ui.host.UiAudioRecordingState
import ru.souz.ui.main.MainState
import souz.sharedui.generated.resources.Res
import souz.sharedui.generated.resources.*
import org.jetbrains.compose.resources.getString

@OptIn(ExperimentalCoroutinesApi::class)
class VoiceInputUseCase(
    val audioRecorder: UiAudioRecorder,
    private val speechRecognitionProvider: SpeechRecognitionProvider,
    private val chatUseCase: ChatUseCase,
    private val speechUseCase: SpeechUseCase,
    private val permissionsUseCase: PermissionsUseCase,
    private val permissionPromptService: PermissionPromptService,
) : VoiceInputController {
    private val l = LoggerFactory.getLogger(VoiceInputUseCase::class.java)
    private var lastRecognizedText: String? = null
    private var lastRecognizedAtMs: Long = 0L
    private val recognitionMutex = Mutex()
    private val captureRoutingMutex = Mutex()
    private val captureRoutes = ArrayDeque<VoiceInputRoute>()

    private val _outputs = Channel<MainUseCaseOutput>()
    override val outputs: Flow<MainUseCaseOutput> = _outputs.consumeAsFlow()

    override suspend fun initialize(
        scope: CoroutineScope,
        stateProvider: () -> MainState,
        onRecognizedInput: suspend (RecognizedVoiceInput) -> Unit,
        voiceInputStartBlocker: suspend () -> String?,
    ) = coroutineScope {
        launch { audioRecorder.logState() }

        val nativeHookRegistered = permissionsUseCase.registerNativeHook()
        if (!nativeHookRegistered) {
            permissionsUseCase.handleMissingInputMonitoringPermission(scope)
        }

        val hotkeyRegistration = if (nativeHookRegistered) {
            permissionPromptService.registerVoiceInputHotkey(
                onPressed = { pressed ->
                    l.info(if (pressed) "onStart" else "onStop")
                    scope.launch {
                        when {
                            pressed -> {
                                val state = stateProvider()
                                startRecordingInternal(
                                    scope = scope,
                                    isListening = state.isListening,
                                    blockedReason = voiceInputStartBlocker(),
                                )
                            }
                            else -> stopRecording(stateProvider().isListening)
                        }
                    }
                },
                onDoubleClick = {
                    scope.launch {
                        chatUseCase.abortActiveRequest()
                    }
                },
            )
        } else {
            null
        }

        try {
            val userInputFlow = audioRecorder.audioFlow
                .onEach { l.debug("[Received audio data: ${it.size} bytes]") }
                .catch { l.error("Error in audio flow: ${it.message}") }
                .mapNotNull { audioData ->
                    val route = takeCaptureRoute()
                    if (route == null) {
                        l.warn("Audio payload has no matching voice capture, skipping recognition")
                        null
                    } else {
                        route to audioData
                    }
                }
                .mapLatest { (route, audioData) ->
                    if (audioData.isEmpty()) {
                        l.warn("Empty audio payload captured, skipping transcription request")
                        emitVoiceCaptureTooShort()
                        return@mapLatest RecognizedVoiceInput("", route)
                    }
                    if (!recognitionMutex.tryLock()) {
                        l.debug("Skipping recognition request because previous one is still in progress")
                        return@mapLatest RecognizedVoiceInput("", route)
                    }

                    val recognizedText = try {
                        l.debug("[Sending PCM audio data: ${audioData.size} bytes]")
                        speechRecognitionProvider.recognize(audioData)
                    } finally {
                        recognitionMutex.unlock()
                    }
                    RecognizedVoiceInput(recognizedText, route)
                }

                .onEach { onTextRecognizeSideEffects(it.text) }
                .filter { it.text.isNotBlank() }

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
                if (cause is LocalMacOsSpeechPermissionDeniedException) {
                    emitSpeechRecognitionPermissionDenied()
                    return@retryWhen false
                }
                if (cause is LocalMacOsSpeechAppBundleMissingUsageDescriptionException) {
                    emitLocalMacOsSpeechBundleRequired()
                    return@retryWhen false
                }
                if (cause is LocalMacOsSpeechLocaleUnsupportedException) {
                    emitLocalMacOsSpeechLocaleUnsupported()
                    return@retryWhen false
                }
                if (cause is LocalMacOsSpeechOnDeviceUnsupportedException) {
                    emitLocalMacOsSpeechUnavailable()
                    return@retryWhen false
                }
                if (cause is LocalMacOsSpeechAudioTooLongException) {
                    emitLocalMacOsSpeechAudioTooLong()
                    return@retryWhen true
                }
                if (cause is LocalMacOsSpeechUnavailableException) {
                    emitLocalMacOsSpeechUnavailable()
                    delay(1000L)
                    return@retryWhen true
                }

                l.error("Agent flow failed, attempt {}, cause: {}", attempt, cause.message, cause)
                val errorMsg = getString(Res.string.error_prefix).format(cause.message ?: "")
                emitState { copy(statusMessage = errorMsg) }
                delay(1000L)
                true
            }.collect { recognizedInput ->
                if (isDuplicateRecognition(recognizedInput.text)) return@collect
                onRecognizedInput(recognizedInput)
            }
        } finally {
            if (nativeHookRegistered) {
                hotkeyRegistration?.invoke()
                permissionsUseCase.unregisterNativeHook()
            }
        }
    }

    override suspend fun startRecording(
        scope: CoroutineScope,
        isListening: Boolean,
    ) {
        startRecordingInternal(scope, isListening, blockedReason = null)
    }

    private suspend fun startRecordingInternal(
        scope: CoroutineScope,
        isListening: Boolean,
        blockedReason: String? = null,
    ) {
        if (isListening) return
        if (!blockedReason.isNullOrBlank()) {
            emitState { copy(isListening = false, statusMessage = blockedReason) }
            return
        }
        if (recognitionMutex.isLocked) {
            val statusMsg = getString(Res.string.voice_status_processing_input)
            emitState { copy(statusMessage = statusMsg) }
            return
        }
        if (!speechRecognitionProvider.enabled) {
            emitVoiceRecognitionUnavailable()
            return
        }
        if (!speechRecognitionProvider.hasRequiredKey) {
            emitVoiceKeyMissing()
            return
        }

        val activeRunRequestId = chatUseCase.captureActiveSkillsRequestId()
        val route = if (activeRunRequestId == null) {
            VoiceInputRoute.NewRequest
        } else {
            VoiceInputRoute.ActiveRunContinuation(activeRunRequestId)
        }
        if (route is VoiceInputRoute.NewRequest) {
            chatUseCase.abortActiveRequest()
        }
        speechUseCase.playMacPingSafely(scope)
        enqueueCaptureRoute(route)

        val statusMsg = getString(Res.string.voice_status_recording_started)
        emitState {
            copy(
                isListening = true,
                statusMessage = statusMsg,
            )
        }

        val started = withContext(Dispatchers.IO) {
            audioRecorder.start()
        }
        if (!started) {
            discardCaptureRoute(route)
            val recorderState = audioRecorder.recordingState.value
            val errorMsg = (recorderState as? UiAudioRecordingState.Error)?.message.orEmpty()
            l.error("Unable to start microphone capture: {}", errorMsg)
            emitVoiceCaptureFailed()
        }
    }

    override suspend fun stopRecording(isListening: Boolean) {
        if (!isListening) return

        try {
            audioRecorder.stop()
        } catch (error: Exception) {
            l.error("Unable to stop microphone capture", error)
            discardLatestCaptureRoute()
            emitVoiceCaptureFailed()
            return
        }
        val statusMsg = getString(Res.string.voice_status_processing_input)
        emitState {
            copy(
                isListening = false,
                statusMessage = statusMsg,
            )
        }

        val terminalState = audioRecorder.recordingState.first { state ->
            state is UiAudioRecordingState.Idle || state is UiAudioRecordingState.Error
        }
        if (terminalState is UiAudioRecordingState.Error) {
            l.error("Unable to stop microphone capture: {}", terminalState.message)
            discardLatestCaptureRoute()
            emitVoiceCaptureFailed()
            return
        }

        delay(300)
        speechUseCase.playInputConfirmation()
    }

    private suspend fun enqueueCaptureRoute(route: VoiceInputRoute) {
        captureRoutingMutex.withLock {
            captureRoutes.addLast(route)
        }
    }

    private suspend fun takeCaptureRoute(): VoiceInputRoute? =
        captureRoutingMutex.withLock {
            captureRoutes.removeFirstOrNull()
        }

    private suspend fun discardCaptureRoute(route: VoiceInputRoute) {
        captureRoutingMutex.withLock {
            if (captureRoutes.lastOrNull() == route) {
                captureRoutes.removeLast()
            }
        }
    }

    private suspend fun discardLatestCaptureRoute() {
        captureRoutingMutex.withLock {
            captureRoutes.removeLastOrNull()
        }
    }

    private suspend fun onTextRecognizeSideEffects(recognizedText: String) {
        if (recognizedText.isNotBlank()) return

        val msg = getString(Res.string.voice_status_speech_not_recognized)
        speechUseCase.queue(msg)
        emitState { copy(statusMessage = msg) }
    }

    private suspend fun emitVoiceKeyMissing() {
        val msg = getString(Res.string.voice_error_missing_key)
        speechUseCase.queue(msg)
        emitState { copy(isListening = false, statusMessage = msg) }
    }

    private suspend fun emitVoiceRecognitionUnavailable() {
        val msg = getString(Res.string.voice_error_recognition_unavailable)
        speechUseCase.queue(msg)
        emitState { copy(isListening = false, statusMessage = msg) }
    }

    private suspend fun emitSpeechRecognitionPermissionDenied() {
        val msg = getString(Res.string.voice_error_speech_permission_denied)
        speechUseCase.queue(msg)
        emitState { copy(isListening = false, statusMessage = msg) }
    }

    private suspend fun emitLocalMacOsSpeechUnavailable() {
        val msg = getString(Res.string.voice_error_local_macos_unavailable)
        speechUseCase.queue(msg)
        emitState { copy(isListening = false, statusMessage = msg) }
    }

    private suspend fun emitLocalMacOsSpeechBundleRequired() {
        val msg = getString(Res.string.voice_error_local_macos_bundle_required)
        speechUseCase.queue(msg)
        emitState { copy(isListening = false, statusMessage = msg) }
    }

    private suspend fun emitLocalMacOsSpeechLocaleUnsupported() {
        val msg = getString(Res.string.voice_error_local_macos_locale_unsupported)
        speechUseCase.queue(msg)
        emitState { copy(isListening = false, statusMessage = msg) }
    }

    private suspend fun emitLocalMacOsSpeechAudioTooLong() {
        val msg = getString(Res.string.voice_error_local_macos_audio_too_long)
        speechUseCase.queue(msg)
        emitState { copy(isListening = false, statusMessage = msg) }
    }

    private suspend fun emitVoiceCaptureTooShort() {
        val msg = getString(Res.string.voice_error_empty_audio)
        emitState { copy(isListening = false, statusMessage = msg) }
    }

    private suspend fun emitVoiceCaptureFailed() {
        val msg = getString(Res.string.voice_error_microphone_unavailable)
        speechUseCase.queue(msg)
        emitState { copy(isListening = false, statusMessage = msg) }
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
