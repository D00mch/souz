package com.dumch.giga

import com.dumch.tool.ToolRunBashCommand
import com.dumch.tool.desktop.ToolOpenApp
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
import io.ktor.http.*
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import org.slf4j.LoggerFactory
import java.io.File
import kotlin.time.Duration.Companion.seconds

class GigaRestChatAPI(private val auth: GigaAuth) : GigaChatAPI {
    private val l = LoggerFactory.getLogger(GigaRestChatAPI::class.java)

    private val client = HttpClient(CIO) {
        gigaDefaults()
        install(Logging) {
            val envLevel = System.getenv("GIGA_LOG_LEVEL")
                ?.let { LogLevel.valueOf(it) } ?: LogLevel.INFO
            l.info("GIGA_LOG_LEVEL: $envLevel")
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

    override suspend fun message(body: GigaRequest.Chat): GigaResponse.Chat = try {
        val response = client.post(URL) {
            setBody(body)
        }
        when {
            response.status.isSuccess() -> response.body<GigaResponse.Chat.Ok>()
            response.status == HttpStatusCode.Unauthorized || response.status == HttpStatusCode.Forbidden ->
                GigaResponse.Chat.Error(response.status.value, "Authentication error: ${response.status.description}")

            else -> runCatching { response.body<GigaResponse.Chat.Error>() }
                .getOrElse {
                    GigaResponse.Chat.Error(response.status.value, response.status.description)
                }
        }
    } catch (t: Throwable) {
        l.error("Error in REST chat", t)
        GigaResponse.Chat.Error(-1, "Connection error: ${t.message}")
    }

    override suspend fun messageStream(body: GigaRequest.Chat): Flow<GigaResponse.Chat> = flow {
        try {
            client.sse(
                urlString = URL,
                request = {
                    method = HttpMethod.Post
                    setBody(body.copy(stream = true))
                }
            ) {
                incoming.collect { event ->
                    val data: String? = event.data
                    if (data == null || data == "[DONE]") {
                        return@collect
                    }
                    emit(parseStreamChunk(data))
                }
            }
        } catch (e: ClientRequestException) {
            val status = e.response.status
            val msg = if (status == HttpStatusCode.Unauthorized || status == HttpStatusCode.Forbidden) {
                "Authentication error: ${status.description}"
            } else {
                "HTTP error: ${status.description}"
            }
            emit(GigaResponse.Chat.Error(status.value, msg))
        } catch (t: Throwable) {
            l.error("Error in REST chat stream", t)
            emit(GigaResponse.Chat.Error(-1, "Connection error: ${t.message}"))
        }
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
        outputFileName: String = "downloaded_file"
    ): String? {
        val downloadsDir = File(System.getProperty("user.home"), "Downloads").apply { mkdirs() }
        val outputFile = File(downloadsDir, outputFileName)
        val result = ToolRunBashCommand.invoke(
            ToolRunBashCommand.Input(
                """
                curl -L -g 'https://gigachat.devices.sberbank.ru/api/v1/files/${fileId}/content' \
                -H 'Accept: application/octet-stream' \
                -H 'Authorization: Bearer $accessToken' \
                -o $outputFileName
                """.trimIndent(),
            )
        )

        return outputFile.absolutePath
    }

    private suspend fun loadAccessToken(): String {
        return System.getProperty("GIGA_ACCESS_TOKEN") ?: refreshAccessToken()
    }

    private suspend fun refreshAccessToken(): String {
        val apiKey = System.getenv("GIGA_KEY")
        val newToken = auth.requestToken(apiKey, "GIGACHAT_API_PERS")
        System.setProperty("GIGA_ACCESS_TOKEN", newToken)
        return newToken
    }

    companion object {
        private val URL = "https://gigachat.devices.sberbank.ru/api/v1/chat/completions"

        val INSTANCE = GigaRestChatAPI(GigaAuth)
    }
}
 
suspend fun main() {
    val api = GigaRestChatAPI.INSTANCE

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
//                    content = "Как дела?",
                    content = "Открой приложение Telegram",
                ),
            ),
            functions = listOf(
                ToolOpenApp(ToolRunBashCommand).toGiga(),
            ).map { it.fn }
        )
    )
    result.collect {
        println("Chunk: $it")
    }
}
