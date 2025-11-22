package tool.dataAnalytics

import com.dumch.tool.ToolRunBashCommand
import com.dumch.tool.dataAnalytics.ToolCreatePlotFromCsv
import kotlin.test.Test
import kotlin.test.assertEquals

class ToolCreatePlotFromCsvTest {
    @Test
    fun `extract headers from csv`() {
        val tool = ToolCreatePlotFromCsv(ToolRunBashCommand)
        val result = tool.invoke(ToolCreatePlotFromCsv.Input("src/jvmTest/resources/sample.csv"))
        assertEquals("[name, age]", result)
    }
}