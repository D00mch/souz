package agent

import io.mockk.*
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import org.kodein.di.bindSingleton
import ru.gigadesk.tool.dataAnalytics.excel.*
import ru.gigadesk.tool.files.ToolFindFilesByName

class GraphAgentExcelScenariosIntegrationTest : GraphAgentTestBase() {

    // ===================== ExcelRead Scenarios =====================

    @ParameterizedTest(name = "excelRead_overview[{index}] {0}")
    @ValueSource(strings = [
        "Покажи структуру файла sales.xlsx",
        "Какие колонки в файле sales.xlsx?",
        "Открой превью таблицы sales.xlsx"
    ])
    fun excelRead_overview(userPrompt: String) = runTest {
        val excelRead: ExcelRead = spyk(ExcelRead(filesUtil))
        val toolFindFiles: ToolFindFilesByName = spyk(ToolFindFilesByName(filesUtil))

        coEvery { toolFindFiles.suspendInvoke(any()) } returns "[\"/tmp/sales.xlsx\"]"
        coEvery { excelRead.invoke(any()) } returns """{"headers":["Date","Amount"],"rowCount":10}"""

        runScenarioWithMocks(userPrompt) {
            bindSingleton<ExcelRead> { excelRead }
            bindSingleton<ToolFindFilesByName> { toolFindFiles }
        }
        coVerify(atLeast = 1) {
            excelRead.invoke(match { it.path.contains("sales") && it.operation == ReadOperation.STRUCTURE })
        }
    }

    @ParameterizedTest(name = "excelRead_query[{index}] {0}")
    @ValueSource(strings = [
        "Найди в sales.xlsx все продажи где Amount > 1000",
        "Покажи строки из sales.xlsx где сумма больше 1000",
        "Отфильтруй sales.xlsx по Amount больше 1000"
    ])
    fun excelRead_query(userPrompt: String) = runTest {
        val excelRead: ExcelRead = spyk(ExcelRead(filesUtil))
        val toolFindFiles: ToolFindFilesByName = spyk(ToolFindFilesByName(filesUtil))

        coEvery { toolFindFiles.suspendInvoke(any()) } returns "[\"/tmp/sales.xlsx\"]"
        coEvery { excelRead.invoke(any()) } returns """[{"Date":"2024-01-01","Amount":"1500"}]"""

        runScenarioWithMocks(userPrompt) {
            bindSingleton<ExcelRead> { excelRead }
            bindSingleton<ToolFindFilesByName> { toolFindFiles }
        }
        coVerify(atLeast = 1) {
            excelRead.invoke(match {
                it.path.contains("sales") &&
                        it.operation == ReadOperation.QUERY &&
                        it.filter != null && it.filter.contains("1000")
            })
        }
    }

    @ParameterizedTest(name = "excelRead_sort[{index}] {0}")
    @ValueSource(strings = [
        "Отсортируй продажи в sales.xlsx по Amount по убыванию",
        "Покажи sales.xlsx сортировка по Amount DESC",
        "Выведи данные из sales.xlsx упорядоченные по Amount"
    ])
    fun excelRead_sort(userPrompt: String) = runTest {
        val excelRead: ExcelRead = spyk(ExcelRead(filesUtil))
        val toolFindFiles: ToolFindFilesByName = spyk(ToolFindFilesByName(filesUtil))

        coEvery { toolFindFiles.suspendInvoke(any()) } returns "[\"/tmp/sales.xlsx\"]"
        coEvery { excelRead.invoke(any()) } returns """[{"Date":"2024-01-01","Amount":"1500"}]"""

        runScenarioWithMocks(userPrompt) {
            bindSingleton<ExcelRead> { excelRead }
            bindSingleton<ToolFindFilesByName> { toolFindFiles }
        }
        coVerify(atLeast = 1) {
            excelRead.invoke(match {
                it.path.contains("sales") &&
                        it.operation == ReadOperation.QUERY &&
                        it.sortBy != null && it.sortBy!!.contains("Amount", ignoreCase = true)
            })
        }
    }

    @ParameterizedTest(name = "excelRead_cell[{index}] {0}")
    @ValueSource(strings = [
        "Покажи значение ячейки B5 в sales.xlsx",
        "Что в ячейке B5 файла sales.xlsx?",
        "Прочитай ячейку B5 из sales.xlsx"
    ])
    fun excelRead_cell(userPrompt: String) = runTest {
        val excelRead: ExcelRead = spyk(ExcelRead(filesUtil))
        val toolFindFiles: ToolFindFilesByName = spyk(ToolFindFilesByName(filesUtil))

        coEvery { toolFindFiles.suspendInvoke(any()) } returns "[\"/tmp/sales.xlsx\"]"
        coEvery { excelRead.invoke(any()) } returns "1500"

        runScenarioWithMocks(userPrompt) {
            bindSingleton<ExcelRead> { excelRead }
            bindSingleton<ToolFindFilesByName> { toolFindFiles }
        }
        coVerify(atLeast = 1) {
            excelRead.invoke(match {
                it.path.contains("sales") &&
                        it.operation == ReadOperation.CELL &&
                        it.range != null && it.range.contains("B5")
            })
        }
    }

    @ParameterizedTest(name = "excelRead_lookup[{index}] {0}")
    @ValueSource(strings = [
        "Найди цену товара Ноутбук в файле price.xlsx",
        "VLOOKUP: найди в price.xlsx цену для Ноутбук",
        "Посмотри в price.xlsx какая цена у товара Ноутбук"
    ])
    fun excelRead_lookup(userPrompt: String) = runTest {
        val excelRead: ExcelRead = spyk(ExcelRead(filesUtil))
        val toolFindFiles: ToolFindFilesByName = spyk(ToolFindFilesByName(filesUtil))

        coEvery { toolFindFiles.suspendInvoke(any()) } returns "[\"/tmp/price.xlsx\"]"
        coEvery { excelRead.invoke(any()) } returns "45000"

        runScenarioWithMocks(userPrompt) {
            bindSingleton<ExcelRead> { excelRead }
            bindSingleton<ToolFindFilesByName> { toolFindFiles }
        }
        coVerify(atLeast = 1) {
            excelRead.invoke(match {
                it.path.contains("price") &&
                        it.operation == ReadOperation.LOOKUP &&
                        it.lookupValue != null && it.lookupValue.contains("Ноутбук") &&
                        it.returnColumn != null
            })
        }
    }

    // ===================== ExcelReport Scenarios =====================

    @ParameterizedTest(name = "excelReport_newFile[{index}] {0}")
    @ValueSource(strings = [
        "Создай отчет report.xlsx с заголовками Имя, Телефон",
        "Сформируй файл report.xlsx с колонками Имя, Телефон",
        "Сделай новый отчет report.xlsx: Имя, Телефон"
    ])
    fun excelReport_newFile(userPrompt: String) = runTest {
        val excelReport: ExcelReport = spyk(ExcelReport(filesUtil))

        coEvery { excelReport.invoke(any()) } returns "Created report.xlsx"

        runScenarioWithMocks(userPrompt) {
            bindSingleton<ExcelReport> { excelReport }
        }
        coVerify(atLeast = 1) {
            excelReport.invoke(match {
                it.path.contains("report") &&
                        it.headers != null && it.headers!!.contains("Имя")
            })
        }
    }

    @ParameterizedTest(name = "excelReport_withData[{index}] {0}")
    @ValueSource(strings = [
        "Создай отчет stats.xlsx с данными: 2024-01-01, 100; 2024-01-02, 200",
        "Запиши в новый файл stats.xlsx данные: [[2024-01-01, 100], [2024-01-02, 200]]",
        "Сформируй stats.xlsx и добавь туда строки: 2024-01-01, 100"
    ])
    fun excelReport_withData(userPrompt: String) = runTest {
        val excelReport: ExcelReport = spyk(ExcelReport(filesUtil))

        coEvery { excelReport.invoke(any()) } returns "Created report stats.xlsx"

        runScenarioWithMocks(userPrompt) {
            bindSingleton<ExcelReport> { excelReport }
        }
        coVerify(atLeast = 1) {
            excelReport.invoke(match {
                it.path.contains("stats") &&
                        it.data != null && it.data!!.isNotEmpty()
            })
        }
    }
}
