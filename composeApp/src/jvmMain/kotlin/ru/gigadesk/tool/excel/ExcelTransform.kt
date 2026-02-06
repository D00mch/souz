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
    PIVOT // Basic pivot: Group By -> Aggregate
}

class ExcelTransform(
    private val filesToolUtil: FilesToolUtil
) : ToolSetup<ExcelTransform.Input> {

    data class Input(
        @InputParamDescription("Path to Excel file")
        val path: String,

        @InputParamDescription("Operation: SORT, DEDUPLICATE, PIVOT")
        val operation: TransformOperation,

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
        @InputParamDescription("Output sheet name for Pivot results")
        val outSheet: String? = null
    )

    override val name = "ExcelTransform"
    override val description = "Transform Excel data: Sort, Deduplicate, Pivot (Group By)"

    override val fewShotExamples = listOf(
        FewShotExample(
            request = "Отсортируй по дате (новые сверху)",
            params = mapOf("path" to "sales.xlsx", "operation" to "SORT", "by" to "Date", "order" to "DESC")
        ),
        FewShotExample(
            request = "Удали дубликаты по Email",
            params = mapOf("path" to "clients.xlsx", "operation" to "DEDUPLICATE", "uniqueKeys" to "Email")
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
            val sheet = workbook.getSheetAt(0)
            
            val res = when(input.operation) {
                TransformOperation.SORT -> sort(sheet, input)
                TransformOperation.DEDUPLICATE -> deduplicate(sheet, input)
                TransformOperation.PIVOT -> pivot(workbook, sheet, input)
            }
            
            FileOutputStream(file).use { workbook.write(it) }
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

        // Re-write rows (simplified: shift values, preserving styles is hard)
        // Ideally we just swap values
        // For MVP: simple value swap
        // (A robust sort needs to move rows, which POI does not support natively well)
        
        // Let's create a temp list of cell values map
        val data = rows.map { row ->
            (0 until row.lastCellNum).map { c ->
                val cell = row.getCell(c)
                val type = cell?.cellType ?: CellType.BLANK
                val value: Any? = when(type) {
                    CellType.STRING -> cell.stringCellValue
                    CellType.NUMERIC -> cell.numericCellValue
                    CellType.BOOLEAN -> cell.booleanCellValue
                    CellType.FORMULA -> cell.cellFormula // formula might break sorting
                    else -> null
                }
                type to value
            }
        }

        // Write back
        data.forEachIndexed { i, rowData ->
             val row = sheet.getRow(i + 1) ?: sheet.createRow(i + 1)
             rowData.forEachIndexed { c, (type, value) ->
                 val cell = row.createCell(c) // reset cell
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

    private fun getVal(row: Row, col: Int): String {
        val cell = row.getCell(col) ?: return ""
        return DataFormatter().formatCellValue(cell).trim()
    }
}
