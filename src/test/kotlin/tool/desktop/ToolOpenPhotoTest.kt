package tool.desktop

import com.dumch.tool.ToolRunBashCommand
import com.dumch.tool.desktop.ToolOpenFolder
import com.dumch.tool.desktop.ToolOpenPhoto

fun main() {
    val toolFolder = ToolOpenFolder(ToolRunBashCommand)
    toolFolder.invoke(ToolOpenFolder.Input("Family"))

    val toolPhoto = ToolOpenPhoto(ToolRunBashCommand)
    toolPhoto.invoke(ToolOpenPhoto.Input("дядя Степан"))
}