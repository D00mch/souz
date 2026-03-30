package ru.souz.llms.openai

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
import ru.souz.llms.VoiceRecognitionProvider
import ru.souz.llms.giga.gigaJsonMapper
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

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
        get() = settingsProvider.voiceRecognitionModel
            .takeIf { it.provider == VoiceRecognitionProvider.OPENAI }
            ?.alias
            ?: System.getenv("OPENAI_TRANSCRIPTION_MODEL")
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
        val wavAudio = pcm16MonoToWav(
            rawPcm = audio,
            sampleRateHz = AUDIO_SAMPLE_RATE_HZ,
            channels = AUDIO_CHANNELS,
            bitsPerSample = AUDIO_BITS_PER_SAMPLE,
        )
        l.debug(
            "Sending OpenAI transcription audio: rawPcmBytes={}, wavBytes={}, sampleRateHz={}, channels={}",
            audio.size,
            wavAudio.size,
            AUDIO_SAMPLE_RATE_HZ,
            AUDIO_CHANNELS,
        )
        val response = client.post(TRANSCRIPTIONS_URL) {
            setBody(
                MultiPartFormDataContent(
                    formData {
                        append("model", transcriptionModel)
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
        const val AUDIO_SAMPLE_RATE_HZ = 16_000
        const val AUDIO_BITS_PER_SAMPLE = 16
        const val AUDIO_CHANNELS = 1
    }
}

private fun pcm16MonoToWav(
    rawPcm: ByteArray,
    sampleRateHz: Int,
    channels: Int,
    bitsPerSample: Int,
): ByteArray {
    val byteRate = sampleRateHz * channels * bitsPerSample / 8
    val blockAlign = channels * bitsPerSample / 8
    return ByteArrayOutputStream(WAV_HEADER_SIZE + rawPcm.size).apply {
        writeAscii("RIFF")
        writeLeInt(36 + rawPcm.size)
        writeAscii("WAVE")
        writeAscii("fmt ")
        writeLeInt(16) // PCM subchunk size
        writeLeShort(1) // PCM format
        writeLeShort(channels)
        writeLeInt(sampleRateHz)
        writeLeInt(byteRate)
        writeLeShort(blockAlign)
        writeLeShort(bitsPerSample)
        writeAscii("data")
        writeLeInt(rawPcm.size)
        write(rawPcm)
    }.toByteArray()
}

private const val WAV_HEADER_SIZE = 44

private fun ByteArrayOutputStream.writeAscii(value: String) {
    write(value.toByteArray(Charsets.US_ASCII))
}

private fun ByteArrayOutputStream.writeLeShort(value: Int) {
    write(
        ByteBuffer.allocate(2)
            .order(ByteOrder.LITTLE_ENDIAN)
            .putShort(value.toShort())
            .array()
    )
}

private fun ByteArrayOutputStream.writeLeInt(value: Int) {
    write(
        ByteBuffer.allocate(4)
            .order(ByteOrder.LITTLE_ENDIAN)
            .putInt(value)
            .array()
    )
}
