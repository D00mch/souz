package ru.souz.ui.main.usecases

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow

interface VoiceInputController {
    val outputs: Flow<MainUseCaseOutput>

    suspend fun initialize(
        scope: CoroutineScope,
        onRecognizedInput: suspend (RecognizedVoiceInput) -> Unit,
        voiceInputStartBlocker: suspend () -> String? = { null },
    )

    suspend fun startRecording(scope: CoroutineScope)
    suspend fun stopRecording()
}

sealed interface VoiceInputRoute {
    data object NewRequest : VoiceInputRoute
    data class ActiveRunContinuation(val requestId: Long) : VoiceInputRoute
}

data class RecognizedVoiceInput(
    val text: String,
    val route: VoiceInputRoute,
)

object NoopVoiceInputController : VoiceInputController {
    override val outputs: Flow<MainUseCaseOutput> = emptyFlow()

    override suspend fun initialize(
        scope: CoroutineScope,
        onRecognizedInput: suspend (RecognizedVoiceInput) -> Unit,
        voiceInputStartBlocker: suspend () -> String?,
    ) = Unit

    override suspend fun startRecording(scope: CoroutineScope) = Unit
    override suspend fun stopRecording() = Unit
}
