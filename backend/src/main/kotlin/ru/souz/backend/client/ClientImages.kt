package ru.souz.backend.client

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.node.ArrayNode
import com.fasterxml.jackson.databind.node.ObjectNode
import java.net.InetAddress
import java.net.Proxy
import java.util.Base64
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runInterruptible
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.HttpUrl.Companion.toHttpUrl
import ru.souz.backend.attachments.*
import ru.souz.llms.FileContent
import ru.souz.llms.FilePresentation
import ru.souz.llms.restJsonMapper
import ru.souz.llms.runtime.ImageSource

/** Host-owned download client; resolve and pin public addresses before connecting, without redirects or proxies. */
internal class ClientImages(private val storage: AttachmentStorage) : AutoCloseable {
    private val client = OkHttpClient.Builder().proxy(Proxy.NO_PROXY).followRedirects(false)
        .followSslRedirects(false).retryOnConnectionFailure(false).callTimeout(10, TimeUnit.SECONDS).build()

    suspend fun store(userId: String, key: String, outcome: ClientToolOutcome): ClientToolOutcome {
        val normalized = outcome.withoutClientAttachments()
        val items = normalized.result?.get("content") as? ArrayNode ?: return normalized
        return try {
            val images = items.mapIndexedNotNull { index, item ->
                val uri = item["uri"]?.asText().orEmpty()
                val existing = if (item["type"]?.asText() == "resource_link" && uri.startsWith("attachment:")) {
                    storage.get(userId, attachmentId(uri.removePrefix("attachment:"))).also {
                        (item as ObjectNode).put("mimeType", it.mimeType)
                    }
                } else null
                val isImage = existing?.mimeType?.startsWith("image/") ?: isImageContent(item)
                if (isImage) index to existing else null
            }
            require(images.size <= ImageSource.MAX_IMAGES)
            val files = mutableListOf<FileContent>()
            var remaining = ImageSource.MAX_BYTES
            for ((index, existing) in images) {
                val item = items[index]
                if (outcome.status != "succeeded" || normalized.result["isError"]?.asBoolean() == true) {
                    items.set(index, restJsonMapper.createObjectNode().put("type", "text").put("text", "Image omitted: tool failed."))
                    continue
                }
                val saved = if (existing != null) {
                    require(existing.sizeBytes <= remaining && existing.mimeType in ImageSource.MIME_TYPES)
                    val source = ImageSource.read(storage.read(userId, existing.id))
                    require(source.mimeType == existing.mimeType)
                    existing
                } else {
                    val bytes = if (item["type"]?.asText() == "image") {
                        val data = requireNotNull(item["data"]?.takeIf { it.isTextual }?.asText())
                        require(data.length <= 4 * ((remaining + 2) / 3))
                        Base64.getDecoder().decode(data)
                    } else download(item["uri"]?.asText().orEmpty(), remaining)
                    require(bytes.size <= remaining)
                    val source = ImageSource.read(bytes)
                    item["mimeType"]?.asText()?.let { require(it == source.mimeType) }
                    storage.save(userId, "$key:$index", "tool-image.${source.mimeType.substringAfter('/')}", bytes.inputStream())
                }
                require(saved.sizeBytes <= remaining && saved.mimeType in ImageSource.MIME_TYPES)
                remaining -= saved.sizeBytes.toInt()
                files += saved.toContent(FilePresentation.image)
                items.set(index, restJsonMapper.createObjectNode().put("type", "resource_link")
                    .put("uri", "attachment:${saved.id}").put("name", saved.name).put("mimeType", saved.mimeType))
            }
            normalized.copy(files = files)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            ClientToolOutcome("failed", null, ClientError("tool_image_unavailable", "Tool image could not be validated or stored."))
        }
    }

    private suspend fun download(raw: String, limit: Int): ByteArray = runInterruptible(Dispatchers.IO) {
        require(raw.length <= 8192 && limit > 0)
        val url = raw.toHttpUrl()
        require(url.username.isEmpty() && url.password.isEmpty())
        val addresses = InetAddress.getAllByName(url.host).toList()
        require(addresses.isNotEmpty() && addresses.all { address ->
            !address.isAnyLocalAddress && !address.isLoopbackAddress && !address.isLinkLocalAddress &&
                !address.isSiteLocalAddress && !address.isMulticastAddress &&
                // IPv6 must be global unicast; reject NAT64 and other translated/private destinations.
                (address.address.size != 16 || address.address[0].toInt() and 0xe0 == 0x20) &&
                (address.address.size != 4 || address.address[0].toInt() and 255 in 1..223) &&
                !(address.address.size == 4 && address.address[0].toInt() and 255 == 100 && address.address[1].toInt() and 255 in 64..127)
        }) { "Image URL must have a public address" }
        // Shares the host client's pool and dispatcher, but pins this lookup against DNS rebinding.
        val pinned = client.newBuilder().dns { host -> require(host == url.host); addresses }.build()
        pinned.newCall(Request.Builder().url(url).build()).execute().use { response ->
            check(response.code == 200)
            requireNotNull(response.body).byteStream().use { input ->
                input.readNBytes(limit + 1).also { require(it.size <= limit) }
            }
        }
    }

    override fun close() {
        client.connectionPool.evictAll()
        client.dispatcher.executorService.shutdown()
    }
}

/** Recognizes untrusted image content; attachment links require an owner-scoped metadata lookup. */
internal fun isImageContent(item: JsonNode): Boolean =
    when (item["type"]?.asText()) {
        "image" -> true
        "resource_link" -> item["mimeType"]?.asText()?.startsWith("image/") == true ||
            item["uri"]?.asText()?.substringBefore('?')?.lowercase()?.let { uri ->
                listOf(".png", ".jpg", ".jpeg", ".webp").any(uri::endsWith)
            } == true
        else -> false
    }

/** Drops client-supplied internal file references even when image storage is disabled. */
internal fun ClientToolOutcome.withoutClientAttachments(): ClientToolOutcome {
    if (result?.get("content")?.isArray != true) return this
    val sanitized = result.deepCopy<ObjectNode>()
    sanitized["content"].forEach { (it as? ObjectNode)?.remove("attachment") }
    return copy(result = sanitized)
}
