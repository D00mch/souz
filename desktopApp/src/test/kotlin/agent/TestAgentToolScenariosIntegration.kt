package agent

import io.mockk.*
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Test
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import ru.souz.llms.LLMModel
import ru.souz.llms.LLMToolSetup
import ru.souz.llms.giga.toGiga
import ru.souz.runtime.files.FilesToolUtil
import ru.souz.tool.ToolRunBashCommand
import ru.souz.tool.application.ToolOpen
import ru.souz.tool.application.ToolShowApps
import ru.souz.tool.browser.*
import ru.souz.tool.calendar.ToolCalendarCreateEvent
import ru.souz.tool.calendar.ToolCalendarDeleteEvent
import ru.souz.tool.calendar.ToolCalendarListEvents
import ru.souz.tool.dataAnalytics.ToolCreatePlotFromCsv
import ru.souz.tool.dataAnalytics.excel.ExcelRead
import ru.souz.tool.dataAnalytics.excel.ExcelReport
import ru.souz.tool.files.*
import ru.souz.tool.mail.ToolMailListMessages
import ru.souz.tool.mail.ToolMailSearch
import ru.souz.tool.mail.ToolMailSendNewMessage
import ru.souz.tool.mail.ToolMailUnreadMessagesCount
import ru.souz.tool.notes.ToolCreateNote
import ru.souz.tool.notes.ToolDeleteNote
import ru.souz.tool.notes.ToolListNotes
import ru.souz.tool.notes.ToolSearchNotes
import ru.souz.tool.textReplace.ToolGetClipboard

/**
 * Integration tests for tool invocation scenarios across graph agent implementations.
 * Every scenario discovers the full compiled-tool catalog and pinned file-backed Skills.
 * Scenario mocks implement operations; other tools and Skill commands return explicit fixture errors.
 * Checks cover completed answers, arguments, and resulting state.
 * Excel discovery/cell/lookup cases exercise the named operation; query/sort allow any correct read-only solution.
 * Set [SOUZ_AGENT_INTEGRATION_TESTS_ON] to `true` before running these integration tests.
 * Select `graph` (default) or `skills` with [SOUZ_AGENT_INTEGRATION_TEST_AGENT].
 * Override the model with [SOUZ_AGENT_INTEGRATION_TEST_MODEL].
 * For a compatible endpoint, select `openai-compatible-custom` and set `OPENAI_MODEL`,
 * `OPENAI_BASE_URL`, and `OPENAI_API_KEY`. [SOUZ_AGENT_INTEGRATION_TEST_REASONING_EFFORT]
 * sets `reasoning_effort` on compatible chat requests, including classification.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class GraphAgentToolScenariosIntegrationTest {

    private val selectedModel = scenarioIntegrationModel(LLMModel.AnthropicHaiku45)
    private val support = AgentScenarioTestSupport(selectedModel)
    private val runTest = support::runTest
    private val filesUtil: FilesToolUtil
        get() = support.filesUtil

    @BeforeEach
    fun checkEnvironment() = support.checkEnvironment()

    @AfterEach
    fun clearMocks() {
        clearAllMocks(answers = false)
        unmockkAll()
    }

    @AfterAll
    fun finish() = support.finish()

    private suspend fun runScenarioWithMocks(
        userPrompt: String,
        mockedTools: List<LLMToolSetup>,
        useFewShotExamples: Boolean = true,
    ) = support.runScenarioWithMocks(userPrompt, mockedTools, useFewShotExamples)

    @ParameterizedTest(name = "scenario1_launchApplication[{index}] {0}")
    @ValueSource(
        strings = [
            "Запусти Telegram",
            "Открой приложение Telegram",
            "Открой Телеграм"
        ]
    )
    fun scenario1_launchApplication(userPrompt: String) = runTest {
        val realToolShowApps = ToolShowApps(filesUtil)
        val testGetApps: ToolShowApps = spyk(realToolShowApps)

        val realToolOpen = ToolOpen(ToolRunBashCommand, filesUtil)
        val testOpenApp: ToolOpen = spyk(realToolOpen)

        coEvery { testGetApps.invoke(any(), any()) } returns """
            [{"app-bundle-id":"ru.keepcoder.Telegram","app-name":"Telegram"}]
        """.trimIndent()

        coEvery { testOpenApp.invoke(any(), any()) } returns "Opened"

        runScenarioWithMocks(userPrompt, listOf(testGetApps.toGiga(), testOpenApp.toGiga()))

        coVerify(exactly = 1) {
            testOpenApp.invoke(match { it.target.contains("ru.keepcoder.Telegram", ignoreCase = true) }, any())
        }
        coVerify(exactly = 1) { testOpenApp.invoke(any(), any()) }
    }

    @ParameterizedTest(name = "scenario2_openWebsite[{index}] {0}")
    @ValueSource(
        strings = [
            "Открой сайт https://example.com",
            "Открой example dot com",
            "Открой в бразуере example точка com",
        ]
    )
    fun scenario2_openWebsite(userPrompt: String) = runTest {
        val realTool = ToolOpenDefaultBrowser(ToolRunBashCommand, filesUtil)
        val toolOpenDefaultBrowser: ToolOpenDefaultBrowser = spyk(realTool)

        val realToolOpen = ToolOpen(ToolRunBashCommand, filesUtil)
        val toolOpen: ToolOpen = spyk(realToolOpen)

        val realToolTab = ToolCreateNewBrowserTab(ToolRunBashCommand)
        val toolCreateNewBrowserTab: ToolCreateNewBrowserTab = spyk(realToolTab)

        var openCalls = 0

        val openTargets = mutableListOf<String>()
        val createNewTabUrls = mutableListOf<String>()

        coEvery { toolOpenDefaultBrowser.invoke(any(), any()) } answers {
            openCalls++
            "Browser opened"
        }
        coEvery { toolOpen.invoke(any(), any()) } answers {
            openCalls++
            openTargets += firstArg<ToolOpen.Input>().target
            "Opened"
        }
        coEvery { toolCreateNewBrowserTab.invoke(any(), any()) } answers {
            openCalls++
            createNewTabUrls += firstArg<ToolCreateNewBrowserTab.Input>().url
            "Tab opened"
        }

        runScenarioWithMocks(
            userPrompt,
            listOf(toolOpenDefaultBrowser.toGiga(), toolOpen.toGiga(), toolCreateNewBrowserTab.toGiga()),
        )
        assertEquals(listOf("example.com"), (openTargets + createNewTabUrls).map { java.net.URI(it).host },
            "Opening a browser without navigating does not fulfill the request")
        assertEquals(
            1,
            openCalls,
            "Expected exactly one tool call among OpenDefaultBrowser/Open/CreateNewBrowserTab, but got $openCalls"
        )
    }

    @ParameterizedTest(name = "scenario3_openWebsiteInNewTab[{index}] {0}")
    @ValueSource(
        strings = [
            "Открой в новой вкладке сайт https://example.com",
            "Создай новую вкладку с example точка com",
            "Открой example dot com в отдельной вкладке браузера",
        ]
    )
    fun scenario3_openWebsiteInNewTab(userPrompt: String) = runTest {
        val realTool = ToolCreateNewBrowserTab(ToolRunBashCommand)
        val toolCreateNewBrowserTab: ToolCreateNewBrowserTab = spyk(realTool)

        coEvery { toolCreateNewBrowserTab.invoke(any(), any()) } returns "Tab opened"

        runScenarioWithMocks(userPrompt, listOf(toolCreateNewBrowserTab.toGiga()))
        coVerify(exactly = 1) {
            toolCreateNewBrowserTab.invoke(match { it.url.contains("example.com") }, any())
        }
        coVerify(exactly = 1) { toolCreateNewBrowserTab.invoke(any(), any()) }
    }

    @ParameterizedTest(name = "scenario4_findSiteInHistory[{index}] {0}")
    @ValueSource(
        strings = [
            "Найди в истории браузера сайт example",
            "Проверь историю и открой сайт example com",
            "Посмотри, был ли в истории example.com",
        ]
    )
    fun scenario4_findSiteInHistory(userPrompt: String) = runTest {
        mockkStatic("ru.souz.tool.browser.DefaultBrowserKt")
        every { ToolRunBashCommand.detectDefaultBrowser() } returns BrowserType.CHROME

        val toolChromeInfo: ToolChromeInfo = spyk(ToolChromeInfo(ToolRunBashCommand))
        val toolSafariInfo: ToolSafariInfo = spyk(ToolSafariInfo(ToolRunBashCommand))
        val toolCreateNewBrowserTab: ToolCreateNewBrowserTab = spyk(ToolCreateNewBrowserTab(ToolRunBashCommand))

        coEvery { toolChromeInfo.invoke(any(), any()) } returns "2026-01-01|https://example.com|Example Domain"
        coEvery { toolSafariInfo.invoke(any(), any()) } returns ""
        coEvery { toolCreateNewBrowserTab.invoke(any(), any()) } returns "Opened"

        try {
            val answer = runScenarioWithMocks(
                userPrompt,
                listOf(toolChromeInfo.toGiga(), toolSafariInfo.toGiga(), toolCreateNewBrowserTab.toGiga()),
            )
            assertAnswerFacts(answer, "example.com")
            coVerify(atLeast = 1) {
                toolChromeInfo.invoke(match { it.type == ToolChromeInfo.InfoType.history }, any())
            }
            if (userPrompt.contains("открой")) {
                coVerify(exactly = 1) { toolCreateNewBrowserTab.invoke(match { java.net.URI(it.url).host == "example.com" }, any()) }
            } else {
                coVerify(exactly = 0) { toolCreateNewBrowserTab.invoke(any(), any()) }
            }
        } finally {
            unmockkStatic("ru.souz.tool.browser.DefaultBrowserKt")
        }
    }

    @ParameterizedTest(name = "scenario5_readPageInOpenTab[{index}] {0}")
    @ValueSource(
        strings = [
            "Прочитай содержимое текущей открытой вкладки",
            "Покажи текст страницы в активной вкладке",
            "Извлеки текст из открытой вкладки браузера",
        ]
    )
    fun scenario5_readPageInOpenTab(userPrompt: String) = runTest {
        val page = ScenarioBrowserPage()
        val safari = spyk(ToolSafariInfo(ToolRunBashCommand))
        val chrome = spyk(ToolChromeInfo(ToolRunBashCommand))
        coEvery { safari.invoke(any(), any()) } answers { page.read(firstArg<ToolSafariInfo.Input>()) }
        coEvery { chrome.invoke(any(), any()) } answers { page.read(firstArg<ToolChromeInfo.Input>()) }
        val answer = runScenarioWithMocks(userPrompt, listOf(safari.toGiga(), chrome.toGiga()))
        assertAnswerFacts(answer, "Орион", "пятниц")
        assertTrue(page.pageTextCalls >= 1, "Read the active page body through pageText; metadata alone is insufficient")
    }

    @ParameterizedTest(name = "scenario6_todayCalendarEvents[{index}] {0}")
    @ValueSource(
        strings = [
            "Покажи сегодняшние события в календаре",
            "Какие у меня события на сегодня в календаре?",
            "Выведи список дел из календаря на сегодня",
        ]
    )
    fun scenario6_todayCalendarEvents(userPrompt: String) = runTest {
        val calendar = ScenarioCalendar()
        val tool = spyk(ToolCalendarListEvents())
        coEvery { tool.invoke(any(), any()) } answers { calendar.list(firstArg<ToolCalendarListEvents.Input>().date) }
        val answer = runScenarioWithMocks(userPrompt, listOf(tool.toGiga()))
        coVerify(atLeast = 1) { tool.invoke(match { it.date.isBlank() || it.date == SCENARIO_TODAY.toString() }, any()) }
        assertAnswerFacts(answer, "Обзор проекта")
        assertTrue(Regex("(?<!\\d)0?9[:.]00(?!\\d)").containsMatchIn(answer), answer)
    }

    @ParameterizedTest(name = "scenario7_createCalendarEvent[{index}] {0}")
    @ValueSource(
        strings = [
            "Создай событие в календаре: встреча завтра в 10:00",
            "Добавь в календарь встречу завтра на десять ноль ноль",
            "Запланируй событие \"встреча\" на завтра в 10 утра",
        ]
    )
    fun scenario7_createCalendarEvent(userPrompt: String) = runTest {
        val tool = spyk(ToolCalendarCreateEvent(ToolRunBashCommand))
        val events = mutableListOf<ToolCalendarCreateEvent.Input>()
        coEvery { tool.invoke(any(), any()) } answers {
            events += firstArg<ToolCalendarCreateEvent.Input>()
            "Event created: ${events.last().title}, ${events.last().startDateTime}"
        }
        runScenarioWithMocks(userPrompt, listOf(tool.toGiga()))
        assertEquals(1, events.size, "Create exactly one event")
        assertTrue(events.single().title.contains("встреч", ignoreCase = true))
        assertEquals(SCENARIO_TODAY.plusDays(1).atTime(10, 0),
            LocalDateTime.parse(events.single().startDateTime, DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")))
    }

    @ParameterizedTest(name = "scenario8_deleteCalendarEvent[{index}] {0}")
    @ValueSource(
        strings = [
            "Удали событие из календаря на завтра в 10:00",
            "Найди и удали событие завтра в 10:00",
            "Удалить встречу в календаре завтра в 10 утра",
        ]
    )
    fun scenario8_deleteCalendarEvent(userPrompt: String) = runTest {
        val calendar = ScenarioCalendar()
        val before = calendar.events
        val list = spyk(ToolCalendarListEvents())
        val create = spyk(ToolCalendarCreateEvent(ToolRunBashCommand))
        val delete = spyk(ToolCalendarDeleteEvent(ToolRunBashCommand))
        coEvery { list.invoke(any(), any()) } answers { calendar.list(firstArg<ToolCalendarListEvents.Input>().date) }
        coEvery { create.invoke(any(), any()) } returns "Error: creation was not requested"
        coEvery { delete.invoke(any(), any()) } answers { calendar.delete(firstArg<ToolCalendarDeleteEvent.Input>().title) }
        runScenarioWithMocks(userPrompt, listOf(list.toGiga(), create.toGiga(), delete.toGiga()))
        coVerify(atLeast = 1) { list.invoke(match { it.date == SCENARIO_TODAY.plusDays(1).toString() }, any()) }
        coVerify(exactly = 1) { delete.invoke(any(), any()) }
        coVerify(exactly = 0) { create.invoke(any(), any()) }
        assertEquals(before.filterNot { it.title == "Важная встреча" }, calendar.events)
    }

    @ParameterizedTest(name = "scenario9_findCalendarEvent[{index}] {0}")
    @ValueSource(
        strings = [
            "Найди события в календаре на эту неделю",
            "Покажи события в календаре на текущую неделю",
            "Поищи в календаре встречи на этой неделе",
        ]
    )
    fun scenario9_findCalendarEvent(userPrompt: String) = runTest {
        val calendar = ScenarioCalendar()
        val before = calendar.events
        val list = spyk(ToolCalendarListEvents())
        val create = spyk(ToolCalendarCreateEvent(ToolRunBashCommand))
        val delete = spyk(ToolCalendarDeleteEvent(ToolRunBashCommand))
        val queriedDates = mutableListOf<String>()
        coEvery { list.invoke(any(), any()) } answers {
            firstArg<ToolCalendarListEvents.Input>().date.let { queriedDates += it; calendar.list(it) }
        }
        coEvery { create.invoke(any(), any()) } returns "Error: creation was not requested"
        coEvery { delete.invoke(any(), any()) } returns "Error: deletion was not requested"
        val answer = runScenarioWithMocks(userPrompt, listOf(list.toGiga(), create.toGiga(), delete.toGiga()))
        assertTrue((0L..6L).all { SCENARIO_TODAY.plusDays(it).toString() in queriedDates.map { date -> date.ifBlank { SCENARIO_TODAY.toString() } } },
            "A week requires reading all seven dates: $queriedDates")
        assertAnswerFacts(answer, *before.map { it.title }.toTypedArray())
        coVerify(exactly = 0) { create.invoke(any(), any()); delete.invoke(any(), any()) }
        assertEquals(before, calendar.events)
    }

    @ParameterizedTest(name = "scenario11_buildChartFromFile[{index}] {0}")
    @ValueSource(
        strings = [
            "Построй график возраста по имени из файла sample.csv по пути home/tmp/test-data",
            "Сделай график из ~/tmp/test-data/sample.csv по полям имя и возраст",
            "Построй chart по sample.csv из папки ~/tmp/test-data",
        ]
    )
    fun scenario11_buildChartFromFile(userPrompt: String) = runTest {
        val plot = spyk(ToolCreatePlotFromCsv(filesUtil))
        val list = spyk(ToolListFiles(filesUtil))
        val read = spyk(ToolExtractText(filesUtil))
        val chart = ScenarioChart("~/tmp/test-data/sample.csv", listOf("Имя", "Возраст"))
        coEvery { list.invoke(any(), any()) } returns "[\"~/tmp/test-data/sample.csv\"]"
        coEvery { read.invoke(any(), any()) } returns "Имя,Возраст\nАнна,30\nБорис,42"
        coEvery { plot.invoke(any(), any()) } answers { chart.plot(firstArg()) }
        runScenarioWithMocks(userPrompt, listOf(plot.toGiga(), list.toGiga(), read.toGiga()))
        assertEquals(1, chart.created.size, "Create exactly one chart; header discovery may repeat")
        assertEquals("имя", chart.created.single().xColumn?.lowercase())
        assertEquals("возраст", chart.created.single().yColumn?.lowercase())
        assertEquals(ScenarioFiles.canonical("~/tmp/test-data/sample.csv"), ScenarioFiles.canonical(chart.created.single().path))
    }

    @Test
    fun scenario11_missingChartColumns() = runTest {
        val plot = spyk(ToolCreatePlotFromCsv(filesUtil))
        val read = spyk(ToolExtractText(filesUtil))
        val chart = ScenarioChart("~/sample.csv", listOf("Date", "Amount"))
        coEvery { read.invoke(any(), any()) } returns "Date,Amount\n2026-06-01,1500"
        coEvery { plot.invoke(any(), any()) } answers { chart.plot(firstArg()) }
        val answer = runScenarioWithMocks(
            "Построй график возраста по имени из ~/sample.csv. Если нужных колонок нет, сообщи об этом и не создавай другой график.",
            listOf(plot.toGiga(), read.toGiga()),
        )
        assertEquals(0, chart.created.size, "Do not substitute a Date/Amount chart for missing name/age data")
        assertAnswerFacts(answer, "Date", "Amount")
        assertTrue(Regex("нет|отсутств|не найден|невозмож|missing|unavailable", RegexOption.IGNORE_CASE).containsMatchIn(answer), answer)
    }

    @ParameterizedTest(name = "scenario12_findFileByName[{index}] {0}")
    @ValueSource(
        strings = [
            "Найди файл по имени 100 ошибок в го",
            "Найди документ с названием 100 ошибок в го",
            "Поищи файл \"100 ошибок в го\"",
        ]
    )
    fun scenario12_findFileByName(userPrompt: String) = runTest {
        val path = "~/books/100 ошибок в го.pdf"
        val files = ScenarioFiles(mapOf(path to ""))
        val find = spyk(ToolFindFilesByName(filesUtil))
        var foundTarget = false
        coEvery { find.suspendInvoke(any(), any()) } answers {
            firstArg<ToolFindFilesByName.Input>().let { files.find(it.path, it.fileName) }.also {
                foundTarget = foundTarget || it.contains(ScenarioFiles.canonical(path))
            }
        }
        val answer = runScenarioWithMocks(userPrompt, listOf(find.toGiga()))
        assertTrue(foundTarget, "A successful search must return the target file; partial names are valid")
        assertAnswerFacts(answer, "100 ошибок в го", ".pdf", "books/")
    }

    @ParameterizedTest(name = "scenario13_listFilesInFolder[{index}] {0}")
    @ValueSource(
        strings = [
            "Покажи список файлов в папке ~/tmp/test-data",
            "Перечисли файлы в директории HOME/tmp/test-data",
            "Что лежит в home slash tmp slash test-data",
        ]
    )
    fun scenario13_listFilesInFolder(userPrompt: String) = runTest {
        val path = "~/tmp/test-data"
        val files = ScenarioFiles(listOf("test.txt", "read_me.txt", "sample.csv").associate { "$path/$it" to "" })
        val list = spyk(ToolListFiles(filesUtil))
        coEvery { list.invoke(any(), any()) } answers { files.list(firstArg<ToolListFiles.Input>().path) }
        val answer = runScenarioWithMocks(userPrompt, listOf(list.toGiga()))
        assertAnswerFacts(answer, "test.txt", "read_me.txt", "sample.csv")
        coVerify(atLeast = 1) { list.invoke(match { ScenarioFiles.canonical(it.path) == ScenarioFiles.canonical(path) }, any()) }
    }

    @ParameterizedTest(name = "scenario14_createFile[{index}] {0}")
    @ValueSource(
        strings = [
            "В папке home/tmp/test-data создай файл test_integration.txt с текстом Hello",
            "Создай ~/tmp/test-data/test_integration.txt и запиши Hello",
            "Нужен файл test_integration.txt в ~/tmp/test-data с содержимым Hello",
        ]
    )
    fun scenario14_createFile(userPrompt: String) = runTest {
        val files = ScenarioFiles(emptyMap())
        val tool = spyk(ToolNewFile(filesUtil))
        coEvery { tool.invoke(any(), any()) } answers {
            firstArg<ToolNewFile.Input>().let { files.create(it.path, it.text) }
        }
        runScenarioWithMocks(userPrompt, listOf(tool.toGiga()))
        coVerify(exactly = 1) { tool.invoke(any(), any()) }
        assertEquals(mapOf(ScenarioFiles.canonical("~/tmp/test-data/test_integration.txt") to "Hello"), files.snapshot())
    }

    @ParameterizedTest(name = "scenario14_readFile[{index}] {0}")
    @ValueSource(
        strings = [
            "Прочитай файл test_integration.txt в папке ~/tmp/test-data",
            "Открой и прочитай home/tmp/test-data/test_integration.txt",
            "Покажи содержимое файла test_integration.txt из home tmp test-data",
        ]
    )
    fun scenario14_readFile(userPrompt: String) = runTest {
        val path = "~/tmp/test-data/test_integration.txt"
        val files = ScenarioFiles(mapOf(path to "Hello"))
        val read = spyk(ToolExtractText(filesUtil))
        val find = spyk(ToolFindFilesByName(filesUtil))
        coEvery { read.invoke(any(), any()) } answers { files.read(firstArg<ToolExtractText.Input>().filePath) }
        coEvery { find.suspendInvoke(any(), any()) } answers {
            firstArg<ToolFindFilesByName.Input>().let { files.find(it.path, it.fileName) }
        }
        val answer = runScenarioWithMocks(userPrompt, listOf(read.toGiga(), find.toGiga()))
        assertAnswerFacts(answer, "Hello")
        coVerify(atLeast = 1) { read.invoke(match { ScenarioFiles.canonical(it.filePath) == ScenarioFiles.canonical(path) }, any()) }
    }

    @ParameterizedTest(name = "scenario14_modifyFile[{index}] {0}")
    @ValueSource(
        strings = [
            "Измени файл test_integration добавь новую строку World is over",
            "В файл test_integration добавь строку World is over",
            "Допиши в test_integration текст World is over новой строкой",
        ]
    )
    fun scenario14_modifyFile(userPrompt: String) = runTest {
        val path = "~/test_integration.txt"
        val files = ScenarioFiles(mapOf(path to "Hello"))
        val edit = spyk(ToolModifyFile(filesUtil))
        val find = spyk(ToolFindFilesByName(filesUtil))
        val read = spyk(ToolExtractText(filesUtil))
        coEvery { find.suspendInvoke(any(), any()) } answers {
            firstArg<ToolFindFilesByName.Input>().let { files.find(it.path, it.fileName) }
        }
        coEvery { read.invoke(any(), any()) } answers { files.read(firstArg<ToolExtractText.Input>().filePath) }
        coEvery { edit.invoke(any(), any()) } answers { files.edit(firstArg()) }
        runScenarioWithMocks(userPrompt, listOf(read.toGiga(), edit.toGiga(), find.toGiga()))
        coVerify(exactly = 1) { edit.invoke(any(), any()) }
        assertEquals("Hello\nWorld is over", files.read(path).trimEnd(), "Preserve the original and append the requested line exactly once")
    }

    @ParameterizedTest(name = "scenario14_deleteFile[{index}] {0}")
    @ValueSource(
        strings = [
            "Удали файл test_integration.txt в папке ~/tmp/test-data",
            "Удали HOME/tmp/test-data/test_integration.txt",
            "Нужно удалить файл test_integration.txt из home slash tmp slash test-data",
        ]
    )
    fun scenario14_deleteFile(userPrompt: String) = runTest {
        val path = "~/tmp/test-data/test_integration.txt"
        val files = ScenarioFiles(mapOf(path to "Hello", "~/tmp/test-data/keep.txt" to "Keep"))
        val delete = spyk(ToolDeleteFile(filesUtil))
        val find = spyk(ToolFindFilesByName(filesUtil))
        val list = spyk(ToolListFiles(filesUtil))
        coEvery { delete.invoke(any(), any()) } answers { files.delete(firstArg<ToolDeleteFile.Input>().path) }
        coEvery { find.suspendInvoke(any(), any()) } answers {
            firstArg<ToolFindFilesByName.Input>().let { files.find(it.path, it.fileName) }
        }
        coEvery { list.invoke(any(), any()) } answers { files.list(firstArg<ToolListFiles.Input>().path) }
        runScenarioWithMocks(userPrompt, listOf(delete.toGiga(), find.toGiga(), list.toGiga()))
        coVerify(exactly = 1) { delete.invoke(any(), any()) }
        assertEquals(mapOf(ScenarioFiles.canonical("~/tmp/test-data/keep.txt") to "Keep"), files.snapshot())
        assertFalse(files.exists(path))
    }

    @ParameterizedTest(name = "scenario15_moveFile[{index}] {0}")
    @ValueSource(
        strings = [
            "Перенеси файл README в папку dest",
            "Перемести read me в директорию dest",
            "Сделай move файла readme в папку dest",
        ]
    )
    fun scenario15_moveFile(userPrompt: String) = runTest {
        val files = ScenarioFiles(mapOf("~/README.md" to "Read me", "~/sample.csv" to "Name,Age"), setOf("~/dest"))
        val move = spyk(ToolMoveFile(filesUtil))
        val list = spyk(ToolListFiles(filesUtil))
        val find = spyk(ToolFindFilesByName(filesUtil))
        coEvery { move.invoke(any(), any()) } answers {
            firstArg<ToolMoveFile.Input>().let { files.move(it.sourcePath, it.destinationPath) }
        }
        coEvery { list.invoke(any(), any()) } answers { files.list(firstArg<ToolListFiles.Input>().path) }
        coEvery { find.suspendInvoke(any(), any()) } answers {
            firstArg<ToolFindFilesByName.Input>().let { files.find(it.path, it.fileName) }
        }
        runScenarioWithMocks(userPrompt, listOf(move.toGiga(), list.toGiga(), find.toGiga()))
        coVerify(exactly = 1) { move.invoke(any(), any()) }
        assertEquals(mapOf(ScenarioFiles.canonical("~/dest/README.md") to "Read me",
            ScenarioFiles.canonical("~/sample.csv") to "Name,Age"), files.snapshot())
    }

    @ParameterizedTest(name = "scenario16_extractTextFromFile[{index}] {0}")
    @ValueSource(
        strings = [
            "Извлеки текст из файла ~/tmp/test.txt",
            "Достань текстовое содержимое файла home tmp slash test.txt",
            "Прочитай и извлеки текст из test.txt по пути home slash tmp",
        ]
    )
    fun scenario16_extractTextFromFile(userPrompt: String) = runTest {
        val path = "~/tmp/test.txt"
        val files = ScenarioFiles(mapOf(path to "Test content\nСтрока для проверки извлечения текста."))
        val read = spyk(ToolExtractText(filesUtil))
        coEvery { read.invoke(any(), any()) } answers { files.read(firstArg<ToolExtractText.Input>().filePath) }
        val answer = runScenarioWithMocks(userPrompt, listOf(read.toGiga()))
        assertAnswerFacts(answer, "Test content", "Строка для проверки извлечения текста")
        coVerify(atLeast = 1) { read.invoke(match { ScenarioFiles.canonical(it.filePath) == ScenarioFiles.canonical(path) }, any()) }
    }

    @ParameterizedTest(name = "scenario17_readPdfPageByPage[{index}] {0}")
    @ValueSource(
        strings = [
            "Прочитай первую страницу PDF файла sample",
            "Открой PDF sample и прочитай страницу 1",
            "Считай первую страницу из файла sample.pdf",
        ]
    )
    fun scenario17_readPdfPageByPage(userPrompt: String) = runTest {
        val realTool = ToolReadPdfPages(filesUtil)
        val toolReadPdfPages: ToolReadPdfPages = spyk(realTool)
        val toolListFiles: ToolListFiles = spyk(ToolListFiles(filesUtil))
        val toolFindFiles: ToolFindFilesByName = spyk(ToolFindFilesByName(filesUtil))

        coEvery { toolFindFiles.suspendInvoke(any(), any()) } returns "[\"~/sample.pdf\"]"
        coEvery { toolListFiles.invoke(any(), any()) } returns "[\"sample.pdf\"]"
        coEvery { toolReadPdfPages.invoke(any(), any()) } returns "Page 1 content"

        val answer = runScenarioWithMocks(
            userPrompt,
            listOf(toolReadPdfPages.toGiga(), toolListFiles.toGiga(), toolFindFiles.toGiga()),
        )
        assertAnswerFacts(answer, "Page 1 content")
        coVerify(atLeast = 1) {
            toolReadPdfPages.invoke(match { it.filePath.contains("sample") && it.startPage == 1 }, any())
        }
    }

    @ParameterizedTest(name = "scenario18_openFile[{index}] {0}")
    @ValueSource(
        strings = [
            "Открой файл ~/tmp/read_me.txt",
            "Открой документ read_me.txt из home slash tmp",
            "Запусти файл HOME /tmp/read_me.txt",
        ]
    )
    fun scenario18_openFile(userPrompt: String) = runTest {
        val realTool = ToolOpen(ToolRunBashCommand, filesUtil)
        val toolOpen: ToolOpen = spyk(realTool)

        coEvery { toolOpen.invoke(any(), any()) } returns "Opened"

        runScenarioWithMocks(userPrompt, listOf(toolOpen.toGiga()))
        coVerify(exactly = 1) {
            toolOpen.invoke(match { it.target.contains("read_me.txt") }, any())
        }
        coVerify(exactly = 1) { toolOpen.invoke(any(), any()) }
    }

    @ParameterizedTest(name = "scenario19_notesFindCreateDeleteList[{index}] {0}")
    @ValueSource(
        strings = [
            "Создай заметку \"тест интеграции\", перечисли заметки, найди заметку тест, удали заметку тест интеграции",
            "Сделай заметку тест интеграции, покажи список заметок, найди тест, затем удали тест интеграции",
            "Работаем с заметками. Добавь заметку \"тест интеграции\", покажи список заметок, найди ее и удали",
        ]
    )
    fun scenario19_notesFindCreateDeleteList(userPrompt: String) = runTest {
        val toolCreateNote: ToolCreateNote = spyk(ToolCreateNote(ToolRunBashCommand))
        val toolListNotes: ToolListNotes = spyk(ToolListNotes(ToolRunBashCommand))
        val toolSearchNotes: ToolSearchNotes = spyk(ToolSearchNotes(ToolRunBashCommand))
        val toolDeleteNote: ToolDeleteNote = spyk(ToolDeleteNote(ToolRunBashCommand))

        val noteTitle = "тест интеграции"
        val notes = mutableListOf("Личная заметка")
        var foundRequestedNote = false
        coEvery { toolCreateNote.invoke(any(), any()) } answers {
            notes += firstArg<ToolCreateNote.Input>().noteText.lineSequence().first().trim()
            "Created"
        }
        coEvery { toolDeleteNote.invoke(any(), any()) } answers {
            val title = firstArg<ToolDeleteNote.Input>().noteName
            if (notes.removeAll { it.equals(title, ignoreCase = true) }) "Deleted" else "Error: Note not found: $title"
        }
        coEvery { toolListNotes.invoke(any(), any()) } answers { notes.joinToString("\n") }
        coEvery { toolSearchNotes.invoke(any(), any()) } answers {
            val query = firstArg<ToolSearchNotes.Input>().query
            val matches = notes.filter { query.isNotBlank() && it.contains(query, ignoreCase = true) }
            foundRequestedNote = foundRequestedNote || matches.any { it.equals(noteTitle, ignoreCase = true) }
            matches.joinToString("\n").ifEmpty { "No matches" }
        }

        runScenarioWithMocks(
            userPrompt,
            listOf(
                toolCreateNote.toGiga(),
                toolListNotes.toGiga(),
                toolSearchNotes.toGiga(),
                toolDeleteNote.toGiga(),
            ),
        )
        coVerify(exactly = 1) { toolCreateNote.invoke(any(), any()) }
        coVerify(exactly = 1) { toolCreateNote.invoke(match { it.noteText.trim().equals(noteTitle, ignoreCase = true) }, any()) }
        coVerify(atLeast = 1) { toolListNotes.invoke(any(), any()) }
        coVerify(atLeast = 1) { toolSearchNotes.invoke(match { it.query.isNotBlank() && noteTitle.contains(it.query, ignoreCase = true) }, any()) }
        coVerify(exactly = 1) { toolDeleteNote.invoke(any(), any()) }
        coVerify(exactly = 1) { toolDeleteNote.invoke(match { it.noteName.equals(noteTitle, ignoreCase = true) }, any()) }
        coVerifyOrder {
            toolCreateNote.invoke(any(), any())
            toolListNotes.invoke(any(), any())
            toolSearchNotes.invoke(any(), any())
            toolDeleteNote.invoke(any(), any())
        }
        assertTrue(foundRequestedNote, "Search must return the requested note before deletion")
        assertEquals(listOf("Личная заметка"), notes, "Delete only the created note")
    }

    @ParameterizedTest(name = "scenario20_mailFindUnreadListReply[{index}] {0}")
    @ValueSource(
        strings = [
            "Сколько непрочитанных писем? Перечисли последние письма. Найди письмо от сегодня.",
            "Покажи число непрочитанных писем и перечисли последние письма.",
            "Проверь unread письма и выведи список последних писем.",
        ]
    )
    fun scenario20_mailFindUnreadListReply(userPrompt: String) = runTest {
        val toolMailUnreadMessagesCount: ToolMailUnreadMessagesCount =
            spyk(ToolMailUnreadMessagesCount(ToolRunBashCommand))
        val toolMailListMessages: ToolMailListMessages = spyk(ToolMailListMessages(ToolRunBashCommand))
        val toolMailSearch: ToolMailSearch = spyk(ToolMailSearch(ToolRunBashCommand))

        coEvery { toolMailUnreadMessagesCount.invoke(any(), any()) } returns "1"
        coEvery { toolMailListMessages.invoke(any(), any()) } returns """
            ID: 50101 | From: Test Contact <test@example.com> | Subject: Отчет за сегодня
            ID: 50002 | From: Service Bot <noreply@example.com> | Subject: Daily digest
        """.trimIndent()
        coEvery { toolMailSearch.invoke(any(), any()) } returns """
            ID: 50101 | From: Test Contact <test@example.com> | Subject: Отчет за сегодня
        """.trimIndent()

        val answer = runScenarioWithMocks(
            userPrompt,
            listOf(
                toolMailUnreadMessagesCount.toGiga(),
                toolMailListMessages.toGiga(),
                toolMailSearch.toGiga(),
            ),
        )
        assertAnswerFacts(answer, "Отчет за сегодня", "Daily digest")
        assertOneUnreadMessage(answer)
        coVerify(atLeast = 1) { toolMailUnreadMessagesCount.invoke(any(), any()) }
        coVerify(atLeast = 1) { toolMailListMessages.invoke(any(), any()) }
        if (userPrompt.contains("Найди", ignoreCase = true)) coVerify(atLeast = 1) { toolMailSearch.invoke(any(), any()) }
    }

    @ParameterizedTest(name = "scenario21_sendEmail[{index}] {0}")
    @ValueSource(
        strings = [
            "Напиши письмо на test собака example.com с темой Тест",
            "Отправь email на test@example.com, тема: Тест",
            "Создай новое письмо для test@example.com с темой Тест",
        ]
    )
    fun scenario21_sendEmail(userPrompt: String) = runTest {
        val toolMailSendNewMessage: ToolMailSendNewMessage = spyk(ToolMailSendNewMessage(ToolRunBashCommand))
        val toolMailSearch: ToolMailSearch = spyk(ToolMailSearch(ToolRunBashCommand))

        coEvery { toolMailSendNewMessage.invoke(any(), any()) } returns "Sent"
        coEvery { toolMailSearch.invoke(any(), any()) } returns """
            ID: 50101 | From: Test Contact <test@example.com> | Subject: Переписка по тестам
        """.trimIndent()

        runScenarioWithMocks(userPrompt, listOf(toolMailSendNewMessage.toGiga(), toolMailSearch.toGiga()))
        coVerify(exactly = 1) {
            toolMailSendNewMessage.invoke(match { it.recipientAddress == "test@example.com" && it.subject == "Тест" }, any())
        }
        coVerify(exactly = 1) { toolMailSendNewMessage.invoke(any(), any()) }
    }

    @ParameterizedTest(name = "scenario22_readSelectedText[{index}] {0}")
    @ValueSource(
        strings = [
            "Получи текст из буфера обмена или выделения и кратко перескажи",
            "Возьми выделенный текст из clipboard и перескажи",
            "Прочитай текст из буфера обмена и дай краткий пересказ",
        ]
    )
    fun scenario22_readSelectedText(userPrompt: String) = runTest {
        val toolGetClipboard: ToolGetClipboard = spyk(ToolGetClipboard())

        coEvery { toolGetClipboard.invoke(any(), any()) } returns "Проект Орион завершён. Выпуск назначен на пятницу."

        val answer = runScenarioWithMocks(userPrompt, listOf(toolGetClipboard.toGiga()))
        assertAnswerFacts(answer, "Орион", "пятниц")
        coVerify(atLeast = 1) { toolGetClipboard.invoke(any(), any()) }
    }

    private fun excelTools(): Pair<ExcelRead, List<LLMToolSetup>> {
        val read = spyk(ExcelRead(filesUtil))
        val find = spyk(ToolFindFilesByName(filesUtil))
        val list = spyk(ToolListFiles(filesUtil))
        val files = ScenarioFiles(mapOf("~/sales.xlsx" to "", "~/price.xlsx" to ""))
        coEvery { read.invoke(any(), any()) } answers { ScenarioExcel.read(firstArg()) }
        coEvery { find.suspendInvoke(any(), any()) } answers {
            firstArg<ToolFindFilesByName.Input>().let { files.find(it.path, it.fileName) }
        }
        coEvery { list.invoke(any(), any()) } answers { files.list(firstArg<ToolListFiles.Input>().path) }
        return read to listOf(read.toGiga(), find.toGiga(), list.toGiga())
    }

    @ParameterizedTest(name = "excelRead_overview[{index}] {0}")
    @ValueSource(
        strings = [
            "Покажи структуру файла sales.xlsx",
            "Какие колонки в файле sales.xlsx?",
            "Открой превью таблицы sales.xlsx"
        ]
    )
    fun excelRead_overview(userPrompt: String) = runTest {
        val (read, tools) = excelTools()
        val answer = runScenarioWithMocks(userPrompt, tools)
        coVerify(atLeast = 1) { read.invoke(match { it.path.endsWith("sales.xlsx") && it.operation == ExcelRead.ReadOperation.STRUCTURE }, any()) }
        assertAnswerFacts(answer, "Date", "Amount")
    }

    @ParameterizedTest(name = "excelRead_query[{index}] {0}")
    @ValueSource(
        strings = [
            "Найди в sales.xlsx все продажи где Amount > 1000",
            "Покажи строки из sales.xlsx где сумма больше 1000",
            "Отфильтруй sales.xlsx по Amount больше 1000"
        ]
    )
    fun excelRead_query(userPrompt: String) = runTest {
        val (read, tools) = excelTools()
        val answer = runScenarioWithMocks("$userPrompt. Результат покажи таблицей с колонками Date и Amount.", tools)
        // Any correct read-only solution is allowed: QUERY aggregates, CELL can supply the raw rows.
        coVerify(atLeast = 1) { read.invoke(match { it.path.endsWith("sales.xlsx") }, any()) }
        assertSalesRows(answer, listOf("2026-05-02" to 2500, "2026-05-04" to 1500))
    }

    @ParameterizedTest(name = "excelRead_sort[{index}] {0}")
    @ValueSource(
        strings = [
            "Отсортируй продажи в sales.xlsx по Amount по убыванию",
            "Покажи sales.xlsx сортировка по Amount DESC",
            "Выведи данные из sales.xlsx упорядоченные по Amount по убыванию"
        ]
    )
    fun excelRead_sort(userPrompt: String) = runTest {
        val (read, tools) = excelTools()
        val answer = runScenarioWithMocks("$userPrompt. Результат покажи таблицей с колонками Date и Amount.", tools)
        // Any correct read-only solution is allowed: QUERY aggregates, CELL can supply the raw rows.
        coVerify(atLeast = 1) { read.invoke(match { it.path.endsWith("sales.xlsx") }, any()) }
        assertSalesRows(answer, listOf("2026-05-02" to 2500, "2026-05-04" to 1500,
            "2026-05-03" to 750, "2026-05-01" to 500), ordered = true)
    }

    @ParameterizedTest(name = "excelRead_cell[{index}] {0}")
    @ValueSource(
        strings = [
            "Покажи значение ячейки B5 в sales.xlsx",
            "Что в ячейке B5 файла sales.xlsx?",
            "Прочитай ячейку B5 из sales.xlsx"
        ]
    )
    fun excelRead_cell(userPrompt: String) = runTest {
        val (read, tools) = excelTools()
        val answer = runScenarioWithMocks(userPrompt, tools)
        coVerify(atLeast = 1) {
            read.invoke(match { it.path.endsWith("sales.xlsx") && it.operation == ExcelRead.ReadOperation.CELL &&
                (it.range == "B5" || it.returnColumn == "B5") }, any())
        }
        assertAnswerFacts(answer, "1500")
    }

    @ParameterizedTest(name = "excelRead_lookup[{index}] {0}")
    @ValueSource(
        strings = [
            "Найди цену товара Ноутбук в файле price.xlsx",
            "VLOOKUP: найди в price.xlsx цену для Ноутбук",
            "Посмотри в price.xlsx какая цена у товара Ноутбук"
        ]
    )
    fun excelRead_lookup(userPrompt: String) = runTest {
        val (read, tools) = excelTools()
        val answer = runScenarioWithMocks(userPrompt, tools)
        coVerify(atLeast = 1) {
            read.invoke(match { it.path.endsWith("price.xlsx") && it.operation == ExcelRead.ReadOperation.LOOKUP &&
                it.lookupValue.equals("Ноутбук", ignoreCase = true) && it.lookupColumn == "Товар" && it.returnColumn == "Цена" }, any())
        }
        assertAnswerFacts(answer, "45000")
    }

    @ParameterizedTest(name = "excelReport_newFile[{index}] {0}")
    @ValueSource(
        strings = [
            "Создай отчет report.xlsx с заголовками Имя, Телефон",
            "Сформируй файл report.xlsx с колонками Имя, Телефон",
            "Сделай новый отчет report.xlsx: Имя, Телефон"
        ]
    )
    fun excelReport_newFile(userPrompt: String) = runTest {
        val excelReport: ExcelReport = spyk(ExcelReport(filesUtil))
        val toolFindFiles: ToolFindFilesByName = spyk(ToolFindFilesByName(filesUtil))
        val toolListFiles: ToolListFiles = spyk(ToolListFiles(filesUtil))

        coEvery { excelReport.invoke(any(), any()) } returns "Created report.xlsx"
        coEvery { toolListFiles.invoke(any(), any()) } returns """["~/price.xlsx", "~/sales.xlsx"]"""
        coEvery { toolFindFiles.suspendInvoke(any(), any()) } returns "[]"

        val answer = runScenarioWithMocks(
            userPrompt,
            listOf(excelReport.toGiga(), toolFindFiles.toGiga(), toolListFiles.toGiga()),
        )
        assertAnswerFacts(answer, "report.xlsx")
        coVerify(exactly = 1) {
            excelReport.invoke(match {
                val headers = it.headers
                it.path.endsWith("report.xlsx") &&
                        headers?.split(",")?.map(String::trim) == listOf("Имя", "Телефон")
            }, any())
        }
        coVerify(exactly = 1) { excelReport.invoke(any(), any()) }
    }

    @ParameterizedTest(name = "excelReport_withData[{index}] {0}")
    @ValueSource(
        strings = [
            "Создай отчет stats.xlsx с данными: 2024-01-01, 100; 2024-01-02, 200",
            "Запиши в новый файл stats.xlsx данные: [[2024-01-01, 100], [2024-01-02, 200]]",
            "Сформируй stats.xlsx и добавь туда строки: 2024-01-01, 100"
        ]
    )
    fun excelReport_withData(userPrompt: String) = runTest {
        val excelReport: ExcelReport = spyk(ExcelReport(filesUtil))
        val toolFindFiles: ToolFindFilesByName = spyk(ToolFindFilesByName(filesUtil))
        val toolListFiles: ToolListFiles = spyk(ToolListFiles(filesUtil))

        coEvery { excelReport.invoke(any(), any()) } returns "Created report stats.xlsx"
        coEvery { toolListFiles.invoke(any(), any()) } returns """["~/price.xlsx", "~/sales.xlsx"]"""
        coEvery { toolFindFiles.suspendInvoke(any(), any()) } returns "[]"

        val answer = runScenarioWithMocks(
            userPrompt,
            listOf(excelReport.toGiga(), toolFindFiles.toGiga(), toolListFiles.toGiga()),
        )
        assertAnswerFacts(answer, "stats.xlsx")
        coVerify(exactly = 1) {
            excelReport.invoke(match {
                val expected = if (userPrompt.contains("2024-01-02")) listOf("2024-01-01,100", "2024-01-02,200") else listOf("2024-01-01,100")
                it.path.endsWith("stats.xlsx") && it.csvData?.lineSequence()?.filter(String::isNotBlank)
                    ?.map { row -> row.replace(" ", "").replace("\"", "") }?.toList() == expected
            }, any())
        }
        coVerify(exactly = 1) { excelReport.invoke(any(), any()) }
    }
}
