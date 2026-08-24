package ru.souz.llms.openai

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
import org.slf4j.LoggerFactory
import ru.souz.db.SettingsProvider
import ru.souz.llms.VoiceRecognitionProvider
import ru.souz.llms.restJsonMapper
import ru.souz.service.speech.SpeechRecognitionLanguage
import ru.souz.service.speech.SpeechRecognitionLanguageProvider
import ru.souz.service.speech.pcm16MonoToWav

class MissingOpenAiVoiceKeyException : IllegalStateException("OPENAI_API_KEY is not set")

class OpenAIVoiceAPI(
    private val settingsProvider: SettingsProvider,
    private val client: HttpClient,
    private val languageProvider: SpeechRecognitionLanguageProvider = SpeechRecognitionLanguageProvider {
        SpeechRecognitionLanguage.fromLanguageCode(settingsProvider.regionProfile)
    },
) {
    private val l = LoggerFactory.getLogger(OpenAIVoiceAPI::class.java)

    private val apiKey: String
        get() = settingsProvider.openaiKey
            ?.trim()
            ?.takeIf { it.isNotBlank() }
            ?: throw MissingOpenAiVoiceKeyException()

    private val transcriptionModel: String
        get() = settingsProvider.voiceRecognitionModel
            .takeIf { it.provider == VoiceRecognitionProvider.OPENAI }
            ?.alias
            ?: System.getenv("OPENAI_TRANSCRIPTION_MODEL")
            ?: System.getProperty("OPENAI_TRANSCRIPTION_MODEL")
            ?: DEFAULT_TRANSCRIPTION_MODEL

    suspend fun recognize(audio: ByteArray): String {
        val wavAudio = pcm16MonoToWav(
            rawPcm = audio,
            sampleRateHz = AUDIO_SAMPLE_RATE_HZ,
            channels = AUDIO_CHANNELS,
            bitsPerSample = AUDIO_BITS_PER_SAMPLE,
        )
        val model = transcriptionModel
        val language = languageProvider.current().apiCode
        l.debug(
            "Sending OpenAI transcription audio: rawPcmBytes={}, wavBytes={}, sampleRateHz={}, channels={}",
            audio.size,
            wavAudio.size,
            AUDIO_SAMPLE_RATE_HZ,
            AUDIO_CHANNELS,
        )
        val response = client.post(transcriptionsUrl) {
            header(HttpHeaders.Authorization, "Bearer $apiKey")
            header(HttpHeaders.Accept, ContentType.Application.Json)
            timeout { requestTimeoutMillis = settingsProvider.requestTimeoutMillis }
            setBody(
                MultiPartFormDataContent(
                    buildOpenAiTranscriptionFormData(
                        model = model,
                        language = language,
                        wavAudio = wavAudio,
                    )
                )
            )
        }
        val responseBody = response.bodyAsText()
        if (!response.status.isSuccess()) {
            l.warn("OpenAI transcription request failed: status={}, body={}", response.status.value, responseBody)
            throw IllegalStateException("OpenAI transcription failed: ${response.status.value}")
        }

        return restJsonMapper.readTree(responseBody)["text"]?.asText()?.trim().orEmpty()
    }

    private companion object {
        const val TRANSCRIPTIONS_PATH = "audio/transcriptions"
        const val DEFAULT_TRANSCRIPTION_MODEL = "gpt-4o-transcribe"
        const val AUDIO_SAMPLE_RATE_HZ = 16_000
        const val AUDIO_BITS_PER_SAMPLE = 16
        const val AUDIO_CHANNELS = 1
    }

    private val transcriptionsUrl: String
        get() = settingsProvider.openAIEndpoint().endpoint(TRANSCRIPTIONS_PATH)
}

internal fun buildOpenAiTranscriptionFormData(
    model: String,
    language: String,
    wavAudio: ByteArray,
) = formData {
    append("model", model)
    if (model == GPT_TRANSCRIBE_MODEL) {
        append("languages[]", language)
    } else {
        append("language", language)
    }
    append(
        key = "file",
        value = wavAudio,
        headers = Headers.build {
            append(HttpHeaders.ContentType, "audio/wav")
            append(
                HttpHeaders.ContentDisposition,
                "form-data; name=\"file\"; filename=\"capture.wav\"",
            )
        }
    )
}

private const val GPT_TRANSCRIBE_MODEL = "gpt-transcribe"
