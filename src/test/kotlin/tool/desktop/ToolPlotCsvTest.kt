package tool.desktop

import com.dumch.tool.ToolRunBashCommand
import com.dumch.tool.desktop.ToolPlotCsv
import kotlin.test.Test
import kotlin.test.assertEquals

class ToolPlotCsvTest {
    @Test
    fun `extract headers from csv`() {
        val tool = ToolPlotCsv(ToolRunBashCommand)
        val result = tool.invoke(ToolPlotCsv.Input("src/test/resources/sample.csv"))
        assertEquals("[name, age]", result)
    }
}
