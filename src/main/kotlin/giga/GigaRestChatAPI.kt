package com.dumch.giga

import com.dumch.tool.ToolRunBashCommand
import com.dumch.tool.desktop.ToolOpenApp
import com.fasterxml.jackson.module.kotlin.readValue
import io.ktor.client.*
import io.ktor.client.call.*
import io.ktor.client.engine.cio.*
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

    override suspend fun message(body: GigaRequest.Chat): GigaResponse.Chat {
        val response = client.post("https://gigachat.devices.sberbank.ru/api/v1/chat/completions") {
            setBody(body)
        }
        return when {
            response.status.isSuccess() -> response.body<GigaResponse.Chat.Ok>()
            else -> response.body<GigaResponse.Chat.Error>()
        }
    }

    override suspend fun messageStream(body: GigaRequest.Chat): Flow<GigaResponse.Chunk> = flow {
        client.sse(
            urlString = URL,
            request = {
                method = HttpMethod.Post
                setBody(body.copy(stream = true))
            }
        ) {
            incoming.collect { event ->
                val data: String? = event.data
                if (data == null ||  data == "[DONE]") {
                    return@collect
                }
                parseStreamChunk(data).forEach { emit(it) }
            }
        }
    }

    override suspend fun uploadImage(file: File): GigaResponse.UploadFile {
        return try {
            uploadImageWithToken(file, loadAccessToken())
        } catch (e: Exception) {
            l.error("Error in REST chat", e)
            uploadImageWithToken(file, refreshAccessToken())
        }
    }

    private fun parseStreamChunk(data: String): List<GigaResponse.Chunk> {
        val node = objectMapper.readTree(data)
        val choices = node["choices"] ?: return emptyList()
        val chunks = mutableListOf<GigaResponse.Chunk>()
        for (choice in choices) {
            val index = choice["index"].asInt()
            val delta = choice["delta"] ?: continue
            val functionCall = delta["function_call"]
            if (functionCall != null && !functionCall.isNull) {
                val name = functionCall["name"].asText()
                val argsText = functionCall["arguments"]?.toString() ?: "{}"
                val args: Map<String, Any> = objectMapper.readValue(argsText)
                chunks.add(GigaResponse.FunctionCall(name, args))
            } else {
                val content = delta["content"]?.asText() ?: ""
                val roleStr = delta["role"]?.asText()
                val role = roleStr?.takeIf { it.isNotBlank() }?.let { GigaMessageRole.valueOf(it) }
                    ?: GigaMessageRole.assistant
                chunks.add(
                    GigaResponse.ChatChunk(
                        index = index,
                        delta = GigaResponse.Delta(content = content, role = role)
                    )
                )
            }
        }
        return chunks
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
                    content = "Как дела?",
//                    content = "Открой приложение Telegram",
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
