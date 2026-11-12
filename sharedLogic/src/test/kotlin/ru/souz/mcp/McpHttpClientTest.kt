package ru.souz.mcp

import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import java.net.InetAddress
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.runBlocking
import mockwebserver3.Dispatcher
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest
import okhttp3.Dns
import okhttp3.Headers
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import ru.souz.llms.restJsonMapper
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

@Timeout(20)
class McpHttpClientTest {
    @Test
    fun `HTTP 503 with Retry-After zero cannot replay an MCP POST`() = runBlocking {
        MockWebServer().use { server ->
            server.start(InetAddress.getByName("127.0.0.1"), 0)
            server.enqueue(MockResponse(code = 503, headers = Headers.headersOf("Retry-After", "0")))
            server.enqueue(MockResponse(body = "unexpected replay"))
            val url = "http://127.0.0.1:${server.port}/mcp"
            val rpc = """{"jsonrpc":"2.0","id":1,"method":"tools/call","params":{"name":"create_event","arguments":{}}}"""
            createMcpHttpClient(McpNetworkPolicy(setOf(url))).use { http ->
                val response = http.post(url) { setBody(rpc) }
                assertEquals(503, response.status.value)
                response.bodyAsText()
            }
            assertEquals(1, server.requestCount)
            assertEquals(rpc, server.takeRequest().body!!.utf8())
        }
    }

    @Test
    fun `real SDK uses bounded HTTP for inline SSE discovery JSON calls and session termination`() = runBlocking {
        val deleted = CompletableDeferred<Unit>()
        MockWebServer().use { server ->
            server.start(InetAddress.getByName("127.0.0.1"), 0)
            server.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    if (request.method == "DELETE") {
                        deleted.complete(Unit)
                        return MockResponse(code = 200)
                    }
                    if (request.method != "POST") return MockResponse(code = 405)
                    val rpc = restJsonMapper.readTree(request.body!!.utf8())
                    val method = rpc.path("method").asText()
                    if (method.startsWith("notifications/")) return MockResponse(code = 202)
                    val result = when (method) {
                        "initialize" -> """{"protocolVersion":"2025-11-25","capabilities":{"tools":{}},"serverInfo":{"name":"fixture","version":"1"}}"""
                        "tools/list" -> """{"tools":[{"name":"read","inputSchema":{"type":"object"}}]}"""
                        else -> """{"content":[{"type":"text","text":"done"}],"isError":false}"""
                    }
                    val json = """{"jsonrpc":"2.0","id":${rpc["id"]},"result":$result}"""
                    return MockResponse(body = if (method == "tools/list") "data: $json\n\n" else json,
                        headers = Headers.headersOf("Content-Type", if (method == "tools/list") "text/event-stream" else "application/json",
                            "Mcp-Session-Id", "fixture"))
                }
            }
            createMcpHttpClient(McpNetworkPolicy(setOf("http://127.0.0.1:${server.port}/mcp"))).use { http ->
                val url = "http://127.0.0.1:${server.port}/mcp"
                val session = McpHttpSession.connect(http, url)
                try {
                    assertEquals("read", session.listTools().single().name)
                    val result = restJsonMapper.readTree(session.callTool("read", emptyMap()))
                    assertEquals("done", result.path("content")[0].path("text").asText())
                } finally { session.close() }
                assertEquals(true, deleted.isCompleted)
                assertEquals(405, http.get(url).status.value)
            }
        }
    }

    @Test
    fun `real HTTP client blocks literal and DNS private destinations before reaching a socket`() = runBlocking {
        MockWebServer().use { server ->
            server.start(InetAddress.getByName("127.0.0.1"), 0)
            createMcpHttpClient().use { http ->
                assertFailsWith<Exception> { http.get("http://127.0.0.1:${server.port}/mcp").bodyAsText() }
            }
            val policy = McpNetworkPolicy(resolver = Dns { listOf(InetAddress.getByName("127.0.0.1")) })
            createMcpHttpClient(policy).use { http ->
                assertFailsWith<Exception> { http.get("http://mcp.example.test:${server.port}/mcp").bodyAsText() }
            }
            assertEquals(0, server.requestCount)
        }
    }

    @Test
    fun `an operator allowed endpoint cannot redirect requests to another origin`() = runBlocking {
        MockWebServer().use { source ->
            MockWebServer().use { target ->
                source.start(InetAddress.getByName("127.0.0.1"), 0)
                target.start(InetAddress.getByName("127.0.0.1"), 0)
                source.enqueue(MockResponse(code = 302, headers = Headers.headersOf(
                    "Location", "http://127.0.0.1:${target.port}/internal")))
                createMcpHttpClient(McpNetworkPolicy(setOf("http://127.0.0.1:${source.port}/mcp"))).use { http ->
                    val response = http.get("http://127.0.0.1:${source.port}/mcp")
                    assertEquals(302, response.status.value)
                    response.bodyAsText()
                    assertFailsWith<Exception> { http.get("http://127.0.0.1:${source.port}/admin").bodyAsText() }
                    assertFailsWith<Exception> { http.get("http://127.0.0.1:${target.port}/mcp").bodyAsText() }
                }
                assertEquals(1, source.requestCount)
                assertEquals(0, target.requestCount)
            }
        }
    }

    @Test
    fun `wire encoding and known or chunked response limits reject without replay`() = runBlocking {
        MockWebServer().use { server ->
            server.start(InetAddress.getByName("127.0.0.1"), 0)
            val oversized = "x".repeat(McpLimits.RESPONSE_BYTES.toInt() + 1)
            val rejected = listOf(
                MockResponse(body = "{}", headers = Headers.headersOf("Content-Type", "application/json; charset=utf-16")),
                MockResponse(body = "{}", headers = Headers.headersOf("Content-Type", "application/json", "Content-Encoding", "gzip")),
                MockResponse(body = oversized),
                MockResponse.Builder().chunkedBody(oversized, 8_192).build(),
            )
            rejected.forEach(server::enqueue)
            server.enqueue(MockResponse(body = "{}", headers = Headers.headersOf("Content-Type", "application/json; charset=UTF-8")))
            val url = "http://127.0.0.1:${server.port}/mcp"
            createMcpHttpClient(McpNetworkPolicy(setOf(url))).use { http ->
                repeat(rejected.size) { assertFailsWith<Exception> { http.get(url).bodyAsText() } }
                assertEquals("{}", http.get(url).bodyAsText())
            }
            assertEquals(rejected.size + 1, server.requestCount)
        }
    }
}
