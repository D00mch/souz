package ru.souz.tool.files

import io.mockk.every
import io.mockk.mockk
import io.mockk.spyk
import io.mockk.verify
import org.apache.pdfbox.pdmodel.PDDocument
import org.apache.pdfbox.pdmodel.PDPage
import org.apache.pdfbox.pdmodel.PDPageContentStream
import org.apache.pdfbox.pdmodel.font.PDType1Font
import org.apache.pdfbox.pdmodel.font.Standard14Fonts
import ru.souz.llms.ToolInvocationMeta
import ru.souz.db.SettingsProvider
import ru.souz.runtime.sandbox.RuntimeSandbox
import ru.souz.runtime.sandbox.SandboxFileSystem
import ru.souz.runtime.sandbox.SandboxPathInfo
import ru.souz.runtime.sandbox.SandboxRuntimePaths
import ru.souz.runtime.sandbox.SandboxScope
import ru.souz.runtime.sandbox.local.LocalRuntimeSandbox
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContains

class ToolReadPdfPagesMemoryTest {
    private val createdPaths = mutableListOf<Path>()

    @AfterTest
    fun cleanup() {
        createdPaths.asReversed().forEach { path ->
            runCatching { path.toFile().deleteRecursively() }
        }
        createdPaths.clear()
    }

    @Test
    fun `reads pdf through local sandbox path without readBytes`() {
        val home = createTempDirectory("pdf-tool-home-")
        val stateRoot = createTempDirectory("pdf-tool-state-")
        val pdf = createPdf(home.resolve("sample.pdf"), "Local sandbox PDF text")
        val settingsProvider = mockk<SettingsProvider>()
        every { settingsProvider.forbiddenFolders } returns emptyList()

        val localSandbox = LocalRuntimeSandbox(
            scope = SandboxScope(userId = "user-1"),
            settingsProvider = settingsProvider,
            homePath = home,
            stateRoot = stateRoot,
        )
        val fileSystem = spyk(localSandbox.fileSystem)
        every { fileSystem.readBytes(any()) } answers {
            throw AssertionError("readBytes() should not be used for PDFs")
        }
        val sandbox = mockk<RuntimeSandbox>(relaxed = true)
        every { sandbox.fileSystem } returns fileSystem
        every { sandbox.runtimePaths } returns localSandbox.runtimePaths

        val result = ToolReadPdfPages(FilesToolUtil(sandbox))
            .invoke(ToolReadPdfPages.Input(filePath = pdf.toString(), startPage = 1), ToolInvocationMeta.Empty)

        assertContains(result, "Local sandbox PDF text")
        verify(exactly = 0) { fileSystem.readBytes(any()) }
    }

    @Test
    fun `reads pdf through input stream fallback without readBytes`() {
        val tempRoot = createTempDirectory("pdf-tool-stream-")
        val pdf = createPdf(tempRoot.resolve("source.pdf"), "Stream fallback PDF text")
        val sandboxPath = SandboxPathInfo(
            rawPath = pdf.toString(),
            path = "/sandbox/source.pdf",
            name = "source.pdf",
            parentPath = "/sandbox",
            exists = true,
            isDirectory = false,
            isRegularFile = true,
            isSymbolicLink = false,
            sizeBytes = Files.size(pdf),
        )
        val runtimePaths = sandboxRuntimePaths(tempRoot)
        val fileSystem = mockk<SandboxFileSystem>()
        every { fileSystem.runtimePaths } returns runtimePaths
        every { fileSystem.resolvePath(pdf.toString()) } returns sandboxPath
        every { fileSystem.readBytes(any()) } answers {
            throw AssertionError("readBytes() should not be used for PDFs")
        }
        every { fileSystem.openInputStream(sandboxPath) } answers { Files.newInputStream(pdf) }
        every { fileSystem.localPathOrNull(sandboxPath) } returns null

        val sandbox = mockk<RuntimeSandbox>(relaxed = true)
        every { sandbox.fileSystem } returns fileSystem
        every { sandbox.runtimePaths } returns runtimePaths

        val result = ToolReadPdfPages(FilesToolUtil(sandbox))
            .invoke(ToolReadPdfPages.Input(filePath = pdf.toString(), startPage = 1), ToolInvocationMeta.Empty)

        assertContains(result, "Stream fallback PDF text")
        verify(exactly = 0) { fileSystem.readBytes(any()) }
        verify(exactly = 1) { fileSystem.openInputStream(sandboxPath) }
    }

    private fun createPdf(path: Path, text: String): Path {
        PDDocument().use { document ->
            val page = PDPage()
            document.addPage(page)
            PDPageContentStream(document, page).use { content ->
                content.beginText()
                content.setFont(PDType1Font(Standard14Fonts.FontName.HELVETICA), 12f)
                content.newLineAtOffset(72f, 720f)
                content.showText(text)
                content.endText()
            }
            document.save(path.toFile())
        }
        return path
    }

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

    private fun createTempDirectory(prefix: String): Path =
        Files.createTempDirectory(prefix).also(createdPaths::add)
}
