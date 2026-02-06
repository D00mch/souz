package ru.gigadesk.tool.excel

import org.apache.poi.ss.usermodel.*
import ru.gigadesk.tool.*
import ru.gigadesk.tool.files.FilesToolUtil
import ru.gigadesk.tool.files.ForbiddenFolder
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream

enum class TransformOperation {
    SORT,
    DEDUPLICATE,
    PIVOT, // Basic pivot: Group By -> Aggregate
    FORMAT // Format values in a column
}

class ExcelTransform(
    private val filesToolUtil: FilesToolUtil
) : ToolSetup<ExcelTransform.Input> {

    data class Input(
        @InputParamDescription("Path to Excel file")
        val path: String,

        @InputParamDescription("Operation: SORT, DEDUPLICATE, PIVOT, FORMAT")
        val operation: TransformOperation,

        // Common
        @InputParamDescription("Output sheet name (optional). If set, creates a new sheet with results.")
        val outSheet: String? = null,

        // SORT
        @InputParamDescription("Column to sort by")
        val by: String? = null,
        @InputParamDescription("Sort order: ASC (default), DESC")
        val order: String? = null,

        // DEDUPLICATE
        @InputParamDescription("Columns to check for duplicates (comma separated, default: all)")
        val uniqueKeys: String? = null,

        // PIVOT
        @InputParamDescription("Rows field for Pivot")
        val rows: String? = null,
        @InputParamDescription("Values field for Pivot")
        val values: String? = null,
        @InputParamDescription("Aggregation: SUM, COUNT")
        val agg: String? = null,

        // FORMAT
        @InputParamDescription("Column to format")
        val column: String? = null,
        @InputParamDescription("Format type: PHONE_RU, PHONE_INTERNATIONAL, EMAIL, TRIM, UPPER, LOWER, CAPITALIZE, TITLE_CASE, DATE (default yyyy-MM-dd), DATE-dd.MM.yyyy")
        val format: String? = null
    )

    override val name = "ExcelTransform"
    override val description = "Transform Excel data: Sort, Deduplicate, Pivot, Format. MUST be used for ANY bulk updates (formatting dates, phones, names). Do NOT use NewFile for Excel."

    override val fewShotExamples = listOf(
        FewShotExample(
            request = "Отсортируй по дате (новые сверху)",
            params = mapOf("path" to "sales.xlsx", "operation" to "SORT", "by" to "Date", "order" to "DESC")
        ),
        FewShotExample(
            request = "Удали дубликаты по Email и сохрани в лист 'Unique'",
            params = mapOf("path" to "clients.xlsx", "operation" to "DEDUPLICATE", "uniqueKeys" to "Email", "outSheet" to "Unique")
        ),
        FewShotExample(
            request = "Приведи телефоны к единому формату",
            params = mapOf("path" to "clients.xlsx", "operation" to "FORMAT", "column" to "Phone", "format" to "PHONE_RU")
        ),
        FewShotExample(
            request = "Приведи даты к формату",
            params = mapOf("path" to "sales.xlsx", "operation" to "FORMAT", "column" to "Date", "format" to "DATE")
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
        try {
            var sheet = workbook.getSheetAt(0)

            // Handle outSheet for non-Pivot operations (Pivot creates its own)
            if (input.operation != TransformOperation.PIVOT && !input.outSheet.isNullOrBlank()) {
                val sheetIdx = workbook.getSheetIndex(sheet)
                sheet = workbook.cloneSheet(sheetIdx)
                workbook.setSheetName(workbook.getSheetIndex(sheet), input.outSheet)
            }
            
            val res = when(input.operation) {
                TransformOperation.SORT -> sort(sheet, input)
                TransformOperation.DEDUPLICATE -> deduplicate(sheet, input)
                TransformOperation.PIVOT -> pivot(workbook, sheet, input)
                TransformOperation.FORMAT -> format(sheet, input)
            }
            
            // Atomic Save: Write to temp file first, then replace original
            val tempFile = File(file.parentFile, "${file.name}.${System.currentTimeMillis()}.tmp")
            FileOutputStream(tempFile).use { workbook.write(it) }
            
            try {
                java.nio.file.Files.move(
                    tempFile.toPath(), 
                    file.toPath(), 
                    java.nio.file.StandardCopyOption.REPLACE_EXISTING
                )
            } catch (e: Exception) {
                // Fallback for some OS/Filesystem locks
                tempFile.delete()
                throw e
            }
            
            return res
        } finally {
            workbook.close()
        }
    }

    private fun sort(sheet: Sheet, input: Input): String {
        val colName = input.by ?: throw BadInputException("by column required")
        val isDesc = input.order?.equals("DESC", true) == true
        
        val formatter = DataFormatter()
        val headerRow = sheet.getRow(0) ?: return "Empty sheet"
        var colIdx = -1
        for (cell in headerRow) {
            if (formatter.formatCellValue(cell).trim().equals(colName, true)) {
                colIdx = cell.columnIndex
                break
            }
        }
        if (colIdx == -1) return "Column $colName not found"

        // Read all rows
        val rows = mutableListOf<Row>()
        for (i in 1..sheet.lastRowNum) {
            val r = sheet.getRow(i)
            if (r != null) rows.add(r)
        }

        // Sort
        rows.sortWith { r1, r2 ->
            val v1 = getVal(r1, colIdx)
            val v2 = getVal(r2, colIdx)
            val res = v1.compareTo(v2)
            if (isDesc) -res else res
        }

        // Re-write rows (simplified value swap)
        // We read all data first to avoid overwriting issues during swap if we were moving rows
        // But here we just extract values and write them back in new order
        val data = rows.map { row ->
            (0 until row.lastCellNum).map { c ->
                val cell = row.getCell(c)
                val type = cell?.cellType ?: CellType.BLANK
                val value: Any? = when(type) {
                    CellType.STRING -> cell.stringCellValue
                    CellType.NUMERIC -> cell.numericCellValue
                    CellType.BOOLEAN -> cell.booleanCellValue
                    CellType.FORMULA -> cell.cellFormula
                    else -> null
                }
                type to value
            }
        }

        // Write back
        data.forEachIndexed { i, rowData ->
             val row = sheet.getRow(i + 1) ?: sheet.createRow(i + 1)
             rowData.forEachIndexed { c, (type, value) ->
                 val cell = row.createCell(c)
                 when(type) {
                     CellType.STRING -> cell.setCellValue(value as String)
                     CellType.NUMERIC -> cell.setCellValue(value as Double)
                     CellType.BOOLEAN -> cell.setCellValue(value as Boolean)
                     CellType.FORMULA -> cell.cellFormula = value as String
                     else -> cell.setBlank()
                 }
             }
        }

        return "Sorted ${data.size} rows by $colName"
    }

    private fun deduplicate(sheet: Sheet, input: Input): String {
        val keys = input.uniqueKeys?.split(",")?.map { it.trim() }
        val formatter = DataFormatter()
        val headers = sheet.getRow(0).map { formatter.formatCellValue(it).trim() }
        
        val keyIndices = if (keys.isNullOrEmpty()) {
            headers.indices.toList()
        } else {
            keys.mapNotNull { k -> 
                val idx = headers.indexOfFirst { it.equals(k, true) }
                if (idx == -1) null else idx
            }
        }

        if (keyIndices.isEmpty() && !keys.isNullOrEmpty()) return "Key columns not found"

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

        // Delete from bottom
        rowsToDelete.sortedDescending().forEach { idx ->
            val r = sheet.getRow(idx)
            sheet.removeRow(r)
            if (idx < sheet.lastRowNum) sheet.shiftRows(idx + 1, sheet.lastRowNum, -1)
        }

        return "Removed ${rowsToDelete.size} duplicates"
    }

    private fun pivot(wb: Workbook, sheet: Sheet, input: Input): String {
        // Very basic pivot: creates a new sheet with Group By result
        val groupCol = input.rows ?: throw BadInputException("rows needed for pivot")
        val valCol = input.values ?: throw BadInputException("values needed for pivot")
        val agg = input.agg ?: "SUM"
        
        val formatter = DataFormatter()
        val headers = sheet.getRow(0).map { formatter.formatCellValue(it).trim() }
        
        val gIdx = headers.indexOf(groupCol)
        val vIdx = headers.indexOf(valCol)
        if (gIdx == -1 || vIdx == -1) return "Columns not found"

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

        return "Pivot created in sheet matches '${resSheet.sheetName}'"
    }

    private fun format(sheet: Sheet, input: Input): String {
        val colName = input.column ?: throw BadInputException("column required for FORMAT")
        val formatType = input.format ?: throw BadInputException("format type required")
        
        val formatter = DataFormatter()
        val headers = sheet.getRow(0).map { formatter.formatCellValue(it).trim() }
        val colIdx = headers.indexOfFirst { it.equals(colName, true) }
        
        if (colIdx == -1) return "Column '$colName' not found. Available: ${headers.joinToString(", ")}"
        
        var count = 0
        for (i in 1..sheet.lastRowNum) {
            val row = sheet.getRow(i) ?: continue
            val cell = row.getCell(colIdx) ?: row.createCell(colIdx)
            val original = formatter.formatCellValue(cell).trim()
            
            if (original.isEmpty()) continue
            
            val formatted = when (formatType.uppercase()) {
                "PHONE_RU" -> formatPhoneRu(original)
                "PHONE_INTERNATIONAL" -> formatPhoneInternational(original)
                "TRIM" -> original.trim()
                "UPPER" -> original.uppercase()
                "LOWER" -> original.lowercase()
                "CAPITALIZE" -> original.replaceFirstChar { it.uppercase() } // Only first letter
                "TITLE_CASE" -> original.lowercase().split(" ").joinToString(" ") { it.replaceFirstChar { c -> c.uppercase() } }
                "EMAIL" -> original.lowercase().replace(" ", "").trim()
                else -> {
                    if (formatType.equals("DATE", ignoreCase = true)) {
                         formatDate(cell, original, "yyyy-MM-dd")
                    } else if (formatType.startsWith("DATE-")) {
                         formatDate(cell, original, formatType.removePrefix("DATE-"))
                    } else {
                        original
                    }
                }
            }
            
            if (formatted != original) {
                // Aggressive update to ensure change is applied (fixes silent persistence failure)
                cell.setBlank()
                cell.setCellValue(formatted)
                count++
            }
        }
        return "Formatted $count cells in $colName column using $formatType"
    }

    private fun formatDate(cell: Cell, original: String, pattern: String): String {
        try {
            // 1. Try to parse "original" as a string date
            // Common formats: dd.MM.yyyy, yyyy-MM-dd, dd-MM-yyyy, etc.
            val d = parseDate(original)
            
            // 2. If null, check if cell is numeric (Excel date)
            val dateObj = if (d != null) {
                d
            } else if (cell.cellType == CellType.NUMERIC && DateUtil.isCellDateFormatted(cell)) {
                cell.dateCellValue
            } else if (cell.cellType == CellType.NUMERIC) {
                // Sometimes numeric but not marked as date
                 DateUtil.getJavaDate(cell.numericCellValue)
            } else {
                return original // Can't parse
            }
            
            if (dateObj == null) return original

            // 3. Format to target pattern
            return java.text.SimpleDateFormat(pattern).format(dateObj)
        } catch (e: Exception) {
            return original
        }
    }

    private fun parseDate(str: String): java.util.Date? {
        val formats = listOf(
            "dd.MM.yyyy", "dd.MM.yy",
            "yyyy-MM-dd", "yyyy/MM/dd",
            "dd-MM-yyyy", "dd/MM/yyyy",
            "MM/dd/yyyy", // US
            "d.M.yyyy", "d.M.yy"
        )
        for (fmt in formats) {
            try {
                val sdf = java.text.SimpleDateFormat(fmt)
                sdf.isLenient = false
                return sdf.parse(str.trim())
            } catch (e: Exception) { continue }
        }
        return null
    }
    
    private fun formatPhoneRu(phone: String): String {
        // Remove everything except digits
        val digits = phone.filter { it.isDigit() }
        if (digits.length == 10) { // 900 123 45 67 -> +7 ...
            return "+7 (${digits.substring(0,3)}) ${digits.substring(3,6)} ${digits.substring(6,8)} ${digits.substring(8,10)}"
        }
        if (digits.length == 11 && (digits.startsWith("7") || digits.startsWith("8"))) {
            val tail = digits.substring(1)
            return "+7 (${tail.substring(0,3)}) ${tail.substring(3,6)} ${tail.substring(6,8)} ${tail.substring(8,10)}"
        }
        return phone // Return original if pattern doesn't match
    }

    private fun formatPhoneInternational(phone: String): String {
        // Just ensure it starts with + and has digits. Very basic specific logic can be added if needed.
        // For general usage: remove non-digits/plus, maybe add spaces?
        // Let's keep it simple: Ensure + at start, then groups of digits.
        // Actually, user just wants "international format". Let's assume generic cleaning.
        val digits = phone.filter { it.isDigit() }
        if (digits.isEmpty()) return phone
        
        // If it looks like RU (starts with 7 or 8 and len 11), treat as RU international +7 ...
        if (digits.length == 11 && (digits.startsWith("7") || digits.startsWith("8"))) {
             val tail = digits.substring(1)
             // +7 XXX XXX XXXX generic
             return "+7 ${tail.substring(0,3)} ${tail.substring(3,6)} ${tail.substring(6)}"
        }
         
        return "+" + digits
    }

    private fun getVal(row: Row, col: Int): String {
        val cell = row.getCell(col) ?: return ""
        return DataFormatter().formatCellValue(cell).trim()
    }
}
