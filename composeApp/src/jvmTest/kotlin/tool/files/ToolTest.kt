package tool.files

import io.mockk.every
import io.mockk.mockk
import org.junit.Assert
import org.slf4j.LoggerFactory
import ru.gigadesk.db.SettingsProvider
import ru.gigadesk.tool.BadInputException
import ru.gigadesk.tool.files.FilesToolUtil
import ru.gigadesk.tool.files.ToolDeleteFile
import ru.gigadesk.tool.files.ToolFindInFiles
import ru.gigadesk.tool.files.ToolFindTextInFiles
import ru.gigadesk.tool.files.ToolListFiles
import ru.gigadesk.tool.files.ToolModifyFile
import ru.gigadesk.tool.files.ToolMoveFile
import ru.gigadesk.tool.files.ToolNewFile
import ru.gigadesk.tool.files.ToolReadFile
import java.io.File
import java.nio.file.Files
import java.util.UUID
import kotlin.test.Ignore
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals

class ToolTest {
    private val filesToolUtil: FilesToolUtil = mockk()

    private fun createTempDirectory(): File =
        Files.createTempDirectory(File("src/jvmTest/resources").toPath(), "gigadesk-test-").toFile()

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
        val l = LoggerFactory.getLogger(ToolTest::class.java)
        l.info(File("src/jvmTest/resources/test.txt").readText())
        val result = ToolReadFile(createFilesToolUtil(listOf("~/Library/")))
            .invoke(ToolReadFile.Input("src/jvmTest/resources/test.txt"))
        assertEquals("Test content\n", result)
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
        val content = "Test"
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
            val newContent = "New"
            ToolModifyFile(filesToolUtil).invoke(ToolModifyFile.Input(path, oldText = content, newText = newContent))

            // move
            ToolMoveFile(filesToolUtil).invoke(ToolMoveFile.Input(path, movedPath))
            val movedContent = ToolReadFile(filesToolUtil).invoke(ToolReadFile.Input(movedPath))
            assertEquals(newContent, movedContent)

            // find
            val findResult = ToolFindTextInFiles(filesToolUtil)
                .invoke(ToolFindTextInFiles.Input(path = resources, newContent))
            assertEquals("[moved-$newFileName]", findResult)

            // delete
            ToolDeleteFile(filesToolUtil).invoke(ToolDeleteFile.Input(movedPath))
            Assert.assertThrows(BadInputException::class.java) {
                ToolReadFile(filesToolUtil).invoke(ToolReadFile.Input(movedPath))
            }
        } finally {
            tempDir.deleteRecursively()
        }
    }
}