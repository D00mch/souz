package ru.souz.tool.files

import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.AfterEach
import ru.souz.db.SettingsProvider
import ru.souz.llms.ToolInvocationMeta
import ru.souz.llms.runtime.VisionGateway
import ru.souz.llms.runtime.VisionInput
import ru.souz.runtime.sandbox.SandboxScope
import ru.souz.runtime.sandbox.local.LocalRuntimeSandbox
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.writeBytes
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class ToolViewImageTest {
    private val tempRoots = mutableListOf<Path>()

    @AfterEach
    fun cleanup() {
        tempRoots.asReversed().forEach { path -> runCatching { path.toFile().deleteRecursively() } }
        tempRoots.clear()
    }

    @Test
    fun `resolves image metadata and delegates to vision gateway`() = runTest {
        val homeDir = tempDir("home")
        val imagePath = homeDir.resolve("Pictures/cat.png")
        imagePath.parent.createDirectories()
        val imageBytes = byteArrayOf(1, 2, 3, 4)
        imagePath.writeBytes(imageBytes)

        val filesToolUtil = createFilesToolUtil(homeDir)
        val gateway = mockk<VisionGateway>()
        val inputSlot = slot<VisionInput>()
        coEvery { gateway.analyze(capture(inputSlot)) } returns "A small cat"

        val tool = ToolViewImage(
            filesToolUtil = filesToolUtil,
            visionGateway = gateway,
        )

        val result = tool.suspendInvoke(
            ToolViewImage.Input(
                imagePath = imagePath.toAbsolutePath().toString(),
                question = "What is in the image?",
            ),
            ToolInvocationMeta.Empty,
        )

        assertEquals("A small cat", result)
        assertEquals(imagePath.toRealPath(), inputSlot.captured.imagePath)
        assertEquals("image/png", inputSlot.captured.mimeType)
        assertEquals(imageBytes.size.toLong(), inputSlot.captured.sizeBytes)
        assertEquals("What is in the image?", inputSlot.captured.question)
    }

    @Test
    fun `rejects oversized images before delegating to vision gateway`() = runTest {
        val homeDir = tempDir("home")
        val imagePath = homeDir.resolve("Pictures/cat.png")
        imagePath.parent.createDirectories()
        imagePath.writeBytes(byteArrayOf(1, 2, 3, 4))

        val filesToolUtil = createFilesToolUtil(homeDir)
        val gateway = mockk<VisionGateway>()
        val tool = ToolViewImage(
            filesToolUtil = filesToolUtil,
            visionGateway = gateway,
            maxImageBytes = 3,
        )

        val error = assertFailsWith<ru.souz.tool.BadInputException> {
            tool.suspendInvoke(
                ToolViewImage.Input(
                    imagePath = imagePath.toAbsolutePath().toString(),
                    question = "What is in the image?",
                ),
                ToolInvocationMeta.Empty,
            )
        }

        assertEquals("image file is too large: 4 bytes exceeds limit of 3 bytes", error.message)
        coVerify(exactly = 0) { gateway.analyze(any()) }
    }

    private fun createFilesToolUtil(homeDir: Path): FilesToolUtil {
        val settingsProvider = mockk<SettingsProvider>()
        every { settingsProvider.forbiddenFolders } returns emptyList()
        return FilesToolUtil(
            LocalRuntimeSandbox(
                scope = SandboxScope(userId = "tool-view-image-test"),
                settingsProvider = settingsProvider,
                homePath = homeDir,
                stateRoot = tempDir("state"),
            )
        )
    }

    private fun tempDir(prefix: String): Path =
        Files.createTempDirectory(prefix).also(tempRoots::add)
}
