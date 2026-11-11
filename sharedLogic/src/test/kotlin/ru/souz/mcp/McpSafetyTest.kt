package ru.souz.mcp

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
        val started = CompletableDeferred<Unit>()
        val cancelling = CompletableDeferred<Unit>()
        val server = McpTestServer(notification = { rpc ->
            if (rpc.path("method").asText() == "notifications/cancelled") {
                cancelling.complete(Unit)
                awaitCancellation()
            }
        }) {
            started.complete(Unit)
            awaitCancellation()
        }
        server.withSession(timeoutMillis = 30_000) { session ->
            val call = async { session.callTool("wait", emptyMap()) }
            withTimeout(2_000) { started.await() }
            withTimeout(1_500) { call.cancelAndJoin() }
            assertTrue(cancelling.isCompleted)
            assertTrue(call.isCancelled)
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
                withTimeout(2_000) { catalog.close() }
                repeat(64) { assertTrue(terminations.tryReceive().isSuccess) }
                assertTrue(catalog.catalog.toolsByCategory.values.all { it.isEmpty() })
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
    fun `execution byte budget spans servers and a failed load retains the previous snapshot`() = runBlocking {
        val server = McpTestServer { request ->
            val page = request.path("params").path("cursor").asText("0").toInt()
            restJsonMapper.createObjectNode().apply {
                if (page < 2) put("nextCursor", (page + 1).toString())
                val tools = putArray("tools")
                repeat(400) { index ->
                    tools.addObject().apply {
                        put("name", "tool${page}_$index")
                        put("description", "x".repeat(1_000))
                        putObject("inputSchema").put("type", "object")
                    }
                }
            }
        }
        server.withCatalog("first", "second") { catalog ->
            assertEquals(1_200, catalog.load("first").size)
            val first = catalog.catalog
            assertFailsWith<IllegalStateException> { catalog.load("second") }
            assertSame(first, catalog.catalog)
            assertEquals(1_200, catalog.loadedTools.size)
        }
    }
}
