package ru.souz.backend.memory.hindsight

import com.fasterxml.jackson.databind.JsonNode
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import kotlinx.coroutines.CancellationException
import org.slf4j.LoggerFactory
import ru.souz.memory.CompletedTurnMemoryInput
import ru.souz.memory.ConversationMemoryRuntime
import ru.souz.memory.MemoryContext
import ru.souz.memory.MemoryOwnerId
import ru.souz.memory.MemoryPromptFact
import ru.souz.memory.MemoryRetrievalRequest
import ru.souz.memory.MemoryRetrievalResult
import ru.souz.memory.MemorySearchPolicy

/**
 * Bridges Souz's [ConversationMemoryRuntime] port to a self-hosted Hindsight instance
 * (https://hindsight.vectorize.io) via its standalone REST API. One bank per Souz user
 * (`ownerId`, sourced from the trusted `ToolInvocationMeta` upstream) keeps users isolated.
 * Hindsight creates a bank on first recall/retain, so no explicit bank-creation call is made.
 */
private const val TOKENS_PER_FACT_BUDGET = 200

class HindsightConversationMemoryRuntime(
    private val httpClient: HttpClient,
    private val baseUrl: String,
    private val apiToken: String,
) : ConversationMemoryRuntime {
    private val l = LoggerFactory.getLogger(HindsightConversationMemoryRuntime::class.java)

    override suspend fun retrieveMemory(request: MemoryRetrievalRequest): MemoryRetrievalResult {
        val bankId = bankIdFor(request.context.ownerId)
        val maxFacts = request.maxFacts ?: MemorySearchPolicy.DEFAULT_MAX_FACTS
        return try {
            val response = httpClient.post("$baseUrl/v1/default/banks/$bankId/memories/recall") {
                authenticated()
                setBody(
                    mapOf(
                        "query" to request.query,
                        "max_tokens" to (request.maxPromptTokens ?: recallTokenBudget(maxFacts)),
                    )
                )
            }.requireSuccess().body<JsonNode>()
            val items = response.path("results").take(maxFacts)
            val block = items.mapNotNull { it.memoryText() }.joinToString("\n") { "- $it" }
            MemoryRetrievalResult(
                renderedPromptBlock = block.takeIf { it.isNotBlank() },
                facts = items.mapIndexed { index, item -> item.toPromptFact(index) },
            )
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (e: Exception) {
            // Auto per-turn recall is best-effort: a failure must not abort the turn.
            l.warn("Hindsight recall failed for bank {}: {}", bankId, e.message)
            MemoryRetrievalResult(renderedPromptBlock = null)
        }
    }

    override suspend fun searchMemory(
        context: MemoryContext,
        semanticQuery: String,
        lexicalHints: List<String>,
        maxFacts: Int,
    ): List<ConversationMemoryRuntime.SearchFact> {
        val bankId = bankIdFor(context.ownerId)
        try {
            val query = (listOf(semanticQuery) + lexicalHints).joinToString(" ")
            val response = httpClient.post("$baseUrl/v1/default/banks/$bankId/memories/recall") {
                authenticated()
                setBody(mapOf("query" to query, "max_tokens" to recallTokenBudget(maxFacts)))
            }.requireSuccess().body<JsonNode>()
            return response.path("results").take(maxFacts).mapIndexed { index, item ->
                ConversationMemoryRuntime.SearchFact(
                    factId = item.path("id").asText("hindsight-$index"),
                    scope = "hindsight",
                    kind = "memory",
                    title = item.memoryText()?.take(80).orEmpty(),
                    body = item.memoryText().orEmpty(),
                    score = item.finalScore(),
                )
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (e: Exception) {
            // Explicit SearchMemory tool call: let the failure reach ToolSearchMemory's
            // `search_failed` result instead of masking an outage as "no matches".
            l.warn("Hindsight search failed for bank {}: {}", bankId, e.message)
            throw e
        }
    }

    override suspend fun captureCompletedTurn(input: CompletedTurnMemoryInput) {
        val bankId = bankIdFor(input.context.ownerId)
        try {
            val content = buildString {
                append("User: ").append(input.userMessage).append('\n')
                append("Assistant: ").append(input.assistantMessage)
                input.evidence.forEach { evidence ->
                    append('\n').append("[${evidence.kind}] ").append(evidence.text)
                }
            }
            val tags = listOfNotNull(input.conversationId?.let { "chat:$it" })
            httpClient.post("$baseUrl/v1/default/banks/$bankId/memories") {
                authenticated()
                setBody(
                    mapOf(
                        "items" to listOf(mapOf("content" to content, "tags" to tags)),
                        "async" to true,
                    )
                )
            }.requireSuccess()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (e: Exception) {
            l.warn("Hindsight retain failed for bank {}: {}", bankId, e.message)
        }
    }

    private fun HttpRequestBuilder.authenticated() {
        header(HttpHeaders.Authorization, "Bearer $apiToken")
        contentType(ContentType.Application.Json)
    }

    /**
     * `ProviderHttpClients.standard` doesn't enable Ktor's `expectSuccess`, so a non-2xx response
     * completes normally instead of throwing — check explicitly, or a failed retain/recall would
     * be silently treated as an empty success.
     */
    private suspend fun HttpResponse.requireSuccess(): HttpResponse {
        if (!status.isSuccess()) error("Hindsight returned $status: ${bodyAsText()}")
        return this
    }

    /**
     * Hindsight's recall endpoint has no result-count parameter, only a token budget — bound it
     * to roughly what [maxFacts] facts need so we don't fetch/parse more than we'll ever keep.
     */
    private fun recallTokenBudget(maxFacts: Int): Int = maxFacts * TOKENS_PER_FACT_BUDGET

    private fun bankIdFor(ownerId: MemoryOwnerId): String {
        val sanitized = ownerId.value.filter { it.isLetterOrDigit() || it == '-' }
        val safe = sanitized.ifEmpty { ownerId.value.hashCode().toUInt().toString(16) }
        return "souz-$safe"
    }

    private fun JsonNode.memoryText(): String? =
        listOf("content", "text", "summary")
            .firstNotNullOfOrNull { field -> path(field).takeIf { it.isTextual }?.asText() }

    private fun JsonNode.finalScore(): Float =
        path("scores").path("final").takeIf { it.isNumber }?.floatValue() ?: 0f

    private fun JsonNode.toPromptFact(index: Int): MemoryPromptFact = MemoryPromptFact(
        factId = path("id").asText("hindsight-$index"),
        scope = "hindsight",
        score = finalScore(),
    )
}
