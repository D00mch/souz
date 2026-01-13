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
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import org.slf4j.LoggerFactory
import ru.gigadesk.tool.files.ToolFindInFiles
import kotlin.test.Ignore
import kotlin.test.assertContains

class ToolTest {

    @Test
    fun `test ToolReadFile`() {
        val l = LoggerFactory.getLogger(ToolTest::class.java)
        l.info(File("src/jvmTest/resources/test.txt").readText())
        val result = ToolReadFile(ToolReadFile.Input("src/jvmTest/resources/test.txt"))
        assertEquals("Test content\n", result)
    }

    @Test
    fun `test ToolListFiles`() {
        val resources = ToolListFiles(ToolListFiles.Input("src/jvmTest/resources"))
        val resourceFiles = resources.removePrefix("[").removeSuffix("]").split(",").toSet()
        assertEquals(
            setOf(
                "src/jvmTest/resources/directory/",
                "src/jvmTest/resources/directory/file.txt",
                "src/jvmTest/resources/sample.csv",
                "src/jvmTest/resources/test.txt",
            ),
            resourceFiles
        )
        val l = LoggerFactory.getLogger(ToolTest::class.java)
        l.info(resources)
    }

    @Test
    @Ignore
    fun `test ToolFindInFiles`() {
        val resources = ToolFindInFiles(ToolFindInFiles.Input("src/jvmTest/resources", "Alice"))
        println(resources)
        assertContains(resources, "sample.csv")
    }

    @Test
    fun `test ToolNewFile, ToolModifyFile, ToolMoveFile, ToolDeleteFile lifecycle`() {
        val content = "Test"
        val resources = "src/jvmTest/resources"
        val newFileName = "${UUID.randomUUID()}.txt"
        val path = "$resources/$newFileName"
        val movedPath = "$resources/moved-$newFileName"

        // create new file
        ToolNewFile(ToolNewFile.Input(path, text = content))
        val fileContent = ToolReadFile(ToolReadFile.Input(path))
        assertEquals(content, fileContent)

        // modify new
        val newContent = "New"
        ToolModifyFile(ToolModifyFile.Input(path, oldText = content, newText = newContent))

        // move
        ToolMoveFile(ToolMoveFile.Input(path, movedPath))
        val movedContent = ToolReadFile(ToolReadFile.Input(movedPath))
        assertEquals(newContent, movedContent)

        // find
        val findResult = ToolFindTextInFiles(ToolFindTextInFiles.Input(path = resources, newContent))
        assertEquals("[moved-$newFileName]", findResult)

        // delete
        ToolDeleteFile(ToolDeleteFile.Input(movedPath))
        assertThrows(BadInputException::class.java) {
            ToolReadFile(ToolReadFile.Input(movedPath))
        }
    }
}
