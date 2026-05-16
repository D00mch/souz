package ru.souz.llms.openai

import com.fasterxml.jackson.databind.DeserializationFeature
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.ClientRequestException
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.plugins.defaultRequest
import io.ktor.client.plugins.logging.LogLevel
import io.ktor.client.plugins.logging.Logger
import io.ktor.client.plugins.logging.Logging
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.isSuccess
import io.ktor.serialization.jackson.jackson
import java.util.Base64
import org.slf4j.LoggerFactory
import ru.souz.db.SettingsProvider
import ru.souz.llms.restJsonMapper
import ru.souz.llms.runtime.GeneratedImage
import ru.souz.llms.runtime.ImageGenerationGateway
import ru.souz.llms.runtime.ImageGenerationInput

class OpenAIImageGenerationGateway(
    private val settingsProvider: SettingsProvider,
) : ImageGenerationGateway {
    private val l = LoggerFactory.getLogger(OpenAIImageGenerationGateway::class.java)

    private val apiKey: String
        get() = settingsProvider.openaiKey
            ?: System.getenv("OPENAI_API_KEY")
            ?: System.getProperty("OPENAI_API_KEY")
            ?: throw IllegalStateException("OPENAI_API_KEY is not set")

    private val defaultImageModel: String
        get() = System.getenv("OPENAI_IMAGE_MODEL")
            ?: System.getProperty("OPENAI_IMAGE_MODEL")
            ?: DEFAULT_IMAGE_MODEL

    private val client = HttpClient(CIO) {
        defaultRequest {
            header(HttpHeaders.ContentType, ContentType.Application.Json)
            header(HttpHeaders.Accept, ContentType.Application.Json)
            header(HttpHeaders.Authorization, "Bearer $apiKey")
        }
        install(HttpTimeout) {
            requestTimeoutMillis = settingsProvider.requestTimeoutMillis
        }
        install(ContentNegotiation) {
            jackson {
                disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
            }
        }
        install(Logging) {
            logger = object : Logger {
                override fun log(message: String) {
                    l.debug(message)
                }
            }
            level = LogLevel.INFO
            sanitizeHeader { it.equals(HttpHeaders.Authorization, true) }
        }
    }

    override suspend fun generate(input: ImageGenerationInput): GeneratedImage = try {
        val outputFormat = (input.outputFormat ?: OpenAIImageGenerationRequestBuilder.DEFAULT_OUTPUT_FORMAT).lowercase()
        val response = client.post(IMAGES_URL) {
            setBody(buildRequestPayload(input))
        }
        val text = response.bodyAsText()
        if (!response.status.isSuccess()) {
            throw IllegalStateException("OpenAI image generation failed: ${response.status.value}. $text")
        }
        parseResponse(text, outputFormat)
    } catch (e: ClientRequestException) {
        val text = e.response.bodyAsText()
        throw IllegalStateException("OpenAI image generation failed: ${e.response.status.value}. $text")
    } catch (t: Throwable) {
        throw IllegalStateException("OpenAI image generation failed: ${t.message}", t)
    }

    fun buildRequestPayload(input: ImageGenerationInput): Map<String, Any> =
        OpenAIImageGenerationRequestBuilder.build(input, defaultModel = defaultImageModel)

    private fun parseResponse(text: String, outputFormat: String): GeneratedImage {
        val node = restJsonMapper.readTree(text)
        val item = node["data"]?.firstOrNull()
            ?: throw IllegalStateException("OpenAI image generation returned no image data.")
        val base64 = item["b64_json"]?.asText()
            ?: throw IllegalStateException("OpenAI image generation response did not contain b64_json.")
        return GeneratedImage(
            bytes = Base64.getDecoder().decode(base64),
            mimeType = OUTPUT_FORMAT_TO_MIME.getValue(outputFormat),
            provider = "OPENAI",
            model = node["model"]?.asText() ?: defaultImageModel,
        )
    }

    private companion object {
        const val IMAGES_URL = "https://api.openai.com/v1/images/generations"
        const val DEFAULT_IMAGE_MODEL = "gpt-image-1"

        val OUTPUT_FORMAT_TO_MIME = mapOf(
            "png" to "image/png",
            "jpeg" to "image/jpeg",
            "webp" to "image/webp",
        )
    }
}
