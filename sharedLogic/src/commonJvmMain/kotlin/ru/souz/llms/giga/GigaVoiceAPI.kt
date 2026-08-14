package ru.souz.llms.giga

import io.ktor.client.*
import io.ktor.client.call.*
import io.ktor.client.request.*
import io.ktor.http.*
import org.slf4j.LoggerFactory
import ru.souz.db.SettingsProvider
import ru.souz.llms.LLMResponse

private val l = LoggerFactory.getLogger("GigaVoiceAPI")

class MissingVoiceKeyException : IllegalStateException("VOICE_KEY is not set")

class GigaVoiceAPI(
    private val auth: GigaAuth,
    private val keysProvider: SettingsProvider,
    private val client: HttpClient,
) {
    suspend fun synthesize(text: String): ByteArray {
        val accessToken = accessToken()
        val response = client.post("https://smartspeech.sber.ru/rest/v1/text:synthesize?format=wav16&voice=Nec_24000") {
            applyAuthenticatedDefaults(accessToken)
            header(HttpHeaders.ContentType, "application/ssml")
            header(HttpHeaders.Accept, "application/octet-stream")
            setBody(text)
        }
        return response.body()
    }

    suspend fun recognize(audio: ByteArray): LLMResponse.RecognizeResponse {
        val accessToken = accessToken()
        val response = client.post("https://smartspeech.sber.ru/rest/v1/speech:recognize") {
            applyAuthenticatedDefaults(accessToken)
            header(HttpHeaders.ContentType, "audio/x-pcm;bit=16;rate=16000")
            header(HttpHeaders.Accept, "application/json")
            setBody(audio)
        }
        return response.body()
    }

    private fun requireVoiceKey(): String =
        keysProvider.saluteSpeechKey
            ?.trim()
            ?.takeIf { it.isNotBlank() }
            ?: throw MissingVoiceKeyException()

    private suspend fun accessToken(): String =
        auth.requestToken(requireVoiceKey(), VOICE_SCOPE)

    private fun HttpRequestBuilder.applyAuthenticatedDefaults(accessToken: String) {
        gigaRequestDefaults(keysProvider)
        header(HttpHeaders.Authorization, "Bearer $accessToken")
    }

    private companion object {
        const val VOICE_SCOPE = "SALUTE_SPEECH_PERS"
    }
}
