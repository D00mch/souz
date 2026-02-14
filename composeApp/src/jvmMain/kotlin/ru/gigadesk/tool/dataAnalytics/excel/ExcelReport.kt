package ru.gigadesk.tool.dataAnalytics.excel

import org.apache.poi.xssf.usermodel.XSSFWorkbook
import ru.gigadesk.tool.*
import ru.gigadesk.tool.files.FilesToolUtil
import ru.gigadesk.tool.files.ForbiddenFolder
import java.io.File
import java.io.FileOutputStream

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

            if (!input.headers.isNullOrBlank()) {
                val row = sheet.createRow(rowIdx++)
                val style = wb.createCellStyle()
                val font = wb.createFont()
                font.bold = true
                style.setFont(font)

                input.headers.split(",").forEachIndexed { i, h ->
                    val cell = row.createCell(i)
                    cell.setCellValue(h.trim())
                    cell.cellStyle = style
                }
            }

            // Data
            if (!input.csvData.isNullOrBlank()) {
                val rawData = input.csvData.replace("\r\n", "\n").replace("\r", "\n")
                
                rawData.lineSequence().forEach { line ->
                    if (line.isNotBlank()) {
                         val row = sheet.createRow(rowIdx++)
                         // Simple CSV parsing (handling quotes)
                         val cells = mutableListOf<String>()
                         var currentCell = StringBuilder()
                         var insideQuote = false
                         
                         for (char in line) {
                             if (char == '"') {
                                 insideQuote = !insideQuote
                             } else if (char == ',' && !insideQuote) {
                                 cells.add(currentCell.toString().trim { it == ' ' || it == '"' })
                                 currentCell = StringBuilder()
                             } else {
                                 currentCell.append(char)
                             }
                         }
                         cells.add(currentCell.toString().trim { it == ' ' || it == '"' })
                         
                         cells.forEachIndexed { i, valueStr ->
                             val cell = row.createCell(i)
                             // Try to detect type
                             val doubleVal = valueStr.toDoubleOrNull()
                             val boolVal = if (valueStr.equals("true", ignoreCase = true)) true else if (valueStr.equals("false", ignoreCase = true)) false else null
                             
                             if (doubleVal != null) {
                                 cell.setCellValue(doubleVal)
                             } else if (boolVal != null) {
                                 cell.setCellValue(boolVal)
                             } else {
                                 cell.setCellValue(valueStr)
                             }
                         }
                    }
                }
            }

            if (rowIdx > 0) {
                 val cols = sheet.getRow(0)?.lastCellNum?.toInt() ?: 0
                 for(i in 0 until cols) sheet.autoSizeColumn(i)
            }

            FileOutputStream(file).use { wb.write(it) }
        }

        return "Created report ${input.path}"
    }
}
