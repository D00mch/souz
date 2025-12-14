package ru.abledo.tool.dataAnalytics

import org.apache.commons.csv.CSVFormat
import org.apache.commons.csv.CSVParser
import org.jetbrains.letsPlot.Stat
import org.jetbrains.letsPlot.export.ggsave
import org.jetbrains.letsPlot.geom.geomBar
import org.jetbrains.letsPlot.geom.geomLine
import org.jetbrains.letsPlot.geom.geomPie
import org.jetbrains.letsPlot.geom.geomPoint
import org.jetbrains.letsPlot.ggsize
import org.jetbrains.letsPlot.label.labs
import org.jetbrains.letsPlot.letsPlot
import ru.abledo.tool.BadInputException
import ru.abledo.tool.FewShotExample
import ru.abledo.tool.InputParamDescription
import ru.abledo.tool.ReturnParameters
import ru.abledo.tool.ReturnProperty
import ru.abledo.tool.ToolSetup
import java.awt.Desktop
import java.io.File
import java.io.FileReader
import java.nio.charset.StandardCharsets

enum class ChartType {
    BAR, LINE, SCATTER, PIE
}

class ToolCreatePlotFromCsv : ToolSetup<ToolCreatePlotFromCsv.Input> {

    data class Input(
        @InputParamDescription("Path to a CSV file")
        val path: String,

        @InputParamDescription("Column name for the x-axis (category). Omit to inspect headers.")
        val xColumn: String? = null,

        @InputParamDescription("Column name for the y-axis (value). Omit to inspect headers.")
        val yColumn: String? = null,

        @InputParamDescription("Type of chart (BAR, LINE, SCATTER, PIE). Defaults to BAR.")
        val chartType: ChartType = ChartType.BAR,

        @InputParamDescription("Output file path. Defaults to 'plot.png'")
        val output: String? = "${System.getProperty("user.home")}/SluxxDocuments/plot.png",
    )

    override val name: String = "CreatePlotFromCsv"
    override val description: String = "Create a plot from a CSV file using pure Kotlin. " +
            "Supports Bar, Line, Scatter, and Pie charts. " +
            "Robustly parses CSVs (handles quotes, trimming) and aggregates data."

    override val fewShotExamples = listOf(
        FewShotExample(
            request = "Show me headers of sales.csv",
            params = mapOf("path" to "sales.csv")
        ),
        FewShotExample(
            request = "Draw a bar chart of Revenue by Client from sales.csv",
            params = mapOf(
                "path" to "sales.csv",
                "xColumn" to "Client",
                "yColumn" to "Revenue",
                "chartType" to "BAR"
            )
        )
    )

    override val returnParameters = ReturnParameters(
        properties = mapOf(
            "result" to ReturnProperty("string", "Execution result or headers list")
        )
    )

    override fun invoke(input: Input): String {
        val file = File(input.path)
        if (!file.exists()) throw BadInputException("File not found: ${input.path}")

        val format = CSVFormat.DEFAULT.builder()
            .setHeader()
            .setSkipHeaderRecord(true)
            .setIgnoreHeaderCase(true)
            .setTrim(true)
            .setIgnoreEmptyLines(true)
            .build()

        FileReader(file, StandardCharsets.UTF_8).use { reader ->
            val parser = CSVParser(reader, format)
            val headers = parser.headerNames

            if (input.xColumn == null || input.yColumn == null) {
                return "Columns required. Available headers: $headers"
            }

            if (!headers.contains(input.xColumn) || !headers.contains(input.yColumn)) {
                throw BadInputException("Missing columns. Found: $headers. Requested: ${input.xColumn}, ${input.yColumn}")
            }

            val dataMap = mutableMapOf<String, Double>()

            for (record in parser) {
                try {
                    val category = record.get(input.xColumn)
                    val valueStr = record.get(input.yColumn)

                    val cleanValue = valueStr.replace("\u00A0", "").replace(" ", "")
                    val value = cleanValue.toDoubleOrNull()

                    if (value != null && category.isNotBlank()) {
                        dataMap[category] = dataMap.getOrDefault(category, 0.0) + value
                    }
                } catch (e: Exception) {
                    println("Warning: skipped row ${record.recordNumber} due to error: ${e.message}")
                }
            }

            if (dataMap.isEmpty()) {
                throw BadInputException("No valid data found to plot.")
            }

            val sortedData = dataMap.toList().sortedByDescending { it.second }
            val xData = sortedData.map { it.first }
            val yData = sortedData.map { it.second }

            val plot = createPlot(xData, yData, input)

            val outputPath = input.output ?: "plot.png"
            ggsave(plot, outputPath)

            openFileInOS(outputPath)

            return "Plot saved to $outputPath"
        }
    }

    private fun createPlot(xData: List<String>, yData: List<Double>, input: Input): org.jetbrains.letsPlot.intern.Plot {
        val data = mapOf(
            input.xColumn!! to xData,
            input.yColumn!! to yData
        )

        var p = letsPlot(data)

        p += when (input.chartType) {
            ChartType.BAR -> geomBar(stat = Stat.identity) {
                x = input.xColumn
                y = input.yColumn
                fill = input.xColumn
            }
            ChartType.LINE -> geomLine {
                x = input.xColumn
                y = input.yColumn
                size = 2 // Толщина линии
            }
            ChartType.SCATTER -> geomPoint {
                x = input.xColumn
                y = input.yColumn
                size = 5
                color = input.xColumn
            }
            ChartType.PIE -> geomPie {
                fill = input.xColumn
                weight = input.yColumn
            }
        }

        return p + labs(
            title = "${input.yColumn} by ${input.xColumn}",
            x = input.xColumn,
            y = input.yColumn
        ) + ggsize(800, 600)
    }

    private fun openFileInOS(path: String) {
        try {
            val file = File(path)
            if (Desktop.isDesktopSupported() && file.exists()) {
                Desktop.getDesktop().open(file)
            }
        } catch (e: Exception) {
            System.err.println("Could not open image viewer: ${e.message}")
        }
    }
}

fun main() {
    val tool = ToolCreatePlotFromCsv()

    println(tool.invoke(ToolCreatePlotFromCsv.Input(path = "/Users/duxx/Отчеты/sales_report.csv")))

    val res = tool.invoke(ToolCreatePlotFromCsv.Input(
        path = "/Users/duxx/Отчеты/sales_report.csv",
        xColumn = "Клиент",
        yColumn = "Доход",
        chartType = ChartType.BAR
    ))
    println(res)
}