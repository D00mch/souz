package ru.gigadesk.tool.dataAnalytics.excel

import org.apache.poi.ss.usermodel.*
import org.apache.poi.openxml4j.util.ZipSecureFile
import ru.gigadesk.tool.*
import ru.gigadesk.tool.files.FilesToolUtil
import ru.gigadesk.tool.files.ForbiddenFolder
import java.io.File

/**
 * Smart Excel reading with multiple operations.
 */
enum class ReadOperation {
    STRUCTURE,
    QUERY,
    CELL,
    LOOKUP
}

class ExcelRead(
    private val filesToolUtil: FilesToolUtil
) : ToolSetup<ExcelRead.Input> {

    init {
        try { ZipSecureFile.setMinInflateRatio(0.0) } catch (_: Throwable) {}
    }

    data class Input(
        @InputParamDescription("Path to Excel file")
        val path: String,

        @InputParamDescription("Operation: STRUCTURE, QUERY, CELL, or LOOKUP")
        val operation: ReadOperation,

        @InputParamDescription("Sheet name (optional, default: first sheet)")
        val sheet: String? = null,

        // For CELL operation
        @InputParamDescription("Cell address like 'B5' or range 'A1:C10' (for CELL operation)")
        val range: String? = null,

        // For QUERY operation
        @InputParamDescription("Column to aggregate (for QUERY)")
        val column: String? = null,

        @InputParamDescription("Aggregation: SUM, COUNT, AVG, MIN, MAX, LIST (for QUERY)")
        val aggregation: String? = null,

        @InputParamDescription("Column to group by (for QUERY)")
        val groupBy: String? = null,

        @InputParamDescription("Filter condition like 'Status=Completed' (for QUERY)")
        val filter: String? = null,

        @InputParamDescription("Limit results (for QUERY, default: 10)")
        val limit: Int? = null,

        // For LOOKUP operation
        @InputParamDescription("Lookup value (for LOOKUP)")
        val lookupValue: String? = null,

        @InputParamDescription("Column to search in (for LOOKUP)")
        val lookupColumn: String? = null,

        @InputParamDescription("Column to return (for LOOKUP)")
        val returnColumn: String? = null
    )

    override val name = "ExcelRead"
    override val description = """READ and GET data from Excel files. READ ONLY tool. Do NOT use this tool for writing, updating or deleting data.
- STRUCTURE: Get columns, row count, stats (Use first to understand file)
- QUERY: Filter, Sort, Aggregate (SUM, COUNT, AVG), Group By
- CELL: Read specific cell (e.g. B5) or range (e.g. A1:C10)
- LOOKUP: VLOOKUP-style search (find value in one specific column, return another)"""

    override val fewShotExamples = listOf(
        FewShotExample(
            request = "Покажи структуру файла sales.xlsx",
            params = mapOf("path" to "sales.xlsx", "operation" to "STRUCTURE")
        ),
        FewShotExample(
            request = "Сумма продаж по менеджерам",
            params = mapOf(
                "path" to "sales.xlsx",
                "operation" to "QUERY",
                "column" to "Revenue",
                "aggregation" to "SUM",
                "groupBy" to "Manager"
            )
        ),
        FewShotExample(
            request = "Найди цену товара Ноутбук в прайсе",
            params = mapOf(
                "path" to "price.xlsx",
                "operation" to "LOOKUP",
                "lookupValue" to "Ноутбук",
                "lookupColumn" to "Товар",
                "returnColumn" to "Цена"
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

        return WorkbookFactory.create(file, null, true).use { workbook ->
            val sheet = if (input.sheet != null) {
                workbook.getSheet(input.sheet) ?: throw BadInputException("Sheet '${input.sheet}' not found")
            } else {
                workbook.getSheetAt(0)
            }

            when (input.operation) {
                ReadOperation.STRUCTURE -> readStructure(sheet)
                ReadOperation.QUERY -> readQuery(sheet, input)
                ReadOperation.CELL -> readCell(sheet, input.range ?: throw BadInputException("range required for CELL"))
                ReadOperation.LOOKUP -> readLookup(sheet, input)
            }
        }
    }

    private fun readStructure(sheet: Sheet): String {
        val formatter = DataFormatter()
        val headerRow = sheet.getRow(0) ?: return """{"error":"Empty sheet"}"""

        val columns = mutableListOf<String>()
        val numericStats = mutableMapOf<String, MutableList<Double>>()

        for (cell in headerRow) {
            val name = formatter.formatCellValue(cell).trim()
            if (name.isNotBlank()) columns.add(name)
        }

        val stringSamples = mutableMapOf<String, MutableSet<String>>()

        val rowCount = sheet.lastRowNum
        val sampleSize = minOf(100, rowCount)

        // Collect stats
        for (i in 1..sampleSize) {
            val row = sheet.getRow(i) ?: continue
            columns.forEachIndexed { idx, colName ->
                val cell = row.getCell(idx) ?: return@forEachIndexed
                if (cell.cellType == CellType.NUMERIC) {
                    numericStats.getOrPut(colName) { mutableListOf() }.add(cell.numericCellValue)
                } else if (cell.cellType == CellType.STRING) {
                     val str = cell.stringCellValue.trim()
                     if (str.isNotEmpty()) {
                         stringSamples.getOrPut(colName) { mutableSetOf() }.add(str)
                     }
                }
            }
        }

        val statsJson = numericStats.entries.joinToString(",") { (col, values) ->
            val sum = values.sum()
            val avg = if (values.isNotEmpty()) sum / values.size else 0.0
            """"$col":{"sum":${formatNum(sum)},"avg":${formatNum(avg)},"min":${formatNum(values.minOrNull() ?: 0.0)},"max":${formatNum(values.maxOrNull() ?: 0.0)}}"""
        }
        
        val stringSamplesJson = stringSamples.entries.joinToString(",") { (col, values) ->
            """"$col":${values.take(5).toList().toJsonArray()}"""
        }

        return """{"rows":$rowCount,"columns":${columns.toJsonArray()},"numericStats":{$statsJson},"stringSamples":{$stringSamplesJson}}"""
    }

    private fun readQuery(sheet: Sheet, input: Input): String {
        val formatter = DataFormatter()
        val headerRow = sheet.getRow(0) ?: return "Empty sheet"

        val headers = mutableListOf<String>()
        for (cell in headerRow) headers.add(formatter.formatCellValue(cell).trim())

        val colIdx = input.column?.let { headers.indexOf(it) } ?: -1
        val groupIdx = input.groupBy?.let { headers.indexOf(it) } ?: -1

        if (input.column != null && colIdx == -1) {
            return "Column '${input.column}' not found. Available: ${headers.joinToString(", ")}"
        }
        
        if (input.groupBy != null && groupIdx == -1) {
             return "Group column '${input.groupBy}' not found. Available: ${headers.joinToString(", ")}"
        }

        // Parse filter
        val filterPair = input.filter?.split("=", limit = 2)?.let {
            val colName = it[0].trim()
            val colIndex = headers.indexOf(colName)
            if (colIndex == -1) return "Filter column '$colName' not found. Available: ${headers.joinToString(", ")}"
            colIndex to it[1].trim()
        }

        if (input.filter != null && filterPair is String) return filterPair // Error message

        val groups = mutableMapOf<String, MutableList<Double>>()
        val limit = input.limit ?: 10
        
        val filterColIdx = (filterPair as? Pair<Int, String>)?.first
        val filterValue = (filterPair as? Pair<Int, String>)?.second

        for (i in 1..sheet.lastRowNum) {
            val row = sheet.getRow(i) ?: continue

            // Apply filter
            if (filterColIdx != null && filterValue != null) {
                val cell = row.getCell(filterColIdx) ?: continue
                val valInCell = formatter.formatCellValue(cell).trim()
                if (!valInCell.equals(filterValue, ignoreCase = true)) continue
            }

            val groupKey = if (groupIdx >= 0) {
                formatter.formatCellValue(row.getCell(groupIdx)).trim().ifEmpty { "(empty)" }
            } else "total"

            if (colIdx >= 0) {
                val cell = row.getCell(colIdx)
                val value = when (cell?.cellType) {
                    CellType.NUMERIC -> cell.numericCellValue
                    CellType.STRING -> cell.stringCellValue.toDoubleOrNull() ?: 0.0
                    else -> 0.0
                }
                groups.getOrPut(groupKey) { mutableListOf() }.add(value)
            } else {
                groups.getOrPut(groupKey) { mutableListOf() }.add(1.0)
            }
        }

        val agg = input.aggregation?.uppercase() ?: "COUNT"
        val results = groups.mapValues { (_, values) ->
            when (agg) {
                "SUM" -> values.sum()
                "COUNT" -> values.size.toDouble()
                "AVG" -> if (values.isNotEmpty()) values.sum() / values.size else 0.0
                "MIN" -> values.minOrNull() ?: 0.0
                "MAX" -> values.maxOrNull() ?: 0.0
                else -> values.size.toDouble()
            }
        }.entries.sortedByDescending { it.value }.take(limit)

        return results.joinToString("\n") { "${it.key}: ${formatNum(it.value)}" }
    }

    private fun readCell(sheet: Sheet, range: String): String {
        val formatter = DataFormatter()

        // Parse range like "A1" or "A1:C3"
        if (range.contains(":")) {
            val (start, end) = range.split(":")
            val startRef = parseCellRef(start)
            val endRef = parseCellRef(end)

            val result = StringBuilder()
            for (r in startRef.first..endRef.first) {
                val row = sheet.getRow(r) ?: continue
                val values = (startRef.second..endRef.second).map { c ->
                    formatter.formatCellValue(row.getCell(c))
                }
                result.appendLine(values.joinToString("\t"))
            }
            return result.toString().trim()
        } else {
            val (rowIdx, colIdx) = parseCellRef(range)
            val row = sheet.getRow(rowIdx) ?: return "(empty)"
            return formatter.formatCellValue(row.getCell(colIdx))
        }
    }

    private fun readLookup(sheet: Sheet, input: Input): String {
        val lookupValue = input.lookupValue ?: throw BadInputException("lookupValue required")
        val lookupColumn = input.lookupColumn ?: throw BadInputException("lookupColumn required")
        val returnColumn = input.returnColumn ?: throw BadInputException("returnColumn required")

        val formatter = DataFormatter()
        val headerRow = sheet.getRow(0) ?: return "Empty sheet"

        val headers = mutableListOf<String>()
        for (cell in headerRow) headers.add(formatter.formatCellValue(cell).trim())

        val lookupIdx = headers.indexOf(lookupColumn)
        val returnIdx = headers.indexOf(returnColumn)

        if (lookupIdx == -1) return "Column '$lookupColumn' not found"
        if (returnIdx == -1) return "Column '$returnColumn' not found"

        for (i in 1..sheet.lastRowNum) {
            val row = sheet.getRow(i) ?: continue
            val cellValue = formatter.formatCellValue(row.getCell(lookupIdx)).trim()
            if (cellValue.equals(lookupValue, ignoreCase = true)) {
                return formatter.formatCellValue(row.getCell(returnIdx))
            }
        }

        return "Not found: '$lookupValue'"
    }

    private fun parseCellRef(ref: String): Pair<Int, Int> {
        val match = Regex("([A-Z]+)(\\d+)").matchEntire(ref.uppercase())
            ?: throw BadInputException("Invalid cell reference: $ref")
        val col = match.groupValues[1].fold(0) { acc, c -> acc * 26 + (c - 'A' + 1) } - 1
        val row = match.groupValues[2].toInt() - 1
        return row to col
    }

    private fun formatNum(d: Double): String = if (d == d.toLong().toDouble()) d.toLong().toString() else "%.2f".format(d)

    private fun List<String>.toJsonArray() = joinToString(",", "[", "]") { "\"${it.replace("\"", "\\\"")}\"" }
}
