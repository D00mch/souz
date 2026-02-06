package ru.gigadesk.tool.dataAnalytics.excel

import org.apache.poi.ss.usermodel.*
import ru.gigadesk.tool.*
import ru.gigadesk.tool.files.FilesToolUtil
import ru.gigadesk.tool.files.ForbiddenFolder
import java.io.File
import java.io.FileInputStream
import java.text.SimpleDateFormat
import java.util.Date

enum class TransformOperation {
    SORT,
    DEDUPLICATE,
    PIVOT,
    FORMAT
}

class ExcelTransform(
    private val filesToolUtil: FilesToolUtil
) : ToolSetup<ExcelTransform.Input> {

    data class Input(
        @InputParamDescription("Path to Excel file")
        val path: String,

        @InputParamDescription("Operation: SORT, DEDUPLICATE, PIVOT, FORMAT")
        val operation: TransformOperation,

        @InputParamDescription("Output sheet name (optional, creates new sheet with results)")
        val outSheet: String? = null,

        @InputParamDescription("Column to sort by")
        val by: String? = null,

        @InputParamDescription("Sort order: ASC (default), DESC")
        val order: String? = null,

        @InputParamDescription("Columns to check for duplicates (comma separated, default: all)")
        val uniqueKeys: String? = null,

        @InputParamDescription("Rows field for Pivot")
        val rows: String? = null,

        @InputParamDescription("Values field for Pivot")
        val values: String? = null,

        @InputParamDescription("Aggregation: SUM, COUNT")
        val agg: String? = null,

        @InputParamDescription("Column to format")
        val column: String? = null,

        @InputParamDescription("Format: PHONE_RU, EMAIL, TRIM, UPPER, LOWER, CAPITALIZE, TITLE_CASE, DATE, DATE-dd.MM.yyyy")
        val format: String? = null
    )

    override val name = "ExcelTransform"
    override val description = "Transform Excel: Sort, Deduplicate, Pivot, Format columns"

    override val fewShotExamples = listOf(
        FewShotExample(
            request = "Отсортируй по дате (новые сверху)",
            params = mapOf("path" to "sales.xlsx", "operation" to "SORT", "by" to "Date", "order" to "DESC")
        ),
        FewShotExample(
            request = "Удали дубликаты по Email",
            params = mapOf("path" to "clients.xlsx", "operation" to "DEDUPLICATE", "uniqueKeys" to "Email")
        ),
        FewShotExample(
            request = "Приведи телефоны к единому формату",
            params = mapOf("path" to "clients.xlsx", "operation" to "FORMAT", "column" to "Phone", "format" to "PHONE_RU")
        )
    )

    override val returnParameters = ReturnParameters(
        properties = mapOf("result" to ReturnProperty("string", "Result message"))
    )

    override fun invoke(input: Input): String {
        val file = File(filesToolUtil.applyDefaultEnvs(input.path))
        if (!filesToolUtil.isPathSafe(file)) throw ForbiddenFolder(file.path)
        if (!file.exists()) throw BadInputException("File not found")

        val workbook = FileInputStream(file).use { WorkbookFactory.create(it) }
        
        return workbook.use { wb ->
            var sheet = wb.getSheetAt(0)

            if (input.operation != TransformOperation.PIVOT && !input.outSheet.isNullOrBlank()) {
                sheet = wb.cloneSheet(wb.getSheetIndex(sheet))
                wb.setSheetName(wb.getSheetIndex(sheet), input.outSheet)
            }

            val result = when (input.operation) {
                TransformOperation.SORT -> sort(sheet, input)
                TransformOperation.DEDUPLICATE -> deduplicate(sheet, input)
                TransformOperation.PIVOT -> pivot(wb, sheet, input)
                TransformOperation.FORMAT -> format(sheet, input)
            }

            wb.saveAtomic(file)
            result
        }
    }

    private fun sort(sheet: Sheet, input: Input): String {
        val colName = input.by ?: throw BadInputException("by column required")
        val isDesc = input.order?.equals("DESC", true) == true

        val formatter = DataFormatter()
        val colIdx = sheet.findColumnIndex(colName, formatter)
        if (colIdx == -1) throw BadInputException("Column '$colName' not found")

        val dataRows = (1..sheet.lastRowNum).mapNotNull { sheet.getRow(it) }
        
        val rowData = dataRows.map { row ->
            (0 until row.lastCellNum).map { c ->
                val cell = row.getCell(c)
                val type = cell?.cellType ?: CellType.BLANK
                val value: Any? = when (type) {
                    CellType.STRING -> cell.stringCellValue
                    CellType.NUMERIC -> cell.numericCellValue
                    CellType.BOOLEAN -> cell.booleanCellValue
                    CellType.FORMULA -> cell.cellFormula
                    else -> null
                }
                type to value
            }
        }

        val sortedData = rowData.sortedWith { a, b ->
            val v1 = getSortValue(a.getOrNull(colIdx)?.second)
            val v2 = getSortValue(b.getOrNull(colIdx)?.second)
            val cmp = v1.compareTo(v2)
            if (isDesc) -cmp else cmp
        }

        sortedData.forEachIndexed { i, cells ->
            val row = sheet.getRow(i + 1) ?: sheet.createRow(i + 1)
            cells.forEachIndexed { c, (type, value) ->
                val cell = row.createCell(c)
                when (type) {
                    CellType.STRING -> cell.setCellValue(value as String)
                    CellType.NUMERIC -> cell.setCellValue(value as Double)
                    CellType.BOOLEAN -> cell.setCellValue(value as Boolean)
                    CellType.FORMULA -> cell.cellFormula = value as String
                    else -> cell.setBlank()
                }
            }
        }

        return "Sorted ${sortedData.size} rows by $colName"
    }

    private fun getSortValue(value: Any?): String {
        return when (value) {
            is Double -> "%020.5f".format(value)
            is String -> value
            else -> ""
        }
    }

    private fun deduplicate(sheet: Sheet, input: Input): String {
        val keys = input.uniqueKeys?.split(",")?.map { it.trim() }
        val formatter = DataFormatter()
        val headers = sheet.getHeaders(formatter)

        val keyIndices = if (keys.isNullOrEmpty()) {
            headers.indices.toList()
        } else {
            keys.mapNotNull { k -> headers.indexOfFirst { it.equals(k, true) }.takeIf { it >= 0 } }
        }

        if (keyIndices.isEmpty() && !keys.isNullOrEmpty()) {
            throw BadInputException("Key columns not found")
        }

        val seen = mutableSetOf<String>()
        val rowsToDelete = mutableListOf<Int>()

        for (i in 1..sheet.lastRowNum) {
            val row = sheet.getRow(i) ?: continue
            val signature = keyIndices.joinToString("|") { idx ->
                formatter.formatCellValue(row.getCell(idx)).trim()
            }
            if (!seen.add(signature)) {
                rowsToDelete.add(i)
            }
        }

        rowsToDelete.sortedDescending().forEach { idx ->
            sheet.getRow(idx)?.let { sheet.removeRow(it) }
            if (idx < sheet.lastRowNum) sheet.shiftRows(idx + 1, sheet.lastRowNum, -1)
        }

        return "Removed ${rowsToDelete.size} duplicates"
    }

    private fun pivot(wb: Workbook, sheet: Sheet, input: Input): String {
        val groupCol = input.rows ?: throw BadInputException("rows needed for pivot")
        val valCol = input.values ?: throw BadInputException("values needed for pivot")
        val agg = input.agg ?: "SUM"

        val formatter = DataFormatter()
        val gIdx = sheet.findColumnIndex(groupCol, formatter)
        val vIdx = sheet.findColumnIndex(valCol, formatter)
        
        if (gIdx == -1 || vIdx == -1) throw BadInputException("Columns not found")

        val groups = mutableMapOf<String, MutableList<Double>>()
        for (i in 1..sheet.lastRowNum) {
            val row = sheet.getRow(i) ?: continue
            val k = formatter.formatCellValue(row.getCell(gIdx)).trim()
            val vCell = row.getCell(vIdx)
            val v = if (vCell?.cellType == CellType.NUMERIC) vCell.numericCellValue else 0.0
            groups.getOrPut(k) { mutableListOf() }.add(v)
        }

        val resSheet = wb.createSheet(input.outSheet ?: "Pivot")
        val hRow = resSheet.createRow(0)
        hRow.createCell(0).setCellValue(groupCol)
        hRow.createCell(1).setCellValue("$agg($valCol)")

        var rIdx = 1
        groups.forEach { (k, vals) ->
            val resVal = if (agg.equals("COUNT", true)) vals.size.toDouble() else vals.sum()
            val r = resSheet.createRow(rIdx++)
            r.createCell(0).setCellValue(k)
            r.createCell(1).setCellValue(resVal)
        }

        return "Pivot created in sheet '${resSheet.sheetName}'"
    }

    private fun format(sheet: Sheet, input: Input): String {
        val colName = input.column ?: throw BadInputException("column required")
        val formatType = input.format ?: throw BadInputException("format type required")

        val formatter = DataFormatter()
        val colIdx = sheet.findColumnIndex(colName, formatter)
        if (colIdx == -1) throw BadInputException("Column '$colName' not found")

        var count = 0
        for (i in 1..sheet.lastRowNum) {
            val row = sheet.getRow(i) ?: continue
            val cell = row.getCell(colIdx) ?: row.createCell(colIdx)
            val original = formatter.formatCellValue(cell).trim()
            if (original.isEmpty()) continue

            val formatted = applyFormat(formatType.uppercase(), original, cell)

            if (formatted != original) {
                cell.setBlank()
                cell.setCellValue(formatted)
                count++
            }
        }
        return "Formatted $count cells in $colName"
    }

    private fun applyFormat(format: String, value: String, cell: Cell): String = when (format) {
        "PHONE_RU" -> formatPhoneRu(value)
        "PHONE_INTERNATIONAL" -> formatPhoneInternational(value)
        "TRIM" -> value.trim()
        "UPPER" -> value.uppercase()
        "LOWER" -> value.lowercase()
        "CAPITALIZE" -> value.replaceFirstChar { it.uppercase() }
        "TITLE_CASE" -> value.lowercase().split(" ").joinToString(" ") { it.replaceFirstChar { c -> c.uppercase() } }
        "EMAIL" -> value.lowercase().replace(" ", "").trim()
        "DATE" -> formatDate(cell, value, "yyyy-MM-dd")
        else -> if (format.startsWith("DATE-")) formatDate(cell, value, format.removePrefix("DATE-")) else value
    }

    private fun formatDate(cell: Cell, original: String, pattern: String): String {
        val dateObj = parseDate(original)
            ?: if (cell.cellType == CellType.NUMERIC && DateUtil.isCellDateFormatted(cell)) cell.dateCellValue
            else if (cell.cellType == CellType.NUMERIC) DateUtil.getJavaDate(cell.numericCellValue)
            else return original

        return runCatching { SimpleDateFormat(pattern).format(dateObj) }.getOrDefault(original)
    }

    private fun parseDate(str: String): Date? {
        val formats = listOf(
            "dd.MM.yyyy", "dd.MM.yy", "yyyy-MM-dd", "yyyy/MM/dd",
            "dd-MM-yyyy", "dd/MM/yyyy", "MM/dd/yyyy", "d.M.yyyy", "d.M.yy"
        )
        for (fmt in formats) {
            runCatching {
                SimpleDateFormat(fmt).apply { isLenient = false }.parse(str.trim())
            }.onSuccess { return it }
        }
        return null
    }

    private fun formatPhoneRu(phone: String): String {
        val digits = phone.filter { it.isDigit() }
        return when {
            digits.length == 10 -> "+7 (${digits.substring(0, 3)}) ${digits.substring(3, 6)} ${digits.substring(6, 8)} ${digits.substring(8)}"
            digits.length == 11 && digits[0] in "78" -> {
                val tail = digits.substring(1)
                "+7 (${tail.substring(0, 3)}) ${tail.substring(3, 6)} ${tail.substring(6, 8)} ${tail.substring(8)}"
            }
            else -> phone
        }
    }

    private fun formatPhoneInternational(phone: String): String {
        val digits = phone.filter { it.isDigit() }
        if (digits.isEmpty()) return phone

        return if (digits.length == 11 && digits[0] in "78") {
            val tail = digits.substring(1)
            "+7 ${tail.substring(0, 3)} ${tail.substring(3, 6)} ${tail.substring(6)}"
        } else "+$digits"
    }
}
