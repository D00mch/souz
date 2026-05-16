package ru.souz.tool.files

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import java.time.Instant
import java.nio.file.Path
import kotlinx.coroutines.runBlocking
import ru.souz.llms.ToolInvocationMeta
import ru.souz.llms.runtime.ImageFileFormats
import ru.souz.llms.runtime.ImageGenerationGateway
import ru.souz.llms.runtime.ImageGenerationInput
import ru.souz.tool.BadInputException
import ru.souz.tool.FewShotExample
import ru.souz.tool.InputParamDescription
import ru.souz.tool.ReturnParameters
import ru.souz.tool.ReturnProperty
import ru.souz.tool.ToolSetup

class ToolGenerateImage(
    private val filesToolUtil: FilesToolUtil,
    private val imageGenerationGateway: ImageGenerationGateway,
) : ToolSetup<ToolGenerateImage.Input> {

    data class Input(
        @InputParamDescription("Prompt describing the image to generate.")
        val prompt: String,
        @InputParamDescription("Optional absolute output file path. Defaults to ~/Documents/souz/generated_image_<timestamp>.png")
        val outputPath: String? = null,
    )

    override val name: String = "GenerateImage"
    override val description: String =
        "Generate a new image with the current provider, save it to a safe file path, and return the saved path."

    override val fewShotExamples: List<FewShotExample> = listOf(
        FewShotExample(
            request = "Сгенерируй картинку красного куба на столе.",
            params = mapOf("prompt" to "A red cube on a white table"),
        )
    )

    override val returnParameters: ReturnParameters = ReturnParameters(
        properties = mapOf(
            "outputPath" to ReturnProperty("string", "Absolute path to the saved image file."),
            "mimeType" to ReturnProperty("string", "Generated image MIME type."),
            "provider" to ReturnProperty("string", "Provider that generated the image."),
            "model" to ReturnProperty("string", "Model that generated the image."),
        )
    )

    override fun invoke(input: Input, meta: ToolInvocationMeta): String = runBlocking { suspendInvoke(input, meta) }

    override suspend fun suspendInvoke(input: Input, meta: ToolInvocationMeta): String {
        if (input.prompt.isBlank()) {
            throw BadInputException("prompt must not be empty")
        }

        val generated = imageGenerationGateway.generate(
            ImageGenerationInput(prompt = input.prompt.trim())
        )
        val target = resolveOutputPath(
            rawOutputPath = input.outputPath,
            mimeType = generated.mimeType,
            meta = meta,
        )
        filesToolUtil.withWritableLocalPath(target, meta) { localPath ->
            java.nio.file.Files.write(localPath, generated.bytes)
        }
        return mapper.writeValueAsString(
            linkedMapOf(
                "outputPath" to target.path,
                "mimeType" to generated.mimeType,
                "provider" to generated.provider,
                "model" to generated.model,
            )
        )
    }

    private fun resolveOutputPath(
        rawOutputPath: String?,
        mimeType: String,
        meta: ToolInvocationMeta,
    ) = if (!rawOutputPath.isNullOrBlank()) {
        filesToolUtil.resolvePath(normalizeRequestedOutputPath(rawOutputPath, mimeType), meta)
    } else {
        val ext = ImageFileFormats.primaryExtensionForMimeType(mimeType)
        filesToolUtil.resolvePath(
            "${filesToolUtil.resolveSouzDocumentsDirectory(meta).path}/generated_image_${Instant.now().toEpochMilli()}.$ext",
            meta,
        )
    }

    private fun normalizeRequestedOutputPath(rawOutputPath: String, mimeType: String): String {
        val expectedExtensions = ImageFileFormats.supportedExtensionsForMimeType(mimeType)
        val requestedExtension = Path.of(rawOutputPath).fileName.toString()
            .substringAfterLast('.', "")
            .lowercase()

        if (requestedExtension.isBlank()) {
            return "$rawOutputPath.${expectedExtensions.first()}"
        }
        if (requestedExtension !in expectedExtensions) {
            throw BadInputException(
                "outputPath extension .$requestedExtension does not match generated MIME type $mimeType",
            )
        }
        return rawOutputPath
    }

    private companion object {
        val mapper = jacksonObjectMapper()
    }
}
