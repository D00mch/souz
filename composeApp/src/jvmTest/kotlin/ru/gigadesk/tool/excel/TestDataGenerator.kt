package ru.gigadesk.tool.excel

import io.mockk.every
import io.mockk.mockk
import ru.gigadesk.tool.dataAnalytics.excel.ExcelCreate
import ru.gigadesk.tool.dataAnalytics.excel.ExcelWrite
import ru.gigadesk.tool.dataAnalytics.excel.WriteOperation
import kotlin.test.Test
import ru.gigadesk.tool.files.FilesToolUtil
import java.io.File

class TestDataGenerator {

    @Test
    fun generateAll() {
        val baseDir = System.getProperty("user.home") + "/Documents/ExcelTestData"
        File(baseDir).mkdirs()

        val filesUtil = mockk<FilesToolUtil>()
        every { filesUtil.applyDefaultEnvs(any()) } answers {
            val path = firstArg<String>()
            if (path.startsWith("/")) path else "$baseDir/$path"
        }
        every { filesUtil.isPathSafe(any()) } returns true

        val create = ExcelCreate(filesUtil)
        val write = ExcelWrite(filesUtil)

        println("Generating data in: $baseDir")

        // 1. sales.xlsx
        create.invoke(ExcelCreate.Input("sales.xlsx", "Date, Manager, Revenue, Status"))
        write.invoke(ExcelWrite.Input("sales.xlsx", WriteOperation.ADD_ROW, rowData = "2024-01-01, Ivanov, 10000, Completed"))
        write.invoke(ExcelWrite.Input("sales.xlsx", WriteOperation.ADD_ROW, rowData = "2024-01-02, Petrov, 20000, Pending"))
        write.invoke(ExcelWrite.Input("sales.xlsx", WriteOperation.ADD_ROW, rowData = "2024-01-03, Ivanov, 15000, Cancelled"))
        write.invoke(ExcelWrite.Input("sales.xlsx", WriteOperation.ADD_ROW, rowData = "2024-01-04, Sidorov, 30000, Completed"))
        write.invoke(ExcelWrite.Input("sales.xlsx", WriteOperation.ADD_ROW, rowData = "2024-01-05, Ivanov, 12000, Completed"))
        println("Created sales.xlsx")

        // 2. price.xlsx
        create.invoke(ExcelCreate.Input("price.xlsx", "ItemCode, ItemName, Price"))
        write.invoke(ExcelWrite.Input("price.xlsx", WriteOperation.ADD_ROW, rowData = "A001, Laptop, 1000"))
        write.invoke(ExcelWrite.Input("price.xlsx", WriteOperation.ADD_ROW, rowData = "A002, Mouse, 50"))
        write.invoke(ExcelWrite.Input("price.xlsx", WriteOperation.ADD_ROW, rowData = "A003, Keyboard, 100"))
        println("Created price.xlsx")

        // 3. orders.xlsx
        create.invoke(ExcelCreate.Input("orders.xlsx", "OrderID, ItemCode, Quantity"))
        write.invoke(ExcelWrite.Input("orders.xlsx", WriteOperation.ADD_ROW, rowData = "101, A001, 2"))
        write.invoke(ExcelWrite.Input("orders.xlsx", WriteOperation.ADD_ROW, rowData = "102, A002, 5"))
        write.invoke(ExcelWrite.Input("orders.xlsx", WriteOperation.ADD_ROW, rowData = "103, A003, 1"))
        write.invoke(ExcelWrite.Input("orders.xlsx", WriteOperation.ADD_ROW, rowData = "104, A001, 1"))
        println("Created orders.xlsx")

        // 4. clients.xlsx (with duplicates)
        create.invoke(ExcelCreate.Input("clients.xlsx", "Name, Email, Phone"))
        write.invoke(ExcelWrite.Input("clients.xlsx", WriteOperation.ADD_ROW, rowData = "Client A, a@example.com, 123"))
        write.invoke(ExcelWrite.Input("clients.xlsx", WriteOperation.ADD_ROW, rowData = "Client B, b@example.com, 456"))
        write.invoke(ExcelWrite.Input("clients.xlsx", WriteOperation.ADD_ROW, rowData = "Client A, a@example.com, 123")) // Duplicate
        write.invoke(ExcelWrite.Input("clients.xlsx", WriteOperation.ADD_ROW, rowData = "Client C, c@example.com, 789"))
        println("Created clients.xlsx")
    }
}
