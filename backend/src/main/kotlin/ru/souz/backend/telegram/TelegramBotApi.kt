package ru.souz.backend.telegram

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
import java.time.Duration
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

interface TelegramBotApi {
    suspend fun getMe(token: String): TelegramGetMeResponse

    suspend fun getUpdates(
        token: String,
        offset: Long?,
        timeoutSeconds: Int = 30,
        allowedUpdates: List<String> = listOf("message"),
    ): TelegramUpdatesResponse

    suspend fun sendMessage(
        token: String,
        chatId: Long,
        text: String,
    )
}

internal class HttpTelegramBotApi : TelegramBotApi {
    private val mapper = jacksonObjectMapper()
        .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
    private val client = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(CONNECT_TIMEOUT_SECONDS))
        .build()

    override suspend fun getMe(token: String): TelegramGetMeResponse =
        request(
            token = token,
            methodName = "getMe",
            formParameters = emptyMap(),
        ).bodyAs<TelegramGetMeResponse>()
            .normalizeHttpStatus()

    override suspend fun getUpdates(
        token: String,
        offset: Long?,
        timeoutSeconds: Int,
        allowedUpdates: List<String>,
    ): TelegramUpdatesResponse =
        request(
            token = token,
            methodName = "getUpdates",
            formParameters = buildMap {
                offset?.let { put("offset", it.toString()) }
                put("timeout", timeoutSeconds.toString())
                put("allowed_updates", mapper.writeValueAsString(allowedUpdates))
            },
        ).bodyAs<TelegramUpdatesResponse>()
            .normalizeHttpStatus()

    override suspend fun sendMessage(
        token: String,
        chatId: Long,
        text: String,
    ) {
        val response = request(
            token = token,
            methodName = "sendMessage",
            formParameters = mapOf(
                "chat_id" to chatId.toString(),
                "text" to text,
            ),
        ).bodyAs<TelegramMethodAckResponse>()
            .normalizeHttpStatus()
        if (!response.ok) {
            throw TelegramBotApiHttpException(
                methodName = "sendMessage",
                statusCode = response.errorCode ?: 500,
                telegramErrorCode = response.errorCode,
                description = response.description,
                parameters = response.parameters,
            )
        }
    }

    private suspend fun request(
        token: String,
        methodName: String,
        formParameters: Map<String, String>,
    ): TelegramRawResponse =
        withContext(Dispatchers.IO) {
            val request = buildRequest(token, methodName, formParameters)
            try {
                client.send(request, HttpResponse.BodyHandlers.ofString()).let { response ->
                    TelegramRawResponse(
                        httpStatus = response.statusCode(),
                        body = response.body(),
                    )
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: HttpTimeoutException) {
                throw TelegramBotApiTransportException("Telegram request timed out.", e)
            } catch (e: IOException) {
                throw TelegramBotApiTransportException("Telegram network request failed.", e)
            } catch (e: InterruptedException) {
                Thread.currentThread().interrupt()
                throw TelegramBotApiTransportException("Telegram request was interrupted.", e)
            }
        }

    private fun buildRequest(
        token: String,
        methodName: String,
        formParameters: Map<String, String>,
    ): HttpRequest {
        val requestBuilder = HttpRequest.newBuilder()
            .uri(URI.create("https://api.telegram.org/bot$token/$methodName"))
            .timeout(Duration.ofSeconds(REQUEST_TIMEOUT_SECONDS))

        return if (formParameters.isEmpty()) {
            requestBuilder.GET().build()
        } else {
            requestBuilder
                .header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString(formParameters.toFormBody()))
                .build()
        }
    }

    private inline fun <reified T> TelegramRawResponse.bodyAs(): T =
        try {
            mapper.readValue(body)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            throw TelegramBotApiTransportException("Telegram response could not be parsed.", e)
        }

    private companion object {
        const val CONNECT_TIMEOUT_SECONDS: Long = 10L
        const val REQUEST_TIMEOUT_SECONDS: Long = 35L
    }
}

internal open class TelegramBotApiException(
    message: String,
    cause: Throwable? = null,
) : RuntimeException(message, cause)

internal class TelegramBotApiTransportException(
    message: String,
    cause: Throwable? = null,
) : TelegramBotApiException(message, cause)

internal class TelegramBotApiHttpException(
    val methodName: String,
    val statusCode: Int,
    val telegramErrorCode: Int?,
    val description: String?,
    val parameters: TelegramResponseParameters?,
) : TelegramBotApiException(description ?: "Telegram API request failed.")

private data class TelegramRawResponse(
    val httpStatus: Int,
    val body: String,
)

@JsonIgnoreProperties(ignoreUnknown = true)
private data class TelegramMethodAckResponse(
    val ok: Boolean,
    val description: String? = null,
    @param:JsonProperty("error_code")
    val errorCode: Int? = null,
    val parameters: TelegramResponseParameters? = null,
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class TelegramGetMeResponse(
    val ok: Boolean,
    val result: TelegramUser? = null,
    val description: String? = null,
    @param:JsonProperty("error_code")
    val errorCode: Int? = null,
    val parameters: TelegramResponseParameters? = null,
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class TelegramUser(
    val id: Long,
    @param:JsonProperty("is_bot")
    val isBot: Boolean,
    @param:JsonProperty("first_name")
    val firstName: String,
    val username: String? = null,
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class TelegramUpdatesResponse(
    val ok: Boolean,
    val result: List<TelegramUpdate> = emptyList(),
    val description: String? = null,
    @param:JsonProperty("error_code")
    val errorCode: Int? = null,
    val parameters: TelegramResponseParameters? = null,
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class TelegramUpdate(
    @param:JsonProperty("update_id")
    val updateId: Long,
    val message: TelegramMessage? = null,
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class TelegramMessage(
    @param:JsonProperty("message_id")
    val messageId: Long,
    val chat: TelegramChat,
    val text: String? = null,
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class TelegramChat(
    val id: Long,
    val type: String? = null,
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class TelegramResponseParameters(
    @param:JsonProperty("retry_after")
    val retryAfter: Int? = null,
)

private fun Map<String, String>.toFormBody(): String =
    entries.joinToString(separator = "&") { (key, value) ->
        "${key.urlEncode()}=${value.urlEncode()}"
    }

private fun String.urlEncode(): String =
    URLEncoder.encode(this, Charsets.UTF_8)

private fun TelegramGetMeResponse.normalizeHttpStatus(): TelegramGetMeResponse =
    if (!ok && errorCode == null) copy(errorCode = 400) else this

private fun TelegramUpdatesResponse.normalizeHttpStatus(): TelegramUpdatesResponse =
    if (!ok && errorCode == null) copy(errorCode = 400) else this

private fun TelegramMethodAckResponse.normalizeHttpStatus(): TelegramMethodAckResponse =
    if (!ok && errorCode == null) copy(errorCode = 400) else this
