package ru.souz.backend.attachments

import java.util.UUID
import kotlinx.coroutines.CancellationException
import org.slf4j.LoggerFactory
import ru.souz.backend.crypto.sha256Hex
import ru.souz.backend.llm.quota.ExecutionQuotaManager
import ru.souz.llms.*
import ru.souz.llms.runtime.*

/** Durable admission for one paid image operation; an uncertain attempt is never replayed automatically. */
internal class ImageGeneration(
    private val storage: AttachmentStorage,
    private val executionQuotas: ExecutionQuotaManager,
) {
    suspend fun generate(
        meta: ToolInvocationMeta,
        prompt: String,
        fileIds: List<String>,
        route: String,
        gateway: ImageGenerationGateway,
    ): FileContent = executionQuotas.withImageProjectionPermit {
        require(prompt.isNotBlank() && prompt.length <= MAX_IMAGE_PROMPT_LENGTH) { "image_prompt_invalid" }
        require(fileIds.size <= ImageSource.MAX_IMAGES) { "image_count_exceeded" }
        val execution = meta.requestId ?: throw AttachmentException("attachment_execution_required")
        // Source access is checked even for a cached result.
        val sources = fileIds.map { storage.get(meta.userId, attachmentId(it)) }
        sources.forEach {
            require(it.mimeType in ImageSource.MIME_TYPES && it.sizeBytes <= ImageSource.MAX_BYTES) {
                "image_source_invalid"
            }
        }
        val fingerprint = restJsonMapper.writeValueAsBytes(
            listOf(route, prompt.trim(), sources.map { listOf(it.id.toString(), it.sha256) }),
        ).sha256Hex()
        val key = "image:$execution:$fingerprint"
        storage.find(meta.userId, key)?.let { return@withImageProjectionPermit it.toContent(FilePresentation.image) }
        // Decode within the shared raster budget before claiming a paid attempt; retain order and original bytes.
        val images = sources.map { ImageSource.read(storage.read(it)) }
        storage.saveGeneratedImage(meta.userId, key, UUID.fromString(execution), fingerprint) {
            gateway.generate(ImageGenerationInput(prompt.trim(), images = images)).image
        }.toContent(FilePresentation.image)
    }
}

internal class ToolGenerateAttachmentImage(
    private val generate: suspend (ToolInvocationMeta, String, List<String>) -> FileContent,
) : LLMToolSetup {
    data class Input(val prompt: String, val fileIds: List<String> = emptyList())
    override val fn = LLMRequest.Function(
        NAME,
        "Generate an image or edit ordered source fileIds (up to five). Returns a new fileId; use AttachFileToReply to select it. Reuse the result as a source for further edits. Do not retry failed or uncertain generation automatically.",
        LLMRequest.Parameters(
            "object",
            mapOf(
                "prompt" to LLMRequest.Property("string"),
                "fileIds" to LLMRequest.Property("array", "Optional ordered PNG/JPEG/WebP source IDs."),
            ),
            listOf("prompt"),
        ),
    )

    override suspend fun invoke(functionCall: LLMResponse.FunctionCall) =
        invoke(functionCall, ToolInvocationMeta.localDefault())

    override suspend fun invoke(
        functionCall: LLMResponse.FunctionCall,
        meta: ToolInvocationMeta,
    ): LLMRequest.Message {
        return try {
            val input = restJsonMapper.convertValue(functionCall.arguments, Input::class.java)
            val file = generate(meta, input.prompt, input.fileIds)
            LLMRequest.Message(
                LLMMessageRole.function,
                restJsonMapper.writeValueAsString(file),
                name = NAME,
                files = listOf(file),
            )
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            val code = when (error) {
                is AttachmentException -> error.code
                is IllegalArgumentException -> "image_arguments_invalid"
                else -> "image_generation_failed"
            }
            logger.warn(
                "Image generation failed: code={}, exceptionClass={}, userId={}, executionId={}",
                code, error.javaClass.name, meta.userId, meta.requestId,
            )
            LLMRequest.Message(
                LLMMessageRole.function,
                restJsonMapper.writeValueAsString(mapOf("error" to code)),
                name = NAME,
            )
        }
    }
    companion object {
        const val NAME = "GenerateAttachmentImage"
        private val logger = LoggerFactory.getLogger(ToolGenerateAttachmentImage::class.java)
    }
}
