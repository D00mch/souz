package ru.souz.tool.files

import com.fasterxml.jackson.module.kotlin.readValue
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.AfterEach
import ru.souz.db.SettingsProvider
import ru.souz.llms.ToolInvocationMeta
import ru.souz.llms.restJsonMapper
import ru.souz.llms.runtime.GeneratedImage
import ru.souz.llms.runtime.ImageGenerationGateway
import ru.souz.runtime.sandbox.SandboxScope
import ru.souz.runtime.sandbox.local.LocalRuntimeSandbox
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.exists
import kotlin.io.path.readBytes
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class ToolGenerateImageTest {
    private val tempRoots = mutableListOf<Path>()

    @AfterEach
    fun cleanup() {
        tempRoots.asReversed().forEach { path -> runCatching { path.toFile().deleteRecursively() } }
        tempRoots.clear()
    }

    @Test
    fun `writes generated image to default safe path`() = runTest {
        val homeDir = tempDir("home")
        val filesToolUtil = createFilesToolUtil(homeDir)
        val gateway = mockk<ImageGenerationGateway>()
        val imageBytes = byteArrayOf(9, 8, 7, 6)
        coEvery { gateway.generate(any()) } returns GeneratedImage(
            bytes = imageBytes,
            mimeType = "image/png",
            provider = "OPENAI",
            model = "gpt-image-1",
        )

        val tool = ToolGenerateImage(
            filesToolUtil = filesToolUtil,
            imageGenerationGateway = gateway,
        )

        val raw = tool.suspendInvoke(
            ToolGenerateImage.Input(
                prompt = "A tiny red cube",
                outputPath = null,
            ),
            ToolInvocationMeta.Empty,
        )

        val result: Map<String, String> = restJsonMapper.readValue(raw)
        val savedPath = Path.of(result.getValue("outputPath"))

        assertTrue(savedPath.exists())
        assertContentEquals(imageBytes, savedPath.readBytes())
        assertEquals("image/png", result["mimeType"])
        assertEquals("OPENAI", result["provider"])
        assertEquals("gpt-image-1", result["model"])
        assertEquals(
            filesToolUtil.souzDocumentsDirectoryPath.toAbsolutePath().normalize().toRealPath(),
            savedPath.parent.toAbsolutePath().normalize().toRealPath(),
        )
    }

    @Test
    fun `propagates unsupported generation errors cleanly`() = runTest {
        val filesToolUtil = createFilesToolUtil(tempDir("home"))
        val gateway = mockk<ImageGenerationGateway>()
        coEvery { gateway.generate(any()) } throws UnsupportedOperationException(
            "Image generation is not supported by the current provider: LOCAL",
        )

        val tool = ToolGenerateImage(
            filesToolUtil = filesToolUtil,
            imageGenerationGateway = gateway,
        )

        val error = assertFailsWith<UnsupportedOperationException> {
            tool.suspendInvoke(
                ToolGenerateImage.Input(prompt = "Draw a cat"),
                ToolInvocationMeta.Empty,
            )
        }

        assertEquals("Image generation is not supported by the current provider: LOCAL", error.message)
    }

    @Test
    fun `rejects explicit output path with mismatched extension`() = runTest {
        val homeDir = tempDir("home")
        val filesToolUtil = createFilesToolUtil(homeDir)
        val gateway = mockk<ImageGenerationGateway>()
        coEvery { gateway.generate(any()) } returns GeneratedImage(
            bytes = byteArrayOf(9, 8, 7, 6),
            mimeType = "image/png",
            provider = "OPENAI",
            model = "gpt-image-1",
        )

        val tool = ToolGenerateImage(
            filesToolUtil = filesToolUtil,
            imageGenerationGateway = gateway,
        )

        val error = assertFailsWith<ru.souz.tool.BadInputException> {
            tool.suspendInvoke(
                ToolGenerateImage.Input(
                    prompt = "A tiny red cube",
                    outputPath = "${homeDir.resolve("Pictures/result.jpg")}",
                ),
                ToolInvocationMeta.Empty,
            )
        }

        assertEquals("outputPath extension .jpg does not match generated MIME type image/png", error.message)
    }

    private fun createFilesToolUtil(homeDir: Path): FilesToolUtil {
        val settingsProvider = mockk<SettingsProvider>()
        every { settingsProvider.forbiddenFolders } returns emptyList()
        return FilesToolUtil(
            LocalRuntimeSandbox(
                scope = SandboxScope(userId = "tool-generate-image-test"),
                settingsProvider = settingsProvider,
                homePath = homeDir,
                stateRoot = tempDir("state"),
            )
        )
    }

    private fun tempDir(prefix: String): Path =
        Files.createTempDirectory(prefix).also(tempRoots::add)
}
