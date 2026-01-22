package ru.gigadesk.tool.dataAnalytics

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
import org.slf4j.LoggerFactory
import ru.gigadesk.tool.BadInputException
import ru.gigadesk.tool.FewShotExample
import ru.gigadesk.tool.InputParamDescription
import ru.gigadesk.tool.ReturnParameters
import ru.gigadesk.tool.ReturnProperty
import ru.gigadesk.tool.ToolSetup
import ru.gigadesk.tool.files.FilesToolUtil // <-- Импортируем утилиту
import java.awt.Desktop
import java.io.File
import java.io.FileReader
import java.nio.charset.StandardCharsets

enum class ChartType {
    BAR, LINE, SCATTER, PIE
}

class ToolCreatePlotFromCsv : ToolSetup<ToolCreatePlotFromCsv.Input> {
    private val l = LoggerFactory.getLogger(ToolCreatePlotFromCsv::class.java)

    data class Input(
        @InputParamDescription("Path to a CSV file (e.g. ~/Documents/data.csv)")
        val path: String,

        @InputParamDescription("Column name for the x-axis. Omit to inspect headers.")
        val xColumn: String? = null,

        @InputParamDescription("Column name for the y-axis. Omit to inspect headers.")
        val yColumn: String? = null,

        @InputParamDescription("Type of chart (BAR, LINE, SCATTER, PIE). Defaults to BAR.")
        val chartType: ChartType = ChartType.BAR,

        @InputParamDescription("Output file path. Defaults to '~/SluxxDocuments/plot.png'")
        val output: String? = "~/SluxxDocuments/plot.png", // Теперь можно использовать ~ в дефолте
    )

    override val name: String = "CreatePlotFromCsv"
    override val description: String = "Create a plot from a CSV file. " +
            "Handles paths with '~'. " +
            "Supports Bar, Line, Scatter, and Pie charts."

    override val fewShotExamples = listOf(
        FewShotExample(
            request = "Show headers of ~/sales.csv",
            params = mapOf("path" to "~/sales.csv")
        ),
        FewShotExample(
            request = "Draw chart from ~/data/report.csv",
            params = mapOf(
                "path" to "~/data/report.csv",
                "xColumn" to "Month",
                "yColumn" to "Sales",
                "chartType" to "LINE"
            )
        )
    )

    override val returnParameters = ReturnParameters(
        properties = mapOf(
            "result" to ReturnProperty("string", "Execution result or headers list")
        )
    )

    override fun invoke(input: Input): String {
        // 1. Нормализуем путь к CSV и проверяем безопасность
        val rawPath = FilesToolUtil.applyDefaultEnvs(input.path)
        val csvFile = File(rawPath)

        // Проверка: файл существует и находится внутри домашней директории
        FilesToolUtil.requirePathIsSave(csvFile)

        if (!csvFile.exists()) {
            throw BadInputException("File not found: $rawPath")
        }

        // 2. То же самое для выходного файла
        val rawOutputPath = FilesToolUtil.applyDefaultEnvs(input.output ?: "~/SluxxDocuments/plot.png")
        val outputFile = File(rawOutputPath)

        // Создаем директорию для output, если её нет (например, SluxxDocuments)
        outputFile.parentFile?.mkdirs()
        FilesToolUtil.requirePathIsSave(outputFile)

        // --- Дальше логика без изменений ---

        val format = CSVFormat.DEFAULT.builder()
            .setHeader()
            .setSkipHeaderRecord(true)
            .setIgnoreHeaderCase(true)
            .setTrim(true)
            .setIgnoreEmptyLines(true)
            .build()

        FileReader(csvFile, StandardCharsets.UTF_8).use { reader ->
            val parser = CSVParser(reader, format)
            val headers = parser.headerNames

            if (input.xColumn == null || input.yColumn == null) {
                return "Columns required. Available headers: $headers"
            }

            if (!headers.contains(input.xColumn) || !headers.contains(input.yColumn)) {
                throw BadInputException("Missing columns. Found: $headers")
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
                    // ignore bad rows
                }
            }

            if (dataMap.isEmpty()) throw BadInputException("No valid data found.")

            val sortedData = dataMap.toList().sortedByDescending { it.second }
            val xData = sortedData.map { it.first }
            val yData = sortedData.map { it.second }

            val plot = createPlot(xData, yData, input)

            // Используем уже обработанный путь outputFile
            ggsave(plot, outputFile.absolutePath)
            openFileInOS(outputFile)

            return "Plot saved to ${outputFile.absolutePath}"
        }
    }

    private fun createPlot(xData: List<String>, yData: List<Double>, input: Input): org.jetbrains.letsPlot.intern.Plot {
        val data = mapOf(input.xColumn!! to xData, input.yColumn!! to yData)

        var p = letsPlot(data)
        p += when (input.chartType) {
            ChartType.BAR -> geomBar(stat = Stat.identity) {
                x = input.xColumn
                y = input.yColumn
                fill = input.xColumn
            }
            ChartType.LINE -> geomLine { x = input.xColumn; y = input.yColumn }
            ChartType.SCATTER -> geomPoint { x = input.xColumn; y = input.yColumn; size = 5 }
            ChartType.PIE -> geomPie { fill = input.xColumn; weight = input.yColumn }
        }

        return p + labs(title = "${input.yColumn} by ${input.xColumn}") + ggsize(800, 600)
    }

    private fun openFileInOS(file: File) {
        try {
            if (Desktop.isDesktopSupported() && file.exists()) {
                Desktop.getDesktop().open(file)
            }
        } catch (e: Exception) {
            l.error("Could not open image: ${e.message}")
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