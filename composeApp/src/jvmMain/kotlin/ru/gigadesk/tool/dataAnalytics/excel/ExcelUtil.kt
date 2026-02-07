package ru.gigadesk.tool.dataAnalytics.excel

import org.apache.poi.ss.usermodel.*
import ru.gigadesk.tool.BadInputException
import java.io.File
import java.io.FileOutputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption

private val CELL_REF_REGEX = Regex("([A-Z]+)(\\d+)")

fun parseCellRef(ref: String): Pair<Int, Int> {
    val match = CELL_REF_REGEX.matchEntire(ref.uppercase())
        ?: throw BadInputException("Invalid cell reference: $ref")
    val col = match.groupValues[1].fold(0) { acc, c -> acc * 26 + (c - 'A' + 1) } - 1
    val row = match.groupValues[2].toInt() - 1
    return row to col
}

fun Sheet.getHeaders(formatter: DataFormatter = DataFormatter()): List<String> {
    val headerRow = getRow(0) ?: return emptyList()
    return headerRow.mapNotNull { cell ->
        formatter.formatCellValue(cell).trim().takeIf { it.isNotBlank() }
    }
}

fun Sheet.findColumnIndex(columnName: String, formatter: DataFormatter = DataFormatter()): Int {
    val headerRow = getRow(0) ?: return -1
    for (cell in headerRow) {
        if (formatter.formatCellValue(cell).trim().equals(columnName, ignoreCase = true)) {
            return cell.columnIndex
        }
    }
    return -1
}

fun formatNum(d: Double): String = 
    if (d == d.toLong().toDouble()) d.toLong().toString() else "%.2f".format(d)

fun List<String>.toJsonArray(): String = 
    joinToString(",", "[", "]") { "\"${it.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n").replace("\r", "\\r").replace("\t", "\\t")}\"" }

fun cloneCell(src: Cell, dest: Cell) {
    when (src.cellType) {
        CellType.STRING -> dest.setCellValue(src.stringCellValue)
        CellType.NUMERIC -> dest.setCellValue(src.numericCellValue)
        CellType.BOOLEAN -> dest.setCellValue(src.booleanCellValue)
        CellType.FORMULA -> dest.cellFormula = src.cellFormula
        else -> dest.setBlank()
    }
}

fun parseCondition(cond: String): Pair<String, String> {
    val parts = cond.split("=", limit = 2)
    if (parts.size != 2) throw BadInputException("Invalid condition: $cond (expected Column=Value)")
    return parts[0].trim() to parts[1].trim()
}
