package com.dumch.tool.desktop

import com.dumch.tool.FewShotExample
import com.dumch.tool.InputParamDescription
import com.dumch.tool.ReturnParameters
import com.dumch.tool.ReturnProperty
import com.dumch.tool.ToolRunBashCommand
import com.dumch.tool.ToolSetup
import java.io.File

class ToolPlotCsv(private val bash: ToolRunBashCommand) : ToolSetup<ToolPlotCsv.Input> {
    data class Input(
        @InputParamDescription("Path to a CSV file with table data")
        val path: String,
        @InputParamDescription("Column name to use for the x-axis")
        val xColumn: String,
        @InputParamDescription("Column name to use for the y-axis")
        val yColumn: String,
        @InputParamDescription("Path for the output image. Defaults to 'plot.png'")
        val output: String? = "/Users/duxx/SluxxDocuments/plot.png",
    )

    override val name: String = "PlotCsv"
    override val description: String = "Generate a plot image from a CSV file using matplotlib. You should first upload the CSV file to GigaChat to get info about the file"

    override val fewShotExamples = listOf(
        FewShotExample(
            request = "Построй график дохода по клиенту из файла sales_report.csv",
            params = mapOf(
                "path" to "path/to/sales_report.csv",
                "xColumn" to "Клиент",
                "yColumn" to "Доход"
            )
        ),
        FewShotExample(
            request = "Построй график количество покупок по категориям из файла sales_report.csv",
            params = mapOf(
                "path" to "path/to/sales_report.csv",
                "xColumn" to "Категория",
                "yColumn" to "Количество"
            )
        ),
    )

    override val returnParameters = ReturnParameters(
        properties = mapOf(
            "result" to ReturnProperty("string", "Stdout from plot command")
        )
    )

    override fun invoke(input: Input): String {
        val scriptPath = File("scripts/plot_csv.py").absolutePath
        val csvPath = File(input.path).absolutePath
        val outputPath = File(input.output ?: "plot.png").absolutePath
        val command = "python3 \"$scriptPath\" \"$csvPath\" \"${input.xColumn}\" \"${input.yColumn}\" \"$outputPath\""
        return bash.sh(command)
    }
}

fun main() {
    val tool = ToolPlotCsv(ToolRunBashCommand)
    println(tool.invoke(ToolPlotCsv.Input("/Users/duxx/Отчеты/sales_report.csv", "Клиент", "Доход")))
}