package ru.souz.tool.excel

import io.mockk.every
import io.mockk.mockk
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertTrue
import ru.souz.db.SettingsProvider
import ru.souz.llms.ToolInvocationMeta
import ru.souz.runtime.sandbox.SandboxScope
import ru.souz.runtime.sandbox.local.LocalRuntimeSandbox
import ru.souz.tool.dataAnalytics.excel.ExcelReport
import ru.souz.tool.files.FilesToolUtil

class TestDataGenerator {

    @Test
    fun generateAll() {
        val homeDir = Files.createTempDirectory("excel-report-home-")
        val stateRoot = Files.createTempDirectory("excel-report-state-")
        val baseDir = homeDir.resolve("directory").toFile().apply { mkdirs() }
        val generatedFiles = listOf("sales.xlsx", "price.xlsx", "orders.xlsx", "clients.xlsx")
            .map { File(baseDir, it) }

        val report = ExcelReport(createFilesToolUtil(homeDir, stateRoot))

        println("Generating data in: $baseDir")

        try {
            // Helper to convert list of lists to CSV
            fun toCsv(data: List<List<Any>>): String {
                return data.joinToString("\n") { row ->
                    row.joinToString(",") { it.toString() }
                }
            }

            // 1. sales.xlsx
            report.invoke(
                ExcelReport.Input(
                    path = generatedFiles[0].absolutePath,
                    headers = listOf("Date", "Manager", "Revenue", "Status").joinToString(","),
                    csvData = toCsv(
                        listOf(
                            listOf("2024-01-01", "Ivanov", 10000, "Completed"),
                            listOf("2024-01-02", "Petrov", 20000, "Pending"),
                            listOf("2024-01-03", "Ivanov", 15000, "Cancelled"),
                            listOf("2024-01-04", "Sidorov", 30000, "Completed"),
                            listOf("2024-01-05", "Ivanov", 12000, "Completed"),
                        )
                    ),
                ),
                ToolInvocationMeta.Empty,
            )
            println("Created sales.xlsx")

            // 2. price.xlsx
            report.invoke(
                ExcelReport.Input(
                    path = generatedFiles[1].absolutePath,
                    headers = listOf("ItemCode", "ItemName", "Price").joinToString(","),
                    csvData = toCsv(
                        listOf(
                            listOf("A001", "Laptop", 1000),
                            listOf("A002", "Mouse", 50),
                            listOf("A003", "Keyboard", 100),
                        )
                    ),
                ),
                ToolInvocationMeta.Empty,
            )
            println("Created price.xlsx")

            // 3. orders.xlsx
            report.invoke(
                ExcelReport.Input(
                    path = generatedFiles[2].absolutePath,
                    headers = listOf("OrderID", "ItemCode", "Quantity").joinToString(","),
                    csvData = toCsv(
                        listOf(
                            listOf(101, "A001", 2),
                            listOf(102, "A002", 5),
                            listOf(103, "A003", 1),
                            listOf(104, "A001", 1),
                        )
                    ),
                ),
                ToolInvocationMeta.Empty,
            )
            println("Created orders.xlsx")

            // 4. clients.xlsx (with duplicates)
            report.invoke(
                ExcelReport.Input(
                    path = generatedFiles[3].absolutePath,
                    headers = listOf("Name", "Email", "Phone").joinToString(","),
                    csvData = toCsv(
                        listOf(
                            listOf("Client A", "a@example.com", "123"),
                            listOf("Client B", "b@example.com", "456"),
                            listOf("Client A", "a@example.com", "123"),
                            listOf("Client C", "c@example.com", "789"),
                        )
                    ),
                ),
                ToolInvocationMeta.Empty,
            )
            println("Created clients.xlsx")
            generatedFiles.forEach { file ->
                assertTrue(file.exists(), "Expected generated file to exist: ${file.name}")
                assertTrue(file.length() > 0L, "Expected generated file to be non-empty: ${file.name}")
            }
        } finally {
            runCatching { homeDir.toFile().deleteRecursively() }
            runCatching { stateRoot.toFile().deleteRecursively() }
        }
    }

    private fun createFilesToolUtil(homeDir: Path, stateRoot: Path): FilesToolUtil {
        val settingsProvider = mockk<SettingsProvider>()
        every { settingsProvider.forbiddenFolders } returns emptyList()
        return FilesToolUtil(
            LocalRuntimeSandbox(
                scope = SandboxScope(userId = "test-user"),
                settingsProvider = settingsProvider,
                homePath = homeDir,
                stateRoot = stateRoot,
                workspaceRoot = homeDir,
            )
        )
    }
}
