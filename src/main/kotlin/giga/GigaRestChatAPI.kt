package com.dumch.giga

import com.dumch.tool.ToolRunBashCommand
import com.fasterxml.jackson.module.kotlin.readValue
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.auth.Auth
import io.ktor.client.plugins.auth.providers.BearerTokens
import io.ktor.client.plugins.auth.providers.bearer
import io.ktor.client.plugins.logging.LogLevel
import io.ktor.client.plugins.logging.Logging
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.HttpHeaders
import io.ktor.http.isSuccess
import org.slf4j.LoggerFactory
import java.io.File

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

    override suspend fun uploadImage(file: File): GigaResponse.UploadFile {
        return try {
            uploadImageWithToken(file, loadAccessToken())
        } catch (e: Exception) {
            uploadImageWithToken(file, refreshAccessToken())
        }
    }

    private suspend fun uploadImageWithToken(file: File, accessToken: String): GigaResponse.UploadFile {
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
        val INSTANCE = GigaRestChatAPI(GigaAuth)
    }
}

//suspend fun main() {
//    val f = File("/Users/m1/Pictures/portrait.jpeg")
//    val resp = GigaRestChatAPI(GigaAuth).uploadImage(f)
//    LoggerFactory.getLogger("GigaRestChatAPI").info("$resp")
//}
