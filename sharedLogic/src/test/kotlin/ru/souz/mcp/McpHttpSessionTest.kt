package ru.souz.mcp

import com.fasterxml.jackson.databind.JsonNode
import io.modelcontextprotocol.kotlin.sdk.types.McpException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import ru.souz.llms.restJsonMapper
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@Timeout(15)
class McpHttpSessionTest {
    @Test
    fun `SDK initialization and pagination preserve supported schemas and borrow the HTTP client`() = runBlocking {
        val schema = json("""{"type":"object","properties":{"value":{"anyOf":[{"type":"integer"},{"type":"null"}]},"options":{"type":"object","additionalProperties":false}},"required":["value"],"${'$'}defs":{"value":{"type":"integer"}}}""")
        val server = McpTestServer { request ->
            if (request.path("params").path("cursor").asText() == "next") {
                json("""{"tools":[{"name":"second","inputSchema":{"type":"object"}}]}""")
            } else {
                restJsonMapper.createObjectNode().apply {
                    put("nextCursor", "next")
                    putArray("tools").addObject().apply {
                        put("name", "first")
                        put("description", "First tool")
                        set<JsonNode>("inputSchema", schema)
                        set<JsonNode>("outputSchema", schema)
                    }
                }
            }
        }
        server.client.use { http ->
            val session = McpHttpSession.connect(http, URL)
            try {
                val tools = session.listTools()
                assertEquals(listOf("first", "second"), tools.map { it.name })
                assertEquals(schema, tools.first().inputSchema)
                assertEquals(schema, tools.first().outputSchema)
            } finally {
                session.close()
            }
            McpHttpSession.connect(http, URL).close()
            assertEquals(2, server.recorded().count { it.path("method").asText() == "initialize" })
            assertEquals(2, server.recorded().count { it.path("method").asText() == "tools/list" })
        }
    }

    @Test
    fun `SDK returns structured content images and tool failures without replaying protocol failures`() = runBlocking {
        val server = McpTestServer { request ->
            val failure = request.path("params").path("name").asText() == "tool_error"
            json("""{"content":[{"type":"text","text":"Result"},{"type":"image","data":"AA==","mimeType":"image/png"}],"structuredContent":{"count":2},"isError":$failure,"_meta":{"example/source":"fixture"}}""")
        }
        server.client.use { http ->
            val session = McpHttpSession.connect(http, URL)
            try {
                val result = session.callTool("success", mapOf("nested" to mapOf("values" to listOf(1, null, true))))
                assertFalse(result.path("isError").asBoolean())
                assertEquals(2, result.path("structuredContent").path("count").asInt())
                assertEquals("image/png", result.path("content")[1].path("mimeType").asText())
                assertEquals("fixture", result.path("_meta").path("example/source").asText())
                assertTrue(session.callTool("tool_error", emptyMap()).path("isError").asBoolean())
                assertFailsWith<McpException> { session.callTool("protocol_error", emptyMap()) }
                assertEquals(1, server.recorded().count {
                    it.path("params").path("name").asText() == "protocol_error"
                })
                assertEquals(json("""{"nested":{"values":[1,null,true]}}"""), server.recorded().first {
                    it.path("params").path("name").asText() == "success"
                }.path("params").path("arguments"))
            } finally {
                session.close()
            }
        }
    }

    @Test
    fun `timeout and caller cancellation propagate instead of becoming tool results`() = runBlocking {
        val secondStarted = CompletableDeferred<Unit>()
        val server = McpTestServer { request ->
            if (request.path("params").path("name").asText() == "wait_again") secondStarted.complete(Unit)
            awaitCancellation()
        }
        server.client.use { http ->
            val session = McpHttpSession.connect(http, URL, timeoutMillis = 2_000)
            try {
                assertFailsWith<McpTimeoutException> { session.callTool("wait", emptyMap()) }
                val call = async { session.callTool("wait_again", emptyMap()) }
                secondStarted.await()
                call.cancelAndJoin()
                assertTrue(call.isCancelled)
            } finally {
                session.close()
            }
        }
    }

    @Test
    fun `a repeating pagination cursor fails instead of returning an incomplete catalogue`() = runBlocking {
        val server = McpTestServer { json("""{"tools":[],"nextCursor":"same"}""") }
        server.client.use { http ->
            val session = McpHttpSession.connect(http, URL)
            try {
                assertFailsWith<IllegalStateException> { session.listTools() }
                assertEquals(2, server.recorded().count { it.path("method").asText() == "tools/list" })
            } finally {
                session.close()
            }
        }
    }

    companion object {
        private const val URL = "https://mcp.example.test/mcp"
        private fun json(value: String): JsonNode = restJsonMapper.readTree(value)
    }
}
