package ru.souz.tool.files

import java.nio.file.Files
import java.nio.file.Path
import kotlinx.coroutines.runBlocking
import ru.souz.llms.ToolInvocationMeta
import ru.souz.llms.runtime.VisionGateway
import ru.souz.llms.runtime.VisionInput
import ru.souz.tool.BadInputException
import ru.souz.tool.FewShotExample
import ru.souz.tool.InputParamDescription
import ru.souz.tool.ReturnParameters
import ru.souz.tool.ReturnProperty
import ru.souz.tool.ToolSetup

class ToolViewImage(
    private val filesToolUtil: FilesToolUtil,
    private val visionGateway: VisionGateway,
) : ToolSetup<ToolViewImage.Input> {

    data class Input(
        @InputParamDescription("Absolute path to a local image file.")
        val imagePath: String,
        @InputParamDescription("Question to ask about the image.")
        val question: String,
    )

    override val name: String = "ViewImage"
    override val description: String =
        "Read a local image from a safe absolute path and ask the current multimodal model to describe or analyze it."

    override val fewShotExamples: List<FewShotExample> = listOf(
        FewShotExample(
            request = "Посмотри /Users/me/Pictures/cat.png и опиши, что на нем.",
            params = mapOf(
                "imagePath" to "/Users/me/Pictures/cat.png",
                "question" to "Что на изображении?",
            ),
        )
    )

    override val returnParameters: ReturnParameters = ReturnParameters(
        properties = mapOf(
            "result" to ReturnProperty("string", "Model answer about the image."),
        )
    )

    override fun invoke(input: Input, meta: ToolInvocationMeta): String = runBlocking { suspendInvoke(input, meta) }

    override suspend fun suspendInvoke(input: Input, meta: ToolInvocationMeta): String {
        if (input.question.isBlank()) {
            throw BadInputException("question must not be empty")
        }
        requireAbsolutePath(input.imagePath)
        val image = filesToolUtil.resolveSafeExistingFile(input.imagePath, meta)
        val bytes = filesToolUtil.readBytes(image, meta)
        val mimeType = detectImageMimeType(image.path)
            ?: throw BadInputException("Unsupported image type: ${image.name}")
        return visionGateway.analyze(
            VisionInput(
                imagePath = image.path,
                imageBytes = bytes,
                mimeType = mimeType,
                question = input.question.trim(),
            )
        )
    }

    private fun requireAbsolutePath(rawPath: String) {
        val path = runCatching { Path.of(rawPath) }.getOrNull()
        if (path == null || !path.isAbsolute) {
            throw BadInputException("imagePath must be an absolute local path")
        }
    }

    private fun detectImageMimeType(path: String): String? {
        val detected = runCatching { Files.probeContentType(Path.of(path)) }.getOrNull()
        if (!detected.isNullOrBlank() && detected.startsWith("image/")) {
            return detected
        }

        return when (Path.of(path).fileName.toString().substringAfterLast('.', "").lowercase()) {
            "png" -> "image/png"
            "jpg", "jpeg" -> "image/jpeg"
            "webp" -> "image/webp"
            "gif" -> "image/gif"
            "bmp" -> "image/bmp"
            else -> null
        }
    }
}
