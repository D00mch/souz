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
import ru.souz.runtime.sandbox.RuntimeSandbox
import ru.souz.runtime.sandbox.SandboxFileSystem
import ru.souz.runtime.sandbox.SandboxPathInfo
import ru.souz.runtime.sandbox.SandboxRuntimePaths
import ru.souz.llms.runtime.VisionGateway
import ru.souz.llms.runtime.VisionInput
import ru.souz.runtime.sandbox.SandboxScope
import ru.souz.runtime.sandbox.local.LocalRuntimeSandbox
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.exists
import kotlin.io.path.writeBytes
import kotlin.test.assertContentEquals
import kotlin.test.Test
import kotlin.test.assertFalse
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
            ToolInvocationMeta.localDefault(),
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
                ToolInvocationMeta.localDefault(),
            )
        }

        assertEquals("image file is too large: 4 bytes exceeds limit of 3 bytes", error.message)
        coVerify(exactly = 0) { gateway.analyze(any()) }
    }

    @Test
    fun `bridges non local sandbox images through a temporary local file`() = runTest {
        val tempRoot = tempDir("sandbox-root")
        val imageBytes = byteArrayOf(9, 8, 7, 6)
        val sandboxPath = SandboxPathInfo(
            rawPath = "/sandbox/cat.png",
            path = "/sandbox/cat.png",
            name = "cat.png",
            parentPath = "/sandbox",
            exists = true,
            isDirectory = false,
            isRegularFile = true,
            isSymbolicLink = false,
            sizeBytes = imageBytes.size.toLong(),
        )
        val runtimePaths = sandboxRuntimePaths(tempRoot)
        val fileSystem = mockk<SandboxFileSystem>()
        every { fileSystem.runtimePaths } returns runtimePaths
        every { fileSystem.resolveExistingFile("/sandbox/cat.png") } returns sandboxPath
        every { fileSystem.localPathOrNull(sandboxPath) } returns null
        every { fileSystem.readBytes(sandboxPath) } returns imageBytes

        val sandbox = mockk<RuntimeSandbox>(relaxed = true)
        every { sandbox.fileSystem } returns fileSystem
        every { sandbox.runtimePaths } returns runtimePaths

        val gateway = mockk<VisionGateway>()
        val inputSlot = slot<VisionInput>()
        coEvery { gateway.analyze(capture(inputSlot)) } answers {
            assertContentEquals(imageBytes, Files.readAllBytes(inputSlot.captured.imagePath))
            "A bridged cat"
        }

        val tool = ToolViewImage(
            filesToolUtil = FilesToolUtil(sandbox),
            visionGateway = gateway,
        )

        val result = tool.suspendInvoke(
            ToolViewImage.Input(
                imagePath = "/sandbox/cat.png",
                question = "What is in the image?",
            ),
            ToolInvocationMeta.localDefault(),
        )

        assertEquals("A bridged cat", result)
        assertEquals("image/png", inputSlot.captured.mimeType)
        assertEquals(imageBytes.size.toLong(), inputSlot.captured.sizeBytes)
        assertEquals("What is in the image?", inputSlot.captured.question)
        assertFalse(inputSlot.captured.imagePath.exists())
        coVerify(exactly = 1) { gateway.analyze(any()) }
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

    private fun sandboxRuntimePaths(root: Path): SandboxRuntimePaths {
        val stateRoot = root.resolve("state").also(Files::createDirectories)
        return SandboxRuntimePaths(
            homePath = root.toString(),
            workspaceRootPath = root.toString(),
            stateRootPath = stateRoot.toString(),
            sessionsDirPath = stateRoot.resolve("sessions").toString(),
            vectorIndexDirPath = stateRoot.resolve("vector-index").toString(),
            logsDirPath = stateRoot.resolve("logs").toString(),
            modelsDirPath = stateRoot.resolve("models").toString(),
            nativeLibsDirPath = stateRoot.resolve("native").toString(),
            skillsDirPath = stateRoot.resolve("skills").toString(),
            skillValidationsDirPath = stateRoot.resolve("skill-validations").toString(),
        )
    }
}
