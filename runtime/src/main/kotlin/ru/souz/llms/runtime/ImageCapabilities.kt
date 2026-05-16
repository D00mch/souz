package ru.souz.llms.runtime

data class VisionInput(
    val imagePath: String,
    val imageBytes: ByteArray,
    val mimeType: String,
    val question: String,
)

fun interface VisionGateway {
    suspend fun analyze(input: VisionInput): String
}

data class ImageGenerationInput(
    val prompt: String,
)

data class GeneratedImage(
    val bytes: ByteArray,
    val mimeType: String,
    val provider: String,
    val model: String? = null,
)

fun interface ImageGenerationGateway {
    suspend fun generate(input: ImageGenerationInput): GeneratedImage
}
