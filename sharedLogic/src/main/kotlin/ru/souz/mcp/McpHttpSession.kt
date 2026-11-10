package ru.souz.mcp

import com.fasterxml.jackson.databind.JsonNode
import io.ktor.client.HttpClient
import io.ktor.client.plugins.timeout
import io.ktor.http.HttpMethod
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
) {
    internal val retainedBytes: Long
        get() = (name.length + description.length) * 2L +
            inputSchema.toString().toByteArray(Charsets.UTF_8).size +
            (outputSchema?.toString()?.toByteArray(Charsets.UTF_8)?.size ?: 0)
}

class McpTimeoutException : Exception("MCP request timed out; the remote outcome may be unknown.")

/** One execution's MCP session. The host owns the borrowed HTTP client. */
class McpHttpSession private constructor(
    private val client: Client,
    private val transport: StreamableHttpClientTransport,
    private val timeoutMillis: Long,
) {
    private val listing = Mutex()
    private val closing = Mutex()
    private var closed = false

    suspend fun listTools(): List<McpToolDefinition> = listing.withLock {
        bounded(timeoutMillis) {
            val definitions = linkedMapOf<String, McpToolDefinition>()
            val cursors = mutableSetOf<String>()
            var cursor: String? = null
            var retainedBytes = 0L
            do {
                check(cursors.size < 100) { "MCP tool catalogue exceeds the page limit." }
                val page = client.listTools(ListToolsRequest(cursor?.let { PaginatedRequestParams(cursor = it) }))
                for (tool in page.tools) {
                    val name = tool.name
                    require(name.isNotBlank()) { "MCP tool name must not be blank." }
                    require(name.toByteArray(Charsets.UTF_8).size <= McpLimits.NAME_BYTES &&
                        name.none(Char::isISOControl)) { "MCP tool name exceeds the size limit or contains controls." }
                    require(tool.description.orEmpty().length <= McpLimits.DESCRIPTION_CHARS &&
                        restJsonMapper.writeValueAsBytes(tool.description.orEmpty()).size <= 4_000) {
                        "MCP tool description exceeds the size limit."
                    }
                    check(name !in definitions) { "MCP tool catalogue contains duplicate names." }
                    val definition = McpToolDefinition(name, tool.description.orEmpty(),
                        schemaJson(tool.inputSchema), tool.outputSchema?.let(::schemaJson))
                    retainedBytes += definition.retainedBytes
                    check(retainedBytes <= McpLimits.CATALOG_BYTES) { "MCP catalogue exceeds the byte limit." }
                    definitions[name] = definition
                    check(definitions.size <= McpLimits.TOOLS) { "MCP tool catalogue exceeds the tool limit." }
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
        closing.withLock {
            if (closed) return@withLock
            closed = true
            try {
                closeStep(McpLimits.CONTROL_TIMEOUT_MS) { transport.terminateSession() }
            } finally {
                closeStep(McpLimits.LOCAL_CLOSE_TIMEOUT_MS) { client.close() }
            }
        }
    }

    companion object {
        suspend fun connect(httpClient: HttpClient, url: String, timeoutMillis: Long = 60_000): McpHttpSession {
            require(timeoutMillis > 0) { "MCP request timeout must be positive." }
            val deadline = timeoutMillis.coerceAtMost(McpLimits.REQUEST_TIMEOUT_MS)
            val client = Client(Implementation(name = "souz", version = "1.0"))
            val transport = StreamableHttpClientTransport(httpClient, url,
                maxInlineSseEventSize = McpLimits.RESPONSE_BYTES.toInt(),
                requestBuilder = {
                    val control = method == HttpMethod.Delete ||
                        headers["Mcp-Method"]?.startsWith("notifications/") == true
                    val requestDeadline = if (control) minOf(deadline, McpLimits.CONTROL_TIMEOUT_MS) else deadline
                    timeout {
                        requestTimeoutMillis = requestDeadline
                        connectTimeoutMillis = minOf(requestDeadline, 5_000)
                        socketTimeoutMillis = requestDeadline
                    }
                })
            val session = McpHttpSession(client, transport, deadline)
            var connected = false
            try {
                bounded(deadline) { client.connect(transport) }
                connected = true
                return session
            } finally {
                if (!connected) session.close()
            }
        }
    }
}

private fun schemaJson(schema: ToolSchema): JsonNode {
    val json = McpJson.encodeToString(ToolSchema.serializer(), schema)
    require(json.toByteArray(Charsets.UTF_8).size <= McpLimits.SCHEMA_BYTES) { "MCP schema exceeds the byte limit." }
    return restJsonMapper.readTree(json)
}

private suspend fun <T : Any> bounded(timeoutMillis: Long, block: suspend () -> T): T =
    withTimeoutOrNull(timeoutMillis) { block() } ?: throw McpTimeoutException()

private suspend fun closeStep(timeoutMillis: Long, block: suspend () -> Unit) {
    try {
        withTimeoutOrNull(timeoutMillis) { block() }
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (failure: Exception) {
        LoggerFactory.getLogger(McpHttpSession::class.java).warn("MCP cleanup failed failure={}",
            failure.javaClass.simpleName)
    }
}
