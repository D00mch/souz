package ru.souz.agent.scenarios

import ru.souz.agent.AgentExecutionResult
import ru.souz.agent.runtime.AgentRuntimeEvent
import ru.souz.agent.runtime.AgentRuntimeEventSink
import ru.souz.llms.LLMChatAPI
import ru.souz.llms.LLMMessageRole
import ru.souz.llms.LLMRequest
import ru.souz.llms.LLMResponse
import ru.souz.tool.skills.ToolGetSkillByName
import ru.souz.tool.skills.ToolInvokeSkill
import kotlin.time.Duration
import kotlin.time.TimeSource

private const val MAX_MODEL_CALLS_PER_TURN = 24

/** The kernel makes model calls and emits events sequentially within one execution. */
internal class ScenarioTrace(
    private val reasoningEffort: String?,
    private val wire: ScenarioWireCapture,
    private val initialHistory: List<LLMRequest.Message>,
    private val assertions: ScenarioAssertions,
) : AgentRuntimeEventSink {
    val calls = mutableListOf<ScenarioCall>()
    val prefixes = mutableListOf<ScenarioPrefix>()
    var seededHistory: ScenarioHistory? = null
        private set
    private val toolUses = mutableListOf<ScenarioToolUse>()
    private var lastResponse: LLMResponse.Chat? = null
    private var turnError: String? = null
    var hasTurnErrors = false
        private set
    private var turn = 0
    private var callsAtTurnStart = 0
    private var currentConversationRequest: Pair<ScenarioWireRequest, ScenarioCall>? = null
    private var previousConversationRequest: Pair<ScenarioWireRequest, ScenarioCall>? = null
    private var currentAnswer: String? = null
    private var previousAnswer: String? = null
    val startedTurns: Int get() = turn

    fun toolsOnTurn(number: Int): List<ScenarioToolUse> = toolUses.filter { it.turn == number }

    fun startTurn() {
        previousConversationRequest = currentConversationRequest
        currentConversationRequest = null
        previousAnswer = currentAnswer
        currentAnswer = null
        lastResponse = null
        turnError = null
        callsAtTurnStart = calls.size
        turn++
    }

    fun recordTurnError(detail: String) {
        turnError = detail
        hasTurnErrors = true
    }

    fun instrument(api: LLMChatAPI): LLMChatAPI = object : LLMChatAPI by api {
        override suspend fun message(body: LLMRequest.Chat): LLMResponse.Chat {
            check(calls.size - callsAtTurnStart < MAX_MODEL_CALLS_PER_TURN) {
                "Turn $turn exceeded $MAX_MODEL_CALLS_PER_TURN model calls without completing."
            }
            val request = body.copy(reasoningEffort = reasoningEffort)
            wire.reset()
            val started = TimeSource.Monotonic.markNow()
            var response: LLMResponse.Chat? = null
            try {
                return api.message(request).also {
                    response = it
                    lastResponse = it
                }
            } finally {
                recordCall(request, response, started.elapsedNow())
            }
        }
    }

    private fun recordCall(request: LLMRequest.Chat, response: LLMResponse.Chat?, duration: Duration) {
        val kind = if (request.functions.isEmpty()) "auxiliary" else "conversation"
        val ok = response as? LLMResponse.Chat.Ok
        val reported = wire.usage
        val call = ScenarioCall(
            number = calls.size + 1,
            turn = turn,
            kind = kind,
            responseModel = ok?.model,
            durationMs = duration.inWholeMilliseconds,
            status = if (ok != null) "ok" else "error",
            providerStatus = (response as? LLMResponse.Chat.Error)?.status,
            inputTokens = reported?.input,
            outputTokens = reported?.output,
            cachedInputTokens = reported?.cached,
        )
        calls += call
        if (kind == "conversation") {
            wire.request?.let { sent ->
                if (currentConversationRequest == null) {
                    if (turn == 1 && initialHistory.isNotEmpty()) seededHistory = sent.checkSeededHistory(initialHistory)
                    previousConversationRequest?.let { (previous, lastCall) ->
                        prefixes += previous.compareTo(sent, turn).copy(
                            previousCall = lastCall.number,
                            currentCall = call.number,
                            previousCachedInputTokens = lastCall.cachedInputTokens,
                            currentCachedInputTokens = call.cachedInputTokens,
                            history = previous.checkHistory(sent, previousAnswer),
                        )
                    }
                }
                currentConversationRequest = sent to call
            }
        }
    }

    override suspend fun emit(event: AgentRuntimeEvent) {
        if (event is AgentRuntimeEvent.ToolCallStarted) {
            val skillId = when (event.name) {
                ToolGetSkillByName.NAME, ToolInvokeSkill.NAME -> event.arguments["skillId"] as? String
                else -> null
            }
            toolUses += ScenarioToolUse(turn, event.name, skillId)
        }
    }

    fun assertCompleted(result: AgentExecutionResult?): String? {
        val response = lastResponse as? LLMResponse.Chat.Ok
        val choices = response?.choices.orEmpty()
        val output = result?.output
        val completed = choices.isNotEmpty() && choices.all {
            it.message.functionCall == null && it.finishReason == LLMResponse.FinishReason.stop
        }
        val outputMatches = !output.isNullOrBlank() && choices.any { it.message.content.trim() == output.trim() }
        val providerFailures = calls.subList(callsAtTurnStart, calls.size).filter { it.status == "error" }
            .map { "call ${it.number} (${it.providerStatus ?: "no response"})" }
        assertions.check(providerFailures.isEmpty(), "Turn $turn provider failures: $providerFailures")
        assertions.check(result != null && response != null, "Turn $turn: ${turnError ?: "No successful final model response."}")
        assertions.check(completed, "Turn $turn ended without a completed assistant response.")
        assertions.check(!output.isNullOrBlank(), "Turn $turn returned no final answer.")
        assertions.check(outputMatches, "Turn $turn output must come from the final model response.")
        currentAnswer = output.takeIf { completed && outputMatches }
        return currentAnswer
    }

    fun assertHistoryPreserved(turns: Int) {
        if (initialHistory.isNotEmpty()) {
            val history = seededHistory
            assertions.check(history != null && history.matched == history.expected,
                if (history == null) "The first conversational request was not captured."
                else "The first conversational request preserved ${history.matched} of ${history.expected} seeded messages " +
                    "(first missing role: ${history.firstMissingRole}).")
        }
        val byTurn = prefixes.associateBy { it.turn }
        for (number in 2..turns) {
            val history = byTurn[number]?.history
            assertions.check(history != null && history.matched == history.expected,
                if (history == null) "Turn $number has no recorded history comparison."
                else "Turn $number preserved ${history.matched} of ${history.expected} earlier messages " +
                    "(first missing role: ${history.firstMissingRole}).")
        }
    }
}

internal data class ScenarioToolUse(val turn: Int, val name: String, val skillId: String?)
