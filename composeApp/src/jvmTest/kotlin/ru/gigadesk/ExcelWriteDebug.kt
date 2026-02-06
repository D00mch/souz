package ru.gigadesk

import ru.gigadesk.tool.dataAnalytics.excel.ExcelWrite
import ru.gigadesk.tool.dataAnalytics.excel.WriteOperation
import ru.gigadesk.tool.files.FilesToolUtil
import ru.gigadesk.giga.GigaModel
import ru.gigadesk.db.SettingsProvider
import org.junit.jupiter.api.Test
import java.io.File
import org.apache.poi.ss.usermodel.WorkbookFactory

class ExcelWriteDebug {

    class DummySettingsProvider : SettingsProvider {
        override fun getSystemPromptForModel(model: GigaModel): String? = null
        override fun setSystemPromptForModel(model: GigaModel, prompt: String?) {}
        override var gigaChatKey: String? = null
        override var saluteSpeechKey: String? = null
        override var supportEmail: String? = null
        override var systemPrompt: String? = null
        override var defaultCalendar: String? = null
        override var gigaModel: GigaModel = GigaModel.Max
        override var useFewShotExamples: Boolean = false
        override var useGrpc: Boolean = false
        override var needsOnboarding: Boolean = false
        override var requestTimeoutMillis: Long = 1000
        override var initialWindowWidthDp: Int = 100
        override var initialWindowHeightDp: Int = 100
        override var temperature: Float = 0.5f
        override var forbiddenFolders: List<String> = emptyList()
    }

    @Test
    fun testAddRowPersistence() {
        val path = "test_write_debug.xlsx"
        val file = File(path)
        
        // 1. Create file with Header
        val wb = org.apache.poi.xssf.usermodel.XSSFWorkbook()
        val sheet = wb.createSheet("Sheet1")
        val header = sheet.createRow(0)
        header.createCell(0).setCellValue("Name")
        header.createCell(1).setCellValue("Value")
        java.io.FileOutputStream(file).use { wb.write(it) }
        wb.close()
        
        println("Created test file: ${file.absolutePath}")
        
        // 2. Add Row using Tool
        val util = FilesToolUtil(DummySettingsProvider())
        val tool = ExcelWrite(util)
        
        try {
            val res = tool.invoke(ExcelWrite.Input(
                path = file.absolutePath,
                operation = WriteOperation.ADD_ROW,
                rowData = "NewItem, 999"
            ))
            println("Tool Result: $res")
            
            // 3. Verify Persistence
            println("--- VERIFYING CONTENT ---")
            val wb2 = WorkbookFactory.create(file)
            val s2 = wb2.getSheetAt(0)
            val r1 = s2.getRow(1)
            
            if (r1 == null) {
                println("FAILURE: Row 1 does not exist!")
            } else {
                println("Row 1 Cell 0: ${r1.getCell(0)}")
                println("Row 1 Cell 1: ${r1.getCell(1)}")
            }
            wb2.close()
            
        } catch (e: Exception) {
            println("ERROR: ${e.message}")
            e.printStackTrace()
        }
    }
}
