package tool.desktop

import com.dumch.tool.ToolRunBashCommand
import com.dumch.tool.desktop.ToolOpenApp

fun main() {
    val tool = ToolOpenApp(ToolRunBashCommand)
    tool.invoke(ToolOpenApp.Input("Safari"))

    // The same
    //  ToolRunBashCommand.invoke(ToolRunBashCommand.Input("open -a Safari"))
}