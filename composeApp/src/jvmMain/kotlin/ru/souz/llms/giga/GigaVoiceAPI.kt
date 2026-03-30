package ru.souz.llms.giga

import io.ktor.client.*
import io.ktor.client.call.*
import io.ktor.client.engine.cio.*
import io.ktor.client.plugins.auth.*
import io.ktor.client.plugins.auth.providers.*
import io.ktor.client.request.*
import io.ktor.http.*
import org.kodein.di.DI
import org.kodein.di.instance
import java.io.File
import org.slf4j.LoggerFactory
import ru.souz.db.SettingsProvider
import ru.souz.di.mainDiModule
import ru.souz.llms.LLMResponse

private val l = LoggerFactory.getLogger("GigaVoiceAPI")

class MissingVoiceKeyException : IllegalStateException("VOICE_KEY is not set")

class GigaVoiceAPI(
    private val auth: GigaAuth,
    private val keysProvider: SettingsProvider,
) {
    private val client = HttpClient(CIO) {
        var token = ""
        gigaDefaults(keysProvider)
        install(Auth) {
            bearer {
                loadTokens {
                    BearerTokens(token, "")
                }
                refreshTokens {
                    val voiceKey = requireVoiceKey()
                    token = auth.requestToken(voiceKey, "SALUTE_SPEECH_PERS")
                    BearerTokens(token, "")
                }
            }
        }
    }

    suspend fun synthesize(text: String): ByteArray {
        val response = client.post("https://smartspeech.sber.ru/rest/v1/text:synthesize?format=wav16&voice=Nec_24000") {
            header(HttpHeaders.ContentType, "application/ssml")
            header(HttpHeaders.Accept, "application/octet-stream")
            setBody(text)
        }
        return response.body()
    }

    suspend fun recognize(audio: ByteArray): LLMResponse.RecognizeResponse {
        val response = client.post("https://smartspeech.sber.ru/rest/v1/speech:recognize") {
            header(HttpHeaders.ContentType, "audio/x-pcm;bit=16;rate=16000")
            header(HttpHeaders.Accept, "application/json")
            setBody(audio)
        }
        return response.body()
    }

    fun clear() = client.close()

    private fun requireVoiceKey(): String =
        keysProvider.saluteSpeechKey
            ?.trim()
            ?.takeIf { it.isNotBlank() }
            ?: throw MissingVoiceKeyException()
}

suspend fun main() {
    val di = DI.invoke { import(mainDiModule) }
    val api: GigaVoiceAPI by di.instance()
    val result = api.recognize(File("capture2.pcm").readBytes())
    l.info("$result")
}
