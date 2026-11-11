package ru.souz.llms.openai

import io.ktor.client.HttpClient
import io.ktor.client.plugins.timeout
import java.util.Base64
import ru.souz.db.SettingsProvider
import io.ktor.client.request.*
import io.ktor.client.request.forms.*
import io.ktor.client.statement.bodyAsChannel
import io.ktor.http.*
import io.ktor.utils.io.readRemaining
import kotlinx.io.readByteArray
import ru.souz.llms.runtime.*

class OpenAIImageGenerationGateway(
    private val settingsProvider: SettingsProvider,
    private val client: HttpClient,
    private val apiKeyProvider: suspend () -> String = {
        settingsProvider.openaiKey ?: System.getenv("OPENAI_API_KEY")
            ?: System.getProperty("OPENAI_API_KEY") ?: error("OPENAI_API_KEY is not set")
    },
    private val baseUrl: String? = null,
) : ImageGenerationGateway {
    override suspend fun generate(input: ImageGenerationInput): GeneratedImage {
        require(input.prompt.isNotBlank() && input.prompt.length <= MAX_IMAGE_PROMPT_LENGTH) { "image_prompt_invalid" }
        val payload = mapOf(
            "model" to (input.model ?: DEFAULT_IMAGE_MODEL),
            "prompt" to input.prompt,
            "size" to DEFAULT_IMAGE_SIZE,
            "quality" to DEFAULT_IMAGE_QUALITY,
            "output_format" to DEFAULT_IMAGE_OUTPUT_FORMAT,
        )
        val path = if (input.images.isEmpty()) "images/generations" else "images/edits"
        val url = baseUrl?.trimEnd('/')?.let { "$it/$path" } ?: settingsProvider.openAIEndpoint().endpoint(path)
        return client.preparePost(url) {
            header(HttpHeaders.Authorization, "Bearer ${apiKeyProvider()}")
            timeout { requestTimeoutMillis = settingsProvider.requestTimeoutMillis }
            if (input.images.isEmpty()) {
                contentType(ContentType.Application.Json)
                setBody(payload)
            } else {
                setBody(MultiPartFormDataContent(formData {
                    payload.forEach { (key, value) -> append(key, value) }
                    input.images.forEachIndexed { index, source ->
                        append("image[]", source.bytes, Headers.build {
                            append(HttpHeaders.ContentType, source.mimeType)
                            append(HttpHeaders.ContentDisposition, "filename=\"source-$index.${source.mimeType.substringAfter('/')}\"")
                        })
                    }
                }))
            }
        }.execute { response ->
            check(response.status.isSuccess()) { "image_provider_http_${response.status.value}" }
            val maxResponse = ImageSource.MAX_BYTES * 4L / 3 + 1_000_000
            val bytes = response.bodyAsChannel().readRemaining(maxResponse + 1).readByteArray()
            check(bytes.size <= maxResponse) { "image_response_too_large" }
            val root = imageResponseMapper.readTree(bytes)
            val data = root["data"]
            check(data != null && data.size() == 1) { "image_result_count_invalid" }
            val encoded = data[0]["b64_json"]?.asText() ?: error("image_result_missing")
            val image = ImageSource.read(Base64.getDecoder().decode(encoded))
            GeneratedImage(image, "OPENAI", input.model ?: DEFAULT_IMAGE_MODEL)
        }
    }

    companion object {
        // Official Image API IDs. Named compatible connections may use other deployment IDs.
        val SUPPORTED_MODELS: Set<String> = setOf(
            DEFAULT_IMAGE_MODEL,
            "gpt-image-2.5-sunburst-2026-09-08",
            "gpt-image-2.5-flare",
            "gpt-image-2.5-flare-2026-09-08",
            "gpt-image-2",
            "gpt-image-2-2026-04-21",
            "gpt-image-1.5",
            "gpt-image-1-mini",
            "chatgpt-image-latest",
        )
    }
}
