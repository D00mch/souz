package ru.souz.backend.vk

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.fasterxml.jackson.annotation.JsonProperty
import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import java.io.IOException
import java.net.URI
import java.net.URLEncoder
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.net.http.HttpTimeoutException
import java.security.SecureRandom
import java.time.Duration
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runInterruptible

interface VkBotApi {
    /** `groups.getById` — validates the group token and returns the community's own identity. */
    suspend fun getGroupInfo(groupToken: String): VkResponse<List<VkGroup>>

    /** `groups.getLongPollServer` — issues a fresh Long Poll session (server/key/ts). */
    suspend fun getLongPollServer(groupToken: String, groupId: Long): VkResponse<VkLongPollServer>

    /** `users.get` — best-effort display-name lookup; never required for the security-critical claim. */
    suspend fun getUserInfo(groupToken: String, userId: Long): VkResponse<List<VkUser>>

    /** `GET <server>?act=a_check&key=&ts=&wait=` against the server VK itself returned. */
    suspend fun pollLongPoll(server: String, key: String, ts: String, waitSeconds: Int = 25): VkLongPollResponse

    /** `messages.send`, with a client-generated `random_id` (VK's own de-dup key). */
    suspend fun sendMessage(groupToken: String, peerId: Long, text: String)

    /**
     * Fire-and-forget "typing…" indicator (`messages.setActivity`); VK expires it after a few
     * seconds. `groupId` must be passed explicitly — without it VK accepts the call (`response: 1`)
     * but never surfaces the indicator to the peer's client.
     */
    suspend fun setActivity(groupToken: String, peerId: Long, groupId: Long, type: String = "typing")
}

internal class HttpVkBotApi : VkBotApi {
    private val mapper = jacksonObjectMapper()
        .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
    private val client = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(CONNECT_TIMEOUT_SECONDS))
        .build()

    override suspend fun getGroupInfo(groupToken: String): VkResponse<List<VkGroup>> =
        methodRequest(
            token = groupToken,
            methodName = "groups.getById",
            formParameters = emptyMap(),
        ).bodyAsGroupList()

    override suspend fun getLongPollServer(groupToken: String, groupId: Long): VkResponse<VkLongPollServer> =
        methodRequest(
            token = groupToken,
            methodName = "groups.getLongPollServer",
            formParameters = mapOf("group_id" to groupId.toString()),
        ).bodyAs()

    override suspend fun getUserInfo(groupToken: String, userId: Long): VkResponse<List<VkUser>> =
        methodRequest(
            token = groupToken,
            methodName = "users.get",
            formParameters = mapOf("user_ids" to userId.toString()),
        ).bodyAs()

    override suspend fun pollLongPoll(server: String, key: String, ts: String, waitSeconds: Int): VkLongPollResponse =
        rawGet(
            uri = "$server?act=a_check&key=${key.urlEncode()}&ts=${ts.urlEncode()}&wait=$waitSeconds",
        ).bodyAs()

    override suspend fun sendMessage(groupToken: String, peerId: Long, text: String) {
        methodRequest(
            token = groupToken,
            methodName = "messages.send",
            formParameters = mapOf(
                "peer_id" to peerId.toString(),
                "message" to text,
                "random_id" to secureRandom.nextInt().let { if (it == 0) 1 else it }.toString(),
            ),
        ).bodyAs<VkResponse<Long>>().throwOnError("messages.send")
    }

    override suspend fun setActivity(groupToken: String, peerId: Long, groupId: Long, type: String) {
        methodRequest(
            token = groupToken,
            methodName = "messages.setActivity",
            formParameters = mapOf(
                "peer_id" to peerId.toString(),
                "group_id" to groupId.toString(),
                "type" to type,
            ),
        ).bodyAs<VkResponse<Int>>().throwOnError("messages.setActivity")
    }

    private fun VkResponse<*>.throwOnError(methodName: String) {
        error?.let { throw VkBotApiHttpException(methodName = methodName, vkError = it) }
    }

    private suspend fun methodRequest(
        token: String,
        methodName: String,
        formParameters: Map<String, String>,
    ): VkRawResponse =
        rawRequest(
            uri = "https://api.vk.com/method/$methodName",
            formParameters = formParameters + mapOf("access_token" to token, "v" to API_VERSION),
        )

    private suspend fun rawGet(uri: String): VkRawResponse =
        rawTransport {
            val request = HttpRequest.newBuilder()
                .uri(URI.create(uri))
                .timeout(Duration.ofSeconds(REQUEST_TIMEOUT_SECONDS))
                .GET()
                .build()
            client.send(request, HttpResponse.BodyHandlers.ofString()).let { response ->
                VkRawResponse(httpStatus = response.statusCode(), body = response.body())
            }
        }

    private suspend fun rawRequest(
        uri: String,
        formParameters: Map<String, String>,
    ): VkRawResponse =
        rawTransport {
            val request = HttpRequest.newBuilder()
                .uri(URI.create(uri))
                .timeout(Duration.ofSeconds(REQUEST_TIMEOUT_SECONDS))
                .header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString(formParameters.toFormBody()))
                .build()
            client.send(request, HttpResponse.BodyHandlers.ofString()).let { response ->
                VkRawResponse(httpStatus = response.statusCode(), body = response.body())
            }
        }

    private suspend fun rawTransport(block: () -> VkRawResponse): VkRawResponse =
        try {
            runInterruptible(Dispatchers.IO, block)
        } catch (e: CancellationException) {
            throw e
        } catch (e: HttpTimeoutException) {
            throw VkBotApiTransportException("VK request timed out.", e)
        } catch (e: IOException) {
            throw VkBotApiTransportException("VK network request failed.", e)
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            throw VkBotApiTransportException("VK request was interrupted.", e)
        }

    private inline fun <reified T> VkRawResponse.bodyAs(): T =
        try {
            mapper.readValue(body)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            throw VkBotApiTransportException("VK response could not be parsed.", e)
        }

    /**
     * `groups.getById`'s `response` field has shipped as both a bare `[...]` array and a wrapped
     * `{"groups": [...], "profiles": [...]}` object across VK API versions — parse the raw tree
     * and accept either shape rather than betting on one.
     */
    private fun VkRawResponse.bodyAsGroupList(): VkResponse<List<VkGroup>> =
        try {
            val tree = mapper.readTree(body)
            val errorNode = tree.get("error")
            if (errorNode != null && !errorNode.isNull) {
                VkResponse(error = mapper.treeToValue(errorNode, VkApiError::class.java))
            } else {
                val responseNode = tree.get("response")
                val groupsNode = when {
                    responseNode == null || responseNode.isNull -> null
                    responseNode.isArray -> responseNode
                    responseNode.isObject && responseNode.has("groups") -> responseNode.get("groups")
                    else -> null
                }
                val groups = groupsNode?.let { node ->
                    mapper.readerForListOf(VkGroup::class.java).readValue<List<VkGroup>>(node)
                }
                VkResponse(response = groups)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            throw VkBotApiTransportException("VK response could not be parsed.", e)
        }

    private companion object {
        const val CONNECT_TIMEOUT_SECONDS: Long = 10L
        const val REQUEST_TIMEOUT_SECONDS: Long = 35L
        const val API_VERSION: String = "5.199"
        val secureRandom = SecureRandom()
    }
}

internal open class VkBotApiException(
    message: String,
    cause: Throwable? = null,
) : RuntimeException(message, cause)

internal class VkBotApiTransportException(
    message: String,
    cause: Throwable? = null,
) : VkBotApiException(message, cause)

internal class VkBotApiHttpException(
    val methodName: String,
    val vkError: VkApiError,
) : VkBotApiException(vkError.errorMsg ?: "VK API request failed.")

private data class VkRawResponse(
    val httpStatus: Int,
    val body: String,
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class VkApiError(
    @param:JsonProperty("error_code")
    val errorCode: Int? = null,
    @param:JsonProperty("error_msg")
    val errorMsg: String? = null,
)

/** Envelope shared by every plain VK API method call: `{"response": T}` or `{"error": {...}}`. */
@JsonIgnoreProperties(ignoreUnknown = true)
data class VkResponse<T>(
    val response: T? = null,
    val error: VkApiError? = null,
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class VkGroup(
    val id: Long,
    val name: String? = null,
    @param:JsonProperty("screen_name")
    val screenName: String? = null,
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class VkLongPollServer(
    val key: String,
    val server: String,
    val ts: String,
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class VkUser(
    val id: Long,
    @param:JsonProperty("first_name")
    val firstName: String? = null,
    @param:JsonProperty("last_name")
    val lastName: String? = null,
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class VkLongPollResponse(
    val ts: String? = null,
    val updates: List<VkLongPollUpdate> = emptyList(),
    val failed: Int? = null,
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class VkLongPollUpdate(
    val type: String,
    @param:JsonProperty("object")
    val obj: VkMessageObjectWrapper? = null,
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class VkMessageObjectWrapper(
    val message: VkMessage? = null,
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class VkMessage(
    val id: Long,
    @param:JsonProperty("from_id")
    val fromId: Long,
    @param:JsonProperty("peer_id")
    val peerId: Long,
    val text: String? = null,
    val date: Long = 0L,
)

private fun Map<String, String>.toFormBody(): String =
    entries.joinToString(separator = "&") { (key, value) ->
        "${key.urlEncode()}=${value.urlEncode()}"
    }

private fun String.urlEncode(): String =
    URLEncoder.encode(this, Charsets.UTF_8)
