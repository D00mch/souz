package ru.souz.mcp

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.node.ObjectNode
import io.mockk.mockk
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import ru.souz.llms.LLMResponse
import ru.souz.llms.ToolInvocationMeta
import ru.souz.llms.restJsonMapper
import ru.souz.tool.RuntimePassThroughToolsFilter
import ru.souz.tool.skills.ToolGetSkillByName
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue

@Timeout(15)
class McpToolCatalogTest {
    @Test
    fun `loaded tools share discovery schemas and remain scoped to their owner and execution`() = runBlocking {
        val schema = restJsonMapper.readTree("""{"type":"object","properties":{"value":{"anyOf":[{"type":"integer"},{"type":"null"}]},"options":{"type":"object","additionalProperties":false}},"required":["value"],"${'$'}defs":{"value":{"type":"integer"}}}""")
        val server = McpTestServer { request ->
            if (request.path("method").asText() == "tools/list") {
                val next = request.path("params").has("cursor")
                mcpTools(1).apply {
                    if (!next) put("nextCursor", "next")
                    (path("tools")[0] as ObjectNode).apply {
                        put("name", if (next) "protocol_error" else "create")
                        set<JsonNode>("inputSchema", schema)
                        set<JsonNode>("outputSchema", schema)
                    }
                }
            } else {
                val failure = request.path("params").path("arguments").path("fail").asBoolean()
                restJsonMapper.readTree("""{"content":[{"type":"text","text":"created"},{"type":"image","data":"AA==","mimeType":"image/png"}],"structuredContent":{"count":2},"_meta":{"source":"fixture"},"isError":$failure}""")
            }
        }
        server.withCatalog("calendar", "other") { catalog ->
            assertTrue(catalog.catalog.toolsByCategory.values.all { it.isEmpty() })
            val first = catalog.load("calendar").first()
            assertEquals(2, catalog.load("calendar").size)
            val second = catalog.load("other").first()
            assertFalse(first.skillId == second.skillId)
            assertEquals(4, server.recorded("tools/list").size)
            val tool = catalog.catalog.toolsByCategory.values.first { first.skillId in it }.getValue(first.skillId)
            val rejected = tool.invoke(LLMResponse.FunctionCall(first.skillId, emptyMap()), ToolInvocationMeta(userId = "other"))
            assertEquals("mcp_identity_mismatch", restJsonMapper.readTree(rejected.content).path("error").path("code").asText())
            assertEquals("mcp_identity_required", restJsonMapper.readTree(tool.invoke(
                LLMResponse.FunctionCall(first.skillId, emptyMap())).content).path("error").path("code").asText())
            for (fail in listOf(false, true)) {
                val args = mapOf("fail" to fail, "values" to listOf(1, null, true))
                val result = tool.callJson(args)
                assertTrue(result.isObject)
                assertEquals(fail, result.path("isError").asBoolean())
                assertEquals(2, result.path("structuredContent").path("count").asInt())
                assertEquals("fixture", result.path("_meta").path("source").asText())
                assertEquals("image/png", result.path("content")[1].path("mimeType").asText())
                val call = server.recorded("tools/call").last().path("params")
                assertEquals("create", call.path("name").asText())
                assertEquals(restJsonMapper.valueToTree<JsonNode>(args), call.path("arguments"))
            }
            val errorTool = catalog.catalog.toolsByCategory.values.flatMap { it.values }
                .first { it.fn.name == McpToolCatalog.toolId("calendar", "protocol_error") }
            assertEquals("mcp_request_failed", errorTool.callJson().path("error").path("code").asText())
            assertEquals(3, server.recorded("tools/call").size, "Failed actions must not replay")
            val details = ToolGetSkillByName({ catalog.catalog },
                RuntimePassThroughToolsFilter, mockk())
                .invoke(LLMResponse.FunctionCall("GetSkillByName", mapOf("skillId" to first.skillId)), ToolInvocationMeta(userId = "owner"))
            for (field in listOf("inputSchema", "returnSchema")) {
                assertEquals(schema, restJsonMapper.readTree(details.content).path("skill").path(field))
            }
            catalog.close()
            val closedResult = catalog.discoveryTool.invoke(LLMResponse.FunctionCall(McpToolCatalog.LOAD_TOOL_NAME,
                mapOf("serverId" to "calendar")), ToolInvocationMeta(userId = "owner"))
            assertTrue(restJsonMapper.readTree(closedResult.content).has("error"))
            McpHttpSession.connect(server.client, MCP_TEST_URL).close() // The HTTP client belongs to the host.
        }
    }

    @Test
    fun `empty and populated discovery pages reuse their session and close once`() = runBlocking {
        for (count in listOf(0, 23)) {
            var terminations = 0
            val server = McpTestServer(terminate = { terminations++ }) { mcpTools(count) }
            server.withCatalog("calendar") { catalog ->
                val first = catalog.discoveryTool.callJson(mapOf("serverId" to "calendar"))
                assertEquals(minOf(count, 20), first.path("tools").size())
                assertEquals(count, first.path("totalTools").asInt())
                assertEquals(if (count == 0) null else 20, first.path("nextOffset").takeUnless { it.isNull }?.asInt())
                val snapshot = catalog.catalog
                for (offset in listOf(20, Int.MAX_VALUE)) {
                    val page = catalog.discoveryTool.callJson(mapOf("serverId" to "calendar", "offset" to offset, "limit" to 50.0))
                    assertEquals((count - offset).coerceAtLeast(0), page.path("tools").size())
                    assertTrue(page.path("nextOffset").isNull)
                }
                assertSame(snapshot, catalog.catalog)
                assertEquals(count, catalog.loadedTools.size)
                assertEquals("mcp_invalid_arguments", catalog.discoveryTool.callJson(mapOf("serverId" to "calendar", "limit" to 51))
                    .path("error").path("code").asText())
                assertEquals("mcp_server_unavailable", catalog.discoveryTool.callJson(mapOf("serverId" to "missing"))
                    .path("error").path("code").asText())
                assertEquals(1, server.recorded("tools/list").size)
                catalog.close()
            }
            assertEquals(1, terminations)
        }
    }

    @Test
    fun `cancelled listing retains installed tools and can be loaded again`() = runBlocking {
        val started = CompletableDeferred<Unit>()
        var stalled = false
        val terminations = Channel<Unit>(3)
        val server = McpTestServer(terminate = { terminations.send(Unit) }) {
            if (stalled) {
                started.complete(Unit)
                awaitCancellation()
            }
            mcpTools(1)
        }
        server.withCatalog("first", "second") { catalog ->
            val first = catalog.load("first").single()
            val snapshot = catalog.catalog
            stalled = true
            val loading = async { catalog.load("second") }
            started.await()
            loading.cancelAndJoin()
            assertSame(snapshot, catalog.catalog)
            assertEquals(setOf(first.skillId), catalog.loadedTools.keys)
            assertTrue(terminations.tryReceive().isSuccess)
            assertTrue(terminations.tryReceive().isFailure)
            stalled = false
            val second = catalog.load("second").single()
            assertEquals(setOf(first.skillId, second.skillId), catalog.loadedTools.keys)
        }
        repeat(2) { assertTrue(terminations.tryReceive().isSuccess) }
        assertTrue(terminations.tryReceive().isFailure)
        terminations.close()
    }

    @Test
    fun `server identity and tool identity cannot alias through separators`() {
        assertFalse(McpToolCatalog.toolId("a.b", "c") == McpToolCatalog.toolId("a", "b.c"))
        assertEquals(McpToolCatalog.toolId("calendar", "create"), McpToolCatalog.toolId("calendar", "create"))
    }
}
