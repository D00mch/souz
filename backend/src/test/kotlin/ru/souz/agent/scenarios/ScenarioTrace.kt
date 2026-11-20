package ru.souz.agent.scenarios

import ru.souz.agent.AgentExecutionResult
import ru.souz.agent.runtime.AgentRuntimeEvent
import ru.souz.agent.runtime.AgentRuntimeEventSink
import ru.souz.llms.LLMChatAPI
import ru.souz.llms.LLMMessageRole
import ru.souz.llms.LLMRequest
import ru.souz.llms.LLMResponse
import ru.souz.llms.plus
import ru.souz.llms.restJsonMapper
import ru.souz.tool.skills.ToolGetSkillByName
import ru.souz.tool.skills.ToolInvokeSkill
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.TimeSource

data class ScenarioResult(
    val execution: AgentExecutionResult,
    val requests: List<LLMRequest.Chat>,
    val events: List<AgentRuntimeEvent>,
    val metrics: ScenarioMetrics,
)

data class ScenarioMetrics(
    val model: String,
    val reasoningEffort: String?,
    val modelCalls: Int,
    val usage: LLMResponse.Usage,
    val duration: Duration,
    val toolCalls: List<AgentRuntimeEvent.ToolCallStarted>,
) {
    override fun toString(): String =
        "model=$model; reasoningEffort=${reasoningEffort ?: "provider-default"}; " +
            "modelCalls=$modelCalls; durationMs=${duration.inWholeMilliseconds}; tokens=$usage; " +
            "tools=${restJsonMapper.writeValueAsString(toolCalls.map { mapOf("name" to it.name, "arguments" to it.arguments) })}"
}

/** The kernel makes model calls and emits events sequentially within one execution. */
internal class ScenarioTrace(
    private val modelId: String,
    private val reasoningEffort: String?,
    private val wire: ScenarioWireCapture? = null,
    private val normalizeDate: Boolean = true,
    private val maxModelCalls: Int = 24,
    private val initialHistory: List<LLMRequest.Message> = emptyList(),
    private val assertions: ScenarioAssertions? = null,
) : AgentRuntimeEventSink {
    val requests = mutableListOf<LLMRequest.Chat>()
    val events = mutableListOf<AgentRuntimeEvent>()
    val calls = mutableListOf<ScenarioCall>()
    val prefixes = mutableListOf<ScenarioPrefix>()
    var seededHistory: ScenarioHistory? = null
        private set
    private val toolUses = mutableListOf<ScenarioToolUse>()
    private var usage = LLMResponse.Usage(0, 0, 0, 0)
    private var lastResponse: LLMResponse.Chat? = null
    private var turnError: String? = null
    var hasTurnErrors = false
        private set
    val continuesAfterFailure: Boolean get() = assertions != null
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
        callsAtTurnStart = requests.size
        turn++
    }

    fun recordTurnError(detail: String) {
        turnError = detail
        hasTurnErrors = true
    }

    fun instrument(api: LLMChatAPI): LLMChatAPI = object : LLMChatAPI by api {
        override suspend fun message(body: LLMRequest.Chat): LLMResponse.Chat {
            check(requests.size - callsAtTurnStart < maxModelCalls) {
                "Turn $turn exceeded $maxModelCalls model calls without completing."
            }
            val request = body.copy(
                reasoningEffort = reasoningEffort,
                messages = if (normalizeDate) body.messages.map { it.withScenarioDate() } else body.messages,
            )
            requests += request
            wire?.reset()
            val started = TimeSource.Monotonic.markNow()
            var response: LLMResponse.Chat? = null
            try {
                return api.message(request).also {
                    response = it
                    lastResponse = it
                    if (it is LLMResponse.Chat.Ok) usage += it.usage
                }
            } finally {
                recordCall(request, response, started.elapsedNow())
            }
        }
    }

    private fun recordCall(request: LLMRequest.Chat, response: LLMResponse.Chat?, duration: Duration) {
        val kind = if (request.functions.isEmpty()) "auxiliary" else "conversation"
        val ok = response as? LLMResponse.Chat.Ok
        val reported = wire?.usage
        val call = ScenarioCall(
            number = requests.size,
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
            wire?.request?.let { sent ->
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
        events += event
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
        assertCheck(providerFailures.isEmpty(), "Turn $turn provider failures: $providerFailures")
        assertCheck(result != null && response != null, "Turn $turn: ${turnError ?: "No successful final model response."}")
        assertCheck(completed, "Turn $turn ended without a completed assistant response.")
        assertCheck(!output.isNullOrBlank(), "Turn $turn returned no final answer.")
        assertCheck(outputMatches, "Turn $turn output must come from the final model response.")
        currentAnswer = output.takeIf { completed && outputMatches }
        return currentAnswer
    }

    fun assertHistoryPreserved(turns: Int) {
        if (initialHistory.isNotEmpty()) {
            val history = seededHistory
            assertCheck(history != null && history.matched == history.expected,
                if (history == null) "The first conversational request was not captured."
                else "The first conversational request preserved ${history.matched} of ${history.expected} seeded messages " +
                    "(first missing role: ${history.firstMissingRole}).")
        }
        val byTurn = prefixes.associateBy { it.turn }
        for (number in 2..turns) {
            val history = byTurn[number]?.history
            assertCheck(history != null && history.matched == history.expected,
                if (history == null) "Turn $number has no recorded history comparison."
                else "Turn $number preserved ${history.matched} of ${history.expected} earlier messages " +
                    "(first missing role: ${history.firstMissingRole}).")
        }
    }

    private fun assertCheck(passed: Boolean, failure: String) {
        if (assertions == null) assertTrue(passed, failure) else assertions.check(passed, failure)
    }

    fun metrics(duration: Duration): ScenarioMetrics = ScenarioMetrics(
        modelId, reasoningEffort, requests.size, usage, duration,
        events.filterIsInstance<AgentRuntimeEvent.ToolCallStarted>(),
    )
}

internal data class ScenarioToolUse(val turn: Int, val name: String, val skillId: String?)

private val HOST_DATE_LINE = Regex("(?m)^- \\[General fact]: Текущие дата и время: .*${'$'}")
internal const val SCENARIO_DATE_LINE = "- [General fact]: Текущие дата и время: понедельник, 2026-06-01 10:00:00"

private fun LLMRequest.Message.withScenarioDate(): LLMRequest.Message =
    if (role == LLMMessageRole.user && content.startsWith("<context>\nBackground information.") && content.endsWith("</context>")) {
        copy(content = HOST_DATE_LINE.replace(content, SCENARIO_DATE_LINE))
    } else this
