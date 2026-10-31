package ru.souz.llms.openai

import io.ktor.client.HttpClient
import io.ktor.client.plugins.timeout
import io.ktor.client.request.*
import io.ktor.client.request.forms.*
import io.ktor.client.statement.bodyAsChannel
import io.ktor.http.*
import io.ktor.utils.io.readRemaining
import kotlinx.io.readByteArray
import java.util.Base64
import ru.souz.db.SettingsProvider
import ru.souz.llms.restJsonMapper
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
    fun buildRequestPayload(input: ImageGenerationInput): Map<String, Any> =
        OpenAIImageGenerationRequestBuilder.build(input, "gpt-image-1")

    override suspend fun generate(input: ImageGenerationInput): GeneratedImage {
        require(input.prompt.isNotBlank() && input.prompt.length <= 32_000) { "image_prompt_invalid" }
        val payload = buildRequestPayload(input)
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
                    payload.forEach { (key, value) -> append(key, value.toString()) }
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
            val image = ImageSource(Base64.getDecoder().decode(encoded))
            GeneratedImage(image.bytes, image.mimeType, "OPENAI", input.model ?: "gpt-image-1")
        }
    }
}
