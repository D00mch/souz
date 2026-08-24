package ru.souz.llms.tunnel

import io.ktor.client.HttpClient
import io.ktor.client.plugins.timeout
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.isSuccess
import org.slf4j.LoggerFactory
import ru.souz.db.SettingsProvider
import ru.souz.llms.VoiceRecognitionProvider
import ru.souz.llms.restJsonMapper
import ru.souz.service.speech.SpeechRecognitionLanguage
import ru.souz.service.speech.SpeechRecognitionLanguageProvider
import ru.souz.service.speech.pcm16MonoToWav
import java.io.ByteArrayOutputStream

class MissingAiTunnelVoiceKeyException : IllegalStateException("AITUNNEL_KEY is not set")

class AiTunnelVoiceAPI(
    private val settingsProvider: SettingsProvider,
    private val client: HttpClient,
    private val languageProvider: SpeechRecognitionLanguageProvider = SpeechRecognitionLanguageProvider {
        SpeechRecognitionLanguage.fromLanguageCode(settingsProvider.regionProfile)
    },
) {
    private val l = LoggerFactory.getLogger(AiTunnelVoiceAPI::class.java)

    private val apiKey: String
        get() = settingsProvider.aiTunnelKey
            ?.trim()
            ?.takeIf { it.isNotBlank() }
            ?: throw MissingAiTunnelVoiceKeyException()

    private val transcriptionModel: String
        get() = settingsProvider.voiceRecognitionModel
            .takeIf { it.provider == VoiceRecognitionProvider.AI_TUNNEL }
            ?.alias
            ?: System.getenv("AITUNNEL_TRANSCRIPTION_MODEL")
            ?: System.getProperty("AITUNNEL_TRANSCRIPTION_MODEL")
            ?: DEFAULT_TRANSCRIPTION_MODEL

    private val transcriptionLanguage: String
        get() = System.getenv("AITUNNEL_TRANSCRIPTION_LANGUAGE")
            ?: System.getProperty("AITUNNEL_TRANSCRIPTION_LANGUAGE")
            ?: languageProvider.current().apiCode

    suspend fun recognize(audio: ByteArray): String {
        val wavAudio = pcm16MonoToWav(
            rawPcm = audio,
            sampleRateHz = AUDIO_SAMPLE_RATE_HZ,
            channels = AUDIO_CHANNELS,
            bitsPerSample = AUDIO_BITS_PER_SAMPLE,
        )
        l.debug(
            "Sending AiTunnel transcription audio: rawPcmBytes={}, wavBytes={}, sampleRateHz={}, channels={}",
            audio.size,
            wavAudio.size,
            AUDIO_SAMPLE_RATE_HZ,
            AUDIO_CHANNELS,
        )

        val boundary = "----souz-aitunnel-${System.currentTimeMillis()}"
        val multipartBody = buildMultipartBody(
            boundary = boundary,
            wavAudio = wavAudio,
            model = transcriptionModel,
            language = transcriptionLanguage,
        )
        val response = client.post(TRANSCRIPTIONS_URL) {
            header(HttpHeaders.Authorization, "Bearer $apiKey")
            header(HttpHeaders.Accept, ContentType.Application.Json)
            header(HttpHeaders.ContentType, "multipart/form-data; boundary=$boundary")
            timeout { requestTimeoutMillis = settingsProvider.requestTimeoutMillis }
            setBody(multipartBody)
        }

        val responseBody = response.bodyAsText()
        if (!response.status.isSuccess()) {
            l.warn("AiTunnel transcription request failed: status={}, body={}", response.status.value, responseBody)
            throw IllegalStateException("AiTunnel transcription failed: ${response.status.value}")
        }

        return restJsonMapper.readTree(responseBody)["text"]?.asText()?.trim().orEmpty()
    }

    private companion object {
        const val TRANSCRIPTIONS_URL = "https://api.aitunnel.ru/v1/audio/transcriptions"
        const val DEFAULT_TRANSCRIPTION_MODEL = "gpt-4o-transcribe"
        const val AUDIO_SAMPLE_RATE_HZ = 16_000
        const val AUDIO_BITS_PER_SAMPLE = 16
        const val AUDIO_CHANNELS = 1
    }
}

private fun buildMultipartBody(
    boundary: String,
    wavAudio: ByteArray,
    model: String,
    language: String,
): ByteArray {
    val separator = "--$boundary\r\n"
    val ending = "--$boundary--\r\n"
    return ByteArrayOutputStream().apply {
        writeAscii(separator)
        writeAscii("Content-Disposition: form-data; name=\"file\"; filename=\"capture.wav\"\r\n")
        writeAscii("Content-Type: audio/wav\r\n\r\n")
        write(wavAudio)
        writeAscii("\r\n")

        writeAscii(separator)
        writeAscii("Content-Disposition: form-data; name=\"model\"\r\n\r\n")
        writeAscii(model)
        writeAscii("\r\n")

        writeAscii(separator)
        writeAscii("Content-Disposition: form-data; name=\"language\"\r\n\r\n")
        writeAscii(language)
        writeAscii("\r\n")

        writeAscii(ending)
    }.toByteArray()
}

private fun ByteArrayOutputStream.writeAscii(value: String) {
    write(value.toByteArray(Charsets.US_ASCII))
}
