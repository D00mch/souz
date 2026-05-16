package ru.souz.llms.runtime

import java.nio.file.Path

data class VisionInput(
    val imagePath: Path,
    val mimeType: String,
    val sizeBytes: Long,
    val question: String,
)

fun interface VisionGateway {
    suspend fun analyze(input: VisionInput): String
}

data class ImageGenerationInput(
    val prompt: String,
    val model: String? = null,
    val size: String? = null,
    val quality: String? = null,
    val outputFormat: String? = null,
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

const val DEFAULT_MAX_VISION_IMAGE_BYTES: Long = 20L * 1024L * 1024L
