package ru.souz.llms.tunnel

import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.defaultRequest
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
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

class MissingAiTunnelVoiceKeyException : IllegalStateException("AITUNNEL_KEY is not set")

class AiTunnelVoiceAPI(
    private val settingsProvider: SettingsProvider,
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
            ?: DEFAULT_TRANSCRIPTION_LANGUAGE

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
            header(HttpHeaders.ContentType, "multipart/form-data; boundary=$boundary")
            setBody(multipartBody)
        }

        val responseBody = response.bodyAsText()
        if (!response.status.isSuccess()) {
            l.warn("AiTunnel transcription request failed: status={}, body={}", response.status.value, responseBody)
            throw IllegalStateException("AiTunnel transcription failed: ${response.status.value}")
        }

        return restJsonMapper.readTree(responseBody)["text"]?.asText()?.trim().orEmpty()
    }

    fun clear() = client.close()

    private companion object {
        const val TRANSCRIPTIONS_URL = "https://api.aitunnel.ru/v1/audio/transcriptions"
        const val DEFAULT_TRANSCRIPTION_MODEL = "gpt-4o-transcribe"
        const val DEFAULT_TRANSCRIPTION_LANGUAGE = "ru"
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
