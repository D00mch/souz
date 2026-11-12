package ru.souz.mcp

import com.fasterxml.jackson.databind.JsonNode
import io.ktor.client.HttpClient
import io.ktor.client.plugins.timeout
import io.ktor.http.HttpMethod
import io.modelcontextprotocol.kotlin.sdk.client.Client
import io.modelcontextprotocol.kotlin.sdk.client.StreamableHttpClientTransport
import io.modelcontextprotocol.kotlin.sdk.shared.Transport
import io.modelcontextprotocol.kotlin.sdk.shared.TransportSendOptions
import io.modelcontextprotocol.kotlin.sdk.types.CallToolResult
import io.modelcontextprotocol.kotlin.sdk.types.Implementation
import io.modelcontextprotocol.kotlin.sdk.types.InitializeResult
import io.modelcontextprotocol.kotlin.sdk.types.JSONRPCMessage
import io.modelcontextprotocol.kotlin.sdk.types.JSONRPCRequest
import io.modelcontextprotocol.kotlin.sdk.types.JSONRPCResponse
import io.modelcontextprotocol.kotlin.sdk.types.ListToolsRequest
import io.modelcontextprotocol.kotlin.sdk.types.McpJson
import io.modelcontextprotocol.kotlin.sdk.types.PaginatedRequestParams
import io.modelcontextprotocol.kotlin.sdk.types.RequestId
import io.modelcontextprotocol.kotlin.sdk.types.ToolSchema
import kotlin.time.Duration.Companion.milliseconds
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
    internal val retainedBytes: Long = (name.length + description.length) * 2L +
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
    private val closing = Mutex()
    private var closed = false

    suspend fun listTools(
        maxTools: Int = McpLimits.TOOLS,
        maxBytes: Long = McpLimits.CATALOG_BYTES,
    ): List<McpToolDefinition> = bounded(timeoutMillis) {
        val definitions = linkedMapOf<String, McpToolDefinition>()
        val cursors = mutableSetOf<String>()
        var cursor: String? = null
        var retainedBytes = 0L
        do {
            check(cursors.size < McpLimits.LIST_PAGES) { "MCP tool catalogue exceeds the page limit." }
            val page = client.listTools(ListToolsRequest(cursor?.let { PaginatedRequestParams(cursor = it) }))
            for ((name, inputSchema, rawDescription, outputSchema) in page.tools) {
                require(name.isNotBlank()) { "MCP tool name must not be blank." }
                require(
                    name.toByteArray(Charsets.UTF_8).size <= McpLimits.NAME_BYTES && name.none(Char::isISOControl)
                ) { "MCP tool name exceeds the size limit or contains controls." }
                val description = rawDescription.orEmpty()
                require(
                    description.length <= McpLimits.DESCRIPTION_CHARS &&
                        restJsonMapper.writeValueAsBytes(description).size <= McpLimits.DESCRIPTION_JSON_BYTES
                ) {
                    "MCP tool description exceeds the size limit."
                }
                check(name !in definitions) { "MCP tool catalogue contains duplicate names." }
                val definition = McpToolDefinition(
                    name = name,
                    description = description,
                    inputSchema = schemaJson(inputSchema),
                    outputSchema = outputSchema?.let(::schemaJson),
                )
                retainedBytes += definition.retainedBytes
                check(retainedBytes <= maxBytes) { "MCP catalogue exceeds the byte limit." }
                definitions[name] = definition
                check(definitions.size <= maxTools) { "MCP tool catalogue exceeds the tool limit." }
            }
            cursor = page.nextCursor
            check(cursor == null || cursors.add(cursor)) { "MCP tool catalogue repeats a pagination cursor." }
        } while (cursor != null)
        definitions.values.toList()
    }

    /** Tool-level isError is data; protocol and transport failures remain exceptions. */
    suspend fun callTool(name: String, arguments: Map<String, Any?>): String = bounded(timeoutMillis) {
        val result = client.callTool(name, arguments)
        McpJson.encodeToString(CallToolResult.serializer(), result)
    }

    suspend fun close() = withContext(NonCancellable) {
        closing.withLock {
            if (closed) return@withLock
            closed = true
            try {
                closeStep("terminate_session", McpLimits.CONTROL_TIMEOUT_MS) { transport.terminateSession() }
            } finally {
                closeStep("close_client", McpLimits.LOCAL_CLOSE_TIMEOUT_MS) { client.close() }
            }
        }
    }

    private suspend fun closeStep(operation: String, timeoutMillis: Long, block: suspend () -> Unit) {
        try {
            val completed = withTimeoutOrNull(timeoutMillis.milliseconds) {
                block()
                true
            } ?: false
            if (!completed) logger.warn("MCP cleanup timed out operation={} timeoutMs={}", operation, timeoutMillis)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            logger.warn("MCP cleanup failed operation={} failure={}", operation, failure.javaClass.simpleName)
        }
    }

    companion object {
        private val logger = LoggerFactory.getLogger(McpHttpSession::class.java)

        suspend fun connect(
            httpClient: HttpClient,
            url: String,
            timeoutMillis: Long = McpLimits.REQUEST_TIMEOUT_MS,
        ): McpHttpSession {
            require(timeoutMillis > 0) { "MCP request timeout must be positive." }
            val deadline = timeoutMillis.coerceAtMost(McpLimits.REQUEST_TIMEOUT_MS)
            val client = Client(Implementation(name = "souz", version = "1.0"))
            val transport = StreamableHttpClientTransport(
                httpClient,
                url,
                maxInlineSseEventSize = McpLimits.RESPONSE_BYTES.toInt(),
                requestBuilder = {
                    val control = method == HttpMethod.Delete ||
                        headers["Mcp-Method"]?.startsWith("notifications/") == true
                    val requestDeadline = if (control) minOf(deadline, McpLimits.CONTROL_TIMEOUT_MS) else deadline
                    timeout {
                        requestTimeoutMillis = requestDeadline
                        connectTimeoutMillis = minOf(requestDeadline, McpLimits.CONNECT_TIMEOUT_MS)
                        socketTimeoutMillis = requestDeadline
                    }
                },
            )
            // SDK 0.16.0 does not copy the negotiated version to its HTTP transport.
            // Apply it before Client.connect resumes and sends notifications/initialized.
            val negotiatingTransport = object : Transport by transport {
                private var initializeId: RequestId? = null

                override suspend fun send(message: JSONRPCMessage, options: TransportSendOptions?) {
                    if (message is JSONRPCRequest && message.method == "initialize") initializeId = message.id
                    transport.send(message, options)
                }

                override fun onMessage(block: suspend (JSONRPCMessage) -> Unit) {
                    transport.onMessage { message ->
                        if (message is JSONRPCResponse && message.id == initializeId) {
                            (message.result as? InitializeResult)?.let { transport.protocolVersion = it.protocolVersion }
                            initializeId = null
                        }
                        block(message)
                    }
                }
            }
            val session = McpHttpSession(client, transport, deadline)
            var connected = false
            try {
                bounded(deadline) { client.connect(negotiatingTransport) }
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
    withTimeoutOrNull(timeoutMillis.milliseconds) { block() } ?: throw McpTimeoutException()
