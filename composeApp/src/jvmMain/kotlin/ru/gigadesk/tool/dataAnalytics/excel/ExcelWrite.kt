package ru.gigadesk.tool.dataAnalytics.excel

import org.apache.poi.ss.usermodel.*
import ru.gigadesk.tool.*
import ru.gigadesk.tool.files.FilesToolUtil
import ru.gigadesk.tool.files.ForbiddenFolder
import java.io.File
import java.io.FileInputStream

enum class WriteOperation {
    SET_CELL,
    ADD_ROW,
    UPDATE_ROWS,
    DELETE_ROWS
}

class ExcelWrite(
    private val filesToolUtil: FilesToolUtil
) : ToolSetup<ExcelWrite.Input> {

    data class Input(
        @InputParamDescription("Path to Excel file")
        val path: String,

        @InputParamDescription("Operation: SET_CELL, ADD_ROW, UPDATE_ROWS, DELETE_ROWS")
        val operation: WriteOperation,

        @InputParamDescription("Sheet name (optional, default: first sheet)")
        val sheet: String? = null,

        @InputParamDescription("Cell address like 'B5' (for SET_CELL)")
        val cell: String? = null,

        @InputParamDescription("Value to set (for SET_CELL, ADD_ROW)")
        val value: String? = null,

        @InputParamDescription("Formula like '=SUM(A1:A10)' (for SET_CELL)")
        val formula: String? = null,

        @InputParamDescription("Row values as comma-separated or JSON array (for ADD_ROW)")
        val rowData: String? = null,

        @InputParamDescription("Condition like 'Status=Pending' (for UPDATE/DELETE)")
        val where: String? = null,

        @InputParamDescription("Updates as 'Column=Value' pairs (for UPDATE_ROWS)")
        val set: String? = null
    )

    override val name = "ExcelWrite"
    
    override val description = """Modify Excel files in place.
- SET_CELL: Set value/formula in specific cell
- ADD_ROW: Append row at bottom
- UPDATE_ROWS: Modify rows matching condition
- DELETE_ROWS: Remove rows matching condition"""

    override val fewShotExamples = listOf(
        FewShotExample(
            request = "Добавь строку с продажей: Иванов, 5000, 2024-01-01",
            params = mapOf(
                "path" to "sales.xlsx",
                "operation" to "ADD_ROW",
                "rowData" to "Иванов,5000,2024-01-01"
            )
        )
    )

    override val returnParameters = ReturnParameters(
        properties = mapOf("result" to ReturnProperty("string", "Operation result"))
    )

    override fun invoke(input: Input): String {
        val file = File(filesToolUtil.applyDefaultEnvs(input.path))
        if (!filesToolUtil.isPathSafe(file)) throw ForbiddenFolder(file.path)
        if (!file.exists()) throw BadInputException("File not found: ${input.path}")

        val workbook = FileInputStream(file).use { WorkbookFactory.create(it) }

        return workbook.use { wb ->
            val sheet = input.sheet?.let { name ->
                wb.getSheet(name) ?: throw BadInputException("Sheet '$name' not found")
            } ?: wb.getSheetAt(0)

            val result = when (input.operation) {
                WriteOperation.SET_CELL -> setCell(sheet, input)
                WriteOperation.ADD_ROW -> addRow(sheet, input)
                WriteOperation.UPDATE_ROWS -> updateRows(sheet, input)
                WriteOperation.DELETE_ROWS -> deleteRows(sheet, input)
            }

            wb.saveAtomic(file)
            result
        }
    }

    private fun setCell(sheet: Sheet, input: Input): String {
        val cellRef = input.cell ?: throw BadInputException("cell required for SET_CELL")
        val (rowIdx, colIdx) = parseCellRef(cellRef)

        val row = sheet.getRow(rowIdx) ?: sheet.createRow(rowIdx)
        val cell = row.getCell(colIdx) ?: row.createCell(colIdx)

        return when {
            input.formula != null -> {
                val formula = input.formula.removePrefix("=")
                cell.setCellFormula(formula)
                "Formula set in $cellRef: =${formula}"
            }
            input.value != null -> {
                cell.setSmartValue(input.value)
                "Value set in $cellRef: ${input.value}"
            }
            else -> throw BadInputException("value or formula required for SET_CELL")
        }
    }

    private fun addRow(sheet: Sheet, input: Input): String {
        val rowData = input.rowData ?: throw BadInputException("rowData required for ADD_ROW")
        val values = parseRowData(rowData)

        val newRowIdx = sheet.lastRowNum + 1
        val row = sheet.createRow(newRowIdx)

        values.forEachIndexed { idx, value ->
            row.createCell(idx).setSmartValue(value)
        }

        return "Added row #${newRowIdx + 1} with ${values.size} values"
    }

    private fun updateRows(sheet: Sheet, input: Input): String {
        val where = input.where ?: throw BadInputException("where required for UPDATE_ROWS")
        val set = input.set ?: throw BadInputException("set required for UPDATE_ROWS")

        val formatter = DataFormatter()
        val headers = sheet.getHeaders(formatter)

        val (whereCol, whereVal) = parseCondition(where)
        val (setCol, setVal) = parseCondition(set)

        val whereIdx = headers.indexOf(whereCol)
        val setIdx = headers.indexOf(setCol)

        if (whereIdx == -1) throw BadInputException("Column '$whereCol' not found")
        if (setIdx == -1) throw BadInputException("Column '$setCol' not found")

        var updated = 0
        for (i in 1..sheet.lastRowNum) {
            val row = sheet.getRow(i) ?: continue
            val cellValue = formatter.formatCellValue(row.getCell(whereIdx)).trim()

            if (cellValue.equals(whereVal, ignoreCase = true)) {
                val cell = row.getCell(setIdx) ?: row.createCell(setIdx)
                cell.setSmartValue(setVal)
                updated++
            }
        }

        return "Updated $updated rows: $setCol = $setVal (where $whereCol = $whereVal)"
    }

    private fun deleteRows(sheet: Sheet, input: Input): String {
        val where = input.where ?: throw BadInputException("where required for DELETE_ROWS")

        val formatter = DataFormatter()
        val headers = sheet.getHeaders(formatter)

        val (whereCol, whereVal) = parseCondition(where)
        val whereIdx = headers.indexOf(whereCol)

        if (whereIdx == -1) throw BadInputException("Column '$whereCol' not found")

        val rowsToDelete = mutableListOf<Int>()
        for (i in 1..sheet.lastRowNum) {
            val row = sheet.getRow(i) ?: continue
            val cellValue = formatter.formatCellValue(row.getCell(whereIdx)).trim()

            if (cellValue.equals(whereVal, ignoreCase = true)) {
                rowsToDelete.add(i)
            }
        }

        rowsToDelete.sortedDescending().forEach { rowIdx ->
            sheet.getRow(rowIdx)?.let { row ->
                sheet.removeRow(row)
                if (rowIdx < sheet.lastRowNum) {
                    sheet.shiftRows(rowIdx + 1, sheet.lastRowNum, -1)
                }
            }
        }

        return "Deleted ${rowsToDelete.size} rows (where $whereCol = $whereVal)"
    }

    private fun parseRowData(data: String): List<String> {
        return if (data.startsWith("[") && data.endsWith("]")) {
            data.trim('[', ']').split(",").map { it.trim().trim('"', '\'') }
        } else {
            data.split(",").map { it.trim() }
        }
    }
}
