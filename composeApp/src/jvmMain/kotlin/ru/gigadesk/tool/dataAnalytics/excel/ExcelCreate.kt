package ru.gigadesk.tool.dataAnalytics.excel

import org.apache.poi.xssf.usermodel.XSSFWorkbook
import ru.gigadesk.tool.*
import ru.gigadesk.tool.files.FilesToolUtil
import ru.gigadesk.tool.files.ForbiddenFolder
import java.io.File
import java.io.FileOutputStream

class ExcelCreate(
    private val filesToolUtil: FilesToolUtil
) : ToolSetup<ExcelCreate.Input> {

    data class Input(
        @InputParamDescription("Path for new file")
        val path: String,
        
        @InputParamDescription("Headers for the first row (comma separated)")
        val headers: String
    )

    override val name = "ExcelCreate"
    override val description = "Create a NEW empty Excel file with headers"

    override val fewShotExamples = listOf(
        FewShotExample(
            request = "Создай таблицу Клиенты с колонками Имя, Телефон",
            params = mapOf("path" to "clients.xlsx", "headers" to "Имя, Телефон")
        )
    )

    override val returnParameters = ReturnParameters(
        properties = mapOf("result" to ReturnProperty("string", "Result"))
    )

    override fun invoke(input: Input): String {
        val file = File(filesToolUtil.applyDefaultEnvs(input.path))
        if (!filesToolUtil.isPathSafe(file)) throw ForbiddenFolder(file.path)
        if (file.exists()) throw BadInputException("File exists. Use ExcelRead to read, ExcelWrite to edit.")

        XSSFWorkbook().use { wb ->
            val sheet = wb.createSheet("Sheet1")
            val row = sheet.createRow(0)

            val style = wb.createCellStyle()
            val font = wb.createFont()
            font.bold = true
            style.setFont(font)

            input.headers.split(",").forEachIndexed { i, h ->
                val cell = row.createCell(i)
                cell.setCellValue(h.trim())
                cell.cellStyle = style
            }

            FileOutputStream(file).use { wb.write(it) }
        }

        return "Created ${input.path}"
    }
}
