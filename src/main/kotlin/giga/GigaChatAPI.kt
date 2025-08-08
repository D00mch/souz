package com.dumch.giga

import io.ktor.client.*
import io.ktor.client.call.*
import io.ktor.client.engine.cio.*
import io.ktor.client.plugins.auth.*
import io.ktor.client.plugins.auth.providers.*
import io.ktor.client.request.*
import io.ktor.client.request.forms.*
import io.ktor.http.*
import java.io.File

class GigaChatAPI(private val auth: GigaAuth) {
    private val client = HttpClient(CIO) {
        var token = "" // get form env, or cache, or db
        val gigaKey = System.getenv("GIGA_KEY")
        gigaDefaults()
        install(Auth) {
            bearer {
                loadTokens {
                    BearerTokens(token, "")
                }
                refreshTokens {
                    token = auth.requestToken(gigaKey, "GIGACHAT_API_PERS")
                    BearerTokens(token, "")
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

    suspend fun uploadImage(image: ByteArray, filename: String): GigaResponse.UploadFile {
        val response = client.post("https://gigachat.devices.sberbank.ru/api/v1/files") {
            contentType(ContentType.MultiPart.FormData)
            setBody(
                MultiPartFormDataContent(
                    formData {
                        append("purpose", "general")
                        append(
                            key = "file",
                            value = image,
                            headers = Headers.build {
                                append(
                                    HttpHeaders.ContentDisposition,
                                    "form-data; name=\"file\"; filename=\"$filename\""
                                )
                                append(HttpHeaders.ContentType, ContentType.Application.OctetStream.toString())
                            }
                        )
                    }
                )
            )
        }
        return response.body()
    }

    fun clear() = client.close()
}

suspend fun main() {
    val f = File("/Users/m1/Pictures/portrait.jpeg")
    val resp = GigaChatAPI(GigaAuth).uploadImage(f.readBytes(), f.name)
    println(resp)
}