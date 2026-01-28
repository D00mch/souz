package tool.coder

import ru.gigadesk.tool.BadInputException
import ru.gigadesk.tool.files.ToolDeleteFile
import ru.gigadesk.tool.files.ToolFindTextInFiles
import ru.gigadesk.tool.files.ToolListFiles
import ru.gigadesk.tool.files.ToolModifyFile
import ru.gigadesk.tool.files.ToolMoveFile
import ru.gigadesk.tool.files.ToolNewFile
import ru.gigadesk.tool.files.ToolReadFile
import org.junit.Assert.assertThrows
import java.io.File
import java.nio.file.Files
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import org.slf4j.LoggerFactory
import ru.gigadesk.db.ConfigStore
import ru.gigadesk.db.SettingsProvider
import ru.gigadesk.tool.files.ToolFindInFiles
import kotlin.test.Ignore
import kotlin.test.assertContains
import ru.gigadesk.tool.files.FilesToolUtil

class ToolTest {
    private val filesToolUtil = FilesToolUtil(SettingsProvider(ConfigStore))
    private val listFiles = ToolListFiles(filesToolUtil)

    private fun createTempDirectory(): File =
        Files.createTempDirectory(FilesToolUtil.homeDirectory.toPath(), "gigadesk-test-").toFile()

    private fun createSampleFiles(baseDir: File) {
        val nestedDir = File(baseDir, "directory").apply { mkdirs() }
        File(nestedDir, "file.txt").writeText("Nested")
        File(baseDir, "sample.csv").writeText("name,score\nAlice,1")
        File(baseDir, "test.txt").writeText("Test content\n")
    }

    @Test
    fun `test ToolReadFile`() {
        val l = LoggerFactory.getLogger(ToolTest::class.java)
        l.info(File("src/jvmTest/resources/test.txt").readText())
        val result = ToolReadFile(FilesToolUtil(SettingsProvider(ConfigStore))).invoke(ToolReadFile.Input("src/jvmTest/resources/test.txt"))
        assertEquals("Test content\n", result)
    }

    @Test
    fun `test ToolListFiles`() {
        val tempDir = createTempDirectory()
        try {
            createSampleFiles(tempDir)
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
        val resources = ToolFindInFiles(filesToolUtil).invoke(ToolFindInFiles.Input("src/jvmTest/resources", "Alice"))
        println(resources)
        assertContains(resources, "sample.csv")
    }

    @Test
    fun `test ToolNewFile, ToolModifyFile, ToolMoveFile, ToolDeleteFile lifecycle`() {
        val content = "Test"
        val tempDir = createTempDirectory()
        try {
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
            assertThrows(BadInputException::class.java) {
                ToolReadFile(filesToolUtil).invoke(ToolReadFile.Input(movedPath))
            }
        } finally {
            tempDir.deleteRecursively()
        }
    }
}
