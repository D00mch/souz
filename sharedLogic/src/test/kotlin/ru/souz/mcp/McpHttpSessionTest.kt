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
        server.withSession { session ->
            val tools = session.listTools()
            assertEquals(listOf("first", "second"), tools.map { it.name })
            assertEquals(schema, tools.first().inputSchema)
            assertEquals(schema, tools.first().outputSchema)
            session.close()
            McpHttpSession.connect(server.client, MCP_TEST_URL).close()
            assertEquals(2, server.recorded("initialize").size)
            assertEquals(2, server.recorded("tools/list").size)
        }
    }

    @Test
    fun `SDK returns structured content images and tool failures without replaying protocol failures`() = runBlocking {
        val server = McpTestServer { request ->
            val failure = request.path("params").path("name").asText() == "tool_error"
            json("""{"content":[{"type":"text","text":"Result"},{"type":"image","data":"AA==","mimeType":"image/png"}],"structuredContent":{"count":2},"isError":$failure,"_meta":{"example/source":"fixture"}}""")
        }
        server.withSession { session ->
            val result = json(session.callTool("success", mapOf("nested" to mapOf("values" to listOf(1, null, true)))))
            assertFalse(result.path("isError").asBoolean())
            assertEquals(2, result.path("structuredContent").path("count").asInt())
            assertEquals("image/png", result.path("content")[1].path("mimeType").asText())
            assertEquals("fixture", result.path("_meta").path("example/source").asText())
            assertTrue(json(session.callTool("tool_error", emptyMap())).path("isError").asBoolean())
            assertFailsWith<McpException> { session.callTool("protocol_error", emptyMap()) }
            assertEquals(1, server.recorded().count {
                it.path("params").path("name").asText() == "protocol_error"
            })
            assertEquals(json("""{"nested":{"values":[1,null,true]}}"""), server.recorded().first {
                it.path("params").path("name").asText() == "success"
            }.path("params").path("arguments"))
        }
    }

    @Test
    fun `timeout and caller cancellation propagate instead of becoming tool results`() = runBlocking {
        val secondStarted = CompletableDeferred<Unit>()
        val server = McpTestServer { request ->
            if (request.path("params").path("name").asText() == "wait_again") secondStarted.complete(Unit)
            awaitCancellation()
        }
        server.withSession(timeoutMillis = 2_000) { session ->
            assertFailsWith<McpTimeoutException> { session.callTool("wait", emptyMap()) }
            val call = async { session.callTool("wait_again", emptyMap()) }
            secondStarted.await()
            call.cancelAndJoin()
            assertTrue(call.isCancelled)
        }
    }

    @Test
    fun `cancellation terminates the session and a stalled termination cannot hold local cleanup`() = runBlocking {
        val started = CompletableDeferred<Unit>()
        val terminating = CompletableDeferred<Unit>()
        val terminationCancelled = CompletableDeferred<Unit>()
        val server = McpTestServer(terminate = {
            terminating.complete(Unit)
            try {
                awaitCancellation()
            } finally {
                terminationCancelled.complete(Unit)
            }
        }) {
            started.complete(Unit)
            awaitCancellation()
        }
        server.client.use { http ->
            val session = McpHttpSession.connect(http, MCP_TEST_URL)
            val call = async {
                try {
                    session.callTool("wait", emptyMap())
                } finally {
                    session.close()
                }
            }
            started.await()
            call.cancelAndJoin()
            assertTrue(call.isCancelled)
            assertTrue(terminating.isCompleted)
            assertTrue(terminationCancelled.isCompleted)
            // A closed session must not close the host-owned client.
            McpHttpSession.connect(server.client, MCP_TEST_URL).close()
        }
    }

    @Test
    fun `a repeating pagination cursor fails instead of returning an incomplete catalogue`() = runBlocking {
        val server = McpTestServer { json("""{"tools":[],"nextCursor":"same"}""") }
        server.withSession { session ->
            assertFailsWith<IllegalStateException> { session.listTools() }
            assertEquals(2, server.recorded("tools/list").size)
        }
    }

    companion object {
        private fun json(value: String): JsonNode = restJsonMapper.readTree(value)
    }
}
