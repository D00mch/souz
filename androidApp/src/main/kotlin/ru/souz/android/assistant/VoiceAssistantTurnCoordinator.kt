package ru.souz.android.assistant

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.slf4j.LoggerFactory
import ru.souz.agent.AgentFacade
import ru.souz.android.voice.AndroidPcmAudioRecorder
import ru.souz.android.voice.CaptureOutcome
import ru.souz.android.voice.CaptureStats
import ru.souz.android.voice.CloudSpeechPlayer
import ru.souz.service.speech.SpeechRecognitionProvider
import ru.souz.ui.main.usecases.SpeechUseCase

sealed interface AssistantTurnState {
    data object Idle : AssistantTurnState
    data object Listening : AssistantTurnState
    data object Recognizing : AssistantTurnState
    data class Thinking(val request: String) : AssistantTurnState
    data class Answered(val request: String, val reply: String) : AssistantTurnState
    data class Failed(val message: String) : AssistantTurnState
}

class VoiceAssistantTurnCoordinator(
    private val recorder: AndroidPcmAudioRecorder,
    private val recognition: SpeechRecognitionProvider,
    private val speechPlayer: CloudSpeechPlayer,
    private val agentFacade: AgentFacade,
    private val scope: CoroutineScope = CoroutineScope(Dispatchers.Default + SupervisorJob()),
) {
    private val l = LoggerFactory.getLogger(VoiceAssistantTurnCoordinator::class.java)

    private val _state = MutableStateFlow<AssistantTurnState>(AssistantTurnState.Idle)
    val state: StateFlow<AssistantTurnState> = _state.asStateFlow()

    val isSpeaking: StateFlow<Boolean> = speechPlayer.isSpeaking

    private var turnJob: Job? = null

    fun startTurn() {
        if (turnJob?.isActive == true) {
            l.info("Turn already in progress, ignoring trigger")
            return
        }
        turnJob = scope.launch { runTurn() }
    }

    fun cancelTurn() {
        turnJob?.cancel()
        recorder.abort()
        speechPlayer.clearQueue()
        _state.value = AssistantTurnState.Idle
    }

    private suspend fun runTurn() {
        if (!recognition.hasRequiredKey) {
            _state.value = AssistantTurnState.Failed("Не задан ключ AiTunnel в настройках")
            return
        }

        speechPlayer.clearQueue()
        agentFacade.cancelActiveJob()
        _state.value = AssistantTurnState.Listening
        speechPlayer.playMacPing()

        val captured = recorder.captureUtterance()
        if (captured.pcm.isEmpty()) {
            _state.value = AssistantTurnState.Failed(captureFailureMessage(captured.stats))
            return
        }

        _state.value = AssistantTurnState.Recognizing
        val request = try {
            recognition.recognize(captured.pcm)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            l.error("Speech recognition failed", e)
            _state.value = AssistantTurnState.Failed("Ошибка распознавания: ${e.message.orEmpty()}")
            return
        }
        if (request.isBlank()) {
            _state.value = AssistantTurnState.Failed("Речь не распознана")
            return
        }

        l.info("Recognized request: {}", request)
        _state.value = AssistantTurnState.Thinking(request)
        val reply = try {
            agentFacade.execute(request)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            l.error("Agent execution failed", e)
            _state.value = AssistantTurnState.Failed("Ошибка агента: ${e.message.orEmpty()}")
            return
        }

        l.info("Agent replied with {} chars", reply.length)
        _state.value = AssistantTurnState.Answered(request, reply)
        speechPlayer.queue(SpeechUseCase.prepareTextForSpeech(reply))
    }
}

internal fun captureFailureMessage(stats: CaptureStats): String = when (stats.outcome) {
    CaptureOutcome.NO_SPEECH ->
        "Звук с микрофона не превысил порог: пик ${stats.peakRms} за ${stats.elapsedMs} мс"

    CaptureOutcome.TOO_SHORT ->
        "Слишком короткая фраза: ${stats.speechMs} мс, пик ${stats.peakRms}"

    CaptureOutcome.ERROR -> stats.error ?: "Ошибка микрофона"
    CaptureOutcome.SPEECH -> "Речь не распознана"
}
