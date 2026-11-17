package ru.souz.llms.polza

import io.ktor.client.HttpClient
import io.ktor.client.plugins.timeout
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
import ru.souz.db.SettingsProvider
import ru.souz.llms.VoiceRecognitionModel
import ru.souz.llms.restJsonMapper
import ru.souz.service.speech.pcm16MonoToWav

class MissingPolzaVoiceKeyException : IllegalStateException("POLZA_API_KEY is not set")

/** Borrows the host's standard transport; each capture uses one direct upload. */
class PolzaVoiceAPI(
    private val settingsProvider: SettingsProvider,
    private val client: HttpClient,
) {
    suspend fun recognize(audio: ByteArray): String {
        val apiKey = settingsProvider.polzaApiKey?.trim()?.takeIf(String::isNotEmpty)
            ?: throw MissingPolzaVoiceKeyException()
        val wavAudio = pcm16MonoToWav(audio, sampleRateHz = 16_000, channels = 1, bitsPerSample = 16)
        val response = client.post("https://polza.ai/api/v1/audio/transcriptions") {
            header(HttpHeaders.Authorization, "Bearer $apiKey")
            header(HttpHeaders.Accept, ContentType.Application.Json)
            timeout { requestTimeoutMillis = settingsProvider.requestTimeoutMillis }
            setBody(MultiPartFormDataContent(formData {
                append("model", VoiceRecognitionModel.PolzaGigaAm.alias)
                append("file", wavAudio, Headers.build {
                    append(HttpHeaders.ContentType, "audio/wav")
                    append(HttpHeaders.ContentDisposition, "filename=\"capture.wav\"")
                })
            }))
        }
        check(response.status.isSuccess()) { "Polza transcription failed: ${response.status.value}" }
        val result = restJsonMapper.readTree(response.bodyAsText())
        check(!result.hasNonNull("error")) { "Polza transcription failed: provider error" }
        val text = result["text"]
        check(text != null && text.isTextual) { "Polza transcription failed: missing text" }
        return text.asText().trim()
    }
}
