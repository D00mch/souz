package ru.gigadesk.tool.dataAnalytics

import org.apache.commons.csv.CSVFormat
import org.apache.commons.csv.CSVParser
import org.apache.poi.ss.usermodel.*
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
import ru.gigadesk.tool.*
import ru.gigadesk.tool.files.FilesToolUtil
import ru.gigadesk.db.ConfigStore
import ru.gigadesk.db.SettingsProviderImpl
import java.awt.Desktop
import java.io.File
import java.io.FileInputStream
import java.io.FileReader
import java.nio.charset.StandardCharsets
import java.util.*

enum class ChartType {
    BAR, LINE, SCATTER, PIE
}

class ToolCreatePlotFromCsv(private val filesToolUtil: FilesToolUtil) : ToolSetup<ToolCreatePlotFromCsv.Input> {
    private val l = LoggerFactory.getLogger(ToolCreatePlotFromCsv::class.java)

    data class Input(
        @InputParamDescription("Path to a data file (CSV, XLS, XLSX). E.g. ~/Documents/data.xlsx")
        val path: String,

        @InputParamDescription("Column name for the x-axis. Omit to inspect headers.")
        val xColumn: String? = null,

        @InputParamDescription("Column name for the y-axis. Omit to inspect headers.")
        val yColumn: String? = null,

        @InputParamDescription("Type of chart (BAR, LINE, SCATTER, PIE). Defaults to BAR.")
        val chartType: ChartType = ChartType.BAR,

        @InputParamDescription("Output file path. Defaults to '~/GigaDesk/Documents/plot.png'")
        val output: String? = "~/GigaDesk/Documents/plot.png",
    )

    override val name: String = "CreatePlot"
    override val description: String = "Create a plot from a CSV or Excel file. " +
            "Handles paths with '~'. " +
            "Supports Bar, Line, Scatter, and Pie charts."

    override val fewShotExamples = listOf(
        FewShotExample(
            request = "Show headers of ~/sales.xlsx",
            params = mapOf("path" to "~/sales.xlsx")
        ),
        FewShotExample(
            request = "Построй график из report.csv, ось X - Month, Y - Sales",
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
        val rawPath = filesToolUtil.applyDefaultEnvs(input.path)
        val inputFile = File(rawPath)

        filesToolUtil.requirePathIsSave(inputFile)

        if (!inputFile.exists()) {
            throw BadInputException("File not found: $rawPath")
        }

        val rawOutputPath = filesToolUtil.applyDefaultEnvs(input.output ?: "~/GigaDesk/Documents/plot.png")
        val outputFile = File(rawOutputPath)

        outputFile.parentFile?.mkdirs()
        filesToolUtil.requirePathIsSave(outputFile)

        // Определяем стратегию парсинга по расширению файла
        val extension = inputFile.extension.lowercase(Locale.getDefault())

        val dataResult = when (extension) {
            "csv" -> parseCsv(inputFile, input.xColumn, input.yColumn)
            "xls", "xlsx" -> parseExcel(inputFile, input.xColumn, input.yColumn)
            else -> throw BadInputException("Unsupported file extension: .$extension. Use CSV, XLS, or XLSX.")
        }

        // Если вернулись только заголовки (пользователь не указал колонки)
        if (dataResult.isHeadersOnly) {
            return "Columns required. Available headers: ${dataResult.headers}"
        }

        val dataMap = dataResult.data
        if (dataMap.isEmpty()) throw BadInputException("No valid data found in selected columns.")

        // Сортировка и подготовка данных для графика
        val sortedData = dataMap.toList().sortedByDescending { it.second }
        val xData = sortedData.map { it.first }
        val yData = sortedData.map { it.second }

        // Построение графика
        val plot = createPlot(xData, yData, input)
        ggsave(plot, outputFile.absolutePath)
        openFileInOS(outputFile)

        return "Plot saved to ${outputFile.absolutePath}"
    }

    // --- Логика для CSV ---
    private fun parseCsv(file: File, xCol: String?, yCol: String?): ParsedData {
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

            if (xCol == null || yCol == null) {
                return ParsedData(headers = headers, isHeadersOnly = true)
            }

            validateColumns(headers, xCol, yCol)

            val dataMap = mutableMapOf<String, Double>()
            for (record in parser) {
                try {
                    val category = record.get(xCol)
                    val valueStr = record.get(yCol)
                    addToMap(dataMap, category, valueStr)
                } catch (e: Exception) { /* ignore */ }
            }
            return ParsedData(headers, dataMap)
        }
    }

    // --- Логика для Excel ---
    private fun parseExcel(file: File, xCol: String?, yCol: String?): ParsedData {
        FileInputStream(file).use { fis ->
            // WorkbookFactory автоматически определяет формат (xls или xlsx)
            val workbook = WorkbookFactory.create(fis)
            // Берем первый лист
            val sheet = workbook.getSheetAt(0)
            val formatter = DataFormatter() // Помогает корректно читать значения как строки

            // Получаем заголовки (первая строка)
            val headerRow = sheet.getRow(0) ?: throw BadInputException("Excel sheet is empty")
            val headersMap = mutableMapOf<String, Int>() // Имя колонки -> Индекс

            for (cell in headerRow) {
                headersMap[formatter.formatCellValue(cell).trim()] = cell.columnIndex
            }
            val headers = headersMap.keys.toList()

            if (xCol == null || yCol == null) {
                return ParsedData(headers = headers, isHeadersOnly = true)
            }

            validateColumns(headers, xCol, yCol)

            // Определяем индексы нужных колонок
            // Ищем case-insensitive соответствие
            val xIndex = headersMap.entries.find { it.key.equals(xCol, ignoreCase = true) }?.value!!
            val yIndex = headersMap.entries.find { it.key.equals(yCol, ignoreCase = true) }?.value!!

            val dataMap = mutableMapOf<String, Double>()

            // Проходим по строкам (начиная со 2-й)
            for (i in 1..sheet.lastRowNum) {
                val row = sheet.getRow(i) ?: continue
                try {
                    val categoryCell = row.getCell(xIndex)
                    val valueCell = row.getCell(yIndex)

                    val category = formatter.formatCellValue(categoryCell)
                    // Для значения берем сырое значение, если возможно, или отформатированное
                    val valueStr = when(valueCell?.cellType) {
                        CellType.NUMERIC -> valueCell.numericCellValue.toString()
                        CellType.FORMULA -> {
                            try {
                                valueCell.numericCellValue.toString()
                            } catch (e: Exception) {
                                valueCell.stringCellValue
                            }
                        }
                        else -> formatter.formatCellValue(valueCell)
                    }

                    addToMap(dataMap, category, valueStr)
                } catch (e: Exception) { /* ignore bad rows */ }
            }
            return ParsedData(headers, dataMap)
        }
    }

    // --- Общие утилиты ---

    private fun validateColumns(headers: List<String>, xCol: String, yCol: String) {
        // Простая проверка (case-insensitive)
        val headersLower = headers.map { it.lowercase() }
        if (!headersLower.contains(xCol.lowercase()) || !headersLower.contains(yCol.lowercase())) {
            throw BadInputException("Missing columns. Found: $headers. Requested: $xCol, $yCol")
        }
    }

    private fun addToMap(map: MutableMap<String, Double>, key: String, valueStr: String) {
        val cleanValue = valueStr.replace("\u00A0", "").replace(" ", "").replace(",", ".")
        val value = cleanValue.toDoubleOrNull()

        if (value != null && key.isNotBlank()) {
            map[key] = map.getOrDefault(key, 0.0) + value
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

    // Вспомогательный класс для передачи результатов парсинга
    private data class ParsedData(
        val headers: List<String>,
        val data: Map<String, Double> = emptyMap(),
        val isHeadersOnly: Boolean = false
    )
}

// Пример запуска
fun main() {
    val tool = ToolCreatePlotFromCsv(FilesToolUtil(SettingsProviderImpl(ConfigStore)))
    println(tool.invoke(ToolCreatePlotFromCsv.Input(
        path = "/Users/duxx/Отчеты/Финансовый отчет первый квартал.xlsx",
        xColumn = "Manager",
        yColumn = "Revenue",
        chartType = ChartType.BAR
    )))
}