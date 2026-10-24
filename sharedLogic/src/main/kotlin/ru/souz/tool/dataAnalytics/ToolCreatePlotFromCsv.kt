package ru.souz.tool.dataAnalytics

import org.apache.commons.csv.CSVFormat
import org.apache.commons.csv.CSVParser
import org.apache.poi.ss.usermodel.*
import org.jfree.chart.ChartFactory
import org.jfree.chart.ChartUtils
import org.jfree.chart.JFreeChart
import org.jfree.chart.plot.PlotOrientation
import org.jfree.chart.renderer.category.LineAndShapeRenderer
import org.jfree.data.category.DefaultCategoryDataset
import org.jfree.data.general.DefaultPieDataset
import ru.souz.llms.ToolInvocationMeta
import ru.souz.tool.*
import ru.souz.runtime.files.FilesToolUtil
import ru.souz.runtime.files.ForbiddenFolder
import java.io.File
import java.io.FileInputStream
import java.io.FileReader
import java.nio.charset.StandardCharsets

enum class ChartType {
    BAR, LINE, SCATTER, PIE
}

class ToolCreatePlotFromCsv(private val filesToolUtil: FilesToolUtil) : ToolSetup<ToolCreatePlotFromCsv.Input> {
    data class Input(
        @InputParamDescription("Path to a data file (CSV, XLS, XLSX). E.g. ~/Documents/data.xlsx")
        val path: String,

        @InputParamDescription("Column name for the x-axis. Omit to inspect headers.")
        val xColumn: String? = null,

        @InputParamDescription("Column name for the y-axis. Omit to inspect headers.")
        val yColumn: String? = null,

        @InputParamDescription("Type of chart (BAR, LINE, SCATTER, PIE). Defaults to BAR.")
        val chartType: ChartType = ChartType.BAR,

        @InputParamDescription("Output file path. Defaults to '~/souz/Documents/plot.png'")
        val output: String? = "~/souz/Documents/plot.png",

        @InputParamDescription("Accepted for compatibility. The backend never opens generated files.")
        val openAfterCreate: Boolean = false,
    )

    override val name: String = "CreatePlot"
    override val description: String = "Create a plot from a CSV or Excel file. " +
            "Handles paths with '~'. " +
            "Supports Bar, Line, Scatter, and Pie charts. " +
            "Returns the path to the saved PNG image."

    override val fewShotExamples = listOf(
        FewShotExample(
            request = "Show headers of ~/sales.xlsx",
            params = mapOf("path" to "~/sales.xlsx")
        ),
        FewShotExample(
            request = "Построй график объема продаж в каждом месяце из report.csv",
            params = mapOf(
                "path" to "~/data/report.csv",
                "xColumn" to "Month",
                "yColumn" to "Sales",
                "chartType" to "BAR"
            )
        )
    )

    override val returnParameters = ReturnParameters(
        properties = mapOf(
            "result" to ReturnProperty("string", "Execution result or headers list")
        )
    )

    override fun invoke(input: Input, meta: ToolInvocationMeta): String {
        val inputFile = filesToolUtil.resolveSafeExistingFile(input.path, meta)
        val outputFile = filesToolUtil.resolvePath(
            input.output ?: "${filesToolUtil.resolveSouzDocumentsDirectory(meta).path}/plot.png",
            meta,
        )
        if (!filesToolUtil.isPathSafe(outputFile, meta)) {
            throw ForbiddenFolder(outputFile.path)
        }

        val extension = inputFile.name.substringAfterLast('.', "").lowercase()

        val dataResult = when (extension) {
            "csv" -> filesToolUtil.withReadableLocalPath(inputFile, meta) { localPath ->
                parseCsv(localPath.toFile(), input.xColumn, input.yColumn)
            }

            "xls", "xlsx" -> filesToolUtil.withReadableLocalPath(inputFile, meta) { localPath ->
                parseExcel(localPath.toFile(), input.xColumn, input.yColumn)
            }

            else -> throw BadInputException("Unsupported file extension: .$extension. Use CSV, XLS, or XLSX.")
        }

        if (dataResult.isHeadersOnly) {
            return "Columns required. Available headers: ${dataResult.headers}"
        }

        val dataMap = dataResult.data
        if (dataMap.isEmpty()) throw BadInputException("No valid data found in selected columns.")

        val sortedData = dataMap.toList().sortedByDescending { it.second }
        val xData = sortedData.map { it.first }
        val yData = sortedData.map { it.second }

        val plot = createPlot(xData, yData, input)
        filesToolUtil.withWritableLocalPath(outputFile, meta) { localPath ->
            ChartUtils.saveChartAsPNG(localPath.toFile(), plot, 800, 600)
        }

        return "Plot saved to ${outputFile.path}"
    }

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

    private fun parseExcel(file: File, xCol: String?, yCol: String?): ParsedData {
        FileInputStream(file).use { fis ->
            val workbook = WorkbookFactory.create(fis)
            val sheet = workbook.getSheetAt(0)
            val formatter = DataFormatter()

            val headerRow = sheet.getRow(0) ?: throw BadInputException("Excel sheet is empty")
            val headersMap = mutableMapOf<String, Int>()

            for (cell in headerRow) {
                headersMap[formatter.formatCellValue(cell).trim()] = cell.columnIndex
            }
            val headers = headersMap.keys.toList()

            if (xCol == null || yCol == null) {
                return ParsedData(headers = headers, isHeadersOnly = true)
            }

            validateColumns(headers, xCol, yCol)

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


    private fun validateColumns(headers: List<String>, xCol: String, yCol: String) {
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

    private fun createPlot(xData: List<String>, yData: List<Double>, input: Input): JFreeChart {
        val title = "${input.yColumn} by ${input.xColumn}"
        if (input.chartType == ChartType.PIE) {
            val dataset = DefaultPieDataset<String>()
            xData.indices.forEach { index -> dataset.setValue(xData[index], yData[index]) }
            return ChartFactory.createPieChart(title, dataset, true, false, false)
        }
        val dataset = DefaultCategoryDataset()
        xData.indices.forEach { index -> dataset.addValue(yData[index], input.yColumn, xData[index]) }
        return when (input.chartType) {
            ChartType.BAR -> ChartFactory.createBarChart(
                title, input.xColumn, input.yColumn, dataset, PlotOrientation.VERTICAL, false, false, false,
            )
            ChartType.LINE, ChartType.SCATTER -> ChartFactory.createLineChart(
                title, input.xColumn, input.yColumn, dataset, PlotOrientation.VERTICAL, false, false, false,
            ).also { chart ->
                chart.categoryPlot.renderer = LineAndShapeRenderer(input.chartType == ChartType.LINE, true)
            }
            ChartType.PIE -> error("Pie charts use a pie dataset")
        }
    }

    private data class ParsedData(
        val headers: List<String>,
        val data: Map<String, Double> = emptyMap(),
        val isHeadersOnly: Boolean = false
    )

    override suspend fun suspendInvoke(input: Input, meta: ToolInvocationMeta): String = invoke(input, meta)
}
