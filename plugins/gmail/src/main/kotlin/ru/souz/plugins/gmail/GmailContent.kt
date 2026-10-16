package ru.souz.plugins.gmail

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.node.ArrayNode
import com.fasterxml.jackson.databind.node.ObjectNode
import java.io.ByteArrayOutputStream
import java.io.StringReader
import java.nio.ByteBuffer
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction
import java.util.Base64
import javax.swing.text.MutableAttributeSet
import javax.swing.text.html.HTML
import javax.swing.text.html.HTMLEditorKit
import javax.swing.text.html.parser.ParserDelegator

internal fun headers(part: JsonNode): Map<String, String> = part.path("headers").associate {
    it.path("name").asText().lowercase() to it.path("value").asText()
}

private fun clip(text: String, limit: Int): String {
    val bytes = text.toByteArray()
    return if (bytes.size <= limit) text else Charsets.UTF_8.newDecoder()
        .onMalformedInput(CodingErrorAction.IGNORE).decode(ByteBuffer.wrap(bytes, 0, limit)).toString()
}

internal fun cleanHeader(raw: String, limit: Int): String {
    val encoded = Regex("=\\?([^?\\s]+)\\?([bBqQ])\\?([^?]*)\\?=")
    val adjacent = raw.replace(Regex("(?<=\\?=)\\s+(?==\\?)"), "")
    val decoded = encoded.replace(adjacent) { match ->
        try {
            val content = match.groupValues[3]
            val bytes = if (match.groupValues[2].equals("b", ignoreCase = true)) Base64.getDecoder().decode(content) else {
                val value = content.replace('_', ' ')
                val output = ByteArrayOutputStream()
                var index = 0
                while (index < value.length) {
                    if (value[index] == '=') { output.write(value.substring(index + 1, index + 3).toInt(16)); index += 3 }
                    else { output.write(value[index].code); index++ }
                }
                output.toByteArray()
            }
            bytes.toString(Charset.forName(match.groupValues[1]))
        } catch (_: IllegalArgumentException) { match.value }
        catch (_: IndexOutOfBoundsException) { match.value }
    }
    return clip(decoded.replace(Regex("[\\s\\p{Cc}]+"), " ").trim(), limit)
}

internal fun emailContext(message: JsonNode): ObjectNode {
    val plain = mutableListOf<String>()
    val html = mutableListOf<String>()
    val stack = ArrayDeque<JsonNode>().apply { add(message.path("payload")) }
    var incomplete = false
    repeat(100) {
        if (stack.isEmpty()) return@repeat
        val part = stack.removeLast()
        val properties = headers(part)
        if (part.path("filename").asText().isNotEmpty() || properties["content-disposition"].orEmpty().startsWith("attachment", ignoreCase = true)) return@repeat
        part.path("parts").reversed().forEach(stack::addLast)
        val mime = part.path("mimeType").asText()
        if (mime !in setOf("text/plain", "text/html")) return@repeat
        val data = part.path("body").path("data").asText()
        if (data.isEmpty()) { incomplete = incomplete || part.path("body").has("attachmentId"); return@repeat }
        try {
            val charset = Regex("charset\\s*=\\s*[\"']?([^;\\s\"']+)", RegexOption.IGNORE_CASE)
                .find(properties["content-type"].orEmpty())?.groupValues?.get(1)
            val encoding = try { Charset.forName(charset ?: "UTF-8") } catch (_: IllegalArgumentException) { Charsets.UTF_8 }
            val text = Base64.getUrlDecoder().decode(data).toString(encoding)
            if (mime == "text/plain") plain += text else html += htmlText(text)
        } catch (_: IllegalArgumentException) { incomplete = true }
    }
    val content = (plain.ifEmpty { html }).joinToString("\n").trim()
    val text = content.ifEmpty { htmlText(message.path("snippet").asText()) }
    val bounded = clip(text, 4800)
    val envelope = headers(message.path("payload"))
    return json.createObjectNode().put("untrusted", true)
        .put("from", cleanHeader(envelope["from"].orEmpty(), 320))
        .put("subject", cleanHeader(envelope["subject"].orEmpty(), 600))
        .put("date", cleanHeader(envelope["date"].orEmpty(), 100))
        .put("body", bounded).put("contentSource", if (content.isEmpty()) "snippet" else "body")
        .put("contentTruncated", stack.isNotEmpty() || incomplete || bounded != text)
}

internal fun attachments(message: JsonNode): ArrayNode {
    val found = json.createArrayNode()
    val stack = ArrayDeque<JsonNode>().apply { add(message.path("payload")) }
    repeat(100) {
        if (stack.isEmpty()) return@repeat
        val part = stack.removeLast()
        part.path("parts").reversed().forEach(stack::addLast)
        val id = part.path("body").path("attachmentId").asText()
        if (id.isNotEmpty()) found.add(json.createObjectNode().put("attachment", id)
            .put("filename", cleanHeader(part.path("filename").asText(), 1024))
            .put("mimeType", part.path("mimeType").asText()).put("sizeBytes", part.path("body").path("size").asLong()))
    }
    return found
}

private fun htmlText(value: String): String {
    val output = StringBuilder()
    var hidden = 0
    ParserDelegator().parse(StringReader(value), object : HTMLEditorKit.ParserCallback() {
        override fun handleStartTag(tag: HTML.Tag, attributes: MutableAttributeSet, position: Int) {
            if (tag in setOf(HTML.Tag.HEAD, HTML.Tag.SCRIPT, HTML.Tag.STYLE)) hidden++
            if (hidden == 0 && tag.toString() in setOf("p", "div", "li", "tr")) output.append('\n')
        }
        override fun handleEndTag(tag: HTML.Tag, position: Int) {
            if (tag in setOf(HTML.Tag.HEAD, HTML.Tag.SCRIPT, HTML.Tag.STYLE)) hidden = maxOf(0, hidden - 1)
            if (hidden == 0 && tag.toString() in setOf("p", "div", "li", "tr")) output.append('\n')
        }
        override fun handleSimpleTag(tag: HTML.Tag, attributes: MutableAttributeSet, position: Int) { if (hidden == 0 && tag == HTML.Tag.BR) output.append('\n') }
        override fun handleText(data: CharArray, position: Int) { if (hidden == 0) output.append(data) }
    }, true)
    return output.toString().trim()
}
