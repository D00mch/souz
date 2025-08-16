package tool.coder

import com.dumch.tool.BadInputException
import com.dumch.tool.files.ToolDeleteFile
import com.dumch.tool.files.ToolFindTextInFiles
import com.dumch.tool.files.ToolListFiles
import com.dumch.tool.files.ToolModifyFile
import com.dumch.tool.files.ToolNewFile
import com.dumch.tool.files.ToolReadFile
import org.junit.jupiter.api.assertThrows
import java.io.File
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import org.slf4j.LoggerFactory

class ToolTest {

    @Test
    fun `test ToolReadFile`() {
        val l = LoggerFactory.getLogger(ToolTest::class.java)
        l.info(File("src/test/resources/test.txt").readText())
        val result = ToolReadFile(ToolReadFile.Input("src/test/resources/test.txt"))
        assertEquals("Test content\n", result)
    }

    @Test
    fun `test ToolListFiles`() {
        val result = ToolListFiles(ToolListFiles.Input("gradle/wrapper"))
        val files = result.removePrefix("[").removeSuffix("]").split(",").toSet()
        assertEquals(setOf("gradle/wrapper/gradle-wrapper.jar", "gradle/wrapper/gradle-wrapper.properties"), files)

        val resources = ToolListFiles(ToolListFiles.Input("src/test/resources"))
        val resourceFiles = resources.removePrefix("[").removeSuffix("]").split(",").toSet()
        assertEquals(setOf("src/test/resources/directory/", "src/test/resources/directory/file.txt", "src/test/resources/test.txt"), resourceFiles)
        val l = LoggerFactory.getLogger(ToolTest::class.java)
        l.info(resources)
    }

    @Test
    fun `test ToolNewFile, ToolModifyFile, ToolDeleteFile lifecycle`() {
        val content = "Test"
        val resources = "src/test/resources"
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
        assertThrows<BadInputException> { ToolReadFile(ToolReadFile.Input(path)) }
    }
}