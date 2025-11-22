package tool.coder

import com.dumch.tool.BadInputException
import com.dumch.tool.files.ToolDeleteFile
import com.dumch.tool.files.ToolFindTextInFiles
import com.dumch.tool.files.ToolListFiles
import com.dumch.tool.files.ToolModifyFile
import com.dumch.tool.files.ToolNewFile
import com.dumch.tool.files.ToolReadFile
import org.junit.Assert.assertThrows
import java.io.File
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import org.slf4j.LoggerFactory

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
    fun `test ToolNewFile, ToolModifyFile, ToolDeleteFile lifecycle`() {
        val content = "Test"
        val resources = "src/jvmTest/resources"
        val newFileName = "${UUID.randomUUID()}.txt"
        val path = "$resources/$newFileName"

        // create new file
        ToolNewFile(ToolNewFile.Input(path, text = content))
        val fileContent = ToolReadFile(ToolReadFile.Input(path))
        assertEquals(content, fileContent)

        // modify new
        val newContent = "New"
        ToolModifyFile(ToolModifyFile.Input(path, oldText = content, newText = newContent))

        // find
        val findResult = ToolFindTextInFiles(ToolFindTextInFiles.Input(path = resources, newContent))
        assertEquals("[$newFileName]", findResult)

        // delete
        ToolDeleteFile(ToolDeleteFile.Input(path))
        assertThrows(BadInputException::class.java) {
            ToolReadFile(ToolReadFile.Input(path))
        }
    }
}