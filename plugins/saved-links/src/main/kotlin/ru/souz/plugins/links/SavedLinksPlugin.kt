package ru.souz.plugins.links

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.node.JsonNodeFactory
import com.fasterxml.jackson.databind.node.ObjectNode
import java.io.StringReader
import java.net.URI
import java.security.MessageDigest
import javax.swing.text.MutableAttributeSet
import javax.swing.text.html.HTML
import javax.swing.text.html.HTMLEditorKit
import javax.swing.text.html.parser.ParserDelegator
import kotlin.coroutines.cancellation.CancellationException
import ru.souz.plugins.PluginCallContext
import ru.souz.plugins.PluginHttpRequest
import ru.souz.plugins.PluginTool
import ru.souz.plugins.PluginToolDefinition
import ru.souz.plugins.SouzPlugin

class SavedLinksPlugin : SouzPlugin {
    override fun tools(): List<PluginTool> = listOf(
        tool("save", "Save a web link for this user, fetching its page title.", "url" to "string") { args, context ->
            val url = normalizeUrl(args.path("url").asText())
            val record = json.objectNode().put("url", url)
            try {
                val response = context.http.execute(PluginHttpRequest(url))
                if (response.status !in 200..299) {
                    record.put("fetchStatus", "http_${response.status}")
                } else {
                    val title = pageTitle(response.body.toString(Charsets.UTF_8))
                    record.put("title", title.ifBlank { url })
                    record.put("fetchStatus", if (title.isBlank()) "title_missing" else "fetched")
                }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { record.put("fetchStatus", "fetch_failed") }
            if (!record.has("title")) record.put("title", url)
            val key = key(url)
            context.data.put(key, record)
            record.deepCopy().put("key", key)
        },
        tool("list", "List this user's saved links. Pass the returned cursor for the next page.", "after" to "string", "limit" to "integer") { args, context ->
            val page = context.data.list(args.get("after")?.asText(), args.get("limit")?.asInt() ?: 20)
            json.objectNode().apply {
                set<JsonNode>("links", json.arrayNode().apply {
                    page.records.forEach { record -> add(json.objectNode().put("key", record.key).set<JsonNode>("value", record.value)) }
                })
                put("nextCursor", page.nextCursor)
            }
        },
        tool("delete", "Delete one of this user's saved links by its key.", "key" to "string") { args, context ->
            json.objectNode().put("deleted", context.data.delete(args.path("key").asText()))
        },
    )

    private fun tool(
      https://habr.com/ru/articles/1092802/  name: String,
        description: String,
        vararg properties: Pair<String, String>,
        invoke: suspend (ObjectNode, PluginCallContext) -> JsonNode,
    ): PluginTool = object : PluginTool {
        override val definition = PluginToolDefinition(name, description, json.objectNode().apply {
            put("type", "object")
            set<JsonNode>("properties", json.objectNode().apply {
                properties.forEach { (name, type) -> set<JsonNode>(name, json.objectNode().put("type", type)) }
            })
            set<JsonNode>("required", json.arrayNode().apply { if (name != "list") add(properties.first().first) })
        })
        override suspend fun invoke(arguments: ObjectNode, context: PluginCallContext): JsonNode = invoke(arguments, context)
    }
}

private val json = JsonNodeFactory.instance

internal fun normalizeUrl(raw: String): String {
    val uri = URI(raw.trim()).normalize()
    val scheme = uri.scheme?.lowercase()
    require(scheme in setOf("http", "https") && uri.host != null && uri.rawUserInfo == null) { "Expected a public HTTP(S) URL" }
    require(uri.port in -1..65535 && uri.port != 0)
    val port = uri.port.takeUnless { it == (if (scheme == "https") 443 else 80) } ?: -1
    val authority = uri.host.lowercase() + if (port == -1) "" else ":$port"
    return "$scheme://$authority${uri.rawPath.takeUnless { it.isNullOrEmpty() } ?: "/"}${uri.rawQuery?.let { "?$it" }.orEmpty()}"
}

private fun key(url: String): String = MessageDigest.getInstance("SHA-256").digest(url.toByteArray()).joinToString("") { "%02x".format(it) }

internal fun pageTitle(html: String): String {
    val title = StringBuilder()
    var inTitle = false
    ParserDelegator().parse(StringReader(html), object : HTMLEditorKit.ParserCallback() {
        override fun handleStartTag(tag: HTML.Tag, attributes: MutableAttributeSet, position: Int) { if (tag == HTML.Tag.TITLE) inTitle = true }
        override fun handleEndTag(tag: HTML.Tag, position: Int) { if (tag == HTML.Tag.TITLE) inTitle = false }
        override fun handleText(data: CharArray, position: Int) { if (inTitle && title.length < 500) title.append(data.concatToString().take(500 - title.length)) }
    }, true)
    return title.toString().trim()
}
