package ru.souz.llms.giga

import com.fasterxml.jackson.module.kotlin.readValue
import io.ktor.client.*
import io.ktor.client.call.*
import io.ktor.client.engine.cio.*
import io.ktor.client.plugins.ClientRequestException
import io.ktor.client.plugins.auth.*
import io.ktor.client.plugins.auth.providers.*
import io.ktor.client.plugins.logging.*
import io.ktor.client.plugins.sse.*
import io.ktor.client.request.*
import io.ktor.client.request.forms.formData
import io.ktor.client.request.forms.submitFormWithBinaryData
import io.ktor.client.statement.bodyAsText
import io.ktor.http.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.withContext
import org.slf4j.LoggerFactory
import ru.souz.db.SettingsProvider
import ru.souz.llms.LLMChatAPI
import ru.souz.llms.LLMMessageRole
import ru.souz.llms.LLMRequest
import ru.souz.llms.LLMResponse
import ru.souz.llms.TokenLogging
import ru.souz.llms.restJsonMapper
import ru.souz.llms.toFinishReason
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID
import kotlin.time.Duration.Companion.seconds

class GigaRestChatAPI(
    private val auth: GigaAuth,
    private val keysProvider: SettingsProvider,
    private val tokenLogging: TokenLogging,
) : LLMChatAPI {
    private val l = LoggerFactory.getLogger(GigaRestChatAPI::class.java)

    private val apiKey: String
        get() = keysProvider.gigaChatKey ?: throw IllegalStateException("GIGA_KEY is not set")

    private val client = HttpClient(CIO) {
        gigaDefaults(keysProvider)
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
        install(Auth) {
            bearer {
                loadTokens {
                    BearerTokens(loadAccessToken(), "")
                }
                refreshTokens {
                    BearerTokens(refreshAccessToken(), "")
                }
            }
        }
        install(SSE) {
            maxReconnectionAttempts = 0
            reconnectionTime = 3.seconds
        }
    }

    private val uuid = UUID.randomUUID().toString() // for cache to work

    override suspend fun message(body: LLMRequest.Chat): LLMResponse.Chat = try {
        val body = body.rmFnIds()
        val response = client.post(URL) {
            header("X-Session-ID", uuid)
            setBody(body)
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
            val body = body.rmFnIds()
            client.sse(
                urlString = URL,
                request = {
                    method = HttpMethod.Post
                    setBody(body.copy(stream = true))
                    header("X-Session-ID", uuid)
                }
            ) {
                incoming.collect { event ->
                    val data: String? = event.data
                    if (data == null || data == "[DONE]") {
                        return@collect
                    }
                    send(parseStreamChunk(data))
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
        } catch (t: Throwable) {
            l.error("Error in REST chat stream", t)
            send(LLMResponse.Chat.Error(-1, "Connection error: ${t.message}"))
        }
    }

    override suspend fun embeddings(body: LLMRequest.Embeddings): LLMResponse.Embeddings = try {
        val response = client.post(EMBEDDINGS_URL) {
            setBody(body)
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
        val response = client.get(BALANCE_URL)
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

    private fun parseStreamChunk(data: String): LLMResponse.Chat {
        val node = restJsonMapper.readTree(data)
        val choicesNode = node["choices"] ?: emptyList()

        val choices = choicesNode.mapNotNull { choice ->
            val finishReasonText = choice["finish_reason"]?.asText()
            if (finishReasonText.equals("stop", ignoreCase = true)) {
                l.info("finishReason: $finishReasonText")
                return@mapNotNull null
            }

            val delta = choice["delta"] ?: return@mapNotNull null
            val functionCallNode = delta["function_call"]
            val functionCall = if (functionCallNode != null && !functionCallNode.isNull) {
                val name = functionCallNode["name"]?.asText() ?: ""
                val argsText = functionCallNode["arguments"]?.toString() ?: "{}"
                val args: Map<String, Any> = restJsonMapper.readValue(argsText)
                LLMResponse.FunctionCall(name, args)
            } else null

            val content = delta["content"]?.asText() ?: ""
            val roleStr = delta["role"]?.asText()
            val role = roleStr?.takeIf { it.isNotBlank() }?.let { LLMMessageRole.valueOf(it) }
                ?: LLMMessageRole.assistant

            LLMResponse.Choice(
                message = LLMResponse.Message(
                    content = content,
                    role = role,
                    functionCall = functionCall,
                    functionsStateId = delta["functions_state_id"]?.asText(),
                ),
                index = choice["index"]?.asInt() ?: 0,
                finishReason = finishReasonText?.toFinishReason(),
            )
        }

        val usageNode = node["usage"]
        val usage = if (usageNode != null && !usageNode.isNull) {
            LLMResponse.Usage(
                promptTokens = usageNode["prompt_tokens"]?.asInt() ?: 0,
                completionTokens = usageNode["completion_tokens"]?.asInt() ?: 0,
                totalTokens = usageNode["total_tokens"]?.asInt() ?: 0,
                precachedTokens = usageNode["precached_prompt_tokens"]?.asInt() ?: 0,
            )
        } else {
            LLMResponse.Usage(0, 0, 0, 0)
        }

        val model = node["model"]?.asText() ?: ""
        val created = node["created"]?.asLong() ?: 0L

        return LLMResponse.Chat.Ok(
            choices = choices,
            created = created,
            model = model,
            usage = usage,
        )
    }

    private suspend fun uploadImage(file: File): LLMResponse.UploadFile {
        val mime = withContext(Dispatchers.IO) {
            Files.probeContentType(file.toPath())
        } ?: "image/jpeg"

        val bytes = withContext(Dispatchers.IO) { file.readBytes() }
        val response = client.submitFormWithBinaryData(
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
        )
        val text = response.bodyAsText()
        if (!response.status.isSuccess()) {
            throw IllegalStateException("GigaChat file upload failed: ${response.status.value}. $text")
        }
        return restJsonMapper.readValue(text)
    }

    private suspend fun downloadFileContent(fileId: String): String? {
        val response = client.get("$FILES_URL/$fileId/content") {
            header(HttpHeaders.Accept, ContentType.Application.OctetStream.toString())
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
        return System.getProperty("GIGA_ACCESS_TOKEN") ?: refreshAccessToken()
    }

    private suspend fun refreshAccessToken(): String {
        val newToken = auth.requestToken(apiKey, "GIGACHAT_API_PERS")
        System.setProperty("GIGA_ACCESS_TOKEN", newToken)
        return newToken
    }

    companion object {
        private const val URL = "https://gigachat.devices.sberbank.ru/api/v1/chat/completions"
        private const val EMBEDDINGS_URL = "https://gigachat.devices.sberbank.ru/api/v1/embeddings"
        private const val BALANCE_URL = "https://gigachat.devices.sberbank.ru/api/v1/balance"
        private const val FILES_URL = "https://gigachat.devices.sberbank.ru/api/v1/files"
    }
}

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
