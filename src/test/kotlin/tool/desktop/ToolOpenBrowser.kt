package tool.desktop

import com.dumch.tool.ToolRunBashCommand
import com.dumch.tool.desktop.ToolOpenApp
import com.dumch.tool.desktop.ToolOpenBrowser

fun main() {
    val tool = ToolOpenBrowser(ToolRunBashCommand)
    tool.invoke(ToolOpenApp.Input("https://www.sberbank.ru"))
}
