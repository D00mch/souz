package ru.souz.llms.giga

import com.fasterxml.jackson.annotation.JsonInclude
import com.fasterxml.jackson.annotation.JsonProperty
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.module.kotlin.readValue
import io.ktor.client.*
import io.ktor.client.call.*
import io.ktor.client.engine.cio.*
import io.ktor.client.plugins.ClientRequestException
import io.ktor.client.plugins.logging.*
import io.ktor.client.plugins.sse.*
import io.ktor.client.plugins.timeout
import io.ktor.client.request.*
import io.ktor.client.request.forms.formData
import io.ktor.client.request.forms.submitFormWithBinaryData
import io.ktor.client.statement.bodyAsText
import io.ktor.client.statement.HttpResponse
import io.ktor.http.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.slf4j.LoggerFactory
import ru.souz.db.SettingsProvider
import ru.souz.llms.DEFAULT_MAX_TOKENS
import ru.souz.llms.LLMChatAPI
import ru.souz.llms.LLMMessageRole
import ru.souz.llms.LLMRequest
import ru.souz.llms.LLMResponse
import ru.souz.llms.ProviderSettings
import ru.souz.llms.TokenLogging
import ru.souz.llms.restJsonMapper
import ru.souz.llms.toFinishReason
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID
import kotlin.time.Duration.Companion.seconds

class GigaRestChatAPI private constructor(
    private val auth: GigaAuth,
    private val settingsProvider: ProviderSettings,
    private val tokenLogging: TokenLogging,
    private val configuredApiKey: () -> String?,
    providedHttpClient: HttpClient?,
) : LLMChatAPI {
    constructor(auth: GigaAuth, settingsProvider: SettingsProvider, tokenLogging: TokenLogging) : this(
        auth = auth,
        settingsProvider = settingsProvider,
        tokenLogging = tokenLogging,
        configuredApiKey = { settingsProvider.gigaChatKey },
        providedHttpClient = null,
    )

    constructor(
        auth: GigaAuth,
        settingsProvider: SettingsProvider,
        tokenLogging: TokenLogging,
        httpClient: HttpClient,
    ) : this(
        auth = auth,
        settingsProvider = settingsProvider,
        tokenLogging = tokenLogging,
        configuredApiKey = { settingsProvider.gigaChatKey },
        providedHttpClient = httpClient,
    )

    constructor(
        auth: GigaAuth,
        settingsProvider: ProviderSettings,
        tokenLogging: TokenLogging,
        apiKey: String,
    ) : this(
        auth = auth,
        settingsProvider = settingsProvider,
        tokenLogging = tokenLogging,
        configuredApiKey = { apiKey },
        providedHttpClient = null,
    )

    constructor(
        auth: GigaAuth,
        settingsProvider: ProviderSettings,
        tokenLogging: TokenLogging,
        apiKey: String,
        httpClient: HttpClient,
    ) : this(
        auth = auth,
        settingsProvider = settingsProvider,
        tokenLogging = tokenLogging,
        configuredApiKey = { apiKey },
        providedHttpClient = httpClient,
    )

    private val l = LoggerFactory.getLogger(GigaRestChatAPI::class.java)

    private val apiKey: String
        get() = configuredApiKey() ?: throw IllegalStateException("GIGA_KEY is not set")

    private val usesSharedHttpClient = providedHttpClient != null
    private val client = providedHttpClient ?: HttpClient(CIO) {
        gigaDefaults(settingsProvider)
        install(Logging) {
            val envLevel = System.getenv("GIGA_LOG_LEVEL")
                ?.let { LogLevel.valueOf(it) } ?: LogLevel.INFO
            this@GigaRestChatAPI.l.info("GIGA_LOG_LEVEL: $envLevel")
            logger = object : Logger {
                override fun log(message: String) {
                    this@GigaRestChatAPI.l.debug(message)
                }
            }
            level = envLevel
            sanitizeHeader { it.equals(HttpHeaders.Authorization, true) }
        }
        install(SSE) {
            maxReconnectionAttempts = 0
            reconnectionTime = 3.seconds
        }
    }

    private val uuid = UUID.randomUUID().toString() // for cache to work
    private val accessTokenMutex = Mutex()
    private var accessTokenState: AccessTokenState? = null

    override suspend fun message(body: LLMRequest.Chat): LLMResponse.Chat = try {
        val requestBody = body.toGigaChatRequest()
        val response = authorizedRequest { token ->
            client.post(URL) {
                applyRequestSettings(token)
                header("X-Session-ID", uuid)
                setBody(requestBody)
            }
        }
        when {
            response.status.isSuccess() -> {
                val result = response.body<LLMResponse.Chat.Ok>()
                l.info("Chat response: ")
                tokenLogging.logTokenUsage(result, body)
                result
            }
            response.status == HttpStatusCode.Unauthorized || response.status == HttpStatusCode.Forbidden ->
                LLMResponse.Chat.Error(response.status.value, "Authentication error: ${response.status.description}")

            else -> runCatching { LLMResponse.Chat.Error(response.status.value, response.bodyAsText()) }
                .getOrElse {
                    LLMResponse.Chat.Error(response.status.value, response.status.description)
                }
        }
    } catch (e: ClientRequestException) {
        val status = e.response.status
        val msg = if (status == HttpStatusCode.Unauthorized || status == HttpStatusCode.Forbidden) {
            "Authentication error: ${status.description}"
        } else {
            "HTTP error: ${e.response.bodyAsText()}"
        }
        LLMResponse.Chat.Error(status.value, msg)
    } catch (t: Throwable) {
        l.error("Error in REST chat", t)
        LLMResponse.Chat.Error(-1, "Connection error: ${t.message}")
    }

    override suspend fun messageStream(body: LLMRequest.Chat): Flow<LLMResponse.Chat> = channelFlow {
        try {
            val requestBody = body.toGigaChatRequest()
            withAuthorizedSse { token ->
                client.sse(
                    urlString = URL,
                    request = {
                        method = HttpMethod.Post
                        applyRequestSettings(token)
                        setBody(requestBody.copy(stream = true))
                        header("X-Session-ID", uuid)
                    }
                ) {
                    incoming.collect { event ->
                        val data: String? = event.data
                        if (data == null || data == "[DONE]") {
                            return@collect
                        }
                        send(parseGigaStreamChunk(data))
                    }
                }
            }
        } catch (e: ClientRequestException) {
            val status = e.response.status
            val msg = if (status == HttpStatusCode.Unauthorized || status == HttpStatusCode.Forbidden) {
                "Authentication error: ${status.description}"
            } else {
                "HTTP error: ${e.response.bodyAsText()}"
            }
            send(LLMResponse.Chat.Error(status.value, msg))
        } catch (e: SSEClientException) {
            val status = e.response?.status
            val message = if (status?.isAuthenticationFailure() == true) {
                "Authentication error: ${status.description}"
            } else {
                "HTTP error: ${e.message}"
            }
            send(LLMResponse.Chat.Error(status?.value ?: -1, message))
        } catch (t: Throwable) {
            l.error("Error in REST chat stream", t)
            send(LLMResponse.Chat.Error(-1, "Connection error: ${t.message}"))
        }
    }

    override suspend fun embeddings(body: LLMRequest.Embeddings): LLMResponse.Embeddings = try {
        val response = authorizedRequest { token ->
            client.post(EMBEDDINGS_URL) {
                applyRequestSettings(token)
                setBody(body)
            }
        }
        l.info("embeddings status: ${response.status}")
        when {
            response.status.isSuccess() -> response.body<LLMResponse.Embeddings.Ok>()
            response.status == HttpStatusCode.Unauthorized || response.status == HttpStatusCode.Forbidden ->
                LLMResponse.Embeddings.Error(
                    response.status.value,
                    "Authentication error: ${response.status.description}"
                )

            else -> runCatching { response.body<LLMResponse.Embeddings.Error>() }
                .getOrElse {
                    LLMResponse.Embeddings.Error(response.status.value, response.status.description)
                }
        }
    } catch (t: Throwable) {
        l.error("Error in REST embeddings", t)
        LLMResponse.Embeddings.Error(-1, "Connection error: ${t.message}")
    }

    override suspend fun uploadFile(file: File): LLMResponse.UploadFile {
        return uploadImage(file)
    }

    override suspend fun downloadFile(fileId: String): String? {
        return downloadFileContent(fileId)
    }

    override suspend fun balance(): LLMResponse.Balance = try {
        val response = authorizedRequest { token ->
            client.get(BALANCE_URL) {
                applyRequestSettings(token)
            }
        }
        when {
            response.status.isSuccess() -> response.body<LLMResponse.Balance.Ok>()
            response.status == HttpStatusCode.Unauthorized || response.status == HttpStatusCode.Forbidden ->
                LLMResponse.Balance.Error(
                    response.status.value,
                    "Authentication error: ${response.status.description}"
                )

            else -> runCatching { response.body<LLMResponse.Balance.Error>() }
                .getOrElse {
                    LLMResponse.Balance.Error(response.status.value, response.status.description)
                }
        }
    } catch (t: Throwable) {
        l.error("Error in REST balance", t)
        LLMResponse.Balance.Error(-1, "Connection error: ${t.message}")
    }

    private suspend fun uploadImage(file: File): LLMResponse.UploadFile {
        val mime = withContext(Dispatchers.IO) {
            Files.probeContentType(file.toPath())
        } ?: "image/jpeg"

        val bytes = withContext(Dispatchers.IO) { file.readBytes() }
        val response = authorizedRequest { token ->
            client.submitFormWithBinaryData(
                url = FILES_URL,
                formData = formData {
                    append(
                        key = "file",
                        value = bytes,
                        headers = Headers.build {
                            append(HttpHeaders.ContentDisposition, "form-data; name=\"file\"; filename=\"${file.name}\"")
                            append(HttpHeaders.ContentType, mime)
                        },
                    )
                    append("purpose", "general")
                },
            ) {
                applyRequestSettings(token, jsonContent = false)
            }
        }
        val text = response.bodyAsText()
        if (!response.status.isSuccess()) {
            throw IllegalStateException("GigaChat file upload failed: ${response.status.value}. $text")
        }
        return restJsonMapper.readValue(text)
    }

    private suspend fun downloadFileContent(fileId: String): String? {
        val response = authorizedRequest { token ->
            client.get("$FILES_URL/$fileId/content") {
                applyRequestSettings(
                    token = token,
                    accept = ContentType.Application.OctetStream,
                    jsonContent = false,
                )
            }
        }
        if (!response.status.isSuccess()) {
            return null
        }
        val fileName = response.headers[HttpHeaders.ContentDisposition]
            ?.let(::contentDispositionFileName)
            ?.takeIf { it.isNotBlank() }
            ?: "$fileId.bin"
        val bytes = response.body<ByteArray>()
        val target = withContext(Dispatchers.IO) {
            val directory = Files.createDirectories(
                Path.of(System.getProperty("java.io.tmpdir")).resolve("souz-giga-downloads"),
            )
            val path = uniqueDownloadPath(directory, sanitizeFileName(fileName))
            Files.write(path, bytes)
            path
        }
        return target.toAbsolutePath().toString()
    }

    private suspend fun loadAccessToken(): String {
        val currentApiKey = apiKey
        return accessTokenMutex.withLock {
            accessTokenState
                ?.takeIf { it.apiKey == currentApiKey }
                ?.accessToken
                ?: requestAccessToken(currentApiKey).also { token ->
                    accessTokenState = AccessTokenState(currentApiKey, token)
                }
        }
    }

    private suspend fun refreshAccessToken(rejectedAccessToken: String): String {
        val currentApiKey = apiKey
        return accessTokenMutex.withLock {
            accessTokenState
                ?.takeIf { it.apiKey == currentApiKey && it.accessToken != rejectedAccessToken }
                ?.accessToken
                ?: requestAccessToken(currentApiKey).also { token ->
                    accessTokenState = AccessTokenState(currentApiKey, token)
                }
        }
    }

    private suspend fun requestAccessToken(apiKey: String): String =
        auth.requestToken(apiKey, "GIGACHAT_API_PERS")

    private suspend fun authorizedRequest(
        request: suspend (accessToken: String) -> HttpResponse,
    ): HttpResponse {
        val initialAccessToken = loadAccessToken()
        val initialResponse = request(initialAccessToken)
        if (!initialResponse.status.isAuthenticationFailure()) {
            return initialResponse
        }

        runCatching { initialResponse.bodyAsText() }
        val refreshedAccessToken = refreshAccessToken(initialAccessToken)
        return request(refreshedAccessToken)
    }

    private suspend fun withAuthorizedSse(
        request: suspend (accessToken: String) -> Unit,
    ) {
        val initialAccessToken = loadAccessToken()
        try {
            request(initialAccessToken)
        } catch (e: SSEClientException) {
            if (e.response?.status?.isAuthenticationFailure() != true) {
                throw e
            }
            request(refreshAccessToken(initialAccessToken))
        }
    }

    private fun HttpRequestBuilder.applyRequestSettings(
        token: String,
        accept: ContentType = ContentType.Application.Json,
        jsonContent: Boolean = true,
    ) {
        if (usesSharedHttpClient) {
            if (jsonContent) {
                header(HttpHeaders.ContentType, ContentType.Application.Json)
            }
            header(HttpHeaders.Accept, accept)
            header(HttpHeaders.UserAgent, "Souz")
            header("RqUID", UUID.randomUUID().toString())
            timeout {
                requestTimeoutMillis = settingsProvider.requestTimeoutMillis
            }
        } else if (accept != ContentType.Application.Json) {
            header(HttpHeaders.Accept, accept)
        }
        header(HttpHeaders.Authorization, "Bearer $token")
    }

    companion object {
        private const val BASE_URL = "https://api.giga.chat/v1"
        private const val URL = "$BASE_URL/chat/completions"
        private const val EMBEDDINGS_URL = "$BASE_URL/embeddings"
        private const val BALANCE_URL = "$BASE_URL/balance"
        private const val FILES_URL = "$BASE_URL/files"
    }
}

private data class AccessTokenState(
    val apiKey: String,
    val accessToken: String,
)

private fun HttpStatusCode.isAuthenticationFailure(): Boolean =
    this == HttpStatusCode.Unauthorized || this == HttpStatusCode.Forbidden

internal data class GigaChatRequest(
    val model: String,
    val messages: List<GigaMessage>,
    @field:JsonProperty("function_call")
    val functionCall: Any = "auto",
    val functions: List<LLMRequest.Function> = emptyList(),
    val temperature: Float? = null,
    @field:JsonInclude(JsonInclude.Include.NON_NULL)
    @field:JsonProperty("top_p") val topP: Float? = null,
    val stream: Boolean = false,
    @field:JsonProperty("max_tokens")
    val maxTokens: Int = DEFAULT_MAX_TOKENS,
    @field:JsonInclude(JsonInclude.Include.NON_NULL)
    @field:JsonProperty("repetition_penalty") val repetitionPenalty: Float? = null,
    @field:JsonProperty("update_interval") val updateInterval: Int? = 0,
    @field:JsonInclude(JsonInclude.Include.NON_NULL)
    @field:JsonProperty("reasoning_effort") val reasoningEffort: String? = null,
    @field:JsonInclude(JsonInclude.Include.NON_NULL)
    @field:JsonProperty("response_format") val responseFormat: Map<String, Any?>? = null,
)

internal data class GigaMessage(
    val role: LLMMessageRole,
    val content: String,
    @field:JsonInclude(JsonInclude.Include.NON_NULL)
    @field:JsonProperty("functions_state_id") val functionsStateId: String? = null,
    @field:JsonInclude(JsonInclude.Include.NON_NULL)
    val attachments: List<String>? = null,
    @field:JsonInclude(JsonInclude.Include.NON_NULL)
    val name: String? = null,
    @field:JsonInclude(JsonInclude.Include.NON_NULL)
    @field:JsonProperty("function_call") val functionCall: GigaFunctionCall? = null,
)

internal data class GigaFunctionCall(
    val name: String,
    val arguments: JsonNode,
)

internal fun LLMRequest.Chat.toGigaChatRequest(): GigaChatRequest {
    val body = rmFnIds()
    return GigaChatRequest(
        model = body.model,
        messages = body.messages.map { it.toGigaMessage() },
        functionCall = body.functionCall,
        functions = body.functions.map { fn ->
            fn.copy(
                parameters = fn.parameters.toGigaSchema(),
                returnParameters = fn.returnParameters?.toGigaSchema(),
            )
        },
        temperature = body.temperature,
        topP = body.topP,
        stream = body.stream,
        maxTokens = body.maxTokens,
        repetitionPenalty = body.repetitionPenalty,
        updateInterval = body.updateInterval,
        reasoningEffort = body.reasoningEffort,
        responseFormat = body.responseFormat,
    )
}

private fun LLMRequest.Message.toGigaMessage(): GigaMessage = GigaMessage(
    role = role,
    content = content,
    functionsStateId = functionsStateId,
    attachments = attachments,
    name = name,
    functionCall = functionCall?.toGigaFunctionCall(),
)

private fun LLMRequest.FunctionCall.toGigaFunctionCall(): GigaFunctionCall =
    GigaFunctionCall(
        name = name,
        arguments = arguments.toGigaFunctionArgumentsNode(),
    )

private fun String.toGigaFunctionArgumentsNode(): JsonNode =
    runCatching { restJsonMapper.readTree(this) }
        .getOrNull()
        ?.takeIf { it.isObject }
        ?: restJsonMapper.createObjectNode()

private fun LLMRequest.Parameters.toGigaSchema(): LLMRequest.Parameters = copy(
    properties = properties.mapValues { it.value.toGigaSchemaProperty() }
)

private fun LLMRequest.Property.toGigaSchemaProperty(): LLMRequest.Property = copy(
    items = if (type == "array") {
        items?.toGigaSchemaProperty() ?: gigaUnconstrainedObjectProperty()
    } else {
        items?.toGigaSchemaProperty()
    },
    properties = if (type == "object") {
        properties.orEmpty().mapValues { it.value.toGigaSchemaProperty() }
    } else {
        properties?.mapValues { it.value.toGigaSchemaProperty() }
    },
)

private fun gigaUnconstrainedObjectProperty(): LLMRequest.Property =
    LLMRequest.Property(type = "object", properties = emptyMap())

internal fun parseGigaStreamChunk(data: String): LLMResponse.Chat {
    val node = restJsonMapper.readTree(data)
    val choicesNode = node["choices"] ?: emptyList<JsonNode>()

    val choices = choicesNode.mapNotNull { choice ->
        val finishReasonText = choice["finish_reason"]?.asText()
        val delta = choice["delta"] ?: choice["message"] ?: return@mapNotNull null
        val functionCall = delta["function_call"]?.takeUnless { it.isNull }?.toGigaFunctionCall()

        val role = delta["role"]?.asText()
            ?.takeIf { it.isNotBlank() }
            ?.let { runCatching { LLMMessageRole.valueOf(it) }.getOrNull() }
            ?: LLMMessageRole.assistant

        LLMResponse.Choice(
            message = LLMResponse.Message(
                content = delta["content"]?.asText() ?: "",
                role = role,
                functionCall = functionCall,
                functionsStateId = delta["functions_state_id"]?.asText(),
                reasoningContent = delta["reasoning_content"]?.asText(),
                created = delta["created"]?.asLong(),
                name = delta["name"]?.asText(),
            ),
            index = choice["index"]?.asInt() ?: 0,
            finishReason = finishReasonText?.toFinishReason(),
        )
    }

    return LLMResponse.Chat.Ok(
        choices = choices,
        created = node["created"]?.asLong() ?: 0L,
        model = node["model"]?.asText() ?: "",
        usage = node["usage"]?.takeUnless { it.isNull }?.toGigaUsage() ?: LLMResponse.Usage(0, 0, 0, 0),
    )
}

private fun JsonNode.toGigaFunctionCall(): LLMResponse.FunctionCall {
    val name = this["name"]?.asText() ?: ""
    val arguments = this["arguments"]?.toGigaFunctionArguments() ?: emptyMap()
    return LLMResponse.FunctionCall(name, arguments)
}

private fun JsonNode.toGigaFunctionArguments(): Map<String, Any> {
    val argumentsNode = if (isTextual) restJsonMapper.readTree(asText()) else this
    if (!argumentsNode.isObject) return emptyMap()
    return restJsonMapper.convertValue(argumentsNode, Map::class.java)
        .entries
        .mapNotNull { (key, value) ->
            if (key is String && value != null) key to value else null
        }
        .toMap()
}

private fun JsonNode.toGigaUsage(): LLMResponse.Usage = LLMResponse.Usage(
    promptTokens = this["prompt_tokens"]?.asInt() ?: 0,
    completionTokens = this["completion_tokens"]?.asInt() ?: 0,
    totalTokens = this["total_tokens"]?.asInt() ?: 0,
    precachedTokens = this["precached_prompt_tokens"]?.asInt() ?: 0,
)

private fun contentDispositionFileName(value: String): String? =
    value.split(';')
        .asSequence()
        .map { it.trim() }
        .firstOrNull { it.startsWith("filename=", ignoreCase = true) }
        ?.substringAfter('=')
        ?.trim()
        ?.removeSurrounding("\"")

private fun sanitizeFileName(raw: String): String =
    raw.replace(Regex("[\\\\/:*?\"<>|\\p{Cntrl}]"), "_")
        .trim()
        .ifBlank { "giga-file.bin" }

private fun uniqueDownloadPath(directory: Path, fileName: String): Path {
    val base = fileName.substringBeforeLast('.', fileName)
    val extension = fileName.substringAfterLast('.', "").takeIf { it != fileName && it.isNotBlank() }
    var candidate = directory.resolve(fileName)
    var index = 1
    while (Files.exists(candidate)) {
        val nextName = if (extension == null) {
            "$base-$index"
        } else {
            "$base-$index.$extension"
        }
        candidate = directory.resolve(nextName)
        index += 1
    }
    return candidate
}
