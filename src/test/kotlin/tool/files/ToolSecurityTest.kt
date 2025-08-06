package tool.files

import com.dumch.tool.BadInputException
import com.dumch.tool.files.ToolDeleteFile
import com.dumch.tool.files.ToolModifyFile
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class ToolSecurityTest {

    @Test
    fun `test delete file rejects paths outside project root`() {
        assertThrows<BadInputException> {
            ToolDeleteFile(ToolDeleteFile.Input("/etc/hosts"))
        }
        assertThrows<BadInputException> {
            ToolDeleteFile(ToolDeleteFile.Input("../outside.txt"))
        }
        assertThrows<BadInputException> {
            ToolDeleteFile(ToolDeleteFile.Input("../../outside.txt"))
        }
        // Ensure absolute paths that try to trick the system are caught
        assertThrows<BadInputException> {
            ToolDeleteFile(ToolDeleteFile.Input("/tmp/proj/src/../../etc/hosts"))
        }
    }

    @Test
    fun `test modify file rejects paths outside project root`() {
        assertThrows<BadInputException> {
            ToolModifyFile(ToolModifyFile.Input("/file.txt", "bob", "rob"))
        }
    }
}