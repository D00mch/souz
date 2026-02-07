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

        @InputParamDescription("Data to write. List of lists (rows).")
        val data: List<List<Any?>>? = null,

        @InputParamDescription("Sheet name")
        val sheetName: String? = null
    )

    override val name = "ExcelReport"
    override val description = "Create a NEW Excel file for reports. Can populate with data immediately."

    override val fewShotExamples = listOf(
        FewShotExample(
            request = "Создай отчет по продажам",
            params = mapOf(
                "path" to "sales_report.xlsx", 
                "headers" to "Date, Amount",
                "data" to listOf(listOf("2024-01-01", 100), listOf("2024-01-02", 200))
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

            // Headers
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
            input.data?.forEach { rowData ->
                val row = sheet.createRow(rowIdx++)
                rowData.forEachIndexed { i, value ->
                    val cell = row.createCell(i)
                    when (value) {
                        is Number -> cell.setCellValue(value.toDouble())
                        is Boolean -> cell.setCellValue(value)
                        null -> cell.setBlank()
                        else -> cell.setCellValue(value.toString())
                    }
                }
            }
            
            // Autosize columns if data exists
            if (rowIdx > 0) {
                 val cols = sheet.getRow(0)?.lastCellNum?.toInt() ?: 0
                 for(i in 0 until cols) sheet.autoSizeColumn(i)
            }

            FileOutputStream(file).use { wb.write(it) }
        }

        return "Created report ${input.path}"
    }
}
