package ru.souz.tool.files

import ru.souz.llms.runtime.ImageSource
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import java.time.Instant
import java.nio.file.Path
import kotlinx.coroutines.runBlocking
import ru.souz.llms.ToolInvocationMeta
import ru.souz.llms.runtime.ImageGenerationGateway
import ru.souz.llms.runtime.ImageGenerationInput
import ru.souz.runtime.files.FilesToolUtil
import ru.souz.runtime.sandbox.SandboxPathInfo
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
        @InputParamDescription("Optional ordered source images for editing, up to five absolute sandbox paths.")
        val imagePaths: List<String> = emptyList(),
    )

    override val name: String = NAME
    override val description: String =
        "Generate or edit an image with the configured image model, save it to a safe file path, and return the saved path."

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

        require(input.imagePaths.size <= ImageSource.MAX_IMAGES) { "image_count_exceeded" }
        val sources = input.imagePaths.map { path ->
            val file = filesToolUtil.resolveSafeExistingFile(path, meta)
            filesToolUtil.openInputStream(file, meta).use { ImageSource.read(it) }
        }
        val generated = imageGenerationGateway.generate(
            ImageGenerationInput(prompt = input.prompt.trim(), images = sources)
        )
        val target = resolveOutputPath(
            rawOutputPath = input.outputPath,
            mimeType = generated.image.mimeType,
            meta = meta,
        )
        filesToolUtil.writeBytes(target, generated.image.bytes, meta)
        return mapper.writeValueAsString(
            linkedMapOf(
                "outputPath" to target.path,
                "mimeType" to generated.image.mimeType,
                "provider" to generated.provider,
                "model" to generated.model,
            )
        )
    }

    private fun resolveOutputPath(
        rawOutputPath: String?,
        mimeType: String,
        meta: ToolInvocationMeta,
    ): SandboxPathInfo {
        val extensions = if (mimeType == "image/jpeg") listOf("jpg", "jpeg") else listOf(mimeType.substringAfter('/'))
        val path = rawOutputPath?.takeIf { it.isNotBlank() }
            ?: "${filesToolUtil.resolveSouzDocumentsDirectory(meta).path}/generated_image_${Instant.now().toEpochMilli()}.${extensions.first()}"
        val requestedExtension = Path.of(path).fileName.toString().substringAfterLast('.', "").lowercase()
        if (requestedExtension.isNotBlank() && requestedExtension !in extensions) {
            throw BadInputException("outputPath extension .$requestedExtension does not match generated MIME type $mimeType")
        }
        return filesToolUtil.resolvePath(if (requestedExtension.isBlank()) "$path.${extensions.first()}" else path, meta)
    }

    companion object {
        const val NAME = "GenerateImage"
        private val mapper = jacksonObjectMapper()
    }
}
