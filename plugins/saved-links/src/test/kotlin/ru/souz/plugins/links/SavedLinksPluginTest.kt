package ru.souz.plugins.links

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.node.JsonNodeFactory
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import ru.souz.plugins.*

class SavedLinksPluginTest {
    @Test fun `normalization and title parsing handle escaped HTML and fragments`() {
        assertEquals("https://example.com/a?x=%2F", normalizeUrl("HTTPS://EXAMPLE.COM:443/a?x=%2F#fragment"))
        assertEquals("A & B", pageTitle("<html><head><title>A &amp; B</title></head></html>"))
        assertEquals("", pageTitle("<body>No title</body>"))
    }

    @Test fun `saving records title or fetch failure but propagates cancellation`() = runTest {
        val records = mutableMapOf<String, JsonNode>()
        val context = object : PluginCallContext {
            override val data = object : PluginData {
                override suspend fun get(key: String) = records[key]
                override suspend fun put(key: String, value: JsonNode) { records[key] = value }
                override suspend fun delete(key: String) = records.remove(key) != null
                override suspend fun list(after: String?, limit: Int) = PluginDataPage(records.map { PluginDataRecord(it.key, it.value) }, null)
            }
            override var http = PluginHttp { PluginHttpResponse(200, emptyMap(), "<title>Example</title>".toByteArray()) }
            override val logger = PluginLogger { }
        }
        val tools = SavedLinksPlugin().tools().associateBy { it.definition.name }
        val args = JsonNodeFactory.instance.objectNode().put("url", "https://example.com")
        val saved = tools.getValue("save").invoke(args, context)
        assertEquals("Example", saved["title"].asText())
        context.http = PluginHttp { throw IllegalStateException("network detail must not leak") }
        val failed = tools.getValue("save").invoke(args, context)
        assertEquals("fetch_failed", failed["fetchStatus"].asText())
        assertEquals(1, records.size)
        context.http = PluginHttp { throw CancellationException("cancelled") }
        assertFailsWith<CancellationException> { tools.getValue("save").invoke(args.put("url", "https://another.example"), context) }
        assertEquals(1, records.size)
        assertTrue(tools.getValue("delete").invoke(JsonNodeFactory.instance.objectNode().put("key", saved["key"].asText()), context)["deleted"].asBoolean())
        assertTrue(records.isEmpty())
    }
}
