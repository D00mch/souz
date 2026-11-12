package ru.souz.mcp

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.node.ObjectNode
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import ru.souz.llms.restJsonMapper
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertSame
import kotlin.test.assertTrue

@Timeout(20)
class McpSafetyTest {
    @Test
    fun `a stalled SDK cancellation notification has its own short deadline`() = runBlocking {
        for (timeout in listOf(2_000L, 30_000L)) {
            val started = Channel<Unit>(2)
            val cancelling = CompletableDeferred<Unit>()
            val server = McpTestServer(notification = { rpc ->
                if (rpc.path("method").asText() == "notifications/cancelled") {
                    cancelling.complete(Unit)
                    awaitCancellation()
                }
            }) {
                started.send(Unit)
                awaitCancellation()
            }
            server.withSession(timeoutMillis = timeout) { session ->
                if (timeout == 2_000L) {
                    assertFailsWith<McpTimeoutException> { session.callTool("wait", emptyMap()) }
                    started.receive()
                }
                val call = async { session.callTool("wait", emptyMap()) }
                withTimeout(2_000) { started.receive() }
                withTimeout(1_500) { call.cancelAndJoin() }
                assertTrue(cancelling.isCompleted)
                assertTrue(call.isCancelled)
            }
        }
    }

    @Test
    fun `preload has an aggregate deadline and timed out servers remain discoverable`() = runBlocking {
        var stalled = true
        val server = McpTestServer {
            if (stalled) awaitCancellation()
            mcpTools(0)
        }
        server.withCatalog("first", "second") { catalog ->
            withTimeout(12_000) { catalog.preload(setOf("first", "second")) }
            assertEquals(1, server.recorded("initialize").size)
            assertEquals(setOf("first", "second"), catalog.servers.keys)
            stalled = false
            assertTrue(catalog.load("second").isEmpty())
        }
    }

    @Test
    fun `64 stalled terminations share one bounded cleanup phase`() = runBlocking {
        val terminations = Channel<Unit>(64)
        val server = McpTestServer(terminate = {
            terminations.send(Unit)
            awaitCancellation()
        }) { mcpTools(0) }
        server.client.use { http ->
            val catalog = McpToolCatalog("owner", (1..64).map { McpServerConfig("server$it", "Server", MCP_TEST_URL) }, http)
            try {
                (1..64).forEach { catalog.load("server$it") }
                val started = CompletableDeferred<Unit>()
                val execution = async {
                    try {
                        started.complete(Unit)
                        awaitCancellation()
                    } finally {
                        catalog.close()
                    }
                }
                started.await()
                withTimeout(2_000) { execution.cancelAndJoin() }
                assertTrue(execution.isCancelled)
                repeat(64) { assertTrue(terminations.tryReceive().isSuccess) }
                assertTrue(catalog.catalog.toolsByCategory.values.all { it.isEmpty() })
                McpHttpSession.connect(http, MCP_TEST_URL).close()
            } finally { catalog.close(); terminations.close() }
        }
    }

    @Test
    fun `oversized names descriptions and schemas never publish a partial catalogue`() = runBlocking {
        val malformed = listOf(
            "name" to "x".repeat(McpLimits.NAME_BYTES + 1),
            "description" to "x".repeat(McpLimits.DESCRIPTION_CHARS + 1),
            "schema" to "x".repeat(McpLimits.SCHEMA_BYTES + 1),
        )
        for ((field, value) in malformed) {
            val server = McpTestServer {
                restJsonMapper.createObjectNode().apply {
                    val tools = putArray("tools")
                    tools.addObject().apply { put("name", "valid"); putObject("inputSchema").put("type", "object") }
                    tools.addObject().apply {
                        put("name", if (field == "name") value else "invalid")
                        put("description", if (field == "description") value else "")
                        putObject("inputSchema").apply {
                            put("type", "object")
                            if (field == "schema") putObject("properties").putObject("value").put("description", value)
                        }
                    }
                }
            }
            server.withCatalog("server") { catalog ->
                assertFailsWith<IllegalArgumentException> { catalog.load("server") }
                assertTrue(catalog.loadedTools.isEmpty())
                assertEquals(setOf("server"), catalog.servers.keys)
            }
        }
    }

    @Test
    fun `invalid pagination never publishes tools`() = runBlocking {
        val replies: List<(JsonNode) -> JsonNode> = listOf(
            { mcpTools(2).apply { (path("tools")[1] as ObjectNode).put("name", "tool0") } },
            { mcpTools(0).put("nextCursor", "same") },
            { rpc -> mcpTools(0).put("nextCursor", (rpc.path("params").path("cursor").asInt() + 1).toString()) },
        )
        for (reply in replies) McpTestServer(reply = reply).withCatalog("server") { catalog ->
            assertFailsWith<IllegalStateException> { catalog.load("server") }
            assertTrue(catalog.loadedTools.isEmpty())
        }
    }

    @Test
    fun `execution budgets span servers and failed loads retain the previous snapshot`() = runBlocking {
        for ((pages, descriptionSize) in listOf(3 to 1_000, 15 to 0)) {
            val server = McpTestServer { request ->
                val page = request.path("params").path("cursor").asInt()
                mcpTools(400).apply {
                    if (page + 1 < pages) put("nextCursor", (page + 1).toString())
                    path("tools").forEachIndexed { index, tool ->
                        (tool as ObjectNode).put("name", "tool${page}_$index")
                        tool.put("description", "x".repeat(descriptionSize))
                    }
                }
            }
            server.withCatalog("first", "second") { catalog ->
                assertEquals(pages * 400, catalog.load("first").size)
                val snapshot = catalog.catalog
                assertFailsWith<IllegalStateException> { catalog.load("second") }
                assertSame(snapshot, catalog.catalog)
                assertEquals(pages * 400, catalog.loadedTools.size)
            }
        }
    }
}
