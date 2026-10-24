package ru.souz.llms.openai

import ru.souz.llms.runtime.ImageGenerationInput

object OpenAIImageGenerationRequestBuilder {
    fun build(
        input: ImageGenerationInput,
        defaultModel: String,
    ): Map<String, Any> = buildMap {
        put("model", input.model ?: defaultModel)
        put("prompt", input.prompt)
        put("size", input.size ?: DEFAULT_IMAGE_SIZE)
        put("quality", input.quality ?: DEFAULT_IMAGE_QUALITY)
        put("output_format", (input.outputFormat ?: DEFAULT_OUTPUT_FORMAT).lowercase())
    }

    const val DEFAULT_IMAGE_SIZE = "1024x1024"
    const val DEFAULT_IMAGE_QUALITY = "medium"
    const val DEFAULT_OUTPUT_FORMAT = "png"
}
