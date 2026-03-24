package ru.souz.tool.dataAnalytics.excel

import org.apache.poi.ss.usermodel.*
import ru.souz.tool.*
import ru.souz.tool.files.FilesToolUtil
import ru.souz.tool.files.ForbiddenFolder
import java.io.File

class ExcelRead(
    private val filesToolUtil: FilesToolUtil
) : ToolSetup<ExcelRead.Input> {

    enum class ReadOperation {
        STRUCTURE,
        QUERY,
        CELL,
        LOOKUP
    }

    data class Input(
        @InputParamDescription("Path to Excel file")
        val path: String,

        @InputParamDescription("Operation: STRUCTURE, QUERY, CELL, or LOOKUP")
        val operation: ReadOperation,

        @InputParamDescription("Sheet name (optional, default: first sheet)")
        val sheet: String? = null,

        @InputParamDescription("Cell address like 'B5' or range 'A1:C10' (for CELL operation)")
        val range: String? = null,

        @InputParamDescription("Column to aggregate (for QUERY)")
        val column: String? = null,

        @InputParamDescription("Aggregation: SUM, COUNT, AVG, MIN, MAX (for QUERY)")
        val aggregation: String? = null,

        @InputParamDescription("Column to group by (for QUERY)")
        val groupBy: String? = null,

        @InputParamDescription("Filter: Column=Value or >, <, >=, <=, <> (e.g. 'Amount>1000')")
        val filter: String? = null,

        @InputParamDescription("Limit results (for QUERY, default: 10)")
        val limit: Int? = null,

        @InputParamDescription("Column to sort by (for QUERY)")
        val sortBy: String? = null,

        @InputParamDescription("Sort order: ASC or DESC")
        val sortOrder: String? = null,



        @InputParamDescription("Lookup value (for LOOKUP)")
        val lookupValue: String? = null,

        @InputParamDescription("Column to search in (for LOOKUP)")
        val lookupColumn: String? = null,

        @InputParamDescription("Column to return (for LOOKUP)")
        val returnColumn: String? = null
    )


    override val name = "ExcelRead"
    
    override val description = """READ and GET data from Excel files. READ ONLY tool.
- STRUCTURE: Get columns, row count, stats (Use first to understand file)
- QUERY: Filter, Sort, Aggregate (SUM, COUNT, AVG), Group By
- CELL: Read specific cell (e.g. B5) or range (e.g. A1:C10)
- LOOKUP: VLOOKUP-style search (find value in one column, return another)"""

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

    override fun describeAction(input: Input): ToolActionDescriptor? = ToolActionDescriptor(
        kind = if (input.operation == ReadOperation.QUERY) {
            ToolActionKind.ANALYZE_SPREADSHEET
        } else {
            ToolActionKind.READ_SPREADSHEET
        },
        primary = ToolActionValueFormatter.fileName(input.path),
    )

    override fun invoke(input: Input): String {
        val file = File(filesToolUtil.applyDefaultEnvs(input.path))
        if (!filesToolUtil.isPathSafe(file)) throw ForbiddenFolder(file.path)
        if (!file.exists()) throw BadInputException("File not found: ${input.path}")

        return WorkbookFactory.create(file, null, true).use { workbook ->
            val sheet = input.sheet?.let { name ->
                workbook.getSheet(name) ?: throw BadInputException("Sheet '$name' not found")
            } ?: workbook.getSheetAt(0)

            when (input.operation) {
                ReadOperation.STRUCTURE -> readStructure(sheet)
                ReadOperation.QUERY -> readQuery(sheet, input)
                ReadOperation.CELL -> readCell(sheet, input.range ?: "${input.returnColumn}:${input.returnColumn}")
                ReadOperation.LOOKUP -> readLookup(sheet, input)
            }
        }
    }

    private fun readStructure(sheet: Sheet): String {
        val formatter = DataFormatter()
        val headerRow = sheet.getRow(0) ?: return """{"error":"Empty sheet"}"""

        val columns = mutableListOf<String>()
        val numericStats = mutableMapOf<String, MutableList<Double>>()
        val stringSamples = mutableMapOf<String, MutableSet<String>>()

        for (cell in headerRow) {
            val name = formatter.formatCellValue(cell).trim()
            if (name.isNotBlank()) columns.add(name)
        }

        val rowCount = sheet.lastRowNum
        val sampleSize = minOf(100, rowCount)

        for (i in 1..sampleSize) {
            val row = sheet.getRow(i) ?: continue
            columns.forEachIndexed { idx, colName ->
                val cell = row.getCell(idx) ?: return@forEachIndexed
                when (cell.cellType) {
                    CellType.NUMERIC -> {
                        numericStats.getOrPut(colName) { mutableListOf() }.add(cell.numericCellValue)
                    }
                    CellType.STRING -> {
                        val str = cell.stringCellValue.trim()
                        if (str.isNotEmpty()) {
                            stringSamples.getOrPut(colName) { mutableSetOf() }.add(str)
                        }
                    }
                    else -> {}
                }
            }
        }

        val statsJson = numericStats.entries.joinToString(",") { (col, values) ->
            val sum = values.sum()
            val avg = if (values.isNotEmpty()) sum / values.size else 0.0
            """"$col":{"sum":${formatNum(sum)},"avg":${formatNum(avg)},"min":${formatNum(values.minOrNull() ?: 0.0)},"max":${formatNum(values.maxOrNull() ?: 0.0)}}"""
        }

        val samplesJson = stringSamples.entries.joinToString(",") { (col, values) ->
            """"$col":${values.take(5).toList().toJsonArray()}"""
        }

        return """{"rows":$rowCount,"columns":${columns.toJsonArray()},"numericStats":{$statsJson},"stringSamples":{$samplesJson}}"""
    }

    private fun readQuery(sheet: Sheet, input: Input): String {
        val formatter = DataFormatter()
        val headers = sheet.getHeaders(formatter)
        if (headers.isEmpty()) return "Empty sheet"

        val colIdx = input.column?.let { col ->
            headers.indexOf(col).also { if (it == -1) throw BadInputException("Column '$col' not found. Available: ${headers.joinToString()}") }
        } ?: -1

        val groupIdx = input.groupBy?.let { col ->
            headers.indexOf(col).also { if (it == -1) throw BadInputException("Group column '$col' not found. Available: ${headers.joinToString()}") }
        } ?: -1

        val filterCondition = input.filter?.let { output ->
            val condition = parseCondition(output)
            val idx = headers.indexOf(condition.column)
            if (idx == -1) throw BadInputException("Filter column '${condition.column}' not found. Available: ${headers.joinToString()}")
            idx to condition
        }

        val groups = mutableMapOf<String, MutableList<Double>>()
        val limit = input.limit ?: 10

        for (i in 1..sheet.lastRowNum) {
            val row = sheet.getRow(i) ?: continue

            if (filterCondition != null) {
                val (filterIdx, cond) = filterCondition
                val cell = row.getCell(filterIdx)
                val cellValue = formatter.formatCellValue(cell).trim()
                
                val matches = when (cond.operator) {
                    ComparisonOperator.EQ -> cellValue.equals(cond.value, ignoreCase = true)
                    ComparisonOperator.NEQ -> !cellValue.equals(cond.value, ignoreCase = true)
                    else -> {
                        val numCell = cellValue.toDoubleOrNull()
                        val numVal = cond.value.toDoubleOrNull()
                        if (numCell != null && numVal != null) {
                            when (cond.operator) {
                                ComparisonOperator.GT -> numCell > numVal
                                ComparisonOperator.LT -> numCell < numVal
                                ComparisonOperator.GTE -> numCell >= numVal
                                ComparisonOperator.LTE -> numCell <= numVal
                                else -> false
                            }
                        } else {
                            val cmp = cellValue.compareTo(cond.value, ignoreCase = true)
                            when (cond.operator) {
                                ComparisonOperator.GT -> cmp > 0
                                ComparisonOperator.LT -> cmp < 0
                                ComparisonOperator.GTE -> cmp >= 0
                                ComparisonOperator.LTE -> cmp <= 0
                                else -> false
                            }
                        }
                    }
                }
                if (!matches) continue
            }

            val groupKey = if (groupIdx >= 0) {
                formatter.formatCellValue(row.getCell(groupIdx)).trim().ifEmpty { "(empty)" }
            } else "total"

            val value = if (colIdx >= 0) {
                row.getCell(colIdx)?.let { cell ->
                    when (cell.cellType) {
                        CellType.NUMERIC -> cell.numericCellValue
                        CellType.STRING -> cell.stringCellValue.toDoubleOrNull() ?: 0.0
                        else -> 0.0
                    }
                } ?: 0.0
            } else 1.0

            groups.getOrPut(groupKey) { mutableListOf() }.add(value)
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
        }.entries.toList()

        val sortKey = input.sortBy
        val isDesc = when (input.sortOrder?.uppercase()) {
            null -> true
            "DESC" -> true
            "ASC" -> false
            else -> throw BadInputException("sortOrder must be ASC or DESC")
        }

        val sortedResults = if (sortKey != null) {
            if (sortKey.equals(input.groupBy, true)) {
                if (isDesc) results.sortedByDescending { it.key } else results.sortedBy { it.key }
            } else {
                 if (isDesc) results.sortedByDescending { it.value } else results.sortedBy { it.value }
            }
        } else {
             results.sortedByDescending { it.value }
        }

        return sortedResults.take(limit).joinToString("\n") { "${it.key}: ${formatNum(it.value)}" }


    }

    private fun readCell(sheet: Sheet, range: String): String {
        val formatter = DataFormatter()

        return if (":" in range) {
            val (start, end) = range.split(":")
            val (startRow, startCol) = parseCellRef(start)
            val (endRow, endCol) = parseCellRef(end)

            (startRow..endRow).mapNotNull { r ->
                sheet.getRow(r)?.let { row ->
                    (startCol..endCol).joinToString("\t") { c ->
                        row.getCell(c)?.let { formatter.formatCellValue(it) } ?: ""
                    }
                }
            }.joinToString("\n")
        } else {
            val (rowIdx, colIdx) = parseCellRef(range)
            sheet.getRow(rowIdx)?.getCell(colIdx)?.let { formatter.formatCellValue(it) } ?: "(empty)"
        }
    }

    private fun readLookup(sheet: Sheet, input: Input): String {
        val lookupValue = input.lookupValue ?: throw BadInputException("lookupValue required")
        val lookupColumn = input.lookupColumn ?: throw BadInputException("lookupColumn required")
        val returnColumn = input.returnColumn ?: throw BadInputException("returnColumn required")

        val formatter = DataFormatter()
        val lookupIdx = sheet.findColumnIndex(lookupColumn, formatter).also { i ->
            if (i == -1) throw BadInputException("Column '$lookupColumn' not found")
        }
        val returnIdx = sheet.findColumnIndex(returnColumn, formatter).also { i ->
            if (i == -1) throw BadInputException("Column '$returnColumn' not found")
        }

        for (i in 1..sheet.lastRowNum) {
            val row = sheet.getRow(i) ?: continue
            val cellValue = formatter.formatCellValue(row.getCell(lookupIdx)).trim()
            if (cellValue.equals(lookupValue, ignoreCase = true)) {
                return formatter.formatCellValue(row.getCell(returnIdx))
            }
        }

        return "Not found: '$lookupValue'"
    }
}
