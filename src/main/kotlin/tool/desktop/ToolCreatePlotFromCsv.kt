package com.dumch.tool.desktop

import com.dumch.tool.BadInputException
import com.dumch.tool.FewShotExample
import com.dumch.tool.InputParamDescription
import com.dumch.tool.ReturnParameters
import com.dumch.tool.ReturnProperty
import com.dumch.tool.ToolRunBashCommand
import com.dumch.tool.ToolSetup
import java.io.File

class ToolCreatePlotFromCsv(private val bash: ToolRunBashCommand) : ToolSetup<ToolCreatePlotFromCsv.Input> {
    data class Input(
        @InputParamDescription("Path to a CSV file with table data")
        val path: String,
        @InputParamDescription("Column name to use for the x-axis; omit to list available headers")
        val xColumn: String? = null,
        @InputParamDescription("Column name to use for the y-axis; omit to list available headers")
        val yColumn: String? = null,
        @InputParamDescription("Path for the output image. Defaults to 'plot.png'")
        val output: String? = "/Users/duxx/SluxxDocuments/plot.png",
    )

    override val name: String = "CreatePlotFromCsv"
    override val description: String = "Generate a plot image from a CSV file using matplotlib. " +
            "If column names are not provided, returns the list of CSV headers"

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
        val csv = File(input.path)
        if (!csv.exists() || csv.isDirectory) {
            throw BadInputException("Invalid file path: ${input.path}")
        }

        if (input.xColumn == null || input.yColumn == null) {
            csv.bufferedReader().use { reader ->
                val headers = reader.readLine()?.split(",") ?: emptyList()
                return headers.joinToString(prefix = "[", postfix = "]")
            }
        }

        val scriptPath = File("scripts/plot_csv.py").absolutePath
        val csvPath = csv.absolutePath
        val outputPath = File(input.output ?: "plot.png").absolutePath
        val command = "python3 \"$scriptPath\" \"$csvPath\" \"${input.xColumn}\" \"${input.yColumn}\" \"$outputPath\""
        return bash.sh(command)
    }
}

fun main() {
    val tool = ToolCreatePlotFromCsv(ToolRunBashCommand)
    println(tool.invoke(ToolCreatePlotFromCsv.Input("src/test/resources/sample.csv")))
}