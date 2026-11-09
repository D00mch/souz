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
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import ru.souz.llms.restJsonMapper

data class McpToolDefinition(
    val name: String,
    val description: String,
    val inputSchema: JsonNode,
    val outputSchema: JsonNode?,
)

/** One execution's MCP session. The host owns the borrowed HTTP client. */
class McpHttpSession private constructor(
    private val client: Client,
    private val timeoutMillis: Long,
) {
    private val listing = Mutex()

    suspend fun listTools(): List<McpToolDefinition> = listing.withLock {
        withTimeout(timeoutMillis) {
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
    suspend fun callTool(name: String, arguments: Map<String, Any?>): JsonNode = withTimeout(timeoutMillis) {
        val result = client.callTool(name, arguments)
        restJsonMapper.readTree(McpJson.encodeToString(CallToolResult.serializer(), result))
    }

    suspend fun close() = client.close()

    companion object {
        suspend fun connect(httpClient: HttpClient, url: String, timeoutMillis: Long = 60_000): McpHttpSession {
            require(timeoutMillis > 0) { "MCP request timeout must be positive." }
            val client = Client(Implementation(name = "souz", version = "1.0"))
            val transport = StreamableHttpClientTransport(httpClient, url,
                requestBuilder = { timeout { requestTimeoutMillis = timeoutMillis } })
            try {
                withTimeout(timeoutMillis) { client.connect(transport) }
                return McpHttpSession(client, timeoutMillis)
            } catch (failure: Throwable) {
                withContext(NonCancellable) {
                    runCatching { withTimeout(5_000) { client.close() } }.exceptionOrNull()?.let(failure::addSuppressed)
                }
                throw failure
            }
        }
    }
}

private fun schemaJson(schema: ToolSchema): JsonNode =
    restJsonMapper.readTree(McpJson.encodeToString(ToolSchema.serializer(), schema))
