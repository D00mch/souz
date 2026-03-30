package ru.souz.mcp

import com.fasterxml.jackson.databind.JsonNode
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.slf4j.LoggerFactory
import ru.souz.llms.giga.gigaJsonMapper
import java.util.concurrent.atomic.AtomicLong

private const val JSON_RPC_VERSION = "2.0"
private const val MCP_PROTOCOL_VERSION = "2025-06-18"
private const val MCP_PROTOCOL_HEADER = "MCP-Protocol-Version"
private const val MCP_SESSION_HEADER = "MCP-Session-Id"

class McpHttpSession(
    private val config: McpServerConfig,
) : McpSession {
    private val l = LoggerFactory.getLogger(McpHttpSession::class.java)
    private val requestSeq = AtomicLong(1L)
    private val ioMutex = Mutex()
    private val initMutex = Mutex()
    private val toolsApi = McpToolProtocolApi(
        initializeIfNeeded = { initializeIfNeeded() },
        request = { method, params -> request(method, params) },
    )
    private val endpointUrl = config.url?.trim().orEmpty().also {
        require(it.isNotBlank()) { "MCP HTTP server ${config.name} has empty url" }
    }

    private val httpClient = HttpClient(CIO) {
        expectSuccess = false
        install(HttpTimeout) {
            val timeoutMillis = config.timeoutMillis
            requestTimeoutMillis = timeoutMillis
            connectTimeoutMillis = timeoutMillis
            socketTimeoutMillis = timeoutMillis
        }
    }
    private val oauthManager = McpOAuthManager(config, httpClient)

    private var initialized = false
    private var sessionId: String? = null
    private var negotiatedProtocolVersion: String = MCP_PROTOCOL_VERSION

    private data class HttpResult(
        val statusCode: Int,
        val contentType: String?,
        val wwwAuthenticate: String?,
        val body: String,
    )

    override suspend fun listTools(): List<McpRemoteTool> {
        return toolsApi.listTools()
    }

    override suspend fun callTool(
        toolName: String,
        arguments: Map<String, Any>,
    ): McpToolCallResult {
        return toolsApi.callTool(
            toolName = toolName,
            arguments = arguments,
        )
    }

    private suspend fun initializeIfNeeded() {
        initMutex.withLock {
            if (initialized) return
            if (config.oauth.enabled) {
                val preflightNeeded = oauthManager.shouldAttemptAuthorization() ||
                    oauthManager.discoverOAuthConfiguration()
                if (preflightNeeded) {
                    oauthManager.accessTokenOrLogin(forceRefresh = false)
                }
            }
            val initializeResult = request(
                method = "initialize",
                params = mapOf(
                    "protocolVersion" to MCP_PROTOCOL_VERSION,
                    "capabilities" to emptyMap<String, Any>(),
                    "clientInfo" to mapOf(
                        "name" to "souz",
                        "version" to "1.0.0",
                    ),
                ),
            )
            val protocol = initializeResult.path("protocolVersion").asText("").trim()
            if (protocol.isNotBlank()) {
                negotiatedProtocolVersion = protocol
            }
            notify("notifications/initialized", emptyMap<String, Any>())
            initialized = true
        }
    }

    private suspend fun request(
        method: String,
        params: Any,
    ): JsonNode = ioMutex.withLock {
        val id = requestSeq.getAndIncrement()
        val payload = linkedMapOf(
            "jsonrpc" to JSON_RPC_VERSION,
            "id" to id,
            "method" to method,
            "params" to params,
        )
        val first = send(payload, includeAuth = true)
        val result = when {
            first.statusCode == 401 && config.oauth.enabled -> {
                oauthManager.invalidateAccessToken()
                val second = send(payload, includeAuth = true, forceAuthRefresh = true, wwwAuthenticate = first.wwwAuthenticate)
                parseResponse(second, id)
            }

            else -> parseResponse(first, id)
        }
        result
    }

    private suspend fun notify(
        method: String,
        params: Any,
    ) = ioMutex.withLock {
        val payload = linkedMapOf(
            "jsonrpc" to JSON_RPC_VERSION,
            "method" to method,
            "params" to params,
        )
        val first = send(payload, includeAuth = true)
        if (first.statusCode == 401 && config.oauth.enabled) {
            oauthManager.invalidateAccessToken()
            val second = send(payload, includeAuth = true, forceAuthRefresh = true, wwwAuthenticate = first.wwwAuthenticate)
            ensureHttpSuccess(second)
            return@withLock
        }
        ensureHttpSuccess(first)
    }

    private suspend fun send(
        payload: Any,
        includeAuth: Boolean,
        forceAuthRefresh: Boolean = false,
        wwwAuthenticate: String? = null,
    ): HttpResult {
        val shouldAuthorize = when {
            !includeAuth -> false
            forceAuthRefresh -> true
            else -> oauthManager.shouldAttemptAuthorization()
        }
        val token = when {
            shouldAuthorize -> oauthManager.accessTokenOrLogin(
                forceRefresh = forceAuthRefresh,
                wwwAuthenticate = wwwAuthenticate,
            )
            else -> null
        }

        val response = httpClient.post(endpointUrl) {
            header(HttpHeaders.ContentType, ContentType.Application.Json.toString())
            header(HttpHeaders.Accept, "${ContentType.Application.Json}, text/event-stream")
            header(MCP_PROTOCOL_HEADER, negotiatedProtocolVersion)
            sessionId?.takeIf { it.isNotBlank() }?.let { header(MCP_SESSION_HEADER, it) }
            config.headers.forEach { (name, value) ->
                header(name, value)
            }
            token?.takeIf { it.isNotBlank() }?.let { header(HttpHeaders.Authorization, "Bearer $it") }
            setBody(gigaJsonMapper.writeValueAsString(payload))
        }

        response.headers[MCP_SESSION_HEADER]
            ?.trim()
            ?.ifBlank { null }
            ?.let { sessionId = it }

        val body = response.bodyAsText()
        return HttpResult(
            statusCode = response.status.value,
            contentType = response.headers[HttpHeaders.ContentType],
            wwwAuthenticate = response.headers[HttpHeaders.WWWAuthenticate],
            body = body,
        )
    }

    private fun ensureHttpSuccess(httpResult: HttpResult) {
        if (httpResult.statusCode in 200..299) return
        throw IllegalStateException(
            "MCP HTTP request failed (${httpResult.statusCode}) for ${config.name}: ${httpResult.body.take(500)}",
        )
    }

    private fun parseResponse(
        httpResult: HttpResult,
        requestId: Long,
    ): JsonNode {
        if (httpResult.statusCode == 401) {
            throw IllegalStateException(
                "MCP HTTP request unauthorized for ${config.name}. " +
                    "WWW-Authenticate: ${httpResult.wwwAuthenticate.orEmpty()}",
            )
        }
        ensureHttpSuccess(httpResult)

        val body = httpResult.body.trim()
        if (body.isBlank()) {
            throw IllegalStateException("MCP HTTP response is empty for ${config.name}")
        }

        val nodes = if (httpResult.contentType.orEmpty().lowercase().contains("text/event-stream")) {
            parseSseEvents(body)
        } else {
            parseJsonBody(body)
        }

        for (node in nodes) {
            val idNode = node.path("id")
            if (!idNode.isMissingNode && !idNode.isNull && idsMatch(idNode, requestId)) {
                val errorNode = node.path("error")
                if (!errorNode.isMissingNode && !errorNode.isNull) {
                    val message = errorNode.path("message").asText(errorNode.toString())
                    throw IllegalStateException("MCP request failed ($requestId): $message")
                }
                val resultNode = node.path("result")
                return if (!resultNode.isMissingNode && !resultNode.isNull) resultNode else node
            }
        }

        val first = nodes.firstOrNull()
        if (first != null) {
            val resultNode = first.path("result")
            if (!resultNode.isMissingNode && !resultNode.isNull) return resultNode
            if (
                first.has("tools") ||
                first.has("content") ||
                first.has("structuredContent") ||
                first.has("isError")
            ) {
                return first
            }
        }

        throw IllegalStateException(
            "MCP HTTP response for ${config.name} does not contain result for request id $requestId",
        )
    }

    private fun parseJsonBody(body: String): List<JsonNode> {
        val root = runCatching { gigaJsonMapper.readTree(body) }.getOrElse { e ->
            throw IllegalStateException("Invalid MCP JSON response for ${config.name}: ${e.message}")
        }
        if (root.isArray) return root.toList()
        return listOf(root)
    }

    private fun parseSseEvents(body: String): List<JsonNode> {
        val nodes = ArrayList<JsonNode>()
        val dataLines = ArrayList<String>()

        fun flushEvent() {
            if (dataLines.isEmpty()) return
            val payload = dataLines.joinToString("\n").trim()
            dataLines.clear()
            if (payload.isBlank() || payload == "[DONE]") return
            runCatching { gigaJsonMapper.readTree(payload) }
                .onSuccess { node -> nodes.add(node) }
                .onFailure { e -> l.debug("Skipping non-JSON SSE event from {}: {}", config.name, e.message) }
        }

        body.lineSequence().forEach { rawLine ->
            val line = rawLine.trimEnd('\r')
            if (line.isBlank()) {
                flushEvent()
                return@forEach
            }
            if (line.startsWith("data:")) {
                dataLines.add(line.substringAfter("data:").trimStart())
            }
        }
        flushEvent()
        return nodes
    }

    private fun idsMatch(idNode: JsonNode, requestId: Long): Boolean {
        return when {
            idNode.isNumber -> idNode.asLong() == requestId
            idNode.isTextual -> idNode.asText() == requestId.toString()
            else -> false
        }
    }

    override fun close() {
        runCatching { httpClient.close() }
    }
}
