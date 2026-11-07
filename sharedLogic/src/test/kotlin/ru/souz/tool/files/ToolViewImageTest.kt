package ru.souz.tool.files

import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.io.TempDir
import ru.souz.db.SettingsProvider
import ru.souz.llms.ToolInvocationMeta
import ru.souz.llms.restJsonMapper
import ru.souz.llms.runtime.GeneratedImage
import ru.souz.llms.runtime.ImageSource
import ru.souz.llms.runtime.VisionInput
import ru.souz.runtime.files.FilesToolUtil
import ru.souz.runtime.sandbox.RuntimeSandbox
import ru.souz.runtime.sandbox.SandboxFileSystem
import ru.souz.runtime.sandbox.SandboxPathInfo
import ru.souz.runtime.sandbox.SandboxScope
import ru.souz.runtime.sandbox.local.LocalRuntimeSandbox
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.writeBytes
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals

class ToolViewImageTest {
    @TempDir
    lateinit var homeDir: Path

    private val png = java.io.ByteArrayOutputStream().also {
        javax.imageio.ImageIO.write(java.awt.image.BufferedImage(1, 1, java.awt.image.BufferedImage.TYPE_INT_RGB), "png", it)
    }.toByteArray()

    @Test
    fun `resolves single and multiple images and delegates to vision gateway`() = runTest {
        val imagePath = homeDir.resolve("Pictures/cat.png")
        imagePath.parent.createDirectories()
        imagePath.writeBytes(png)
        val settings = mockk<SettingsProvider> { every { forbiddenFolders } returns emptyList() }
        val files = FilesToolUtil(LocalRuntimeSandbox(
            scope = SandboxScope(userId = "tool-view-image-test"),
            settingsProvider = settings,
            homePath = homeDir,
            stateRoot = homeDir.resolve("state"),
        ))
        lateinit var supplied: VisionInput
        val tool = ToolViewImage(files) { input -> supplied = input; "A small cat" }
        val meta = ToolInvocationMeta.localDefault()

        assertEquals("A small cat", tool.suspendInvoke(
            ToolViewImage.Input(imagePath = imagePath.toString(), question = "What is in the image?"), meta,
        ))
        assertContentEquals(png, supplied.images.single().bytes)
        tool.suspendInvoke(ToolViewImage.Input(imagePaths = List(2) { imagePath.toString() }, question = "Compare"), meta)
        assertEquals(2, supplied.images.size)
        assertEquals("Compare", supplied.question)
    }

    @Test
    fun `image tools read and write remote sandbox bytes without local paths`() = runTest {
        val source = SandboxPathInfo(
            rawPath = "/sandbox/cat.png", path = "/sandbox/cat.png", name = "cat.png", parentPath = "/sandbox",
            exists = true, isDirectory = false, isRegularFile = true, isSymbolicLink = false,
        )
        val target = source.copy(rawPath = "/sandbox/edit.png", path = "/sandbox/edit.png", name = "edit.png", exists = false)
        val fileSystem = mockk<SandboxFileSystem> {
            every { resolveExistingFile(source.path) } returns source
            every { openInputStream(source) } answers { png.inputStream() }
            every { resolvePath(target.path) } returns target
            every { writeBytes(target, png) } returns Unit
        }
        val sandbox = mockk<RuntimeSandbox> { every { this@mockk.fileSystem } returns fileSystem }
        val files = FilesToolUtil(sandbox)
        val meta = ToolInvocationMeta.localDefault()
        val view = ToolViewImage(files) { input ->
            assertContentEquals(png, input.images.single().bytes)
            assertEquals("image/png", input.images.single().mimeType)
            assertEquals("Describe", input.question)
            "A cat"
        }
        assertEquals("A cat", view.suspendInvoke(ToolViewImage.Input(imagePath = source.path, question = "Describe"), meta))

        val generate = ToolGenerateImage(files) { input ->
            assertEquals("Edit", input.prompt)
            assertContentEquals(png, input.images.single().bytes)
            GeneratedImage(ImageSource.read(png), "OPENAI")
        }
        val output = generate.suspendInvoke(ToolGenerateImage.Input("Edit", target.path, listOf(source.path)), meta)
        assertEquals(target.path, restJsonMapper.readTree(output)["outputPath"].asText())
        verify(exactly = 1) { fileSystem.writeBytes(target, png) }
    }
}
