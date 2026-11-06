package ru.souz.llms.openai

import ru.souz.llms.runtime.*

object OpenAIImageGenerationRequestBuilder {
    fun build(input: ImageGenerationInput): Map<String, Any> = buildMap {
        put("model", input.model ?: DEFAULT_IMAGE_MODEL)
        put("prompt", input.prompt)
        put("size", input.size ?: DEFAULT_IMAGE_SIZE)
        put("quality", input.quality ?: DEFAULT_IMAGE_QUALITY)
        put("output_format", (input.outputFormat ?: DEFAULT_IMAGE_OUTPUT_FORMAT).lowercase())
    }
}
