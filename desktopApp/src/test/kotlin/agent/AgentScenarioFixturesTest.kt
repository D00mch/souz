package agent

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import ru.souz.llms.restJsonMapper
import ru.souz.tool.BadInputException
import ru.souz.tool.browser.ToolChromeInfo
import ru.souz.tool.browser.ToolSafariInfo
import ru.souz.tool.dataAnalytics.ToolCreatePlotFromCsv
import ru.souz.tool.dataAnalytics.excel.ExcelRead
import ru.souz.tool.dataAnalytics.excel.ExcelRead.ReadOperation.*
import ru.souz.tool.files.ToolModifyFile

class AgentScenarioFixturesTest {
    @Test
    fun `unread count accepts whole English and Russian words and the digit one`() {
        for (answer in listOf("Unread: **1**.", "Unread: ONE.", "Непрочитано: одно письмо.", "Всего: ОДИН.")) {
            assertOneUnreadMessage(answer)
        }
    }

    @Test
    fun `unread count rejects embedded words and unrelated message IDs`() {
        for (answer in listOf(
            "Done. Отчет за сегодня; Daily digest.",
            "Someone sent the daily digest.",
            "Одноразовый код; одиннадцать писем.",
            "неодно; неодин; oneписьмо; письмоone",
            "ID: 50101 | Отчет за сегодня; ID: 50002 | Daily digest.",
            "Unread: 11.",
            "Unread: 21.",
        )) {
            assertThrows<AssertionError>(answer) { assertOneUnreadMessage(answer) }
        }
    }

    @Test
    fun `browser metadata never returns page text and pageText resolves only the requested page`() {
        val page = ScenarioBrowserPage()
        ToolChromeInfo.InfoType.entries.filter { it != ToolChromeInfo.InfoType.pageText }.forEach {
            assertFalse(page.read(ToolChromeInfo.Input(it)).contains("пятницу"))
        }
        ToolSafariInfo.InfoType.entries.filter { it != ToolSafariInfo.InfoType.pageText }.forEach {
            assertFalse(page.read(ToolSafariInfo.Input(it)).contains("пятницу"))
        }
        assertEquals(page.url, page.read(ToolChromeInfo.Input(ToolChromeInfo.InfoType.currentTabUrl)))
        assertEquals(page.url, page.read(ToolSafariInfo.Input(ToolSafariInfo.InfoType.currentTabUrl)))
        assertEquals(0, page.pageTextCalls)
        assertTrue(page.read(ToolChromeInfo.Input(ToolChromeInfo.InfoType.pageText, url = " ")).startsWith("Error:"))
        assertTrue(page.read(ToolSafariInfo.Input(ToolSafariInfo.InfoType.pageText, url = "https://example.com/other")).startsWith("Error:"))
        assertEquals(0, page.pageTextCalls)
        assertEquals(page.body, page.read(ToolChromeInfo.Input(ToolChromeInfo.InfoType.pageText)))
        assertEquals(page.body, page.read(ToolSafariInfo.Input(ToolSafariInfo.InfoType.pageText, url = page.url)))
        assertEquals(2, page.pageTextCalls)
    }

    @Test
    fun `file search accepts trimmed partial names but rejects blank path and unrelated queries`() {
        val target = "~/books/100 ошибок в го.pdf"
        val files = ScenarioFiles(mapOf(target to ""), setOf("~/other"))
        val expected = listOf(ScenarioFiles.canonical(target))
        fun paths(query: String) = restJsonMapper.readTree(files.find("~", query)).map { it.asText() }
        assertEquals(expected, paths("100 ошибок в го"))
        assertEquals(expected, paths(" 100 ОШИБОК "))
        assertEquals(emptyList<String>(), paths("другой документ"))
        assertEquals("[]", files.find("~/other", "100 ошибок"))
        assertThrows<BadInputException> { files.find("", "100 ошибок") }
        assertThrows<BadInputException> { files.find("~", " ") }
    }

    @Test
    fun `file paths use local sandbox aliases and preserve ordinary relative names`() {
        val home = System.getProperty("user.home")
        assertEquals("$home/tmp/test.txt", ScenarioFiles.canonical("`file://$home/tmp/test.txt`"))
        assertEquals("$home/tmp/test.txt", ScenarioFiles.canonical(" \"~/tmp/test.txt\" "))
        assertEquals("$home/tmp/test.txt", ScenarioFiles.canonical("\$HOME/tmp/test.txt"))
        assertEquals(home, ScenarioFiles.canonical("home"))
        assertEquals("$home/home/tmp/test.txt", ScenarioFiles.canonical("home/tmp/test.txt"))
        assertEquals("$home/HOME/tmp/test.txt", ScenarioFiles.canonical("HOME/tmp/test.txt"))
        assertEquals("$home/books/test.txt", ScenarioFiles.canonical("books/test.txt"))
        assertEquals("/tmp/test.txt", ScenarioFiles.canonical("/tmp/test.txt"))
        assertThrows<BadInputException> { ScenarioFiles.canonical("\"\"") }
    }

    @Test
    fun `missing directories fail while empty parents survive deletions and moves`() {
        val files = ScenarioFiles(mapOf("~/source/test.txt" to "Hello"))
        assertThrows<BadInputException> { files.list("~/missing") }
        assertThrows<BadInputException> { files.find("~/missing", "test") }
        files.move("~/source/test.txt", "~/dest/test.txt")
        assertEquals("[]", files.list("~/source"))
        assertTrue(files.list("~/dest").contains("test.txt"))
        files.delete("~/dest/test.txt")
        assertEquals("[]", files.list("~/dest"))
        assertEquals("[]", files.find("~/dest", "test"))
    }

    @Test
    fun `deleted file disappears from repeated search and listing without deleting siblings`() {
        val files = ScenarioFiles(mapOf("~/tmp/test.txt" to "Hello", "~/tmp/keep.txt" to "Keep"))
        assertTrue(files.find("~", "test").contains("test.txt"))
        files.delete("\$HOME/tmp/test.txt")
        repeat(2) {
            assertEquals("[]", files.find("~", "test"))
            assertFalse(files.list("~/tmp").contains("test.txt"))
        }
        assertEquals("Keep", files.read("~/tmp/keep.txt"))
        assertThrows<BadInputException> { files.delete("~/tmp/test.txt") }
    }

    @Test
    fun `move updates source destination content and discovery`() {
        val files = ScenarioFiles(mapOf("~/README.md" to "Hello"), setOf("~/dest"))
        assertThrows<BadInputException> { files.move("~/README.md", "~/dest") }
        files.move("~/README.md", "~/dest/README.md")
        assertFalse(files.exists("~/README.md"))
        assertEquals("Hello", files.read("~/dest/README.md"))
        assertEquals(listOf(ScenarioFiles.canonical("~/dest/README.md")),
            restJsonMapper.readTree(files.find("~", "README")).map { it.asText() })
        assertThrows<BadInputException> { files.move("~/README.md", "~/dest/README.md") }
    }

    @Test
    fun `edit preserves original content and rejects absent or ambiguous matches`() {
        val files = ScenarioFiles(mapOf("~/test.txt" to "Hello"))
        val edit = ToolModifyFile.Input("~/test.txt", "Hello", "Hello\nWorld is over")
        files.edit(edit)
        assertEquals("Hello\nWorld is over", files.read("~/test.txt"))
        assertThrows<BadInputException> { files.edit(edit.copy(oldString = "missing")) }
        assertThrows<BadInputException> { files.edit(edit.copy(oldString = "")) }
        val ambiguous = ScenarioFiles(mapOf("~/test.txt" to "Hello Hello"))
        assertThrows<BadInputException> { ambiguous.edit(edit) }
        assertEquals("Hello Hello", ambiguous.read("~/test.txt"))
    }

    @Test
    fun `calendar dates derive from fixed clock and deletion changes future reads`() {
        val calendar = ScenarioCalendar()
        assertEquals("${SCENARIO_TODAY} 09:00 | Обзор проекта", calendar.list(""))
        val tomorrow = SCENARIO_TODAY.plusDays(1).toString()
        assertTrue(calendar.list(tomorrow).contains("10:00 | Важная встреча"))
        assertTrue(calendar.events.all { it.date in SCENARIO_TODAY..SCENARIO_TODAY.plusDays(6) })
        calendar.delete("Важная встреча")
        assertFalse(calendar.list(tomorrow).contains("Важная встреча"))
        assertTrue(calendar.list(tomorrow).contains("Командный синк"))
        assertThrows<BadInputException> { calendar.delete("Важная встреча") }
    }

    @Test
    fun `chart header discovery is read-only and absent columns cannot create a chart`() {
        val chart = ScenarioChart("~/sample.csv", listOf("Имя", "Возраст"))
        val inspect = ToolCreatePlotFromCsv.Input("~/sample.csv")
        repeat(2) { assertTrue(chart.plot(inspect).contains("Имя, Возраст")) }
        assertEquals(0, chart.created.size)
        assertThrows<BadInputException> { chart.plot(inspect.copy(xColumn = "Date", yColumn = "Amount")) }
        assertEquals(0, chart.created.size)
        chart.plot(inspect.copy(xColumn = "Имя", yColumn = "Возраст"))
        assertEquals(1, chart.created.size)
    }

    @Test
    fun `Excel discovery query ordering cell and lookup honor arguments`() {
        val structure = restJsonMapper.readTree(ScenarioExcel.read(ExcelRead.Input("~/sales.xlsx", STRUCTURE)))
        assertEquals(listOf("Date", "Amount"), structure["columns"].map { it.asText() })
        assertEquals(4, structure["rows"].asInt())
        val query = ExcelRead.Input("~/sales.xlsx", QUERY, filter = "Amount > 1000", sortBy = "Amount", sortOrder = "DESC")
        assertEquals("total: 2", ScenarioExcel.read(query))
        val totals = query.copy(column = "Amount", aggregation = "SUM", groupBy = "Date")
        assertEquals("2026-05-02: 2500\n2026-05-04: 1500", ScenarioExcel.read(totals))
        assertEquals("2026-05-04: 1500\n2026-05-02: 2500", ScenarioExcel.read(totals.copy(sortOrder = "ASC")))
        assertEquals("2026-05-02: 2500", ScenarioExcel.read(totals.copy(limit = 1)))
        assertEquals("1500", ScenarioExcel.read(ExcelRead.Input("~/sales.xlsx", CELL, range = "B5")))
        assertEquals("Date\tAmount\n2026-05-01\t500\n2026-05-02\t2500",
            ScenarioExcel.read(ExcelRead.Input("~/sales.xlsx", CELL, range = "A1:B3")))
        val price = ExcelRead.Input("~/price.xlsx", STRUCTURE)
        assertTrue(ScenarioExcel.read(price).contains("Товар"))
        val lookup = price.copy(operation = LOOKUP, lookupValue = "Ноутбук", lookupColumn = "Товар", returnColumn = "Цена")
        assertEquals("45000", ScenarioExcel.read(lookup))
        assertEquals("Not found: 'Телефон'", ScenarioExcel.read(lookup.copy(lookupValue = "Телефон")))
    }

    @Test
    fun `Excel invalid arguments produce useful errors instead of fabricated success`() {
        val query = ExcelRead.Input("sales.xlsx", QUERY)
        assertTrue(assertThrows<BadInputException> { ScenarioExcel.read(query.copy(filter = "Missing>1000")) }.message!!.contains("Available: Date, Amount"))
        assertThrows<BadInputException> { ScenarioExcel.read(query.copy(filter = "not a filter")) }
        assertThrows<BadInputException> { ScenarioExcel.read(query.copy(sortBy = "Amount", sortOrder = "sideways")) }
        assertThrows<BadInputException> { ScenarioExcel.read(query.copy(path = "missing.xlsx")) }
        assertThrows<BadInputException> { ScenarioExcel.read(query.copy(operation = LOOKUP)) }
    }

    @Test
    fun `fact assertions tolerate formatting but reject missing or wrong numeric results`() {
        assertAnswerFacts("Цена **Ноутбук**: 45 000 рублей", "Ноутбук", "45000")
        assertAnswerFacts("Цена 45\u202f000 рублей", "45000")
        assertAnswerFacts("Файлы: `read_me.txt`, **sample.csv**", "read_me.txt", "sample.csv")
        assertThrows<AssertionError> { assertAnswerFacts("readme.txt", "read_me.txt") }
        assertThrows<AssertionError> { assertAnswerFacts("Цена 145000", "45000") }
        assertThrows<AssertionError> { assertAnswerFacts("Готово", "1500") }
    }
    @Test
    fun `sales result assertions accept localized dates and ignore excluded-row explanations`() {
        val expected = listOf("2026-05-02" to 2500, "2026-05-04" to 1500)
        assertSalesRows("""
            | Date | Amount |
            | --- | ---: |
            | 2 мая 2026 | 2 500 |
            | 04.05.2026 | 1500.00 |
            Не вошли строки 2026-05-01 (500) и 2026-05-03 (750).
        """.trimIndent(), expected, ordered = true)
        assertSalesRows("| Дата | Сумма |\n| 4 May | 1500 |\n| 02/05/2026 | 2500 |", expected)
        assertSalesRows("| Date | Amount |\n| May 2, 2026 | 2,500 |\n| 4 мая | 1\u202f500,00 |", expected)
    }

    @Test
    fun `sales result assertions reject swapped values wrong rows duplicates and order`() {
        val expected = listOf("2026-05-02" to 2500, "2026-05-04" to 1500)
        val header = "| Date | Amount |\n"
        assertThrows<AssertionError> { assertSalesRows(header + "| 2 мая | 1500 |\n| 4 мая | 2500 |", expected) }
        assertThrows<AssertionError> { assertSalesRows(header + "| 2 мая | 2500 |\n| 1 мая | 500 |", expected) }
        assertThrows<AssertionError> { assertSalesRows(header + "| 2 мая | 2500 |\n| 4 мая | 1500 |\n| 4 мая | 1500 |", expected) }
        assertThrows<AssertionError> { assertSalesRows(header + "| 4 мая | 1500 |\n| 2 мая | 2500 |", expected, ordered = true) }
        assertThrows<AssertionError> { assertSalesRows("2 мая 2026: 2500, 4 мая 2026: 1500", expected) }
        assertThrows<AssertionError> { assertSalesRows(header + "| 2 мая | 2500 |\n| 4 мая | 1500 |\n| bogus | 900 |", expected) }
    }

}
