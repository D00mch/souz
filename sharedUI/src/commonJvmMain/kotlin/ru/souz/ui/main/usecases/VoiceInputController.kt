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
        onRecognizedText: suspend (String) -> Unit,
        voiceInputStartBlocker: suspend () -> String? = { null },
    )

    suspend fun startRecording(scope: CoroutineScope, isListening: Boolean)
    suspend fun stopRecording(isListening: Boolean)
}

object NoopVoiceInputController : VoiceInputController {
    override val outputs: Flow<MainUseCaseOutput> = emptyFlow()

    override suspend fun initialize(
        scope: CoroutineScope,
        stateProvider: () -> MainState,
        onRecognizedText: suspend (String) -> Unit,
        voiceInputStartBlocker: suspend () -> String?,
    ) = Unit

    override suspend fun startRecording(scope: CoroutineScope, isListening: Boolean) = Unit
    override suspend fun stopRecording(isListening: Boolean) = Unit
}
