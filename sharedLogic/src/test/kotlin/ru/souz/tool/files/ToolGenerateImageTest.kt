package ru.souz.tool.files

import com.fasterxml.jackson.module.kotlin.readValue
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.io.TempDir
import ru.souz.db.SettingsProvider
import ru.souz.llms.ToolInvocationMeta
import ru.souz.llms.restJsonMapper
import ru.souz.llms.runtime.GeneratedImage
import ru.souz.llms.runtime.ImageSource
import ru.souz.llms.runtime.ImageGenerationGateway
import ru.souz.runtime.files.FilesToolUtil
import ru.souz.runtime.sandbox.SandboxScope
import ru.souz.runtime.sandbox.local.LocalRuntimeSandbox
import ru.souz.tool.BadInputException
import java.nio.file.Path
import kotlin.io.path.readBytes
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class ToolGenerateImageTest {
    @TempDir
    lateinit var homeDir: Path

    private val imageBytes = java.io.ByteArrayOutputStream().also {
        javax.imageio.ImageIO.write(java.awt.image.BufferedImage(1, 1, java.awt.image.BufferedImage.TYPE_INT_RGB), "png", it)
    }.toByteArray()
    private val gateway = ImageGenerationGateway { GeneratedImage(ImageSource.read(imageBytes), "OPENAI", "gpt-image-1") }

    @Test
    fun `writes generated image to default safe path`() = runTest {
        val files = createFilesToolUtil()
        val raw = ToolGenerateImage(files, gateway).suspendInvoke(
            ToolGenerateImage.Input(prompt = "A tiny red cube"), ToolInvocationMeta.localDefault(),
        )
        val result: Map<String, String> = restJsonMapper.readValue(raw)
        val savedPath = Path.of(result.getValue("outputPath"))

        assertContentEquals(imageBytes, savedPath.readBytes())
        assertEquals("image/png", result["mimeType"])
        assertEquals("OPENAI", result["provider"])
        assertEquals("gpt-image-1", result["model"])
        assertEquals(files.souzDocumentsDirectoryPath.toRealPath(), savedPath.parent.toRealPath())
    }

    @Test
    fun `rejects explicit output path with mismatched extension`() = runTest {
        val tool = ToolGenerateImage(createFilesToolUtil(), gateway)
        val error = assertFailsWith<BadInputException> {
            tool.suspendInvoke(
                ToolGenerateImage.Input(prompt = "A tiny red cube", outputPath = homeDir.resolve("Pictures/result.jpg").toString()),
                ToolInvocationMeta.localDefault(),
            )
        }
        assertEquals("outputPath extension .jpg does not match generated MIME type image/png", error.message)
    }

    private fun createFilesToolUtil(): FilesToolUtil {
        val settings = mockk<SettingsProvider> { every { forbiddenFolders } returns emptyList() }
        return FilesToolUtil(LocalRuntimeSandbox(
            scope = SandboxScope(userId = "tool-generate-image-test"),
            settingsProvider = settings,
            homePath = homeDir,
            stateRoot = homeDir.resolve("state"),
        ))
    }
}
