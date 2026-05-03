package ru.souz.tool.excel

import io.mockk.every
import io.mockk.mockk
import ru.souz.test.invoke
import ru.souz.tool.dataAnalytics.excel.ExcelReport
import ru.souz.tool.files.FilesToolUtil
import java.io.File
import kotlin.test.Test

class TestDataGenerator {

    @Test
    fun generateAll() {
        val baseDir = File("composeApp/src/jvmTest/resources/directory").canonicalFile
        baseDir.mkdirs()
        val generatedFiles = listOf("sales.xlsx", "price.xlsx", "orders.xlsx", "clients.xlsx")
            .map { File(baseDir, it) }

        val filesUtil = mockk<FilesToolUtil>()
        every { filesUtil.applyDefaultEnvs(any()) } answers {
            val path = firstArg<String>()
            if (path.startsWith("/")) path else File(baseDir, path).absolutePath
        }
        every { filesUtil.isPathSafe(any<File>()) } returns true

        val report = ExcelReport(filesUtil)

        println("Generating data in: $baseDir")

        try {
            // Helper to convert list of lists to CSV
        fun toCsv(data: List<List<Any>>): String {
            return data.joinToString("\n") { row ->
                row.joinToString(",") { it.toString() }
            }
        }

        // 1. sales.xlsx
        report.invoke(ExcelReport.Input(
            path = "sales.xlsx",
            headers = listOf("Date", "Manager", "Revenue", "Status").joinToString(","),
            csvData = toCsv(listOf(
                listOf("2024-01-01", "Ivanov", 10000, "Completed"),
                listOf("2024-01-02", "Petrov", 20000, "Pending"),
                listOf("2024-01-03", "Ivanov", 15000, "Cancelled"),
                listOf("2024-01-04", "Sidorov", 30000, "Completed"),
                listOf("2024-01-05", "Ivanov", 12000, "Completed")
            ))
            ))
            println("Created sales.xlsx")

            // 2. price.xlsx
            report.invoke(ExcelReport.Input(
                path = "price.xlsx",
                headers = listOf("ItemCode", "ItemName", "Price").joinToString(","),
                csvData = toCsv(listOf(
                listOf("A001", "Laptop", 1000),
                listOf("A002", "Mouse", 50),
                listOf("A003", "Keyboard", 100)
            ))
            ))
            println("Created price.xlsx")

            // 3. orders.xlsx
            report.invoke(ExcelReport.Input(
                path = "orders.xlsx",
                headers = listOf("OrderID", "ItemCode", "Quantity").joinToString(","),
                csvData = toCsv(listOf(
                listOf(101, "A001", 2),
                listOf(102, "A002", 5),
                listOf(103, "A003", 1),
                listOf(104, "A001", 1)
            ))
            ))
            println("Created orders.xlsx")

            // 4. clients.xlsx (with duplicates)
            report.invoke(ExcelReport.Input(
                path = "clients.xlsx",
                headers = listOf("Name", "Email", "Phone").joinToString(","),
                csvData = toCsv(listOf(
                 listOf("Client A", "a@example.com", "123"),
                 listOf("Client B", "b@example.com", "456"),
                 listOf("Client A", "a@example.com", "123"),
                 listOf("Client C", "c@example.com", "789")
             ))
            ))
            println("Created clients.xlsx")
        } finally {
            generatedFiles.forEach { it.delete() }
        }
    }
}
