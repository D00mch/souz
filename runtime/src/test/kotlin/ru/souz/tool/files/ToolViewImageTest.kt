package ru.souz.tool.files

import io.mockk.coEvery
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
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals

class ToolViewImageTest {
    private val tempRoots = mutableListOf<Path>()

    @AfterEach
    fun cleanup() {
        tempRoots.asReversed().forEach { path -> runCatching { path.toFile().deleteRecursively() } }
        tempRoots.clear()
    }

    @Test
    fun `reads image through files tool util and delegates to vision gateway`() = runTest {
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
        assertEquals(imagePath.toRealPath().toString(), inputSlot.captured.imagePath)
        assertContentEquals(imageBytes, inputSlot.captured.imageBytes)
        assertEquals("image/png", inputSlot.captured.mimeType)
        assertEquals("What is in the image?", inputSlot.captured.question)
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
