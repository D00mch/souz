package ru.souz.agent.scenarios

import com.fasterxml.jackson.annotation.JsonIgnore
import com.fasterxml.jackson.databind.JsonNode
import io.ktor.client.plugins.api.createClientPlugin
import io.ktor.client.statement.bodyAsText
import io.ktor.http.isSuccess
import kotlin.time.TimeSource
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import ru.souz.agent.AgentExecutionResult
import ru.souz.agent.runtime.AgentRuntimeEvent
import ru.souz.agent.runtime.AgentRuntimeEventSink
import ru.souz.llms.LLMChatAPI
import ru.souz.llms.LLMRequest
import ru.souz.llms.LLMResponse
import ru.souz.llms.restJsonMapper
import ru.souz.tool.skills.ToolGetSkillByName
import ru.souz.tool.skills.ToolInvokeSkill

internal class ScenarioTurn(val number: Int, val input: String) {
    var assistantReply: String? = null
    var error: String? = null
    val tools = mutableListOf<ToolUse>()
    val telegram = mutableListOf<String>()
    @get:JsonIgnore val searches = mutableListOf<Search>()
}

internal data class ToolUse(val name: String, val skillId: String?)
internal data class ModelCall(
    val number: Int, val turn: Int, val kind: String, val status: String, val providerStatus: Int?,
    val responseModel: String?, val durationMs: Long, val inputTokens: Int?, val outputTokens: Int?,
    val cachedInputTokens: Int?,
)

internal class ScenarioChecks {
    var total = 0
        private set
    val failures = mutableListOf<String>()
    val passed get() = total - failures.size

    fun check(condition: Boolean, failure: String) {
        total++
        if (!condition) failures += failure
    }
}

/** The adapter sends one request at a time. Only comparisons and counts go into reports. */
internal class ScenarioWire {
    var request: JsonNode? = null
    var usage: JsonNode? = null
    val plugin = createClientPlugin("ScenarioWire") {
        onRequest { _, body ->
            if (body is Map<*, *> && "messages" in body) request = restJsonMapper.valueToTree(body)
        }
        onResponse { response ->
            if (response.status.isSuccess()) {
                val text = response.bodyAsText()
                usage = runCatching { restJsonMapper.readTree(text)?.get("usage") }.getOrNull()
            }
        }
    }
}

internal class ScenarioTrace(
    private val model: ScenarioLiveModel,
    private val fixture: ScenarioFixture,
    private val checks: ScenarioChecks,
) : AgentRuntimeEventSink {
    val calls = mutableListOf<ModelCall>()
    val prefixes = mutableListOf<ScenarioBoundary>()
    var seededHistory: HistoryMatch? = null
        private set
    private var lastRequest: JsonNode? = null
    private var lastConversationCall: ModelCall? = null
    private var finalResponse: LLMResponse.Chat.Ok? = null
    private val turn get() = fixture.current

    fun start(next: ScenarioTurn) {
        fixture.current = next
        finalResponse = null
    }

    val api = object : LLMChatAPI by model.api {
        override suspend fun message(body: LLMRequest.Chat): LLMResponse.Chat {
            check(calls.count { it.turn == turn.number } < 24) { "Turn exceeded its 24-call budget." }
            model.wire.request = null
            model.wire.usage = null
            val started = TimeSource.Monotonic.markNow()
            var response: LLMResponse.Chat? = null
            try {
                return model.api.message(body.copy(reasoningEffort = model.reasoningEffort)).also { response = it }
            } finally {
                val cancelled = !currentCoroutineContext().isActive
                val elapsed = started.elapsedNow().inWholeMilliseconds
                val ok = response as? LLMResponse.Chat.Ok
                val usage = model.wire.usage
                val conversation = body.functions.isNotEmpty()
                val call = ModelCall(
                    calls.size + 1, turn.number, if (conversation) "conversation" else "auxiliary",
                    when {
                        cancelled -> "cancelled"
                        ok == null -> "error"
                        else -> "ok"
                    },
                    (response as? LLMResponse.Chat.Error)?.status, ok?.model, elapsed,
                    usage.count("prompt_tokens") ?: usage.count("input_tokens"),
                    usage.count("completion_tokens") ?: usage.count("output_tokens"),
                    usage?.get("prompt_tokens_details").count("cached_tokens"),
                )
                calls += call
                if (conversation) {
                    finalResponse = ok
                    recordRequest(call, model.wire.request)
                }
            }
        }
    }

    private fun recordRequest(call: ModelCall, sent: JsonNode?) {
        val previous = lastRequest
        val priorCall = lastConversationCall
        if (priorCall == null && call.turn == 1 && sent != null) {
            val seed = fixture.seed.map { message(it.role.name, it.content) }
            seededHistory = matchHistory(seed, sent)
        } else if (priorCall?.turn == call.turn - 1 && previous != null && sent != null) {
            val before = previous.path("messages")
            val after = sent.path("messages")
            val common = before.zip(after).takeWhile { (a, b) -> a == b }.size
            val old = before.get(common)
            val new = after.get(common)
            val answer = fixture.turns[priorCall.turn - 1].assistantReply
            val expected = history(previous) + listOfNotNull(answer?.let { message("assistant", it) })
            prefixes += ScenarioBoundary(
                turn = call.turn, previousCall = priorCall.number, currentCall = call.number,
                previousMessages = before.size(), currentMessages = after.size(), unchangedMessages = common,
                firstChangedPreviousRole = old?.get("role")?.asText(), firstChangedCurrentRole = new?.get("role")?.asText(),
                commonSystemPrefixChars = if (old?.path("role")?.asText() == "system" && new?.path("role")?.asText() == "system")
                    old.path("content").asText().commonPrefixWith(new.path("content").asText()).length else null,
                toolDefinitionsUnchanged = (previous.get("tools") == sent.get("tools")),
                previousCachedInputTokens = priorCall.cachedInputTokens, currentCachedInputTokens = call.cachedInputTokens,
                history = matchHistory(expected, sent),
            )
        }
        lastRequest = sent
        lastConversationCall = call
    }

    override suspend fun emit(event: AgentRuntimeEvent) {
        if (event is AgentRuntimeEvent.ToolCallStarted) {
            val id = when (event.name) {
                ToolGetSkillByName.NAME, ToolInvokeSkill.NAME -> event.arguments["skillId"] as? String
                else -> null
            }
            turn.tools += ToolUse(event.name, id)
        }
    }

    fun finish(result: AgentExecutionResult?) {
        val output = result?.output
        val choices = finalResponse?.choices.orEmpty()
        val completed = choices.isNotEmpty() && choices.all {
            it.finishReason == LLMResponse.FinishReason.stop && it.message.functionCall == null
        }
        val matches = !output.isNullOrBlank() && choices.any { it.message.content.trim() == output.trim() }
        checks.check(calls.none { it.turn == turn.number && it.status == "error" }, "Turn ${turn.number} had a provider error.")
        checks.check(completed && matches, "Turn ${turn.number} did not finish with the model's answer.")
        turn.assistantReply = output.takeIf { completed && matches }
        val match = if (turn.number == 1) seededHistory else
            prefixes.lastOrNull { it.turn == turn.number }?.history
        checks.check(match != null && match.matched == match.expected, "Turn ${turn.number} lost conversation history ($match).")
    }
}

internal data class ScenarioBoundary(
    val turn: Int, val previousCall: Int, val currentCall: Int,
    val previousMessages: Int, val currentMessages: Int, val unchangedMessages: Int,
    val firstChangedPreviousRole: String?, val firstChangedCurrentRole: String?, val commonSystemPrefixChars: Int?,
    val toolDefinitionsUnchanged: Boolean, val previousCachedInputTokens: Int?, val currentCachedInputTokens: Int?,
    val history: HistoryMatch,
)

internal data class HistoryMatch(val expected: Int, val matched: Int, val firstMissingRole: String?)

private fun JsonNode?.count(field: String) = this?.get(field)?.takeIf { it.isIntegralNumber }?.intValue()

private fun message(role: String, text: String): JsonNode = restJsonMapper.createObjectNode().put("role", role).put("content", text.trim())

private fun history(request: JsonNode): List<JsonNode> = request.path("messages").mapNotNull { msg ->
    when (msg.path("role").asText()) {
        "user" -> msg.takeUnless { it.path("content").asText().startsWith("<context>\nBackground information.") }
        "assistant" -> if (!msg.has("tool_calls") && msg.path("content").isTextual) message("assistant", msg.path("content").asText()) else msg
        "tool", "function" -> msg
        else -> null
    }
}

private fun matchHistory(expected: List<JsonNode>, sent: JsonNode): HistoryMatch {
    var matched = 0
    for (observed in history(sent)) if (expected.getOrNull(matched) == observed) matched++
    return HistoryMatch(expected.size, matched, expected.getOrNull(matched)?.path("role")?.asText())
}
