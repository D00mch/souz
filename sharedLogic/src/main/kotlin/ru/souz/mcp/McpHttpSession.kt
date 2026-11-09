package ru.souz.mcp

import com.fasterxml.jackson.databind.JsonNode
import io.ktor.client.HttpClient
import io.ktor.client.plugins.timeout
import io.modelcontextprotocol.kotlin.sdk.client.Client
import io.modelcontextprotocol.kotlin.sdk.client.StreamableHttpClientTransport
import io.modelcontextprotocol.kotlin.sdk.types.CallToolResult
import io.modelcontextprotocol.kotlin.sdk.types.Implementation
import io.modelcontextprotocol.kotlin.sdk.types.ListToolsRequest
import io.modelcontextprotocol.kotlin.sdk.types.McpJson
import io.modelcontextprotocol.kotlin.sdk.types.PaginatedRequestParams
import io.modelcontextprotocol.kotlin.sdk.types.ToolSchema
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.slf4j.LoggerFactory
import ru.souz.llms.restJsonMapper

data class McpToolDefinition(
    val name: String,
    val description: String,
    val inputSchema: JsonNode,
    val outputSchema: JsonNode?,
)

class McpTimeoutException : Exception("MCP request timed out; the remote outcome may be unknown.")

/** One execution's MCP session. The host owns the borrowed HTTP client. */
class McpHttpSession private constructor(
    private val client: Client,
    private val transport: StreamableHttpClientTransport,
    private val timeoutMillis: Long,
) {
    private val listing = Mutex()

    suspend fun listTools(): List<McpToolDefinition> = listing.withLock {
        bounded(timeoutMillis) {
            val definitions = linkedMapOf<String, McpToolDefinition>()
            val cursors = mutableSetOf<String>()
            var cursor: String? = null
            do {
                check(cursors.size < 100) { "MCP tool catalogue exceeds the page limit." }
                val page = client.listTools(ListToolsRequest(cursor?.let { PaginatedRequestParams(cursor = it) }))
                for (tool in page.tools) {
                    val name = tool.name
                    require(name.isNotBlank()) { "MCP tool name must not be blank." }
                    check(name !in definitions) { "MCP tool catalogue contains duplicate names." }
                    definitions[name] = McpToolDefinition(name, tool.description.orEmpty(),
                        schemaJson(tool.inputSchema), tool.outputSchema?.let(::schemaJson))
                    check(definitions.size <= 10_000) { "MCP tool catalogue exceeds the tool limit." }
                }
                cursor = page.nextCursor
                check(cursor == null || cursors.add(cursor)) { "MCP tool catalogue repeats a pagination cursor." }
            } while (cursor != null)
            definitions.values.toList()
        }
    }

    /** Tool-level isError is data; protocol and transport failures remain exceptions. */
    suspend fun callTool(name: String, arguments: Map<String, Any?>): JsonNode = bounded(timeoutMillis) {
        val result = client.callTool(name, arguments)
        restJsonMapper.readTree(McpJson.encodeToString(CallToolResult.serializer(), result))
    }

    suspend fun close() = withContext(NonCancellable) {
        try {
            closeStep { transport.terminateSession() }
        } finally {
            closeStep { client.close() }
        }
    }

    companion object {
        suspend fun connect(httpClient: HttpClient, url: String, timeoutMillis: Long = 60_000): McpHttpSession {
            require(timeoutMillis > 0) { "MCP request timeout must be positive." }
            val client = Client(Implementation(name = "souz", version = "1.0"))
            val transport = StreamableHttpClientTransport(httpClient, url,
                requestBuilder = { timeout { requestTimeoutMillis = timeoutMillis } })
            val session = McpHttpSession(client, transport, timeoutMillis)
            var connected = false
            try {
                bounded(timeoutMillis) { client.connect(transport) }
                connected = true
                return session
            } finally {
                if (!connected) session.close()
            }
        }
    }
}

private fun schemaJson(schema: ToolSchema): JsonNode =
    restJsonMapper.readTree(McpJson.encodeToString(ToolSchema.serializer(), schema))

private suspend fun <T : Any> bounded(timeoutMillis: Long, block: suspend () -> T): T =
    withTimeoutOrNull(timeoutMillis) { block() } ?: throw McpTimeoutException()

private suspend fun closeStep(block: suspend () -> Unit) {
    try {
        withTimeoutOrNull(2_000) { block() }
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (failure: Exception) {
        LoggerFactory.getLogger(McpHttpSession::class.java).warn("MCP cleanup failed failure={}",
            failure.javaClass.simpleName)
    }
}
