package tool

import com.dumch.tool.ToolRunBashCommand
import kotlin.test.Test
import kotlin.test.assertTrue

class ToolRunBashCommandTest {
    @Test
    fun `test ls command execution`() {
        val result = ToolRunBashCommand.invoke(ToolRunBashCommand.Input("ls"))

        assertTrue(result.contains("src"), "Output should contain 'src' directory")
        assertTrue(result.contains("build.gradle.kts"), "Output should contain 'build.gradle.kts' file")
    }
}