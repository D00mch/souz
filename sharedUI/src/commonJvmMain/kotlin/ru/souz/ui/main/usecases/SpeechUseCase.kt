package ru.souz.ui.main.usecases

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.consumeAsFlow
import kotlinx.coroutines.launch
import org.slf4j.LoggerFactory
import ru.souz.ui.host.UiSpeechPlayer
import ru.souz.ui.main.MainState

class SpeechUseCase(
    private val speechPlayer: UiSpeechPlayer,
) {
    private val l = LoggerFactory.getLogger(SpeechUseCase::class.java)

    private val _outputs = Channel<MainUseCaseOutput>()
    val outputs: Flow<MainUseCaseOutput> = _outputs.consumeAsFlow()

    fun start(scope: CoroutineScope) {
        scope.launch {
            speechPlayer.isSpeaking.collect { isSpeaking ->
                emitState {
                    val userAskWithVoice = chatMessages.lastOrNull()?.isVoice == true
                    copy(isSpeaking = isSpeaking && userAskWithVoice)
                }
            }
        }
    }

    fun queue(text: String) {
        if (text.isBlank()) return
        speechPlayer.queue(text)
    }

    fun queuePrepared(text: String) {
        val prepared = prepareTextForSpeech(text)
        if (prepared.isBlank()) return
        speechPlayer.queue(prepared)
    }

    fun playMacPingSafely(scope: CoroutineScope) {
        scope.launch(Dispatchers.IO) {
            runCatching { speechPlayer.playMacPing() }
                .onFailure { l.warn("Failed to play mac ping: {}", it.message) }
        }
    }

    fun playMacPingMsgSafely(scope: CoroutineScope) {
        scope.launch(Dispatchers.IO) {
            runCatching { speechPlayer.playMacPingMsg() }
                .onFailure { l.warn("Failed to play mac ping msg: {}", it.message) }
        }
    }

    fun playInputConfirmation() {
        speechPlayer.playTextRand(speed = 120, "ok", "okey", "окей", "ок")
    }

    fun clearQueue() {
        speechPlayer.clearQueue()
    }

    private suspend fun emitState(reduce: MainState.() -> MainState) {
        _outputs.send(MainUseCaseOutput.State(reduce))
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
