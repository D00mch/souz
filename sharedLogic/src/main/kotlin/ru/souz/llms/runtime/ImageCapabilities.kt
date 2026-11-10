package ru.souz.llms.runtime

import com.fasterxml.jackson.core.StreamReadConstraints
import ru.souz.llms.LlmProvider
import ru.souz.llms.restJsonMapper

internal val imageResponseMapper = restJsonMapper.copy().apply {
    factory.setStreamReadConstraints(
        StreamReadConstraints.builder().maxStringLength(ImageSource.MAX_ENCODED_BYTES).build(),
    )
}

/** Validated request-local images, in the order supplied by the caller. */
data class VisionInput(val images: List<ImageSource>, val question: String) {
    init {
        require(images.size in 1..ImageSource.MAX_IMAGES) { "image_count_exceeded" }
    }
}

fun interface VisionGateway {
    suspend fun analyze(input: VisionInput): String
}

data class ImageGenerationInput(
    val prompt: String,
    val model: String? = null,
    val images: List<ImageSource> = emptyList(),
) {
    init {
        require(images.size <= ImageSource.MAX_IMAGES) { "image_count_exceeded" }
    }
}

/** Validated provider output; retain original bytes for storage and editing. */
data class GeneratedImage(
    val image: ImageSource,
    val provider: String,
    val model: String? = null,
)

/** Generation and editing use the configured image route, independently of chat selection. */
fun interface ImageGenerationGateway {
    suspend fun generate(input: ImageGenerationInput): GeneratedImage
}

const val DEFAULT_IMAGE_MODEL = "gpt-image-2.5-sunburst"
const val DEFAULT_IMAGE_SIZE = "1024x1024"
const val DEFAULT_IMAGE_QUALITY = "medium"
const val DEFAULT_IMAGE_OUTPUT_FORMAT = "png"
const val MAX_IMAGE_PROMPT_LENGTH = 32_000

fun LlmProvider?.supportsImageInput(): Boolean =
    this == LlmProvider.OPENAI || this == LlmProvider.CODEX || this == LlmProvider.ANTHROPIC
