package ru.souz.llms

import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.defaultRequest
import io.ktor.client.request.forms.MultiPartFormDataContent
import io.ktor.client.request.forms.formData
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.Headers
import io.ktor.http.HttpHeaders
import io.ktor.http.isSuccess
import org.slf4j.LoggerFactory
import ru.souz.db.SettingsProvider
import ru.souz.giga.gigaJsonMapper

class MissingOpenAiVoiceKeyException : IllegalStateException("OPENAI_API_KEY is not set")

class OpenAIVoiceAPI(
    private val settingsProvider: SettingsProvider,
) {
    private val l = LoggerFactory.getLogger(OpenAIVoiceAPI::class.java)

    private val apiKey: String
        get() = settingsProvider.openaiKey
            ?.trim()
            ?.takeIf { it.isNotBlank() }
            ?: throw MissingOpenAiVoiceKeyException()

    private val transcriptionModel: String
        get() = System.getenv("OPENAI_TRANSCRIPTION_MODEL")
            ?: System.getProperty("OPENAI_TRANSCRIPTION_MODEL")
            ?: DEFAULT_TRANSCRIPTION_MODEL

    private val client = HttpClient(CIO) {
        defaultRequest {
            header(HttpHeaders.Authorization, "Bearer $apiKey")
            header(HttpHeaders.Accept, ContentType.Application.Json)
        }
        install(HttpTimeout) {
            requestTimeoutMillis = settingsProvider.requestTimeoutMillis
        }
    }

    suspend fun recognize(audio: ByteArray): String {
        val response = client.post(TRANSCRIPTIONS_URL) {
            setBody(
                MultiPartFormDataContent(
                    formData {
                        append("model", transcriptionModel)
                        append(
                            key = "file",
                            value = audio,
                            headers = Headers.build {
                                append(HttpHeaders.ContentType, "audio/ogg")
                                append(
                                    HttpHeaders.ContentDisposition,
                                    "form-data; name=\"file\"; filename=\"capture.ogg\"",
                                )
                            }
                        )
                    }
                )
            )
        }
        val responseBody = response.bodyAsText()
        if (!response.status.isSuccess()) {
            l.warn("OpenAI transcription request failed: status={}, body={}", response.status.value, responseBody)
            throw IllegalStateException("OpenAI transcription failed: ${response.status.value}")
        }

        return gigaJsonMapper.readTree(responseBody)["text"]?.asText()?.trim().orEmpty()
    }

    fun clear() = client.close()

    private companion object {
        const val TRANSCRIPTIONS_URL = "https://api.openai.com/v1/audio/transcriptions"
        const val DEFAULT_TRANSCRIPTION_MODEL = "gpt-4o-transcribe"
    }
}
