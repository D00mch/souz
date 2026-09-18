package agent

import com.fasterxml.jackson.databind.JsonNode
import io.ktor.client.HttpClient
import io.ktor.client.plugins.HttpSend
import io.ktor.client.plugins.plugin
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.http.encodeURLPathPart
import io.ktor.http.isSuccess
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import ru.souz.llms.restJsonMapper
import ru.souz.memory.CompletedTurnMemoryInput
import ru.souz.memory.ConversationMemoryRuntime
import ru.souz.memory.MemoryContext
import ru.souz.memory.MemoryRetrievalRequest
import ru.souz.memory.MemoryRetrievalResult

/** Observes the real HTTP boundary, including failures swallowed by the production runtime. */
internal class HindsightHttpTrace(private val client: HttpClient) {
    private val mutex = Mutex()
    private val exchanges = mutableListOf<MemoryHttpExchange>()

    init {
        client.plugin(HttpSend).intercept { request ->
            val path = request.url.build().encodedPath
            val operation = if (path.endsWith("/recall")) "recall"
                else if (path.endsWith("/memories")) "retain"
                else if ("/documents/" in path) "document" else "bank"
            val bankId = path.substringAfter("/banks/").substringBefore('/')
            try {
                val call = execute(request)
                val response = restJsonMapper.readTree(call.response.bodyAsText())
                mutex.withLock {
                    exchanges += MemoryHttpExchange(
                        operation, call.response.status.value,
                        response.takeIf { call.response.status.isSuccess() }, bankId = bankId,
                    )
                }
                call
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                // Exception messages and request URLs can contain credentials; record only the type.
                mutex.withLock { exchanges += MemoryHttpExchange(operation, failure = error.javaClass.simpleName) }
                throw error
            }
        }
    }

    suspend fun snapshot(): List<MemoryHttpExchange> = mutex.withLock { exchanges.toList() }

    suspend fun requireHealthy(since: Int = 0) {
        val failed = snapshot().drop(since).filter {
            it.failure != null || it.status !in 200..299 ||
                (it.operation == "retain" && it.response?.path("success")?.asBoolean() != true) ||
                (it.operation == "recall" && it.response?.path("results")?.isArray != true)
        }
        check(failed.isEmpty()) {
            "Hindsight operations failed: ${failed.map { "${it.operation}:${it.status ?: it.failure}" }}"
        }
    }

    suspend fun verifyDocument(baseUrl: String, token: String?, owner: String, documentId: String) {
        val response = client.get(
            "${baseUrl.trimEnd('/')}/v1/default/banks/${owner.encodeURLPathPart()}/documents/${documentId.encodeURLPathPart()}"
        ) { if (!token.isNullOrBlank()) header(HttpHeaders.Authorization, "Bearer $token") }
        check(response.status.isSuccess()) { "Missing retained document $documentId (HTTP ${response.status.value})" }
        val document = restJsonMapper.readTree(response.bodyAsText())
        check(document.path("id").asText() == documentId) { "Unexpected document identity for $documentId" }
    }

    suspend fun createBank(baseUrl: String, token: String?, owner: String) {
        require(owner.startsWith("souz-memory-test-")) { "Only test banks may be created" }
        val response = client.put("${baseUrl.trimEnd('/')}/v1/default/banks/${owner.encodeURLPathPart()}") {
            if (!token.isNullOrBlank()) header(HttpHeaders.Authorization, "Bearer $token")
            contentType(ContentType.Application.Json)
            setBody(emptyMap<String, String>())
        }
        check(response.status.isSuccess()) { "Could not create test bank (HTTP ${response.status.value})" }
    }
}

internal data class MemoryHttpExchange(
    val operation: String,
    val status: Int? = null,
    val response: JsonNode? = null,
    val failure: String? = null,
    val bankId: String? = null,
)

/** Captures only facts actually returned to the graph/tool, not the entire HTTP candidate set. */
internal class ScenarioMemoryRuntime(
    private val delegate: ConversationMemoryRuntime,
) : ConversationMemoryRuntime {
    var captureEnabled: Boolean = true
    val factIds = mutableSetOf<String>()
    val contextBlocks = mutableListOf<String>()
    val capturedDocuments = mutableListOf<String>()

    fun resetProbe() {
        factIds.clear()
        contextBlocks.clear()
    }

    override suspend fun retrieveMemory(request: MemoryRetrievalRequest): MemoryRetrievalResult =
        delegate.retrieveMemory(request).also { result ->
            factIds += result.facts.map { it.factId }
            result.renderedPromptBlock?.let { contextBlocks += it }
        }

    override suspend fun searchMemory(
        context: MemoryContext,
        semanticQuery: String,
        lexicalHints: List<String>,
        maxFacts: Int,
    ): List<ConversationMemoryRuntime.SearchFact> =
        delegate.searchMemory(context, semanticQuery, lexicalHints, maxFacts).also { facts ->
            factIds += facts.map { it.factId }
            contextBlocks += facts.map { it.body }
        }

    override suspend fun captureCompletedTurn(input: CompletedTurnMemoryInput) {
        if (!captureEnabled) return
        delegate.captureCompletedTurn(input)
        capturedDocuments += "souz-turn-${requireNotNull(input.userMessageId)}"
    }
}

internal fun sourceDocuments(exchanges: List<MemoryHttpExchange>, usedFactIds: Set<String>): Set<String> {
    val facts = exchanges.filter { it.operation == "recall" }.flatMap { exchange ->
        val response = exchange.response ?: return@flatMap emptyList()
        response.path("results").toList() + response.path("source_facts").toList()
    }.associateBy { it.path("id").asText() }
    val visited = mutableSetOf<String>()
    val documents = mutableSetOf<String>()
    fun visit(id: String) {
        if (!visited.add(id)) return
        val fact = facts[id] ?: return
        fact.path("document_id").asText("").takeIf { it.isNotBlank() }?.let(documents::add)
        fact.path("source_fact_ids").forEach { visit(it.asText()) }
    }
    usedFactIds.forEach(::visit)
    return documents
}
