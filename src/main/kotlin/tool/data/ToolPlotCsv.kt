package com.dumch.tool.data

import com.dumch.tool.FewShotExample
import com.dumch.tool.InputParamDescription
import com.dumch.tool.ReturnParameters
import com.dumch.tool.ReturnProperty
import com.dumch.tool.ToolRunBashCommand
import com.dumch.tool.ToolSetup

class ToolPlotCsv(private val bash: ToolRunBashCommand) : ToolSetup<ToolPlotCsv.Input> {
    data class Input(
        @InputParamDescription("A relative path to a CSV file with table data")
        val path: String,
        @InputParamDescription("Column name to use for the x-axis")
        val xColumn: String,
        @InputParamDescription("Column name to use for the y-axis")
        val yColumn: String,
        @InputParamDescription("Path for the output image. Defaults to 'plot.png'")
        val output: String? = null,
    )

    override val name: String = "PlotCsv"
    override val description: String = "Generate a line plot image from a CSV file using matplotlib"

    override val fewShotExamples = listOf(
        FewShotExample(
            request = "Построй график зависимости sales от month из файла data.csv",
            params = mapOf(
                "path" to "data.csv",
                "xColumn" to "month",
                "yColumn" to "sales"
            )
        )
    )

    override val returnParameters = ReturnParameters(
        properties = mapOf(
            "result" to ReturnProperty("string", "Stdout from plot command")
        )
    )

    override fun invoke(input: Input): String {
        val outputPath = input.output ?: "plot.png"
        val command = "python scripts/plot_csv.py \"${input.path}\" \"${input.xColumn}\" \"${input.yColumn}\" \"$outputPath\""
        return bash.sh(command)
    }
}
