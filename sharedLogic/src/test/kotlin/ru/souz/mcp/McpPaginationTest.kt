package ru.souz.mcp

import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertSame
import kotlin.test.assertTrue

@Timeout(15)
class McpPaginationTest {
    @Test
    fun `oversized cursors are rejected before requesting another page or publishing tools`() = runBlocking {
        val cursors = listOf(
            "x".repeat(900_000),
            "x".repeat(McpLimits.CURSOR_BYTES + 1),
            "я".repeat(McpLimits.CURSOR_BYTES / 2) + "x",
        )
        for (cursor in cursors) {
            val server = McpTestServer { mcpTools(1).put("nextCursor", cursor) }
            server.withCatalog("server") { catalog ->
                val failure = assertFailsWith<IllegalStateException> { catalog.load("server") }
                assertEquals("MCP pagination cursor exceeds the byte limit.", failure.message)
                assertEquals(1, server.recorded("tools/list").size)
                assertTrue(catalog.loadedTools.isEmpty())
                assertTrue(catalog.catalog.toolsByCategory.values.all { it.isEmpty() })
            }
        }
    }

    @Test
    fun `empty pages exhaust the cumulative cursor budget and preserve the previous snapshot`() = runBlocking {
        var page = 0
        var paginating = false
        val server = McpTestServer {
            if (paginating) mcpTools(0).put("nextCursor", fullCursor(++page)) else mcpTools(1)
        }
        server.withCatalog("first", "second") { catalog ->
            catalog.load("first")
            val snapshot = catalog.catalog
            val hints = catalog.loadedTools
            paginating = true
            val failure = assertFailsWith<IllegalStateException> { catalog.load("second") }
            assertEquals("MCP pagination cursors exceed the cumulative byte limit.", failure.message)
            assertEquals(McpLimits.TOTAL_CURSOR_BYTES / McpLimits.CURSOR_BYTES + 1, page)
            assertEquals(page + 1, server.recorded("tools/list").size)
            assertSame(snapshot, catalog.catalog)
            assertSame(hints, catalog.loadedTools)
        }
    }

    @Test
    fun `cursors at both UTF-8 byte limits round trip unchanged and allow a terminal page`() = runBlocking {
        val cursors = (1..McpLimits.TOTAL_CURSOR_BYTES / McpLimits.CURSOR_BYTES).map(::fullCursor)
        var page = 0
        val server = McpTestServer { request ->
            assertEquals(cursors.getOrNull(page - 1), request.path("params").path("cursor").textValue())
            if (page < cursors.size) mcpTools(0).put("nextCursor", cursors[page++]) else mcpTools(1)
        }
        server.withCatalog("server") { catalog ->
            assertEquals(1, catalog.load("server").size)
            assertEquals(cursors.size + 1, server.recorded("tools/list").size)
            assertEquals(1, catalog.loadedTools.size)
        }
    }

    private fun fullCursor(page: Int): String =
        "я".repeat((McpLimits.CURSOR_BYTES - 2) / 2) + page.toString().padStart(2, '0')
}
