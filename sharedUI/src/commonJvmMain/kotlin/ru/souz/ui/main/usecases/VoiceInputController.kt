package ru.souz.ui.main.usecases

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import ru.souz.ui.main.MainState

interface VoiceInputController {
    val outputs: Flow<MainUseCaseOutput>

    suspend fun initialize(
        scope: CoroutineScope,
        stateProvider: () -> MainState,
        onRecognizedInput: suspend (RecognizedVoiceInput) -> Unit,
        voiceInputStartBlocker: suspend () -> String? = { null },
    )

    suspend fun startRecording(
        scope: CoroutineScope,
        isListening: Boolean,
        routingIntent: VoiceInputRoutingIntent,
    )
    suspend fun stopRecording(isListening: Boolean)
}

enum class VoiceInputRoutingIntent {
    NEW_REQUEST,
    ACTIVE_RUN_CONTINUATION,
}

data class RecognizedVoiceInput(
    val text: String,
    val routingIntent: VoiceInputRoutingIntent,
)

internal fun MainState.voiceInputRoutingIntent(): VoiceInputRoutingIntent =
    if (isProcessing && supportsActiveRunInput) {
        VoiceInputRoutingIntent.ACTIVE_RUN_CONTINUATION
    } else {
        VoiceInputRoutingIntent.NEW_REQUEST
    }

object NoopVoiceInputController : VoiceInputController {
    override val outputs: Flow<MainUseCaseOutput> = emptyFlow()

    override suspend fun initialize(
        scope: CoroutineScope,
        stateProvider: () -> MainState,
        onRecognizedInput: suspend (RecognizedVoiceInput) -> Unit,
        voiceInputStartBlocker: suspend () -> String?,
    ) = Unit

    override suspend fun startRecording(
        scope: CoroutineScope,
        isListening: Boolean,
        routingIntent: VoiceInputRoutingIntent,
    ) = Unit
    override suspend fun stopRecording(isListening: Boolean) = Unit
}
