package ru.souz.backend.client

import com.fasterxml.jackson.databind.node.ObjectNode
import com.fasterxml.jackson.databind.node.ArrayNode
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
        val content = outcome.result?.get("content")?.takeIf { it.isArray } ?: return outcome
        if (content.none { it["type"]?.asText() == "image" || isImageLink(it) }) return outcome
        return try {
            require(content.count { it["type"]?.asText() == "image" || isImageLink(it) } <= ImageSource.MAX_IMAGES)
            val result = outcome.result.deepCopy<ObjectNode>()
            val items = result["content"] as ArrayNode
            var remaining = ImageSource.MAX_BYTES
            content.forEachIndexed { index, item ->
                if (item["type"]?.asText() != "image" && !isImageLink(item)) return@forEachIndexed
                if (outcome.status != "succeeded" || result["isError"]?.asBoolean() == true) {
                    items.set(index, restJsonMapper.createObjectNode().put("type", "text").put("text", "Image omitted: tool failed."))
                    return@forEachIndexed
                }
                val uri = item["uri"]?.asText().orEmpty()
                val saved = if (uri.startsWith("attachment:")) {
                    storage.get(userId, attachmentId(uri.removePrefix("attachment:")))
                } else {
                    val bytes = if (item["type"]?.asText() == "image") {
                        val data = requireNotNull(item["data"]?.takeIf { it.isTextual }?.asText())
                        require(data.length <= 4 * ((remaining + 2) / 3))
                        Base64.getDecoder().decode(data)
                    } else download(uri, remaining)
                    require(bytes.size <= remaining)
                    val source = ImageSource(bytes)
                    item["mimeType"]?.asText()?.let { require(it == source.mimeType) }
                    storage.save(userId, "$key:$index", "tool-image.${source.mimeType.substringAfter('/')}", bytes.inputStream())
                }
                require(saved.sizeBytes <= remaining && saved.mimeType in ImageSource.MIME_TYPES)
                remaining -= saved.sizeBytes.toInt()
                items.set(index, restJsonMapper.createObjectNode().put("type", "resource_link")
                    .put("uri", "attachment:${saved.id}").put("name", saved.name).put("mimeType", saved.mimeType)
                    .set<ObjectNode>("attachment", restJsonMapper.valueToTree(saved.toContent(FilePresentation.image))))
            }
            outcome.copy(result = result)
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

internal fun isImageLink(item: com.fasterxml.jackson.databind.JsonNode): Boolean =
    item["type"]?.asText() == "resource_link" && (item["uri"]?.asText()?.startsWith("attachment:") == true ||
        item["mimeType"]?.asText()?.startsWith("image/") == true ||
        item["uri"]?.asText()?.substringBefore('?')?.lowercase()?.let { uri ->
            listOf(".png", ".jpg", ".jpeg", ".webp").any(uri::endsWith)
        } == true)

internal fun ClientToolOutcome.imageFiles(): List<FileContent> = if (status != "succeeded") emptyList() else
    result?.get("content")?.filter(::isImageLink)?.mapNotNull { item ->
        item["attachment"]?.let { restJsonMapper.treeToValue(it, FileContent::class.java) }
    }.orEmpty()
