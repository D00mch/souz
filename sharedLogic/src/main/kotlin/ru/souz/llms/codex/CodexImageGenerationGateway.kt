package ru.souz.llms.codex

import io.ktor.client.HttpClient
import io.ktor.client.plugins.timeout
import io.ktor.client.request.*
import io.ktor.client.statement.bodyAsChannel
import io.ktor.http.*
import io.ktor.utils.io.readUTF8Line
import java.util.Base64
import ru.souz.db.SettingsProvider
import ru.souz.llms.restJsonMapper
import ru.souz.llms.runtime.*

/** A single provider attempt: a failed or incomplete stream must not repeat a paid operation. */
class CodexImageGenerationGateway(
    private val settings: SettingsProvider,
    private val oauth: CodexOAuthService,
    private val client: HttpClient,
) : ImageGenerationGateway {
    override suspend fun generate(input: ImageGenerationInput): GeneratedImage {
        require(input.prompt.isNotBlank() && input.prompt.length <= 32_000) { "image_prompt_invalid" }
        require(input.outputFormat == null || input.outputFormat == "png") { "image_format_unsupported" }
        val model = requireNotNull(input.model)
        val token = oauth.refreshTokenIfNeeded()
        return client.preparePost("https://chatgpt.com/backend-api/codex/responses") {
            timeout { requestTimeoutMillis = 180_000 }
            contentType(ContentType.Application.Json)
            header(HttpHeaders.Authorization, "Bearer $token")
            header("Chatgpt-Account-Id", settings.codexAccountId.orEmpty())
            header("originator", "codex_cli_rs")
            header("OpenAI-Beta", "responses=experimental")
            setBody(restJsonMapper.writeValueAsString(mapOf(
                "model" to model,
                "instructions" to "Use image_generation to generate or edit the requested image. Preserve source details unless asked to change them.",
                "input" to listOf(mapOf("role" to "user", "content" to buildList {
                    add(mapOf("type" to "input_text", "text" to input.prompt))
                    input.images.forEach { add(mapOf("type" to "input_image", "image_url" to it.dataUrl())) }
                })),
                "tools" to listOf(buildMap {
                    put("type", "image_generation")
                    put("output_format", "png")
                    put("size", input.size ?: "1024x1024")
                    put("quality", input.quality ?: "medium")
                    if (input.images.isNotEmpty()) put("action", "edit")
                }),
                "tool_choice" to mapOf("type" to "image_generation"), "store" to false, "stream" to true,
            )))
        }.execute { response ->
            check(response.status.isSuccess()) { "image_provider_http_${response.status.value}" }
            val stream = response.bodyAsChannel()
            val encodedLimit = ImageSource.MAX_ENCODED_BYTES
            var remaining = encodedLimit.toLong() * 2 + 1_000_000
            var result: ImageSource? = null
            var completed = false
            while (!stream.isClosedForRead) {
                val line = stream.readUTF8Line(encodedLimit + 65_536) ?: break
                remaining -= line.length + 1
                check(remaining >= 0) { "image_response_too_large" }
                if (!line.startsWith("data:")) continue
                val data = line.substringAfter(':').trim()
                if (data == "[DONE]") break
                val event = imageResponseMapper.readTree(data)
                when (event["type"]?.asText()) {
                    "error", "response.failed", "response.incomplete" -> error("image_generation_failed")
                    "response.output_item.done" -> if (event["item"]?.get("type")?.asText() == "image_generation_call") {
                        check(result == null) { "image_result_count_invalid" }
                        val encoded = event["item"]["result"]?.asText() ?: error("image_result_missing")
                        check(encoded.length <= encodedLimit) { "image_response_too_large" }
                        result = ImageSource(Base64.getDecoder().decode(encoded))
                    }
                    "response.completed" -> {
                        check(event["response"]?.get("status")?.asText() == "completed") { "image_generation_incomplete" }
                        completed = true
                        break
                    }
                }
            }
            check(completed) { "image_generation_incomplete" }
            val image = checkNotNull(result) { "image_result_missing" }
            GeneratedImage(image.bytes, image.mimeType, "CODEX", model)
        }
    }
}
