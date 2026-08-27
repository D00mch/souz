package ru.souz.llms.openai

import com.fasterxml.jackson.databind.JsonNode
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.plugins.ClientRequestException
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import kotlinx.coroutines.CancellationException
import org.slf4j.LoggerFactory
import ru.souz.llms.LLMMessageRole
import ru.souz.llms.LLMRequest
import ru.souz.llms.LLMResponse
import ru.souz.llms.LlmMessageApi

/**
 * Narrow OpenAI-compatible client pinned to a single fixed model on a single fixed endpoint —
 * message-only, no streaming/tools/embeddings. Used as the optional dedicated LLM for
 * context-window compaction (see `NodesSummarization`), never for general conversation, so it
 * deliberately stays outside the LlmProvider/OpenAICompatibleChatAPI machinery, which is scoped
 * per-conversation-provider and not meant for a fixed background task.
 */
class OpenAICompatibleMessageAPI(
    private val client: HttpClient,
    private val apiKey: String,
    baseUrl: String,
    private val model: String,
    private val reasoningEffort: String? = null,
) : LlmMessageApi {
    private val l = LoggerFactory.getLogger(OpenAICompatibleMessageAPI::class.java)
    private val baseUrl = baseUrl.trimEnd('/')

    override suspend fun message(body: LLMRequest.Chat): LLMResponse.Chat = try {
        val response = client.post("$baseUrl/chat/completions") {
            header(HttpHeaders.Authorization, "Bearer $apiKey")
            contentType(ContentType.Application.Json)
            setBody(buildRequestBody(body))
        }
        if (!response.status.isSuccess()) {
            LLMResponse.Chat.Error(response.status.value, response.bodyAsText())
        } else {
            parseResponse(response.body<JsonNode>())
        }
    } catch (e: ClientRequestException) {
        LLMResponse.Chat.Error(e.response.status.value, e.response.bodyAsText())
    } catch (cancellation: CancellationException) {
        throw cancellation
    } catch (e: Exception) {
        l.error("OpenAI-compatible message call failed", e)
        LLMResponse.Chat.Error(-1, "OpenAI-compatible API error: ${e.message}")
    }

    private fun buildRequestBody(body: LLMRequest.Chat): Map<String, Any?> = buildMap {
        put("model", model)
        put("messages", body.messages.map { it.toOpenAiMessage() })
        reasoningEffort?.let { put("reasoning", mapOf("effort" to it)) }
        put("stream", false)
    }

    private fun LLMRequest.Message.toOpenAiMessage(): Map<String, String> = when (role) {
        LLMMessageRole.system -> mapOf("role" to "system", "content" to content)
        LLMMessageRole.user -> mapOf("role" to "user", "content" to content)
        LLMMessageRole.assistant, LLMMessageRole.function_in_progress ->
            mapOf("role" to "assistant", "content" to content)
        // Compaction is a one-shot text call, never a tool loop — fold tool output into a
        // plain assistant-authored note instead of dealing with tool_call_id bookkeeping.
        LLMMessageRole.function ->
            mapOf("role" to "assistant", "content" to "[${name ?: "tool"}]\n$content")
    }

    private fun parseResponse(node: JsonNode): LLMResponse.Chat {
        val choiceNode = node.path("choices").firstOrNull()
            ?: return LLMResponse.Chat.Error(-1, "No choices in response: $node")
        val usageNode = node.path("usage")
        return LLMResponse.Chat.Ok(
            choices = listOf(
                LLMResponse.Choice(
                    message = LLMResponse.Message(
                        content = choiceNode.path("message").path("content").asText(""),
                        role = LLMMessageRole.assistant,
                        functionsStateId = null,
                    ),
                    index = 0,
                    finishReason = LLMResponse.FinishReason.stop,
                )
            ),
            created = node.path("created").asLong(System.currentTimeMillis() / 1000),
            model = model,
            usage = LLMResponse.Usage(
                promptTokens = usageNode.path("prompt_tokens").asInt(0),
                completionTokens = usageNode.path("completion_tokens").asInt(0),
                totalTokens = usageNode.path("total_tokens").asInt(0),
                precachedTokens = 0,
            ),
        )
    }
}
