package ru.souz.ui.main.usecases

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.consumeAsFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.jetbrains.compose.resources.StringResource
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
import ru.souz.ui.main.MainEffect
import ru.souz.ui.main.MainState
import souz.sharedui.generated.resources.Res
import souz.sharedui.generated.resources.*
import org.jetbrains.compose.resources.getString

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
    private val captureMutex = Mutex()
    private var pendingCaptureRoute: VoiceInputRoute? = null

    private val _outputs = Channel<MainUseCaseOutput>()
    override val outputs: Flow<MainUseCaseOutput> = _outputs.consumeAsFlow()

    override suspend fun initialize(
        scope: CoroutineScope,
        onRecognizedInput: suspend (RecognizedVoiceInput) -> Unit,
        voiceInputStartBlocker: suspend (VoiceInputRoute) -> String?,
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
                            pressed -> startRecordingInternal(scope, voiceInputStartBlocker)
                            else -> stopRecording()?.let { onRecognizedInput(it) }
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
            awaitCancellation()
        } finally {
            if (nativeHookRegistered) {
                hotkeyRegistration?.invoke()
                permissionsUseCase.unregisterNativeHook()
            }
        }
    }

    override suspend fun startRecording(
        scope: CoroutineScope,
        voiceInputStartBlocker: suspend (VoiceInputRoute) -> String?,
    ) = startRecordingInternal(scope, voiceInputStartBlocker)

    private suspend fun startRecordingInternal(
        scope: CoroutineScope,
        voiceInputStartBlocker: suspend (VoiceInputRoute) -> String?,
    ) {
        if (!captureMutex.tryLock()) {
            val statusMsg = getString(Res.string.voice_status_processing_input)
            emitState { copy(statusMessage = statusMsg) }
            return
        }
        try {
            if (pendingCaptureRoute != null) return
            if (!speechRecognitionProvider.enabled) {
                emitVoiceError(Res.string.voice_error_recognition_unavailable)
                return
            }
            if (!speechRecognitionProvider.hasRequiredKey) {
                emitVoiceError(Res.string.voice_error_missing_key)
                return
            }

            val activeRunRequestId = chatUseCase.captureActiveRunRequestId()
            val route = activeRunRequestId?.let { VoiceInputRoute.ActiveRunContinuation(it) }
                ?: VoiceInputRoute.NewRequest
            val blockedReason = voiceInputStartBlocker(route)
            if (!blockedReason.isNullOrBlank()) {
                emitVoiceInputBlocked(blockedReason)
                return
            }
            pendingCaptureRoute = route
            if (route is VoiceInputRoute.NewRequest) {
                chatUseCase.abortActiveRequest()
            }
            speechUseCase.playMacPingSafely(scope)

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
                pendingCaptureRoute = null
                val recorderState = audioRecorder.recordingState.value
                val errorMsg = (recorderState as? UiAudioRecordingState.Error)?.message.orEmpty()
                l.error("Unable to start microphone capture: {}", errorMsg)
                emitVoiceError(Res.string.voice_error_microphone_unavailable)
            }
        } finally {
            captureMutex.unlock()
        }
    }

    override suspend fun stopRecording(): RecognizedVoiceInput? {
        val recognizedInput = captureMutex.withLock {
            val route = pendingCaptureRoute ?: return@withLock null
            val audioData = try {
                withContext(Dispatchers.IO) { audioRecorder.stop() }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                l.error("Unable to stop microphone capture", error)
                pendingCaptureRoute = null
                emitVoiceError(Res.string.voice_error_microphone_unavailable)
                return@withLock null
            }
            pendingCaptureRoute = null
            if (audioData == null) {
                val recorderState = audioRecorder.recordingState.value
                val errorMsg = (recorderState as? UiAudioRecordingState.Error)?.message.orEmpty()
                l.error("Unable to stop microphone capture: {}", errorMsg)
                emitVoiceError(Res.string.voice_error_microphone_unavailable)
                return@withLock null
            }

            val statusMsg = getString(Res.string.voice_status_processing_input)
            emitState {
                copy(
                    isListening = false,
                    statusMessage = statusMsg,
                )
            }
            recognize(route, audioData)
        }

        if (recognizedInput == null) return null
        val acceptedInput = recognizedInput
            .also { onTextRecognizeSideEffects(it.text) }
            .takeIf { it.text.isNotBlank() && !isDuplicateRecognition(it.text) }
        delay(300)
        speechUseCase.playInputConfirmation()
        return acceptedInput
    }

    private suspend fun onTextRecognizeSideEffects(recognizedText: String) {
        if (recognizedText.isNotBlank()) return

        val msg = getString(Res.string.voice_status_speech_not_recognized)
        speechUseCase.queue(msg)
        emitState { copy(statusMessage = msg) }
    }

    private suspend fun emitVoiceCaptureTooShort() {
        emitVoiceError(Res.string.voice_error_empty_audio, speak = false)
    }

    private suspend fun recognize(
        route: VoiceInputRoute,
        audioData: ByteArray,
    ): RecognizedVoiceInput? {
        l.debug("[Received audio data: ${audioData.size} bytes]")
        if (audioData.isEmpty()) {
            l.warn("Empty audio payload captured, skipping transcription request")
            emitVoiceCaptureTooShort()
            return RecognizedVoiceInput("", route)
        }

        return try {
            l.debug("[Sending PCM audio data: ${audioData.size} bytes]")
            RecognizedVoiceInput(speechRecognitionProvider.recognize(audioData), route)
        } catch (cause: Throwable) {
            handleRecognitionFailure(cause)
            null
        }
    }

    private suspend fun handleRecognitionFailure(cause: Throwable) {
        when (cause) {
            is CancellationException -> throw cause
            is MissingVoiceKeyException,
            is MissingOpenAiVoiceKeyException,
            is MissingAiTunnelVoiceKeyException -> emitVoiceError(Res.string.voice_error_missing_key)
            is VoiceRecognitionUnavailableException -> emitVoiceError(Res.string.voice_error_recognition_unavailable)
            is LocalMacOsSpeechPermissionDeniedException -> emitVoiceError(Res.string.voice_error_speech_permission_denied)
            is LocalMacOsSpeechAppBundleMissingUsageDescriptionException ->
                emitVoiceError(Res.string.voice_error_local_macos_bundle_required)
            is LocalMacOsSpeechLocaleUnsupportedException ->
                emitVoiceError(Res.string.voice_error_local_macos_locale_unsupported)
            is LocalMacOsSpeechOnDeviceUnsupportedException ->
                emitVoiceError(Res.string.voice_error_local_macos_unavailable)
            is LocalMacOsSpeechAudioTooLongException ->
                emitVoiceError(Res.string.voice_error_local_macos_audio_too_long)
            is LocalMacOsSpeechUnavailableException ->
                emitVoiceError(Res.string.voice_error_local_macos_unavailable, retryDelayMs = 1_000L)
            else -> {
                l.error("Voice recognition failed: {}", cause.message, cause)
                val errorMsg = getString(Res.string.error_prefix).format(cause.message ?: "")
                emitState { copy(isListening = false, statusMessage = errorMsg) }
                delay(1_000L)
            }
        }
    }

    private suspend fun emitVoiceError(
        resource: StringResource,
        speak: Boolean = true,
        retryDelayMs: Long = 0L,
    ) {
        val message = getString(resource)
        if (speak) speechUseCase.queue(message)
        emitState { copy(isListening = false, statusMessage = message) }
        if (retryDelayMs > 0L) delay(retryDelayMs)
    }

    private suspend fun emitVoiceInputBlocked(message: String) {
        emitState { copy(isListening = false, statusMessage = message) }
        _outputs.send(MainUseCaseOutput.Effect(MainEffect.ShowError(message)))
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
