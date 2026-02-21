package ru.souz.llms

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
import ru.souz.giga.gigaJsonMapper
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Base64

class MissingQwenVoiceKeyException : IllegalStateException("QWEN_KEY is not set")

class QwenVoiceAPI(
    private val settingsProvider: SettingsProvider,
) {
    private val l = LoggerFactory.getLogger(QwenVoiceAPI::class.java)

    private val apiKey: String
        get() = settingsProvider.qwenChatKey
            ?.trim()
            ?.takeIf { it.isNotBlank() }
            ?: System.getenv("QWEN_KEY")
                ?.trim()
                ?.takeIf { it.isNotBlank() }
            ?: System.getProperty("QWEN_KEY")
                ?.trim()
                ?.takeIf { it.isNotBlank() }
            ?: throw MissingQwenVoiceKeyException()

    private val transcriptionModel: String
        get() = System.getenv("QWEN_TRANSCRIPTION_MODEL")
            ?: System.getProperty("QWEN_TRANSCRIPTION_MODEL")
            ?: DEFAULT_TRANSCRIPTION_MODEL

    private val client = HttpClient(CIO) {
        defaultRequest {
            header(HttpHeaders.Authorization, "Bearer $apiKey")
            header(HttpHeaders.Accept, ContentType.Application.Json)
            header(HttpHeaders.ContentType, ContentType.Application.Json)
        }
        install(HttpTimeout) {
            requestTimeoutMillis = settingsProvider.requestTimeoutMillis
        }
    }

    suspend fun recognize(audio: ByteArray): String {
        val response = client.post(CHAT_COMPLETIONS_URL) {
            setBody(
                mapOf(
                    "model" to transcriptionModel,
                    "modalities" to listOf("text"),
                    "messages" to listOf(
                        mapOf(
                            "role" to "user",
                            "content" to listOf(
                                mapOf(
                                    "type" to "input_audio",
                                    "input_audio" to mapOf(
                                        "data" to toWavDataUri(audio),
                                        "format" to "wav",
                                    ),
                                ),
                            ),
                        ),
                    ),
                )
            )
        }
        val responseBody = response.bodyAsText()
        if (!response.status.isSuccess()) {
            l.warn("Qwen transcription request failed: status={}, body={}", response.status.value, responseBody)
            throw IllegalStateException("Qwen transcription failed: ${response.status.value}")
        }

        val root = gigaJsonMapper.readTree(responseBody)
        val contentNode = root["choices"]?.get(0)?.get("message")?.get("content")

        return when {
            contentNode == null || contentNode.isNull -> ""
            contentNode.isTextual -> contentNode.asText().trim()
            contentNode.isArray -> contentNode
                .mapNotNull { node ->
                    node["text"]?.asText()
                        ?: node["content"]?.asText()
                        ?: node.takeIf { it.isTextual }?.asText()
                }
                .joinToString("\n")
                .trim()

            else -> contentNode.toString().trim()
        }
    }

    fun clear() = client.close()

    private fun toWavDataUri(audio: ByteArray): String {
        val wavBytes = pcm16MonoToWav(audio)
        val encoded = Base64.getEncoder().encodeToString(wavBytes)
        return "data:audio/wav;base64,$encoded"
    }

    private fun pcm16MonoToWav(pcm: ByteArray): ByteArray {
        val channels = 1
        val sampleRate = 16_000
        val bitsPerSample = 16
        val byteRate = sampleRate * channels * bitsPerSample / 8
        val blockAlign = channels * bitsPerSample / 8

        val buffer = ByteBuffer.allocate(WAV_HEADER_SIZE + pcm.size)
            .order(ByteOrder.LITTLE_ENDIAN)

        buffer.put("RIFF".toByteArray())
        buffer.putInt(36 + pcm.size)
        buffer.put("WAVE".toByteArray())
        buffer.put("fmt ".toByteArray())
        buffer.putInt(16)
        buffer.putShort(1.toShort())
        buffer.putShort(channels.toShort())
        buffer.putInt(sampleRate)
        buffer.putInt(byteRate)
        buffer.putShort(blockAlign.toShort())
        buffer.putShort(bitsPerSample.toShort())
        buffer.put("data".toByteArray())
        buffer.putInt(pcm.size)
        buffer.put(pcm)

        return buffer.array()
    }

    private companion object {
        const val CHAT_COMPLETIONS_URL = "https://dashscope-intl.aliyuncs.com/compatible-mode/v1/chat/completions"
        const val DEFAULT_TRANSCRIPTION_MODEL = "qwen3-asr-flash"
        const val WAV_HEADER_SIZE = 44
    }
}
