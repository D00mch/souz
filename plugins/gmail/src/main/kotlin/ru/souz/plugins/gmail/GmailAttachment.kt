package ru.souz.plugins.gmail

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.node.ObjectNode
import java.security.MessageDigest
import java.util.Base64
import ru.souz.plugins.PluginCallContext

internal suspend fun downloadAttachment(context: PluginCallContext, args: ObjectNode): JsonNode {
    val message = messageId(args.text("message"))
    val attachment = args.text("attachment").also { require(it.matches(Regex("[A-Za-z0-9_-]{1,4096}"))) }
    val filename = args.text("filename").also { require(it.length <= 1024) }
    val body = context.gmail("/messages/$message/attachments/$attachment").ok()
    val size = body.path("size").also { require(it.isIntegralNumber && it.canConvertToInt()) }.asInt()
    require(size in 0..25 * 1024 * 1024) { "Attachment exceeds 25 MiB" }
    val data = body.path("data").also { require(it.isTextual) }.asText()
    require(data.length <= 4 * ((25 * 1024 * 1024 + 2) / 3) && data.matches(Regex("[A-Za-z0-9_-]*={0,2}")))
    val bytes = Base64.getUrlDecoder().decode(data)
    require(bytes.size == size) { "Attachment size mismatch" }
    val extension = filename.substringAfterLast('.', "").lowercase().takeIf { it.matches(Regex("[a-z0-9]{1,12}")) } ?: "bin"
    if (extension == "pdf") require(bytes.take(1024).toByteArray().toString(Charsets.ISO_8859_1).contains("%PDF-")) { "Attachment is not a PDF" }
    val path = context.files.save(extension, bytes)
    return result("downloaded").put("filePath", path).put("filename", filename).put("sizeBytes", bytes.size)
        .put("sha256", MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }).put("untrusted", true)
}
