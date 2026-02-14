package ru.gigadesk.ui.main.usecases

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch
import ru.gigadesk.audio.Say
import ru.gigadesk.ui.main.MainState

class SpeechUseCase(
    private val say: Say,
) {

    private val _outputs = MutableSharedFlow<MainUseCaseOutput>(replay = 1, extraBufferCapacity = 64)
    val outputs: Flow<MainUseCaseOutput> = _outputs.asSharedFlow()

    fun start(scope: CoroutineScope) {
        scope.launch {
            say.isSpeaking.collect { isSpeaking ->
                emitState {
                    val userAskWithVoice = chatMessages.lastOrNull()?.isVoice == true
                    copy(isSpeaking = isSpeaking && userAskWithVoice)
                }
            }
        }
    }

    fun queue(text: String) {
        if (text.isBlank()) return
        say.queue(text)
    }

    fun queuePrepared(text: String) {
        val prepared = prepareTextForSpeech(text)
        if (prepared.isBlank()) return
        say.queue(prepared)
    }

    fun playMacPing() {
        say.playMacPing()
    }

    fun playInputConfirmation() {
        say.playTextRand(speed = 120, "ok", "okey", "окей", "ок")
    }

    fun clearQueue() {
        say.clearQueue()
    }

    private suspend fun emitState(reduce: MainState.() -> MainState) {
        _outputs.emit(MainUseCaseOutput.State(reduce))
    }

    companion object {
        private const val CODE_BLOCK = "```"

        fun prepareTextForSpeech(text: String): String {
            var result = text.replace(Regex("$CODE_BLOCK[\\s\\S]*?$CODE_BLOCK"), "")
            result = result.replace(Regex("`[^`]+`"), "")
            result = result.replace(Regex("[\"«»„“”]"), "")
            result = result.replace(Regex("[*#]"), "")
            result = result.replace(Regex("\\s+"), " ")
            return result.trim()
        }
    }
}
