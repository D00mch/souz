package ru.souz.agent.scenarios

import com.fasterxml.jackson.databind.JsonNode
import io.ktor.client.plugins.api.createClientPlugin
import io.ktor.client.statement.bodyAsText
import io.ktor.http.isSuccess
import ru.souz.llms.restJsonMapper

/** Observes the OpenAI-compatible adapter's payload and usage without recording their contents. */
internal class ScenarioWireCapture {
    var request: ScenarioWireRequest? = null
        private set
    var usage: ScenarioWireUsage? = null
        private set

    val plugin = createClientPlugin("ScenarioWireCapture") {
        onRequest { _, content ->
            if (content is Map<*, *> && "messages" in content) {
                val json = restJsonMapper.valueToTree<JsonNode>(content)
                request = ScenarioWireRequest(json.path("messages").toList(), json.get("tools"))
            }
        }
        onResponse { response ->
            if (request != null && response.status.isSuccess()) {
                // Ktor's saved response remains available to the provider adapter.
                val text = response.bodyAsText()
                val json = runCatching { restJsonMapper.readTree(text) }.getOrNull()
                usage = json?.path("usage")?.let { node ->
                    ScenarioWireUsage(
                        input = node.number("prompt_tokens") ?: node.number("input_tokens"),
                        output = node.number("completion_tokens") ?: node.number("output_tokens"),
                        cached = node.path("prompt_tokens_details").number("cached_tokens"),
                    )
                }
            }
        }
    }

    fun reset() {
        request = null
        usage = null
    }
}

private fun JsonNode.number(name: String): Int? = get(name)?.takeIf { it.isIntegralNumber }?.intValue()

internal data class ScenarioWireUsage(val input: Int?, val output: Int?, val cached: Int?)

internal data class ScenarioWireRequest(val messages: List<JsonNode>, val tools: JsonNode?) {
    fun compareTo(next: ScenarioWireRequest, turn: Int): ScenarioPrefix {
        val common = messages.zip(next.messages).takeWhile { (before, after) -> before == after }.size
        val firstPrevious = messages.getOrNull(common)
        val firstNext = next.messages.getOrNull(common)
        val previousSystem = firstPrevious?.systemContent()
        val currentSystem = firstNext?.systemContent()
        return ScenarioPrefix(
            turn = turn,
            previousMessages = messages.size,
            currentMessages = next.messages.size,
            unchangedMessages = common,
            firstChangedPreviousRole = firstPrevious?.path("role")?.asText(),
            firstChangedCurrentRole = firstNext?.path("role")?.asText(),
            toolDefinitionsUnchanged = tools == next.tools,
            commonSystemPrefixChars = if (previousSystem != null && currentSystem != null) {
                previousSystem.commonPrefixWith(currentSystem).length
            } else null,
        )
    }

    fun checkHistory(next: ScenarioWireRequest, finalAnswer: String?): ScenarioHistory {
        val expected = messages.filter(JsonNode::isConversationHistory).map(JsonNode::historyIdentity).toMutableList()
        if (finalAnswer != null) {
            expected.add(restJsonMapper.createObjectNode().put("role", "assistant").put("content", finalAnswer.trim()))
        }
        val observed = next.messages.filter(JsonNode::isConversationHistory).map(JsonNode::historyIdentity)
        var matched = 0
        for (message in observed) {
            if (matched < expected.size && expected[matched] == message) matched++
        }
        return ScenarioHistory(
            expected = expected.size,
            matched = matched,
            firstMissingRole = expected.getOrNull(matched)?.path("role")?.asText(),
        )
    }
}

private fun JsonNode.systemContent(): String? =
    if (path("role").asText() == "system") path("content").textValue() else null

private fun JsonNode.isConversationHistory(): Boolean = when (path("role").asText()) {
    "user" -> !path("content").asText().startsWith("<context>\nBackground information.")
    "assistant", "tool", "function" -> true
    else -> false
}

private fun JsonNode.historyIdentity(): JsonNode =
    if (path("role").asText() == "assistant" && !has("tool_calls") && path("content").isTextual) {
        restJsonMapper.createObjectNode().put("role", "assistant").put("content", path("content").asText().trim())
    } else this

internal data class ScenarioHistory(val expected: Int, val matched: Int, val firstMissingRole: String?)

internal data class ScenarioCall(
    val number: Int,
    val turn: Int,
    val kind: String,
    val responseModel: String?,
    val durationMs: Long,
    val status: String,
    val providerStatus: Int?,
    val inputTokens: Int?,
    val outputTokens: Int?,
    val cachedInputTokens: Int?,
)

internal data class ScenarioPrefix(
    val turn: Int,
    val previousMessages: Int,
    val currentMessages: Int,
    val unchangedMessages: Int,
    val firstChangedPreviousRole: String?,
    val firstChangedCurrentRole: String?,
    val toolDefinitionsUnchanged: Boolean,
    val previousCall: Int? = null,
    val currentCall: Int? = null,
    val previousCachedInputTokens: Int? = null,
    val currentCachedInputTokens: Int? = null,
    val history: ScenarioHistory? = null,
    val commonSystemPrefixChars: Int? = null,
)
