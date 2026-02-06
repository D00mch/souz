package ru.gigadesk.tool.dataAnalytics.excel

import org.apache.poi.ss.usermodel.*
import org.apache.poi.xssf.usermodel.XSSFWorkbook
import ru.gigadesk.tool.*
import ru.gigadesk.tool.files.FilesToolUtil
import ru.gigadesk.tool.files.ForbiddenFolder
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream

enum class JoinOperation {
    VLOOKUP,   // Add column from another file/sheet
    JOIN,      // SQL-style join of two files -> new file
    MERGE      // Vertical merge of files in a folder -> new file
}

class ExcelJoin(
    private val filesToolUtil: FilesToolUtil
) : ToolSetup<ExcelJoin.Input> {

    data class Input(
        @InputParamDescription("Main file path (for VLOOKUP/JOIN)")
        val path: String? = null,

        @InputParamDescription("Operation: VLOOKUP, JOIN, MERGE")
        val operation: JoinOperation,

        @InputParamDescription("Second file path (for VLOOKUP/JOIN/MERGE output)")
        val otherPath: String? = null,

        @InputParamDescription("Folder path (only for MERGE input)")
        val folderPath: String? = null,

        // VLOOKUP / JOIN params
        @InputParamDescription("Key column in main file")
        val key: String? = null,

        @InputParamDescription("Key column in other file (default: same as 'key')")
        val otherKey: String? = null,

        @InputParamDescription("Column to take from other file")
        val valueColumn: String? = null,

        @InputParamDescription("New column name in main file (default: same as valueColumn)")
        val newColumn: String? = null,
        
        @InputParamDescription("Join type: LEFT, INNER (for JOIN, default: LEFT)")
        val type: String? = null
    )

    override val name = "ExcelJoin"
    override val description = """Join and merge Excel files:
- VLOOKUP: Enrich 'path' with a column from 'otherPath' matching by key
- JOIN: Create new file 'otherPath' by joining two files (SQL-like)
- MERGE: Combine all Excel files from 'folderPath' into 'path'"""

    override val fewShotExamples = listOf(
        FewShotExample(
            request = "Добавь в orders.xlsx цену из price.xlsx по коду товара",
            params = mapOf(
                "operation" to "VLOOKUP",
                "path" to "orders.xlsx",
                "otherPath" to "price.xlsx",
                "key" to "ItemCode",
                "valueColumn" to "Price"
            )
        ),
        FewShotExample(
            request = "Объедини все отчеты из папки reports в all_reports.xlsx",
            params = mapOf(
                "operation" to "MERGE",
                "folderPath" to "reports",
                "path" to "all_reports.xlsx"
            )
        )
    )

    override val returnParameters = ReturnParameters(
        properties = mapOf("result" to ReturnProperty("string", "Operation result"))
    )

    override fun invoke(input: Input): String {
        return when (input.operation) {
            JoinOperation.VLOOKUP -> vlookup(input)
            JoinOperation.JOIN -> join(input)
            JoinOperation.MERGE -> merge(input)
        }
    }

    private fun vlookup(input: Input): String {
        val targetPath = input.path ?: throw BadInputException("path required for VLOOKUP")
        val sourcePath = input.otherPath ?: throw BadInputException("otherPath required for VLOOKUP")
        val keyCol = input.key ?: throw BadInputException("key required for VLOOKUP")
        val sourceValCol = input.valueColumn ?: throw BadInputException("valueColumn required for VLOOKUP")
        val targetColName = input.newColumn ?: sourceValCol
        val sourceKeyCol = input.otherKey ?: keyCol

        val targetFile = resolveFile(targetPath)
        val sourceFile = resolveFile(sourcePath)

        val sourceMap = readColumnMap(sourceFile, sourceKeyCol, sourceValCol)

        val workbook = FileInputStream(targetFile).use { WorkbookFactory.create(it) }
        try {
            val sheet = workbook.getSheetAt(0)
            val formatter = DataFormatter()

            val headerRow = sheet.getRow(0) ?: throw BadInputException("Target file empty")
            var keyIdx = -1
            var newColIdx = -1

            for (cell in headerRow) {
                val txt = formatter.formatCellValue(cell).trim()
                if (txt.equals(keyCol, ignoreCase = true)) keyIdx = cell.columnIndex
            }

            if (keyIdx == -1) throw BadInputException("Key column '$keyCol' not found in target")

            for (cell in headerRow) {
                 if (formatter.formatCellValue(cell).trim().equals(targetColName, true)) {
                     newColIdx = cell.columnIndex
                     break
                 }
            }
            if (newColIdx == -1) {
                newColIdx = headerRow.lastCellNum.toInt()
                headerRow.createCell(newColIdx).setCellValue(targetColName)
            }

            var updated = 0
            for (i in 1..sheet.lastRowNum) {
                val row = sheet.getRow(i) ?: continue
                val keyVal = formatter.formatCellValue(row.getCell(keyIdx)).trim()
                
                if (sourceMap.containsKey(keyVal)) {
                    val valToSet = sourceMap[keyVal]
                    val cell = row.createCell(newColIdx)
                    if (valToSet != null) {
                         val num = valToSet.toDoubleOrNull()
                         if (num != null) cell.setCellValue(num) else cell.setCellValue(valToSet)
                    }
                    updated++
                }
            }

            FileOutputStream(targetFile).use { workbook.write(it) }
            return "VLOOKUP completed. Added/Updated column '$targetColName', matched $updated rows."
        } finally {
            workbook.close()
        }
    }

    private fun join(input: Input): String {
        // Simplified JOIN: writes to 'otherPath' (output)
        // Implementation similar to VLOOKUP but creates new file. 
        // For MVP, lets assume VLOOKUP is preferred for enriching.
        // If users ask for joining two files into NEW one:
        
        throw BadInputException("JOIN operation not fully implemented in MVP. Use VLOOKUP to enrich existing file or MERGE to combine files.")
    }

    private fun merge(input: Input): String {
        val folderPath = input.folderPath ?: throw BadInputException("folderPath required for MERGE")
        val outPath = input.path ?: throw BadInputException("path (output) required for MERGE")
        
        val folder = File(filesToolUtil.applyDefaultEnvs(folderPath))
        if (!filesToolUtil.isPathSafe(folder)) throw ForbiddenFolder(folder.path)
        
        val files = folder.listFiles { _, name -> name.endsWith(".xlsx", true) }?.toList()
            ?: throw BadInputException("No .xlsx files found in $folderPath")

        val outBook = XSSFWorkbook()
        val outSheet = outBook.createSheet("Merged")
        var rowIdx = 0
        var headers: List<String>? = null
        var mergedCount = 0

        val formatter = DataFormatter()

        files.forEach { file ->
            WorkbookFactory.create(file, null, true).use { wb ->
                val sheet = wb.getSheetAt(0)
                if (sheet.physicalNumberOfRows > 0) {
                    val currentHeaders = sheet.getRow(0).map { formatter.formatCellValue(it).trim() }
                    
                    if (headers == null) {
                        headers = currentHeaders
                        val hRow = outSheet.createRow(rowIdx++)
                        currentHeaders.forEachIndexed { i, h -> hRow.createCell(i).setCellValue(h) }
                        hRow.createCell(currentHeaders.size).setCellValue("Source File")
                    }

                    for (i in 1..sheet.lastRowNum) {
                        val row = sheet.getRow(i) ?: continue
                        val newRow = outSheet.createRow(rowIdx++)

                        for (c in 0 until row.lastCellNum) {
                            val cell = row.getCell(c) ?: continue
                            val newCell = newRow.createCell(c)
                            cloneCell(cell, newCell)
                        }
                        newRow.createCell((headers?.size ?: 0)).setCellValue(file.name)
                    }
                    mergedCount++
                }
            }
        }

        val outFile = File(filesToolUtil.applyDefaultEnvs(outPath))
        FileOutputStream(outFile).use { outBook.write(it) }
        
        return "Merged $mergedCount files into $outPath. Total rows: $rowIdx"
    }

    private fun readColumnMap(file: File, keyCol: String, valCol: String): Map<String, String> {
        val map = mutableMapOf<String, String>()
        WorkbookFactory.create(file, null, true).use { wb ->
            val sheet = wb.getSheetAt(0)
            val formatter = DataFormatter()
            val headerRow = sheet.getRow(0) ?: return emptyMap()
            
            var keyIdx = -1
            var valIdx = -1
            
            for (cell in headerRow) {
                val txt = formatter.formatCellValue(cell).trim()
                if (txt.equals(keyCol, true)) keyIdx = cell.columnIndex
                if (txt.equals(valCol, true)) valIdx = cell.columnIndex
            }

            if (keyIdx != -1 && valIdx != -1) {
                for (i in 1..sheet.lastRowNum) {
                    val row = sheet.getRow(i) ?: continue
                    val key = formatter.formatCellValue(row.getCell(keyIdx)).trim()
                    val value = formatter.formatCellValue(row.getCell(valIdx)).trim()
                    if (key.isNotEmpty()) map[key] = value
                }
            }
        }
        return map
    }

    private fun cloneCell(src: Cell, dest: Cell) {
        when (src.cellType) {
            CellType.STRING -> dest.setCellValue(src.stringCellValue)
            CellType.NUMERIC -> dest.setCellValue(src.numericCellValue)
            CellType.BOOLEAN -> dest.setCellValue(src.booleanCellValue)
            CellType.FORMULA -> dest.cellFormula = src.cellFormula
            else -> dest.setCellValue("")
        }
    }

    private fun resolveFile(path: String): File {
        val f = File(filesToolUtil.applyDefaultEnvs(path))
        if (!filesToolUtil.isPathSafe(f)) throw ForbiddenFolder(path)
        if (!f.exists()) throw BadInputException("File not found: $path")
        return f
    }
}
