package ru.souz.mcp

import com.fasterxml.jackson.databind.JsonNode
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.TextContent
import io.ktor.http.headersOf
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import ru.souz.llms.http.providerHttpClientDefaults
import ru.souz.llms.restJsonMapper

internal class McpTestServer(
    terminate: suspend () -> Unit = {},
    notification: suspend (JsonNode) -> Unit = {},
    reply: suspend (JsonNode) -> JsonNode,
) {
    private val requests = mutableListOf<JsonNode>()
    private val mutex = Mutex()
    val client = HttpClient(MockEngine { request ->
        if (request.method == HttpMethod.Delete) {
            terminate()
            return@MockEngine respond("", HttpStatusCode.OK)
        }
        if (request.method != HttpMethod.Post) return@MockEngine respond("", HttpStatusCode.MethodNotAllowed)
        val rpc = restJsonMapper.readTree((request.body as TextContent).text)
        mutex.withLock { requests.add(rpc) }
        val method = rpc.path("method").asText()
        if (method.startsWith("notifications/")) {
            notification(rpc)
            return@MockEngine respond("", HttpStatusCode.Accepted)
        }
        val response = restJsonMapper.createObjectNode().apply {
            put("jsonrpc", "2.0")
            set<JsonNode>("id", rpc["id"])
            if (rpc.path("params").path("name").asText() == "protocol_error") {
                set<JsonNode>("error", restJsonMapper.readTree("""{"code":-32602,"message":"Unknown tool"}"""))
            } else {
                set<JsonNode>("result", if (method == "initialize") {
                    restJsonMapper.readTree("""{"protocolVersion":"2025-11-25","capabilities":{"tools":{}},"serverInfo":{"name":"fixture","version":"1"}}""")
                } else reply(rpc))
            }
        }
        respond(response.toString(), headers = headersOf("Content-Type" to listOf("application/json"),
            "Mcp-Session-Id" to listOf("fixture")))
    }) { providerHttpClientDefaults() }

    suspend fun recorded(): List<JsonNode> = mutex.withLock { requests.toList() }
}
