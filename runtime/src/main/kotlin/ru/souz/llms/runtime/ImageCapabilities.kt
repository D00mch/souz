package ru.souz.llms.runtime

import java.nio.file.Path

/**
 * Provider-neutral image-understanding input passed from tools into provider adapters.
 *
 * `ToolViewImage` resolves sandbox-safe access first, then hands providers a local readable path
 * together with already validated metadata so gateways do not need to know about sandbox internals.
 */
data class VisionInput(
    val imagePath: Path,
    val mimeType: String,
    val sizeBytes: Long,
    val question: String,
)

/**
 * Abstraction over provider-specific vision implementations.
 *
 * The active chat provider can vary per environment, so runtime tools depend on this shared contract
 * instead of wiring directly to OpenAI, Anthropic, or local multimodal adapters.
 */
fun interface VisionGateway {
    suspend fun analyze(input: VisionInput): String
}

/**
 * Provider-neutral image-generation request used by runtime tools.
 */
data class ImageGenerationInput(
    val prompt: String,
    val model: String? = null,
    val size: String? = null,
    val quality: String? = null,
    val outputFormat: String? = null,
)

/**
 * Standardized image-generation result returned by provider adapters.
 */
data class GeneratedImage(
    val bytes: ByteArray,
    val mimeType: String,
    val provider: String,
    val model: String? = null,
)

/**
 * Abstraction over provider-specific image-generation implementations.
 *
 * Generation is selected by capability availability rather than by the currently active chat model,
 * so tools can request a new image without being coupled to a specific provider client.
 */
fun interface ImageGenerationGateway {
    suspend fun generate(input: ImageGenerationInput): GeneratedImage
}

const val DEFAULT_MAX_VISION_IMAGE_BYTES: Long = 20L * 1024L * 1024L
