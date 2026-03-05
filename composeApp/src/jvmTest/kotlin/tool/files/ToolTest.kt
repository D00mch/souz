package tool.files

import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeoutOrNull
import org.slf4j.LoggerFactory
import ru.souz.db.SettingsProvider
import ru.souz.tool.BadInputException
import ru.souz.tool.ToolPermissionBroker
import ru.souz.tool.files.FilesToolUtil
import ru.souz.tool.files.ToolDeleteFile
import ru.souz.tool.files.ToolExtractText
import ru.souz.tool.files.ToolFindInFiles
import ru.souz.tool.files.ToolFindTextInFiles
import ru.souz.tool.files.ToolListFiles
import ru.souz.tool.files.ToolModifyFile
import ru.souz.tool.files.ToolMoveFile
import ru.souz.tool.files.ToolNewFile
import ru.souz.tool.files.ToolReadFile
import ru.souz.tool.files.ToolReadPdfPages
import java.io.File
import java.nio.file.Files
import java.util.UUID
import kotlin.test.Ignore
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertFalse
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class ToolTest {
    companion object {
        private const val PDF_FIXTURE_NAME = "Android_ исследование IPC Android и хукинг нативных библиотек — «Хакер».pdf"
    }

    private val filesToolUtil: FilesToolUtil = mockk()

    private fun createTempDirectory(): File =
        Files.createTempDirectory(FilesToolUtil.homeDirectory.toPath(), "souz-test-").toFile()

    private fun fixtureRoot(): File {
        val starts = generateSequence(File(System.getProperty("user.dir")).absoluteFile) { it.parentFile }
        for (base in starts) {
            val candidates = listOf(
                File(base, "composeApp/src/jvmTest/resources"),
                File(base, "src/jvmTest/resources"),
            )
            val found = candidates.firstOrNull { it.exists() }
            if (found != null) return found
        }
        error("Fixture resources directory not found")
    }

    private fun fixtureDirectory(): File = File(fixtureRoot(), "directory")

    private fun fixturePath(name: String): String = File(fixtureDirectory(), name).path

    private fun fixtureInHome(name: String): File {
        val stream = sequenceOf(name, "directory/$name")
            .mapNotNull { path -> javaClass.classLoader.getResourceAsStream(path) }
            .firstOrNull()
            ?: run {
                val source = listOf(
                    File(fixtureRoot(), name),
                    File(fixtureDirectory(), name),
                ).firstOrNull { it.exists() }
                if (source != null) {
                    val dir = createTempDirectory()
                    val target = File(dir, name)
                    source.copyTo(target, overwrite = true)
                    return target
                }
                error("Fixture $name not found")
            }

        val dir = createTempDirectory()
        val target = File(dir, name)
        stream.use { input -> target.outputStream().use { output -> input.copyTo(output) } }
        return target
    }

    private fun firstFixturePdfPath(): String = fixtureInHome(PDF_FIXTURE_NAME).absolutePath


    private fun createSampleFiles(baseDir: File) {
        val nestedDir = File(baseDir, "directory").apply { mkdirs() }
        File(nestedDir, "file.txt").writeText("Nested")
        File(baseDir, "sample.csv").writeText("name,score\nAlice,1")
        File(baseDir, "test.txt").writeText("Test content\n")
    }

    private fun createFilesToolUtil(forbiddenFolders: List<String>): FilesToolUtil {
        val settingsProvider = mockk<SettingsProvider>()
        every { settingsProvider.forbiddenFolders } returns forbiddenFolders
        return FilesToolUtil(settingsProvider)
    }

    @Test
    fun `test isPathSafe allows non-forbidden paths`() {
        val tempDir = createTempDirectory()
        val forbiddenDir = createTempDirectory()
        val filesToolUtil = createFilesToolUtil(listOf(forbiddenDir.absolutePath, "~/Library/"))
        try {
            val safeFile = File(tempDir, "safe.txt").apply { writeText("ok") }
            assertEquals(true, filesToolUtil.isPathSafe(safeFile))
        } finally {
            tempDir.deleteRecursively()
            forbiddenDir.deleteRecursively()
        }
    }

    @Test
    fun `test isPathSafe blocks forbidden paths and canonical traversal`() {
        val forbiddenDir = createTempDirectory()
        val filesToolUtil = createFilesToolUtil(listOf(forbiddenDir.absolutePath, "~/Library/"))
        try {
            val directForbidden = File(forbiddenDir, "blocked.txt").apply { writeText("nope") }
            assertEquals(false, filesToolUtil.isPathSafe(directForbidden))

            val traversalPath = File(forbiddenDir.parentFile, "${forbiddenDir.name}/blocked.txt")
            assertEquals(false, filesToolUtil.isPathSafe(traversalPath))
        } finally {
            forbiddenDir.deleteRecursively()
        }
    }

    @Test
    fun `test ToolReadFile`() {
        val filesToolUtil = createFilesToolUtil(listOf("~/Library/"))
        val fixture = fixtureInHome("test.txt")

        val result = ToolReadFile(filesToolUtil)
            .invoke(ToolReadFile.Input(fixture.absolutePath))
        assertEquals("Test content\n", result)

        val extracted = ToolExtractText(filesToolUtil)
            .invoke(ToolExtractText.Input(fixture.absolutePath))
        assertContains(extracted, "=== METADATA ===")
        assertContains(extracted, "Filename: test.txt")
        assertContains(extracted, "=== CONTENT ===")
        assertContains(extracted, "Test content")

        fixture.parentFile?.deleteRecursively()
    }

    @Test
    fun `test ToolReadPdfPages reads fixture pdf`() {
        val filesToolUtil = createFilesToolUtil(forbiddenFolders = listOf("~/Library/"))
        val fixturePdf = fixtureInHome(firstFixturePdfPath().let(::File).name)

        val result = ToolReadPdfPages(filesToolUtil)
            .invoke(ToolReadPdfPages.Input(filePath = fixturePdf.absolutePath, startPage = 1))

        assertFalse(result.startsWith("Error:"))
        assertFalse(result.startsWith("IO Error"))
        assertFalse(result.startsWith("Unexpected error"))
        assertTrue(result.contains("=== PDF CONTENT (Pages 1-1 of"))

        fixturePdf.parentFile?.deleteRecursively()
    }

    @Test
    fun `test ToolReadPdfPages validates non-pdf and missing files`() {
        val filesToolUtil = createFilesToolUtil(forbiddenFolders = listOf("~/Library/"))
        val tool = ToolReadPdfPages(filesToolUtil)
        val nonPdfFile = fixtureInHome("file.txt")

        val nonPdfResult = tool.invoke(ToolReadPdfPages.Input(filePath = nonPdfFile.absolutePath))
        assertEquals("Error: Expecting .pdf file", nonPdfResult)

        val missingPath = File(nonPdfFile.parentFile, "missing.pdf").absolutePath
        val missingResult = tool.invoke(ToolReadPdfPages.Input(filePath = missingPath))
        assertEquals("Error: File not found at $missingPath", missingResult)

        nonPdfFile.parentFile?.deleteRecursively()
    }

    @Test
    fun `test ToolReadPdfPages reports out-of-range page`() {
        val filesToolUtil = createFilesToolUtil(listOf("~/Library/"))
        val fixturePdf = fixtureInHome(firstFixturePdfPath().let(::File).name)

        val result = ToolReadPdfPages(filesToolUtil)
            .invoke(ToolReadPdfPages.Input(filePath = fixturePdf.absolutePath, startPage = 10000))

        assertContains(result, "Error: Requested page 10000 but document only has")

        fixturePdf.parentFile?.deleteRecursively()
    }

    @Test
    fun `test ToolExtractText supports plain text previews for multiple extensions`() {
        val tempDir = createTempDirectory()
        try {
            val filesToolUtil = createFilesToolUtil(forbiddenFolders = listOf("~/Library/"))
            val tool = ToolExtractText(filesToolUtil)
            val fixtureText = fixtureInHome("file.txt")

            val markdownFile = File(tempDir, "notes.md").apply {
                writeText("# Notes\n\n- Alpha\n- Beta\n")
            }
            val jsonFile = File(tempDir, "payload.json").apply {
                writeText("""{"name":"Souz","enabled":true}""")
            }
            val yamlFile = File(tempDir, "config.yaml").apply {
                writeText("name: Souz\nenabled: true\n")
            }

            val cases = listOf(
                fixtureText.path to "Содержимое file.txt",
                markdownFile.path to "# Notes",
                jsonFile.path to "\"name\":\"Souz\"",
                yamlFile.path to "enabled: true"
            )

            cases.forEach { (path, expectedText) ->
                val extracted = tool.invoke(ToolExtractText.Input(path))
                assertContains(extracted, "=== METADATA ===")
                assertContains(extracted, "Filename: ${File(path).name}")
                assertContains(extracted, "Content-Type: text/plain (direct)")
                assertContains(extracted, "Charset: UTF-8")
                assertContains(extracted, "=== CONTENT ===")
                assertContains(extracted, expectedText)
            }
            fixtureText.parentFile?.deleteRecursively()
        } finally {
            tempDir.deleteRecursively()
        }
    }

    @Test
    fun `test ToolExtractText reads provided xlsx fixtures`() {
        val filesToolUtil = createFilesToolUtil(listOf("~/Library/"))
        val tool = ToolExtractText(filesToolUtil)
        val expectedMarkersByFile = mapOf(
            "clients.xlsx" to "Client A",
            "orders.xlsx" to "OrderID",
            "price.xlsx" to "Laptop",
            "sales.xlsx" to "Ivanov"
        )

        expectedMarkersByFile.forEach { (fileName, expectedMarker) ->
            val fixture = fixtureInHome(fileName)
            val extracted = tool.invoke(ToolExtractText.Input(fixture.absolutePath))
            assertContains(extracted, "=== METADATA ===")
            assertContains(extracted, "Filename: $fileName")
            assertContains(extracted, "=== CONTENT ===")
            assertContains(extracted, expectedMarker)
            fixture.parentFile?.deleteRecursively()
        }
    }

    @Test
    fun `test ToolListFiles`() {
        val tempDir = createTempDirectory()
        try {
            createSampleFiles(tempDir)
            val listFiles = ToolListFiles(createFilesToolUtil(listOf("~/Library/")))
            val resources = listFiles(ToolListFiles.Input(tempDir.absolutePath))
            val resourceFiles = resources.removePrefix("[").removeSuffix("]").split(",").toSet()
            assertEquals(
                setOf(
                    "${tempDir.absolutePath}/directory/",
                    "${tempDir.absolutePath}/directory/file.txt",
                    "${tempDir.absolutePath}/sample.csv",
                    "${tempDir.absolutePath}/test.txt",
                ),
                resourceFiles
            )
            val l = LoggerFactory.getLogger(ToolTest::class.java)
            l.info(resources)
        } finally {
            tempDir.deleteRecursively()
        }
    }

    @Test
    @Ignore
    fun `test ToolFindInFiles`() {
        val resources = ToolFindInFiles(filesToolUtil)
            .invoke(ToolFindInFiles.Input("src/jvmTest/resources", "Alice"))
        println(resources)
        assertContains(resources, "sample.csv")
    }

    @Test
    fun `test ToolNewFile, ToolModifyFile, ToolMoveFile, ToolDeleteFile lifecycle`() {
        val content = "Test\n"
        val tempDir = createTempDirectory()
        try {
            val filesToolUtil = createFilesToolUtil(listOf("~/Library/"))
            val resources = tempDir.absolutePath
            val newFileName = "${UUID.randomUUID()}.txt"
            val path = "$resources/$newFileName"
            val movedPath = "$resources/moved-$newFileName"

            // create new file
            ToolNewFile(filesToolUtil).invoke(ToolNewFile.Input(path, text = content))
            val fileContent = ToolReadFile(filesToolUtil).invoke(ToolReadFile.Input(path))
            assertEquals(content, fileContent)

            // modify new
            val newContent = "New\n"
            val patch = """
                --- a/$newFileName
                +++ b/$newFileName
                @@ -1 +1 @@
                -${content.trimEnd('\n')}
                +${newContent.trimEnd('\n')}
            """.trimIndent() + "\n"
            ToolModifyFile(filesToolUtil).invoke(ToolModifyFile.Input(path = path, patch = patch, strip = 1))

            // move
            ToolMoveFile(filesToolUtil).invoke(ToolMoveFile.Input(path, movedPath))
            val movedContent = ToolReadFile(filesToolUtil).invoke(ToolReadFile.Input(movedPath))
            assertEquals(newContent, movedContent)

            // find
            val findResult = ToolFindTextInFiles(filesToolUtil)
                .invoke(ToolFindTextInFiles.Input(path = resources, "New"))
            assertEquals("[moved-$newFileName]", findResult)

            // delete
            ToolDeleteFile(filesToolUtil).invoke(ToolDeleteFile.Input(movedPath))
            assertFailsWith<BadInputException> {
                ToolReadFile(filesToolUtil).invoke(ToolReadFile.Input(movedPath))
            }
        } finally {
            tempDir.deleteRecursively()
        }
    }

    @Test
    fun `test ToolModifyFile applies patch for filename with spaces`() {
        val tempDir = createTempDirectory()
        try {
            val filesToolUtil = createFilesToolUtil(listOf("~/Library/"))
            val fileName = "my note.txt"
            val path = "${tempDir.absolutePath}/$fileName"
            File(path).writeText("Hello\n")
            val ts = "\t1970-01-01 00:00:00 +0000"

            val patch = listOf(
                "--- a/$fileName$ts",
                "+++ b/$fileName$ts",
                "@@ -1 +1,2 @@",
                " Hello",
                "+World",
                ""
            ).joinToString("\n")

            val result = ToolModifyFile(filesToolUtil).invoke(
                ToolModifyFile.Input(path = path, patch = patch, strip = 1)
            )

            assertEquals("OK", result)
            assertEquals("Hello\nWorld\n", File(path).readText())
        } finally {
            tempDir.deleteRecursively()
        }
    }

    @Test
    fun `test ToolModifyFile applies patch with absolute file headers`() {
        val tempDir = createTempDirectory()
        try {
            val filesToolUtil = createFilesToolUtil(listOf("~/Library/"))
            val path = "${tempDir.absolutePath}/absolute.patch.txt"
            File(path).writeText("before\n")

            val patch = listOf(
                "--- $path",
                "+++ $path",
                "@@ -1 +1 @@",
                "-before",
                "+after",
                ""
            ).joinToString("\n")

            val result = runCatching {
                ToolModifyFile(filesToolUtil).invoke(
                    ToolModifyFile.Input(path = path, patch = patch, strip = 0)
                )
            }

            if (result.isSuccess) {
                assertEquals("OK", result.getOrThrow())
                assertEquals("after\n", File(path).readText())
            } else {
                val error = result.exceptionOrNull()
                assertTrue(error is BadInputException)
                assertContains(error.message.orEmpty(), "Patch dry-run failed")
                assertEquals("before\n", File(path).readText())
            }
        } finally {
            tempDir.deleteRecursively()
        }
    }

    @Test
    fun `test ToolModifyFile rejects invalid hunk header without ranges`() {
        val tempDir = createTempDirectory()
        try {
            val filesToolUtil = createFilesToolUtil(listOf("~/Library/"))
            val fileName = "invalid-hunk.txt"
            val path = "${tempDir.absolutePath}/$fileName"
            File(path).writeText("x\n")

            val patch = listOf(
                "--- $fileName",
                "+++ $fileName",
                "@@",
                "-x",
                "+y",
                ""
            ).joinToString("\n")

            val error = assertFailsWith<BadInputException> {
                ToolModifyFile(filesToolUtil).invoke(
                    ToolModifyFile.Input(path = path, patch = patch, strip = 0)
                )
            }

            assertContains(error.message.orEmpty(), "Invalid hunk header")
            assertEquals("x\n", File(path).readText())
        } finally {
            tempDir.deleteRecursively()
        }
    }

    @Test
    fun `test ToolModifyFile validates patch before asking permission`() = runTest {
        val tempDir = createTempDirectory()
        try {
            val filesToolUtil = createFilesToolUtil(listOf("~/Library/"))
            val path = "${tempDir.absolutePath}/prevalidate.txt"
            File(path).writeText("line\n")

            val settingsProvider = mockk<SettingsProvider>()
            every { settingsProvider.safeModeEnabled } returns true
            val permissionBroker = ToolPermissionBroker(settingsProvider)
            val tool = ToolModifyFile(filesToolUtil, permissionBroker)

            val patch = """
                *** Begin Patch
                *** Update File: $path
                @@
                -line
                +LINE
                *** End Patch
            """.trimIndent()

            val error = assertFailsWith<BadInputException> {
                tool.suspendInvoke(ToolModifyFile.Input(path = path, patch = patch, strip = 0))
            }

            assertContains(error.message.orEmpty(), "unified diff")
            val pendingRequest = withTimeoutOrNull(150) { permissionBroker.requests.first() }
            assertEquals(null, pendingRequest)
            assertEquals("line\n", File(path).readText())
        } finally {
            tempDir.deleteRecursively()
        }
    }

    private fun hasOpenGlRuntime(): Boolean {
        val mapped = System.mapLibraryName("GL")
        val candidates = listOf(
            File("/usr/lib/x86_64-linux-gnu/$mapped"),
            File("/lib/x86_64-linux-gnu/$mapped"),
            File("/usr/lib64/$mapped"),
            File("/usr/lib/$mapped"),
        )
        return candidates.any { it.exists() }
    }

    @Test
    fun `test ToolDeleteFile returns disapproved when user rejects action`() = runTest {
        if (!hasOpenGlRuntime()) return@runTest
        val tempDir = createTempDirectory()
        try {
            val filesToolUtil = createFilesToolUtil(listOf("~/Library/"))
            val path = "${tempDir.absolutePath}/to-delete.txt"
            File(path).writeText("test")

            val settingsProvider = mockk<SettingsProvider>()
            every { settingsProvider.safeModeEnabled } returns true
            val permissionBroker = ToolPermissionBroker(settingsProvider)
            val tool = ToolDeleteFile(filesToolUtil, permissionBroker)

            val resultDeferred = async {
                tool.suspendInvoke(ToolDeleteFile.Input(path))
            }
            val request = permissionBroker.requests.first()
            permissionBroker.resolve(request.id, approved = false)

            val result = resultDeferred.await()

            assertEquals("User disapproved", result)
            assertEquals(true, File(path).exists())
        } finally {
            tempDir.deleteRecursively()
        }
    }

    @Test
    fun `test ToolDeleteFile deletes folder`() {
        val tempDir = Files.createTempDirectory(FilesToolUtil.homeDirectory.toPath(), "souz-test-delete-folder-").toFile()
        try {
            val filesToolUtil = createFilesToolUtil(listOf("~/Library/"))
            val folderToDelete = File(tempDir, "folder-to-delete").apply { mkdirs() }
            File(folderToDelete, "nested.txt").writeText("nested")

            val result = ToolDeleteFile(filesToolUtil).invoke(ToolDeleteFile.Input(folderToDelete.absolutePath))

            assertContains(result, "Path moved to Trash")
            assertEquals(false, folderToDelete.exists())
        } finally {
            tempDir.deleteRecursively()
        }
    }
}
