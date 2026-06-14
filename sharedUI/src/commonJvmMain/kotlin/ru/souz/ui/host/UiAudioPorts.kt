package ru.souz.ui.host

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

interface UiAudioRecorder {
    val audioFlow: Flow<ByteArray>
    val recordingState: StateFlow<UiAudioRecordingState>

    suspend fun logState(): Nothing
    fun start(): Boolean
    fun stop()
}

sealed interface UiAudioRecordingState {
    data object Starting : UiAudioRecordingState
    data object Recording : UiAudioRecordingState
    data object Stopping : UiAudioRecordingState
    data object Idle : UiAudioRecordingState
    data class Error(val message: String) : UiAudioRecordingState
}

interface UiSpeechPlayer {
    val isSpeaking: StateFlow<Boolean>

    fun queue(text: String, speed: Int? = null)
    fun clearQueue()
    fun playTextRand(speed: Int, vararg texts: String)
    fun playMacPing()
    fun playMacPingMsg()
    fun chooseVoice()
}

object NoopUiAudioRecorder : UiAudioRecorder {
    override val audioFlow: Flow<ByteArray> = emptyFlow()
    override val recordingState: StateFlow<UiAudioRecordingState> = MutableStateFlow(UiAudioRecordingState.Idle)

    override suspend fun logState(): Nothing {
        error("Audio recording is unavailable on this host")
    }

    override fun start(): Boolean = false
    override fun stop() = Unit
}

object NoopUiSpeechPlayer : UiSpeechPlayer {
    override val isSpeaking: StateFlow<Boolean> = MutableStateFlow(false)

    override fun queue(text: String, speed: Int?) = Unit
    override fun clearQueue() = Unit
    override fun playTextRand(speed: Int, vararg texts: String) = Unit
    override fun playMacPing() = Unit
    override fun playMacPingMsg() = Unit
    override fun chooseVoice() = Unit
}
