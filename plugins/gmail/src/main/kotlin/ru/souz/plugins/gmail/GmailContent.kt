package ru.souz.plugins.gmail

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.node.ObjectNode
import java.nio.ByteBuffer
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction
import java.net.URLDecoder
import java.security.MessageDigest
import java.util.Base64
import org.jsoup.Jsoup
import ru.souz.plugins.PluginFiles

internal fun headers(part: JsonNode): Map<String, String> = part.path("headers").associate {
    it.path("name").asText().lowercase() to it.path("value").asText()
}

internal fun cleanHeader(value: String, limit: Int): String {
    val adjacent = value.replace(Regex("(?<=\\?=)\\s+(?==\\?)"), "")
    val decoded = ENCODED_WORD.replace(adjacent) { match ->
        try {
            val charset = Charset.forName(match.groupValues[1])
            val payload = match.groupValues[3]
            if (match.groupValues[2].equals("B", ignoreCase = true)) Base64.getDecoder().decode(payload).toString(charset)
            else URLDecoder.decode(payload.replace("+", "%2B").replace('_', ' ').replace('=', '%'), charset)
        } catch (_: IllegalArgumentException) { match.value }
    }
    return clip(decoded.replace(Regex("[\\s\\p{Cc}]+"), " ").trim(), limit)
}

internal fun emailContext(email: JsonNode): ObjectNode {
    val plain = mutableListOf<String>()
    val html = mutableListOf<String>()
    val attachments = json.createArrayNode()
    val pending = ArrayDeque<JsonNode>().apply { add(email.path("payload")) }
    var incomplete = false
    var visited = 0
    while (pending.isNotEmpty() && visited++ < 100) {
        val part = pending.removeLast()
        val metadata = headers(part)
        val body = part.path("body")
        val filename = part.path("filename").asText()
        if (body.hasNonNull("attachmentId")) attachments.add(tree(mapOf(
            "attachment" to body.text("attachmentId"), "filename" to cleanHeader(filename, 1024),
            "mimeType" to part.path("mimeType").asText(), "sizeBytes" to body.path("size").asLong(),
        )))
        if (filename.isNotEmpty() || metadata["content-disposition"].orEmpty().startsWith("attachment", ignoreCase = true)) continue
        part.path("parts").reversed().forEach(pending::addLast)
        val mime = part.path("mimeType").asText()
        if (mime != "text/plain" && mime != "text/html") continue
        val encoded = body.path("data").asText()
        if (encoded.isEmpty()) { incomplete = incomplete || body.hasNonNull("attachmentId"); continue }
        try {
            val bounded = encoded.take(131_072)
            incomplete = incomplete || bounded.length != encoded.length
            val encoding = CHARSET.find(metadata["content-type"].orEmpty())?.groupValues?.get(1)
            val charset = try { Charset.forName(encoding ?: "UTF-8") } catch (_: IllegalArgumentException) { Charsets.UTF_8 }
            val text = Base64.getUrlDecoder().decode(bounded).toString(charset)
            if (mime == "text/plain") plain += text else html += htmlText(text)
        } catch (_: IllegalArgumentException) { incomplete = true }
    }
    val content = plain.ifEmpty { html }.joinToString("\n").trim()
    val text = content.ifEmpty { htmlText(email.path("snippet").asText()) }
    val bounded = clip(text, 4800)
    val envelope = headers(email.path("payload"))
    return tree(mapOf(
        "untrusted" to true, "from" to cleanHeader(envelope["from"].orEmpty(), 320),
        "subject" to cleanHeader(envelope["subject"].orEmpty(), 600), "date" to cleanHeader(envelope["date"].orEmpty(), 100),
        "body" to bounded, "contentSource" to if (content.isEmpty()) "snippet" else "body",
        "contentTruncated" to (incomplete || pending.isNotEmpty() || bounded != text), "attachments" to attachments,
    ))
}

internal suspend fun downloadAttachment(api: GmailApi, files: PluginFiles, args: ObjectNode): JsonNode {
    val message = messageId(args.text("message"))
    val attachment = args.text("attachment").also { require(it.matches(Regex("[A-Za-z0-9_-]{1,4096}"))) }
    val filename = args.text("filename").also { require(it.length <= 1024) }
    val response = api.request("/messages/$message/attachments/$attachment")
    val size = response.path("size").also { require(it.isIntegralNumber && it.canConvertToInt()) }.asInt()
    require(size in 0..MAX_ATTACHMENT_BYTES)
    val encoded = response.path("data").also { require(it.isTextual) }.asText()
    require(encoded.length <= 4 * ((MAX_ATTACHMENT_BYTES + 2) / 3))
    val bytes = Base64.getUrlDecoder().decode(encoded)
    require(bytes.size == size) { "Attachment size differs from metadata" }
    val extension = filename.substringAfterLast('.', "").lowercase().takeIf { it.matches(Regex("[a-z0-9]{1,12}")) } ?: "bin"
    if (extension == "pdf") require(bytes.take(1024).toByteArray().toString(Charsets.ISO_8859_1).contains("%PDF-")) { "Invalid PDF signature" }
    val path = files.save(extension, bytes)
    return tree(mapOf("status" to "downloaded", "filePath" to path, "filename" to filename, "sizeBytes" to size,
        "sha256" to MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }, "untrusted" to true))
}

private fun clip(text: String, limit: Int): String {
    val bytes = text.toByteArray()
    return if (bytes.size <= limit) text else Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.IGNORE)
        .decode(ByteBuffer.wrap(bytes, 0, limit)).toString()
}
private fun htmlText(text: String): String = Jsoup.parse(text).apply { select("script,style,head").remove() }.text()
private val ENCODED_WORD = Regex("=\\?([^?\\s]+)\\?([bBqQ])\\?([^?]*)\\?=")
private val CHARSET = Regex("charset\\s*=\\s*[\"']?([^;\\s\"']+)", RegexOption.IGNORE_CASE)
private const val MAX_ATTACHMENT_BYTES = 25 * 1024 * 1024
