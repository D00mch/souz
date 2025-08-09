package com.dumch.giga

import com.dumch.tool.ToolRunBashCommand
import com.fasterxml.jackson.module.kotlin.readValue
import io.ktor.client.*
import io.ktor.client.call.*
import io.ktor.client.engine.cio.*
import io.ktor.client.plugins.auth.*
import io.ktor.client.plugins.auth.providers.*
import io.ktor.client.plugins.logging.*
import io.ktor.client.request.*
import io.ktor.http.*
import java.io.File

class GigaChatAPI(private val auth: GigaAuth) {
    private val client = HttpClient(CIO) {
        var token = "" // get form env, or cache, or db
        gigaDefaults()
        install(Logging) {
            level = LogLevel.INFO
            sanitizeHeader { it.equals(HttpHeaders.Authorization, true) }
        }
        install(Auth) {
            bearer {
                loadTokens {
                    BearerTokens(token, "")
                }
                refreshTokens {
                    BearerTokens(getAccessToken(), "")
                }
            }
        }
    }

    suspend fun message(body: GigaRequest.Chat): GigaResponse.Chat {
        val response = client.post("https://gigachat.devices.sberbank.ru/api/v1/chat/completions") {
            setBody(body)
        }
        return when {
            response.status.isSuccess() -> response.body<GigaResponse.Chat.Ok>()
            else -> response.body<GigaResponse.Chat.Error>()
        }
    }

    suspend fun uploadImage(file: File): GigaResponse.UploadFile {
        val token = getAccessToken()
        val result = ToolRunBashCommand.invoke(
            ToolRunBashCommand.Input(
                """
                curl -X POST 'https://gigachat.devices.sberbank.ru/api/v1/files' \
                     -H "Authorization: Bearer $token" \
                     -F "file=@${file.path};type=image/jpeg" \
                     -F "purpose=general"
                """.trimIndent()
            )
        )
        val body = result.lines().last()
        return objectMapper.readValue(body)
    }

    private suspend fun getAccessToken(): String {
        val gigaKey = System.getenv("GIGA_KEY")
        return auth.requestToken(gigaKey, "GIGACHAT_API_PERS")
    }
}

//suspend fun main() {
//    val f = File("/Users/m1/Pictures/portrait.jpeg")
//    val resp = GigaChatAPI(GigaAuth).uploadImage(f)
//    LoggerFactory.getLogger("GigaChatAPI").info("$resp")
//}
