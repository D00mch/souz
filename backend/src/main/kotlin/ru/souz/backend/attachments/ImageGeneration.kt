package ru.souz.backend.attachments

import javax.sql.DataSource
import kotlinx.coroutines.CancellationException
import org.slf4j.LoggerFactory
import ru.souz.backend.crypto.sha256Hex
import ru.souz.backend.storage.postgres.write
import ru.souz.llms.*
import ru.souz.llms.runtime.*

/** Durable admission for one paid image operation; an uncertain attempt is never replayed automatically. */
internal class ImageGeneration(private val storage: AttachmentStorage, private val dataSource: DataSource) {
    suspend fun generate(meta: ToolInvocationMeta, prompt: String, fileIds: List<String>, route: String,
        gateway: ImageGenerationGateway): FileContent {
        require(prompt.isNotBlank() && prompt.length <= 32_000) { "image_prompt_invalid" }
        require(fileIds.size <= ImageSource.MAX_IMAGES) { "image_count_exceeded" }
        val execution = meta.requestId ?: throw AttachmentException("attachment_execution_required")
        // Source access is checked even for a cached result.
        val sources = fileIds.map { storage.get(meta.userId, attachmentId(it)) }
        sources.forEach { require(it.mimeType in ImageSource.MIME_TYPES &&
            it.sizeBytes <= ImageSource.MAX_BYTES) { "image_source_invalid" } }
        val fingerprint = restJsonMapper.writeValueAsBytes(listOf(route, prompt.trim(),
            sources.map { listOf(it.id.toString(), it.sha256) })).sha256Hex()
        val key = "image:$execution:$fingerprint"
        storage.find(meta.userId, key)?.let { return it.toContent(FilePresentation.image) }
        // Validate pixels before consuming the attempt, and preserve source order and original bytes.
        val images = sources.map { ImageSource(storage.read(meta.userId, it.id)) }
        val claimed = dataSource.write { connection ->
            connection.prepareStatement("""
                insert into image_generation_attempts(user_id, execution_id, fingerprint) values (?, ?, ?)
                on conflict do nothing
            """.trimIndent()).use {
                it.setString(1, meta.userId); it.setObject(2, java.util.UUID.fromString(execution)); it.setString(3, fingerprint)
                it.executeUpdate() == 1
            }
        }
        if (!claimed) throw AttachmentException("image_previously_attempted")
        val result = gateway.generate(ImageGenerationInput(prompt.trim(), images = images))
        val image = ImageSource(result.bytes)
        return storage.save(meta.userId, key, "image.${image.mimeType.substringAfter('/')}", image.bytes.inputStream())
            .toContent(FilePresentation.image)
    }
}

internal class ToolGenerateAttachmentImage(
    private val generate: suspend (ToolInvocationMeta, String, List<String>) -> FileContent,
) : LLMToolSetup {
    data class Input(val prompt: String, val fileIds: List<String> = emptyList())
    override val fn = LLMRequest.Function(NAME,
        "Generate an image or edit ordered source fileIds (up to five). Returns a new fileId; use AttachFileToReply to select it. Reuse the result as a source for further edits. Do not retry failed or uncertain generation automatically.",
        LLMRequest.Parameters("object", mapOf(
            "prompt" to LLMRequest.Property("string"),
            "fileIds" to LLMRequest.Property("array", "Optional ordered PNG/JPEG/WebP source IDs."),
        ), listOf("prompt")))
    override suspend fun invoke(functionCall: LLMResponse.FunctionCall) = invoke(functionCall, ToolInvocationMeta.localDefault())
    override suspend fun invoke(functionCall: LLMResponse.FunctionCall, meta: ToolInvocationMeta): LLMRequest.Message {
        return try {
            val input = restJsonMapper.convertValue(functionCall.arguments, Input::class.java)
            val file = generate(meta, input.prompt, input.fileIds)
            LLMRequest.Message(LLMMessageRole.function, restJsonMapper.writeValueAsString(file), name = NAME, files = listOf(file))
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            LoggerFactory.getLogger(javaClass).warn("Image generation failed: exceptionClass={}", error.javaClass.name)
            val code = when (error) {
                is AttachmentException -> error.code
                is IllegalArgumentException -> "image_arguments_invalid"
                else -> "image_generation_failed"
            }
            LLMRequest.Message(LLMMessageRole.function, restJsonMapper.writeValueAsString(mapOf("error" to code)), name = NAME)
        }
    }
    companion object { const val NAME = "GenerateAttachmentImage" }
}
