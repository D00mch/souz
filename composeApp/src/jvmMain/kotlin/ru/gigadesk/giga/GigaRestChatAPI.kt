package ru.gigadesk.giga

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.SerializationFeature
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import ru.gigadesk.tool.ToolRunBashCommand
import ru.gigadesk.tool.application.ToolOpen
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
import io.ktor.client.statement.bodyAsText
import io.ktor.http.*
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import org.kodein.di.DI
import org.kodein.di.instance
import org.slf4j.LoggerFactory
import ru.gigadesk.db.SettingsProvider
import ru.gigadesk.di.mainDiModule
import java.io.File
import java.util.UUID
import kotlin.concurrent.atomics.AtomicReference
import kotlin.concurrent.atomics.ExperimentalAtomicApi
import kotlin.time.Duration.Companion.seconds

@OptIn(ExperimentalAtomicApi::class)
class GigaRestChatAPI(
    private val auth: GigaAuth,
    private val keysProvider: SettingsProvider,
    private val logObjectMapper: ObjectMapper = jacksonObjectMapper().enable(SerializationFeature.INDENT_OUTPUT)
) : GigaChatAPI {
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

    private val uuid = UUID.randomUUID().toString() // для того, чтобы работал кеш

    private val currentSessionTokensUsage = AtomicReference(GigaResponse.Usage(0, 0, 0, 0))

    override suspend fun message(body: GigaRequest.Chat): GigaResponse.Chat = try {
        val response = client.post(URL) {
            header("X-Session-ID", uuid)
            setBody(body)
        }
        when {
            response.status.isSuccess() -> {
                val result = response.body<GigaResponse.Chat.Ok>()
                logTokenUsage(result, body)
                result
            }
            response.status == HttpStatusCode.Unauthorized || response.status == HttpStatusCode.Forbidden ->
                GigaResponse.Chat.Error(response.status.value, "Authentication error: ${response.status.description}")

            else -> runCatching { GigaResponse.Chat.Error(response.status.value, response.bodyAsText()) }
                .getOrElse {
                    GigaResponse.Chat.Error(response.status.value, response.status.description)
                }
        }
    } catch (t: Throwable) {
        l.error("Error in REST chat", t)
        GigaResponse.Chat.Error(-1, "Connection error: ${t.message}")
    }

    fun logTokenUsage(result: GigaResponse.Chat.Ok, body: GigaRequest.Chat) {
        val newCurrentTokensUsage = currentSessionTokensUsage.load() + result.usage
        currentSessionTokensUsage.store(newCurrentTokensUsage)

        val (_, _, spent, cached) = result.usage
        val (_, _, sSpent, sCached) = newCurrentTokensUsage
        l.info("Chat response: ")
        println(
            """
            |--  History.len: ${body.messages.size},  Functions.len: ${body.functions.size}
            |--  Tokens spent: $spent, cached: $cached, per session spent: $sSpent, cached: $sCached
            |--  Choice.len: ${result.choices.size}, Last choice:"
            |${logObjectMapper.writeValueAsString(result.choices.lastOrNull())}
            """.trimMargin()
        )
    }

    override suspend fun messageStream(body: GigaRequest.Chat): Flow<GigaResponse.Chat> = channelFlow {
        try {
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
            send(GigaResponse.Chat.Error(status.value, msg))
        } catch (t: Throwable) {
            l.error("Error in REST chat stream", t)
            send(GigaResponse.Chat.Error(-1, "Connection error: ${t.message}"))
        }
    }

    override suspend fun embeddings(body: GigaRequest.Embeddings): GigaResponse.Embeddings = try {
        val response = client.post(EMBEDDINGS_URL) {
            setBody(body)
        }
        l.info("embeddings status: ${response.status}")
        when {
            response.status.isSuccess() -> response.body<GigaResponse.Embeddings.Ok>()
            response.status == HttpStatusCode.Unauthorized || response.status == HttpStatusCode.Forbidden ->
                GigaResponse.Embeddings.Error(
                    response.status.value,
                    "Authentication error: ${response.status.description}"
                )

            else -> runCatching { response.body<GigaResponse.Embeddings.Error>() }
                .getOrElse {
                    GigaResponse.Embeddings.Error(response.status.value, response.status.description)
                }
        }
    } catch (t: Throwable) {
        l.error("Error in REST embeddings", t)
        GigaResponse.Embeddings.Error(-1, "Connection error: ${t.message}")
    }

    override suspend fun uploadFile(file: File): GigaResponse.UploadFile {
        return try {
            uploadImageWithToken(file, loadAccessToken())
        } catch (e: Exception) {
            l.error("Error in REST chat", e)
            uploadImageWithToken(file, refreshAccessToken())
        }
    }

    override suspend fun downloadFile(fileId: String): String? {
        return try {
            downloadFileWithToken(fileId, loadAccessToken())
        } catch (e: Exception) {
            l.error("Error in REST chat", e)
            downloadFileWithToken(fileId, refreshAccessToken())
        }
    }

    override suspend fun balance(): GigaResponse.Balance = try {
        val response = client.get(BALANCE_URL)
        when {
            response.status.isSuccess() -> response.body<GigaResponse.Balance.Ok>()
            response.status == HttpStatusCode.Unauthorized || response.status == HttpStatusCode.Forbidden ->
                GigaResponse.Balance.Error(
                    response.status.value,
                    "Authentication error: ${response.status.description}"
                )

            else -> runCatching { response.body<GigaResponse.Balance.Error>() }
                .getOrElse {
                    GigaResponse.Balance.Error(response.status.value, response.status.description)
                }
        }
    } catch (t: Throwable) {
        l.error("Error in REST balance", t)
        GigaResponse.Balance.Error(-1, "Connection error: ${t.message}")
    }

    private fun parseStreamChunk(data: String): GigaResponse.Chat {
        val node = objectMapper.readTree(data)
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
                val args: Map<String, Any> = objectMapper.readValue(argsText)
                GigaResponse.FunctionCall(name, args)
            } else null

            val content = delta["content"]?.asText() ?: ""
            val roleStr = delta["role"]?.asText()
            val role = roleStr?.takeIf { it.isNotBlank() }?.let { GigaMessageRole.valueOf(it) }
                ?: GigaMessageRole.assistant

            GigaResponse.Choice(
                message = GigaResponse.Message(
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
            GigaResponse.Usage(
                promptTokens = usageNode["prompt_tokens"]?.asInt() ?: 0,
                completionTokens = usageNode["completion_tokens"]?.asInt() ?: 0,
                totalTokens = usageNode["total_tokens"]?.asInt() ?: 0,
                precachedTokens = usageNode["precached_prompt_tokens"]?.asInt() ?: 0,
            )
        } else {
            GigaResponse.Usage(0, 0, 0, 0)
        }

        val model = node["model"]?.asText() ?: ""
        val created = node["created"]?.asLong() ?: 0L

        return GigaResponse.Chat.Ok(
            choices = choices,
            created = created,
            model = model,
            usage = usage,
        )
    }

    private fun uploadImageWithToken(file: File, accessToken: String): GigaResponse.UploadFile {
        val result = ToolRunBashCommand.invoke(
            ToolRunBashCommand.Input(
                """
                curl -X POST 'https://gigachat.devices.sberbank.ru/api/v1/files' \
                     -H "Authorization: Bearer $accessToken" \
                     -F "file=@${file.path};type=image/jpeg" \
                     -F "purpose=general"
                """.trimIndent(),
            )
        )
        val body = result.lines().last()
        return objectMapper.readValue(body)
    }

    private fun downloadFileWithToken(
        fileId: String,
        accessToken: String,
    ): String? {
        val documentsDir = File(System.getProperty("user.home"), "SluxxDocuments").apply { mkdirs() }
        val command = """
            cd "${documentsDir.absolutePath}" && \
            curl -s -L -g 'https://gigachat.devices.sberbank.ru/api/v1/files/${fileId}/content' \
            -H 'Accept: application/octet-stream' \
            -H 'Authorization: Bearer $accessToken' \
            -OJ -w '%{filename_effective}' -o /dev/null
        """.trimIndent()
        val fileName = ToolRunBashCommand.invoke(
            ToolRunBashCommand.Input(command)
        )

        return File(documentsDir, fileName).absolutePath
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
        private val URL = "https://gigachat.devices.sberbank.ru/api/v1/chat/completions"
        private val EMBEDDINGS_URL = "https://gigachat.devices.sberbank.ru/api/v1/embeddings"
        private val BALANCE_URL = "https://gigachat.devices.sberbank.ru/api/v1/balance"
    }
}

suspend fun main() {
    val di = DI.invoke { import(mainDiModule) }
    val api: GigaRestChatAPI by di.instance()

    val systemPrompt = GigaRequest.Message(
        role = GigaMessageRole.system,
        content = """
                Ты — помощник человека с ограниченными возможностями. Будь полезным. Говори только по существу. 
                Если какую-то за дачу можно решить c помощью имеющихся функций, сделай, 
                а не проси пользователя сделать это. Если сомневаешься, уточни.
            """.trimIndent()
    )

    val result = api.messageStream(
        GigaRequest.Chat(
            model = GigaModel.Pro.alias,
            stream = true,
            messages = listOf(
                systemPrompt,
                GigaRequest.Message(
                    role = GigaMessageRole.user,
                    content = "Открой приложение Telegram. Оно расположено по пути /Applications/Telegram.app",
                ),
            ),
            functions = listOf(
                ToolOpen(ToolRunBashCommand).toGiga(),
            ).map { it.fn }
        )
    )
    println(result.collect { value -> println(value) })
}
