package ru.souz.backend.vk

import com.fasterxml.jackson.annotation.JsonProperty
import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import java.io.IOException
import java.net.URI
import java.net.URLEncoder
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import kotlin.random.Random
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runInterruptible

interface VkBotApi {
    suspend fun getGroupInfo(groupToken: String): VkGroup
    suspend fun getLongPollServer(groupToken: String, groupId: Long): VkLongPollServer
    suspend fun getUserInfo(groupToken: String, userId: Long): VkUser?
    suspend fun pollLongPoll(server: String, key: String, ts: String, waitSeconds: Int = 25): VkLongPollResponse
    suspend fun sendMessage(groupToken: String, peerId: Long, text: String)
    suspend fun setActivity(groupToken: String, peerId: Long, groupId: Long)
}

internal class HttpVkBotApi(
    private val baseUrl: String = "https://api.vk.com/method",
) : VkBotApi, AutoCloseable {
    private val mapper = jacksonObjectMapper().disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
    private val client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build()

    override suspend fun getGroupInfo(groupToken: String): VkGroup {
        val response = method(groupToken, "groups.getById")
        val group = (if (response.isArray) response else response.path("groups")).firstOrNull()
            ?: throw VkBotApiException(5)
        return mapper.treeToValue(group, VkGroup::class.java)
    }

    override suspend fun getLongPollServer(groupToken: String, groupId: Long): VkLongPollServer =
        mapper.treeToValue(
            method(groupToken, "groups.getLongPollServer", "group_id" to groupId.toString()),
            VkLongPollServer::class.java,
        )

    override suspend fun getUserInfo(groupToken: String, userId: Long): VkUser? =
        method(groupToken, "users.get", "user_ids" to userId.toString()).firstOrNull()
            ?.let { mapper.treeToValue(it, VkUser::class.java) }

    override suspend fun pollLongPoll(server: String, key: String, ts: String, waitSeconds: Int): VkLongPollResponse =
        mapper.treeToValue(
            request(HttpRequest.newBuilder(URI.create("$server?${form(
                "act" to "a_check", "key" to key, "ts" to ts, "wait" to waitSeconds.toString(),
            )}")).GET()),
            VkLongPollResponse::class.java,
        )

    override suspend fun sendMessage(groupToken: String, peerId: Long, text: String) {
        method(
            groupToken, "messages.send", "peer_id" to peerId.toString(), "message" to text,
            "random_id" to Random.nextInt(1, Int.MAX_VALUE).toString(),
        )
    }

    override suspend fun setActivity(groupToken: String, peerId: Long, groupId: Long) {
        method(
            groupToken, "messages.setActivity", "peer_id" to peerId.toString(),
            "group_id" to groupId.toString(), "type" to "typing",
        )
    }

    private suspend fun method(token: String, name: String, vararg parameters: Pair<String, String>): JsonNode {
        val body = request(
            HttpRequest.newBuilder(URI.create("$baseUrl/$name"))
                .header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString(form(*parameters, "access_token" to token, "v" to "5.199"))),
        )
        // VK error bodies may echo credentials; only retain the numeric code.
        if (body.hasNonNull("error")) throw VkBotApiException(body.path("error").path("error_code").asInt())
        return body.get("response") ?: throw IOException("Missing VK response.")
    }

    private suspend fun request(builder: HttpRequest.Builder): JsonNode = runInterruptible(Dispatchers.IO) {
        val response = client.send(builder.timeout(Duration.ofSeconds(35)).build(), HttpResponse.BodyHandlers.ofString())
        if (response.statusCode() !in 200..299) throw IOException("VK HTTP ${response.statusCode()}.")
        mapper.readTree(response.body()) ?: throw IOException("Empty VK response.")
    }

    override fun close() = client.close()
}

internal class VkBotApiException(val code: Int) : RuntimeException("VK API error $code.")

data class VkGroup(val id: Long, val name: String? = null)
data class VkLongPollServer(val key: String, val server: String, val ts: String)
data class VkUser(
    val id: Long,
    @param:JsonProperty("first_name") val firstName: String? = null,
    @param:JsonProperty("last_name") val lastName: String? = null,
)
data class VkLongPollResponse(
    val ts: String? = null,
    val updates: List<VkLongPollUpdate> = emptyList(),
    val failed: Int? = null,
)
data class VkLongPollUpdate(
    val type: String,
    @param:JsonProperty("object") val obj: VkMessageObjectWrapper? = null,
)
data class VkMessageObjectWrapper(val message: VkMessage? = null)
data class VkMessage(
    val id: Long,
    @param:JsonProperty("from_id") val fromId: Long,
    @param:JsonProperty("peer_id") val peerId: Long,
    val text: String? = null,
    val out: Int = 0,
)

private fun form(vararg values: Pair<String, String>): String = values.joinToString("&") { (key, value) ->
    "${URLEncoder.encode(key, Charsets.UTF_8)}=${URLEncoder.encode(value, Charsets.UTF_8)}"
}
