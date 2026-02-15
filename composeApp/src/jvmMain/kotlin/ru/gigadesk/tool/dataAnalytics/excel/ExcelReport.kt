package ru.gigadesk.tool.dataAnalytics.excel

import org.apache.commons.csv.CSVFormat
import org.apache.commons.csv.CSVParser
import org.apache.poi.xssf.usermodel.XSSFWorkbook
import ru.gigadesk.tool.*
import ru.gigadesk.tool.files.FilesToolUtil
import ru.gigadesk.tool.files.ForbiddenFolder
import java.io.File
import java.io.FileOutputStream
import java.io.StringReader

class ExcelReport(
    private val filesToolUtil: FilesToolUtil
) : ToolSetup<ExcelReport.Input> {

    data class Input(
        @InputParamDescription("Path for new file")
        val path: String,
        
        @InputParamDescription("Headers for the first row (comma separated string, e.g. 'Name, Age')")
        val headers: String? = null,

        @InputParamDescription("Data to write (CSV format). Rows separated by newline, cells by comma.")
        val csvData: String? = null,

        @InputParamDescription("Sheet name")
        val sheetName: String? = null
    )

    override val name = "ExcelReport"
    override val description = """Create a NEW Excel file with headers and data.
- headers: comma-separated, e.g. 'Name, Age, City'
- csvData: data in CSV format, e.g. "John,25\nJane,30"
File must not exist. For reading use ExcelRead."""

    override val fewShotExamples = listOf(
        FewShotExample(
            request = "Создай отчет по продажам",
            params = mapOf(
                "path" to "sales_report.xlsx", 
                "headers" to "Date, Amount",
                "csvData" to "2024-01-01,100\n2024-01-02,200"
            )
        )
    )

    override val returnParameters = ReturnParameters(
        properties = mapOf("result" to ReturnProperty("string", "Result"))
    )

    override fun invoke(input: Input): String {
        val file = File(filesToolUtil.applyDefaultEnvs(input.path))
        if (!filesToolUtil.isPathSafe(file)) throw ForbiddenFolder(file.path)
        if (file.exists()) throw BadInputException("File exists. Use ExcelRead to read, or choose new name.")

        XSSFWorkbook().use { wb ->
            val sheet = wb.createSheet(input.sheetName ?: "Report")
            
            var rowIdx = 0
            var maxColumns = 0

            if (!input.headers.isNullOrBlank()) {
                val row = sheet.createRow(rowIdx++)
                val style = wb.createCellStyle()
                val font = wb.createFont()
                font.bold = true
                style.setFont(font)

                parseCsvRows(input.headers).firstOrNull().orEmpty().forEachIndexed { i, h ->
                    val cell = row.createCell(i)
                    cell.setCellValue(h)
                    cell.cellStyle = style
                }
                maxColumns = maxOf(maxColumns, row.lastCellNum.toInt())
            }

            // Data
            if (!input.csvData.isNullOrBlank()) {
                parseCsvRows(input.csvData).forEach { cells ->
                    val row = sheet.createRow(rowIdx++)

                    cells.forEachIndexed { i, valueStr ->
                        val cell = row.createCell(i)
                        val doubleVal = valueStr.toDoubleOrNull()
                        val boolVal = when {
                            valueStr.equals("true", ignoreCase = true) -> true
                            valueStr.equals("false", ignoreCase = true) -> false
                            else -> null
                        }

                        when {
                            doubleVal != null -> cell.setCellValue(doubleVal)
                            boolVal != null -> cell.setCellValue(boolVal)
                            else -> cell.setCellValue(valueStr)
                        }
                    }
                    maxColumns = maxOf(maxColumns, row.lastCellNum.toInt())
                }
            }

            if (rowIdx > 0 && maxColumns > 0) {
                for (i in 0 until maxColumns) sheet.autoSizeColumn(i)
            }

            FileOutputStream(file).use { wb.write(it) }
        }

        return "Created report ${input.path}"
    }

    private fun parseCsvRows(csvData: String): List<List<String>> {
        val format = CSVFormat.DEFAULT.builder()
            .setTrim(true)
            .setIgnoreEmptyLines(true)
            .build()

        return CSVParser(StringReader(csvData), format).use { parser ->
            parser.records.map { record -> record.map { it } }
        }
    }
}
