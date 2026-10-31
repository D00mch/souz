package ru.souz.tool.files

import java.nio.file.Files
import java.nio.file.Path
import kotlinx.coroutines.runBlocking
import ru.souz.llms.ToolInvocationMeta
import ru.souz.llms.runtime.DEFAULT_MAX_VISION_IMAGE_BYTES
import ru.souz.llms.runtime.ImageSource
import ru.souz.llms.runtime.VisionGateway
import ru.souz.llms.runtime.VisionInput
import ru.souz.runtime.files.FilesToolUtil
import ru.souz.tool.BadInputException
import ru.souz.tool.FewShotExample
import ru.souz.tool.InputParamDescription
import ru.souz.tool.ReturnParameters
import ru.souz.tool.ReturnProperty
import ru.souz.tool.ToolSetup

class ToolViewImage(
    private val filesToolUtil: FilesToolUtil,
    private val visionGateway: VisionGateway,
    private val maxImageBytes: Long = DEFAULT_MAX_VISION_IMAGE_BYTES,
) : ToolSetup<ToolViewImage.Input> {

    data class Input(
        @InputParamDescription("Absolute path to a local image file.")
        val imagePath: String? = null,
        @InputParamDescription("Ordered absolute image paths, up to five; use instead of imagePath.")
        val imagePaths: List<String>? = null,
        @InputParamDescription("Question to ask about the image.")
        val question: String,
    )

    override val name: String = NAME
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
        require((input.imagePath != null) xor (input.imagePaths != null)) { "Supply imagePath or imagePaths" }
        val paths = input.imagePaths ?: listOf(requireNotNull(input.imagePath))
        require(paths.size in 1..ImageSource.MAX_IMAGES) { "Supply one to five images" }
        val images = paths.map { path ->
            requireAbsolutePath(path)
            val file = filesToolUtil.resolveSafeExistingFile(path, meta)
            filesToolUtil.withReadableLocalPathSuspend(file, meta) { local ->
                requireSizeWithinLimit(Files.size(local))
                Files.newInputStream(local).use(ImageSource::read)
            }
        }
        return visionGateway.analyze(VisionInput(images, input.question.trim()))
    }

    private fun requireAbsolutePath(rawPath: String) {
        // Docker sandbox paths are POSIX-absolute even when the host is Windows.
        val path = runCatching { Path.of(rawPath) }.getOrNull()
        if (path == null || !(path.isAbsolute || rawPath.startsWith("/"))) {
            throw BadInputException("imagePath must be an absolute local path")
        }
    }

    private fun requireSizeWithinLimit(sizeBytes: Long) {
        if (sizeBytes > maxImageBytes) {
            throw BadInputException("image file is too large: $sizeBytes bytes exceeds limit of $maxImageBytes bytes")
        }
    }

    companion object {
        const val NAME = "ViewImage"
    }
}
