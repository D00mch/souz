package ru.souz.llms.runtime

import com.fasterxml.jackson.core.StreamReadConstraints
import ru.souz.llms.restJsonMapper

internal val imageResponseMapper = restJsonMapper.copy().apply {
    factory.setStreamReadConstraints(StreamReadConstraints.builder().maxStringLength(ImageSource.MAX_ENCODED_BYTES).build())
}

/** Validated request-local images, in the order supplied by the caller. */
data class VisionInput(val images: List<ImageSource>, val question: String) {
    init { require(images.size in 1..ImageSource.MAX_IMAGES) { "image_count_exceeded" } }
}

fun interface VisionGateway {
    suspend fun analyze(input: VisionInput): String
}

data class ImageGenerationInput(
    val prompt: String,
    val model: String? = null,
    val size: String? = null,
    val quality: String? = null,
    val outputFormat: String? = null,
    val images: List<ImageSource> = emptyList(),
) {
    init { require(images.size <= ImageSource.MAX_IMAGES) { "image_count_exceeded" } }
}

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

const val DEFAULT_MAX_VISION_IMAGE_BYTES: Long = ImageSource.MAX_BYTES * 1L
