package ru.abledo.giga

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
import ru.abledo.db.SettingsProvider
import ru.abledo.di.mainDiModule

private val l = LoggerFactory.getLogger("GigaVoiceAPI")

class GigaVoiceAPI(
    private val auth: GigaAuth,
    private val keysProvider: SettingsProvider,
) {
    private val client = HttpClient(CIO) {
        var token = ""
        val voiceKey = keysProvider.saluteSpeechKey ?: throw IllegalStateException("VOICE_KEY is not set")
        gigaDefaults()
        install(Auth) {
            bearer {
                loadTokens {
                    BearerTokens(token, "")
                }
                refreshTokens {
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

    suspend fun recognize(audio: ByteArray): GigaResponse.RecognizeResponse {
        val response = client.post("https://smartspeech.sber.ru/rest/v1/speech:recognize") {
            header(HttpHeaders.ContentType, "audio/ogg;codecs=opus")
            header(HttpHeaders.Accept, "application/json")
            setBody(audio)
        }
        return response.body()
    }

    fun clear() = client.close()
}

suspend fun main() {
    val di = DI.invoke { import(mainDiModule) }
    val api: GigaVoiceAPI by di.instance()
    val result = api.recognize(File("capture2.ogg").readBytes())
    l.info("$result")
}
