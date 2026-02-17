package agent

import agent.GraphAgentToolScenariosIntegrationTest.Setup.selectedModel
import agent.GraphAgentToolScenariosIntegrationTest.Setup.spySettings
import giga.getHttpClient
import giga.getSessionTokenUsage
import io.ktor.client.plugins.*
import io.mockk.*
import kotlinx.coroutines.test.TestScope
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import org.kodein.di.DI
import org.kodein.di.bindProvider
import org.kodein.di.bindSingleton
import org.kodein.di.instance
import ru.gigadesk.agent.GraphBasedAgent
import ru.gigadesk.db.ConfigStore
import ru.gigadesk.db.DesktopInfoRepository
import ru.gigadesk.db.SettingsProvider
import ru.gigadesk.db.SettingsProviderImpl
import ru.gigadesk.di.mainDiModule
import ru.gigadesk.giga.*
import ru.gigadesk.llms.AiTunnelChatAPI
import ru.gigadesk.llms.AnthropicChatAPI
import ru.gigadesk.llms.OpenAIChatAPI
import ru.gigadesk.llms.QwenChatAPI
import ru.gigadesk.tool.ToolRunBashCommand
import ru.gigadesk.tool.application.ToolOpen
import ru.gigadesk.tool.application.ToolShowApps
import ru.gigadesk.tool.browser.*
import ru.gigadesk.tool.calendar.ToolCalendarCreateEvent
import ru.gigadesk.tool.calendar.ToolCalendarDeleteEvent
import ru.gigadesk.tool.calendar.ToolCalendarListEvents
import ru.gigadesk.tool.dataAnalytics.ToolCreatePlotFromCsv
import ru.gigadesk.tool.dataAnalytics.excel.ExcelRead
import ru.gigadesk.tool.dataAnalytics.excel.ExcelReport
import ru.gigadesk.tool.files.*
import ru.gigadesk.tool.mail.ToolMailListMessages
import ru.gigadesk.tool.mail.ToolMailReplyMessage
import ru.gigadesk.tool.mail.ToolMailSearch
import ru.gigadesk.tool.mail.ToolMailSendNewMessage
import ru.gigadesk.tool.mail.ToolMailUnreadMessagesCount
import ru.gigadesk.tool.notes.ToolCreateNote
import ru.gigadesk.tool.notes.ToolDeleteNote
import ru.gigadesk.tool.notes.ToolListNotes
import ru.gigadesk.tool.notes.ToolSearchNotes
import ru.gigadesk.tool.textReplace.ToolGetClipboard
import java.util.concurrent.atomic.AtomicLong
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes


/**
 * Integration tests for tool invocation scenarios via [GraphBasedAgent.execute].
 * Tools are mocked: we verify that LLM calls the required tools with the expected parameters.
 * All scenarios are run via graphAgent.execute(input).
 */
class GraphAgentToolScenariosIntegrationTest {

    private object Setup {
        val selectedModel = GigaModel.OpenAIGpt5Nano

        val spySettings: SettingsProviderImpl by lazy {
            spyk(SettingsProviderImpl(ConfigStore)) {
                every { forbiddenFolders } returns emptyList()
                every { useStreaming } returns false
                every { gigaModel } returns selectedModel
                every { requestTimeoutMillis } returns 60_000L
                every { temperature } returns 0.2f
                every { systemPrompt } returns "Будь полезен. Выполняй инструкции с помощью тулов."
            }
        }
    }

    @BeforeEach
    fun checkEnvironment() {
        val apiKeyName = when (selectedModel.provider) {
            LlmProvider.GIGA -> "GIGA_KEY"
            LlmProvider.QWEN -> "QWEN_KEY"
            LlmProvider.AI_TUNNEL -> "AITUNNEL_KEY"
            LlmProvider.ANTHROPIC -> "ANTHROPIC_API_KEY"
            LlmProvider.OPENAI -> "OPENAI_API_KEY"
        }
        val apiKey = System.getenv(apiKeyName) ?: System.getProperty(apiKeyName)
        Assumptions.assumeTrue(
            !apiKey.isNullOrBlank(),
            "Skipping integration tests: $apiKeyName is not set (selected model=${selectedModel.alias})"
        )
    }

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

        coEvery { testGetApps.invoke(any()) } returns """
            [{"app-bundle-id":"ru.keepcoder.Telegram","app-name":"Telegram"}]
        """.trimIndent()

        coEvery { testOpenApp.invoke(any()) } returns "Opened"

        val di = DI.invoke(allowSilentOverride = true) {
            import(mainDiModule)
            import(testOverrideModule, allowOverride = true)
            bindProvider<DI> { this.di }
            bindSingleton<ToolShowApps> { testGetApps }
            bindSingleton<ToolOpen> { testOpenApp }
        }

        val agent = GraphBasedAgent(di, gigaJsonMapper)
        agent.execute(userPrompt)

        coVerify(atLeast = 1) {
            testOpenApp.invoke(match { it.target.contains("ru.keepcoder.Telegram", ignoreCase = true) })
        }
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

        coEvery { toolOpenDefaultBrowser.invoke(any()) } answers {
            openCalls++
            "Browser opened"
        }
        coEvery { toolOpen.invoke(any()) } answers {
            openCalls++
            openTargets += firstArg<ToolOpen.Input>().target
            "Opened"
        }
        coEvery { toolCreateNewBrowserTab.invoke(any()) } answers {
            openCalls++
            createNewTabUrls += firstArg<ToolCreateNewBrowserTab.Input>().url
            "Tab opened"
        }

        runScenarioWithMocks(userPrompt) {
            bindSingleton<ToolOpenDefaultBrowser> { toolOpenDefaultBrowser }
            bindSingleton<ToolOpen> { toolOpen }
            bindSingleton<ToolCreateNewBrowserTab> { toolCreateNewBrowserTab }
        }
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

        coEvery { toolCreateNewBrowserTab.invoke(any()) } returns "Tab opened"

        runScenarioWithMocks(userPrompt) {
            bindSingleton<ToolCreateNewBrowserTab> { toolCreateNewBrowserTab }
        }
        coVerify(exactly = 1) {
            toolCreateNewBrowserTab.invoke(match { it.url.contains("example.com") })
        }
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
        mockkStatic("ru.gigadesk.tool.browser.DefaultBrowserKt")
        every { ToolRunBashCommand.detectDefaultBrowser() } returns BrowserType.CHROME

        val toolChromeInfo: ToolChromeInfo = spyk(ToolChromeInfo(ToolRunBashCommand))
        val toolSafariInfo: ToolSafariInfo = spyk(ToolSafariInfo(ToolRunBashCommand))
        val toolCreateNewBrowserTab: ToolCreateNewBrowserTab = spyk(ToolCreateNewBrowserTab(ToolRunBashCommand))

        coEvery { toolChromeInfo.invoke(any()) } returns "2026-01-01|https://example.com|Example Domain"
        coEvery { toolSafariInfo.invoke(any()) } returns ""
        coEvery { toolCreateNewBrowserTab.invoke(any()) } returns "Opened"

        try {
            runScenarioWithMocks(userPrompt) {
                bindSingleton<ToolChromeInfo> { toolChromeInfo }
                bindSingleton<ToolSafariInfo> { toolSafariInfo }
                bindSingleton<ToolCreateNewBrowserTab> { toolCreateNewBrowserTab }
            }
            coVerify(atLeast = 1) {
                toolChromeInfo.invoke(match { it.type == ToolChromeInfo.InfoType.history })
            }
        } finally {
            unmockkStatic("ru.gigadesk.tool.browser.DefaultBrowserKt")
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
        val realSafari = ToolSafariInfo(ToolRunBashCommand)
        val toolSafariInfo: ToolSafariInfo = spyk(realSafari)

        val realChrome = ToolChromeInfo(ToolRunBashCommand)
        val toolChromeInfo: ToolChromeInfo = spyk(realChrome)

        var pageTextCalls = 0

        coEvery { toolSafariInfo.invoke(any()) } answers {
            val input = firstArg<ToolSafariInfo.Input>()
            if (input.type == ToolSafariInfo.InfoType.pageText) pageTextCalls++
            "Page content"
        }
        coEvery { toolChromeInfo.invoke(any()) } answers {
            val input = firstArg<ToolChromeInfo.Input>()
            if (input.type == ToolChromeInfo.InfoType.pageText) pageTextCalls++
            "Page content"
        }

        runScenarioWithMocks(userPrompt) {
            bindSingleton<ToolSafariInfo> { toolSafariInfo }
            bindSingleton<ToolChromeInfo> { toolChromeInfo }
        }
        assertTrue(
            pageTextCalls >= 1,
            "Expected at least one pageText action via SafariInfo or ChromeInfo, but got $pageTextCalls"
        )
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
        val realTool = ToolCalendarListEvents(ToolRunBashCommand)
        val toolCalendarListEvents: ToolCalendarListEvents = spyk(realTool)

        coEvery { toolCalendarListEvents.invoke(any()) } returns "[]"

        runScenarioWithMocks(userPrompt) {
            bindSingleton<ToolCalendarListEvents> { toolCalendarListEvents }
        }
        coVerify(exactly = 1) { toolCalendarListEvents.invoke(any()) }
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
        val realTool = ToolCalendarCreateEvent(ToolRunBashCommand)
        val toolCalendarCreateEvent: ToolCalendarCreateEvent = spyk(realTool)

        coEvery { toolCalendarCreateEvent.invoke(any()) } returns "Event created"

        runScenarioWithMocks(userPrompt) {
            bindSingleton<ToolCalendarCreateEvent> { toolCalendarCreateEvent }
        }
        coVerify(exactly = 1) {
            toolCalendarCreateEvent.invoke(match { it.title.isNotBlank() && it.startDateTime.isNotBlank() })
        }
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
        val realToolList = ToolCalendarListEvents(ToolRunBashCommand)
        val toolCalendarListEvents: ToolCalendarListEvents = spyk(realToolList)

        val realToolCreate = ToolCalendarCreateEvent(ToolRunBashCommand)
        val toolCalendarCreateEvent: ToolCalendarCreateEvent = spyk(realToolCreate)

        val realToolDel = ToolCalendarDeleteEvent(ToolRunBashCommand)
        val toolCalendarDeleteEvent: ToolCalendarDeleteEvent = spyk(realToolDel)

        coEvery { toolCalendarListEvents.invoke(any()) } returns """
            2026-02-11 10:00 - 11:00 | Важная встреча
            2026-02-11 15:00 - 16:00 | Командный синк
        """.trimIndent()
        coEvery { toolCalendarCreateEvent.invoke(any()) } returns "Event created"
        coEvery { toolCalendarDeleteEvent.invoke(any()) } returns "Deleted"

        runScenarioWithMocks(userPrompt) {
            bindSingleton<ToolCalendarListEvents> { toolCalendarListEvents }
            bindSingleton<ToolCalendarCreateEvent> { toolCalendarCreateEvent }
            bindSingleton<ToolCalendarDeleteEvent> { toolCalendarDeleteEvent }
        }

        coVerify(atLeast = 1) { toolCalendarListEvents.invoke(any()) }
        coVerify(atLeast = 1) {
            toolCalendarDeleteEvent.invoke(match { it.title.contains("Важная встреча", ignoreCase = true) })
        }
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
        val toolCalendarListEvents: ToolCalendarListEvents = spyk(ToolCalendarListEvents(ToolRunBashCommand))
        val toolCalendarCreateEvent: ToolCalendarCreateEvent = spyk(ToolCalendarCreateEvent(ToolRunBashCommand))
        val toolCalendarDeleteEvent: ToolCalendarDeleteEvent = spyk(ToolCalendarDeleteEvent(ToolRunBashCommand))

        coEvery { toolCalendarListEvents.invoke(any()) } returns """
            2026-02-10 10:00 - 10:30 | Статус встреча
            2026-02-12 16:00 - 17:00 | Планирование спринта
        """.trimIndent()
        coEvery { toolCalendarCreateEvent.invoke(any()) } returns "Event created"
        coEvery { toolCalendarDeleteEvent.invoke(any()) } returns "Deleted"

        runScenarioWithMocks(userPrompt) {
            bindSingleton<ToolCalendarListEvents> { toolCalendarListEvents }
            bindSingleton<ToolCalendarCreateEvent> { toolCalendarCreateEvent }
            bindSingleton<ToolCalendarDeleteEvent> { toolCalendarDeleteEvent }
        }
        coVerify(atLeast = 1) { toolCalendarListEvents.invoke(any()) }
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
        val toolCreatePlotFromCsv: ToolCreatePlotFromCsv = spyk(ToolCreatePlotFromCsv(filesUtil))
        val toolListFiles: ToolListFiles = spyk(ToolListFiles(filesUtil))
        val excelRead: ExcelRead = spyk(ExcelRead(filesUtil))

        coEvery { toolCreatePlotFromCsv.invoke(any()) } returns "Plot saved"
        coEvery { toolListFiles.invoke(any()) } returns "[\"sample.csv\"]"
        coEvery { excelRead.invoke(any()) } returns """{"headers":["Date","Amount"],"rowCount":10}"""

        runScenarioWithMocks(userPrompt) {
            bindSingleton<ToolCreatePlotFromCsv> { toolCreatePlotFromCsv }
            bindSingleton<ToolListFiles> { toolListFiles }
            bindSingleton<ExcelRead> { excelRead }
        }
        coVerify(exactly = 1) {
            toolCreatePlotFromCsv.invoke(match { it.path.contains("sample.csv") })
        }
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
        val toolFindFilesByName: ToolFindFilesByName = spyk(ToolFindFilesByName(filesUtil))

        coEvery { toolFindFilesByName.invoke(any()) } returns "/path/to/test.txt"
        coEvery { toolFindFilesByName.suspendInvoke(any()) } returns "/path/to/test.txt"

        runScenarioWithMocks(userPrompt) {
            bindSingleton<ToolFindFilesByName> { toolFindFilesByName }
        }
        coVerify(atLeast = 1) {
            toolFindFilesByName.suspendInvoke(match { it.fileName.contains("100 ошибок в го") })
        }
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
        val realTool = ToolListFiles(filesUtil)
        val toolListFiles: ToolListFiles = spyk(realTool)

        coEvery { toolListFiles.invoke(any()) } returns "test.txt, read_me.txt, sample.csv"

        runScenarioWithMocks(userPrompt) {
            bindSingleton<ToolListFiles> { toolListFiles }
        }
        coVerify(exactly = 1) { toolListFiles.invoke(any()) }
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
        val realToolNew = ToolNewFile(filesUtil)
        val toolNewFile: ToolNewFile = spyk(realToolNew)

        coEvery { toolNewFile.invoke(any()) } returns "Created"

        val tempFile = "test_integration.txt"
        runScenarioWithMocks(userPrompt) {
            bindSingleton<ToolNewFile> { toolNewFile }
        }
        coVerify(exactly = 1) { toolNewFile.invoke(match { it.path.contains(tempFile) && it.text.contains("Hello") }) }
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
        val toolExtractText: ToolExtractText = spyk(ToolExtractText(filesUtil))
        val toolFindFilesByName: ToolFindFilesByName = spyk(ToolFindFilesByName(filesUtil))

        coEvery { toolExtractText.invoke(any()) } returns "Hello"
        coEvery { toolFindFilesByName.suspendInvoke(any()) } returns "[\"~/tmp/test-data/test_integration.txt\"]"

        val tempFile = "test_integration.txt"
        runScenarioWithMocks(userPrompt) {
            bindSingleton<ToolExtractText> { toolExtractText }
            bindSingleton<ToolFindFilesByName> { toolFindFilesByName }
        }
        coVerify(exactly = 1) { toolExtractText.invoke(match { it.filePath.contains(tempFile) }) }
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
        val realToolMod = ToolModifyFile(filesUtil)
        val toolModifyFile: ToolModifyFile = spyk(realToolMod)

        val realToolFind = ToolFindFilesByName(filesUtil)
        val toolFindFilesByName: ToolFindFilesByName = spyk(realToolFind)
        val toolExtractText: ToolExtractText = spyk(ToolExtractText(filesUtil))

        var currentContent = ""
        val tempFile = "test_integration"
        val appendText = "World is over"

        coEvery { toolFindFilesByName.suspendInvoke(any()) } returns "[\"~/test_integration.txt\"]"
        coEvery { toolExtractText.invoke(any()) } answers { currentContent }
        coEvery { toolModifyFile.invoke(any()) } answers {
            val request = firstArg<ToolModifyFile.Input>()
            currentContent = "$currentContent\n${request.newText}"
            "Modified"
        }

        runScenarioWithMocks(userPrompt) {
            bindSingleton<ToolExtractText> { toolExtractText }
            bindSingleton<ToolModifyFile> { toolModifyFile }
            bindSingleton<ToolFindFilesByName> { toolFindFilesByName }
        }
        coVerify(exactly = 1) {
            toolModifyFile.invoke(match { it.path.contains(tempFile) && it.newText.contains(appendText) })
        }
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
        val realToolDel = ToolDeleteFile(filesUtil)
        val toolDeleteFile: ToolDeleteFile = spyk(realToolDel)

        val realToolFind = ToolFindFilesByName(filesUtil)
        val toolFindFilesByName: ToolFindFilesByName = spyk(realToolFind)

        coEvery { toolDeleteFile.invoke(any()) } returns "Deleted"
        coEvery { toolFindFilesByName.suspendInvoke(any()) } returns "[\"/tmp/test-data/test_integration.txt\"]"

        val tempFile = "test_integration.txt"
        runScenarioWithMocks(userPrompt) {
            bindSingleton<ToolDeleteFile> { toolDeleteFile }
            bindSingleton<ToolFindFilesByName> { toolFindFilesByName }
        }
        coVerify(exactly = 1) { toolDeleteFile.invoke(match { it.path.contains(tempFile) }) }
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
        val realTool = ToolMoveFile(filesUtil)
        val toolMoveFile: ToolMoveFile = spyk(realTool)
        val toolListFiles: ToolListFiles = spyk(ToolListFiles(filesUtil))
        val toolFindFiles: ToolFindFilesByName = spyk(ToolFindFilesByName(filesUtil))

        coEvery { toolMoveFile.invoke(any()) } returns "Moved"
        coEvery { toolListFiles.invoke(any()) } returns """["sample.csv", "README.md", "/dest"]"""
        coEvery { toolFindFiles.suspendInvoke(any()) } returns """["~/sample.csv", "~/README.md", "~/dest/"]"""

        runScenarioWithMocks(userPrompt) {
            bindSingleton<ToolMoveFile> { toolMoveFile }
            bindSingleton<ToolListFiles> { toolListFiles }
            bindSingleton<ToolFindFilesByName> { toolFindFiles }
        }
        coVerify(exactly = 1) {
            toolMoveFile.invoke(match { it.sourcePath.contains("README") && it.destinationPath.contains("dest") })
        }
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
        val realTool = ToolExtractText(filesUtil)
        val toolExtractText: ToolExtractText = spyk(realTool)

        coEvery { toolExtractText.invoke(any()) } returns "Test content\nСтрока для проверки извлечения текста."

        runScenarioWithMocks(userPrompt) {
            bindSingleton<ToolExtractText> { toolExtractText }
        }
        coVerify(exactly = 1) {
            toolExtractText.invoke(match { it.filePath.contains("test.txt") })
        }
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

        coEvery { toolFindFiles.suspendInvoke(any()) } returns "[\"~/sample.pdf\"]"
        coEvery { toolListFiles.invoke(any()) } returns "[\"sample.pdf\"]"
        coEvery { toolReadPdfPages.invoke(any()) } returns "Page 1 content"

        runScenarioWithMocks(userPrompt) {
            bindSingleton<ToolReadPdfPages> { toolReadPdfPages }
            bindSingleton<ToolListFiles> { toolListFiles }
            bindSingleton<ToolFindFilesByName> { toolFindFiles }
        }
        coVerify(exactly = 1) {
            toolReadPdfPages.invoke(match { it.filePath.contains("sample") })
        }
    }

    @ParameterizedTest(name = "scenario18_openFile[{index}] {0}")
    @ValueSource(
        strings = [
            "Открой файл ~/tmp/read_me.txt",
            "Открой документ read_me.txt из home slash tmp",
            "Запусти файл HOME/tmp/read_me.txt",
        ]
    )
    fun scenario18_openFile(userPrompt: String) = runTest {
        val realTool = ToolOpen(ToolRunBashCommand, filesUtil)
        val toolOpen: ToolOpen = spyk(realTool)

        coEvery { toolOpen.invoke(any()) } returns "Opened"

        runScenarioWithMocks(userPrompt) {
            bindSingleton<ToolOpen> { toolOpen }
        }
        coVerify(exactly = 1) {
            toolOpen.invoke(match { it.target.contains("read_me.txt") })
        }
    }

    @ParameterizedTest(name = "scenario19_notesFindCreateDeleteList[{index}] {0}")
    @ValueSource(
        strings = [
            "Создай заметку \"тест интеграции\", перечисли заметки, найди заметку тест, удали заметку тест интеграции",
            "Сделай заметку тест интеграции, покажи список заметок, найди тест, затем удали тест интеграции",
            "Работаем с заметками. Добавь заметку \"тест интеграции\", проверь список, найди ее и удали",
        ]
    )
    fun scenario19_notesFindCreateDeleteList(userPrompt: String) = runTest {
        val toolCreateNote: ToolCreateNote = spyk(ToolCreateNote(ToolRunBashCommand))
        val toolListNotes: ToolListNotes = spyk(ToolListNotes(ToolRunBashCommand))
        val toolSearchNotes: ToolSearchNotes = spyk(ToolSearchNotes(ToolRunBashCommand))
        val toolDeleteNote: ToolDeleteNote = spyk(ToolDeleteNote(ToolRunBashCommand))

        val noteTitle = "тест интеграции"
        var hasNote = false

        coEvery { toolCreateNote.invoke(any()) } answers {
            hasNote = true
            "Created"
        }
        coEvery { toolDeleteNote.invoke(any()) } answers {
            hasNote = false
            "Deleted"
        }
        coEvery { toolListNotes.invoke(any()) } answers {
            if (hasNote) "[\"$noteTitle\"]" else "[]"
        }
        coEvery { toolSearchNotes.invoke(any()) } answers {
            if (hasNote) "[\"$noteTitle\"]" else "[]"
        }

        runScenarioWithMocks(userPrompt) {
            bindSingleton<ToolCreateNote> { toolCreateNote }
            bindSingleton<ToolListNotes> { toolListNotes }
            bindSingleton<ToolSearchNotes> { toolSearchNotes }
            bindSingleton<ToolDeleteNote> { toolDeleteNote }
        }
        coVerify(exactly = 1) { toolCreateNote.invoke(match { it.noteText.contains(noteTitle) }) }
        coVerify(atLeast = 1) { toolListNotes.invoke(any()) }
        coVerify(atLeast = 0) { toolSearchNotes.invoke(any()) }
        coVerify(exactly = 1) { toolDeleteNote.invoke(match { it.noteName.contains("тест") }) }
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

        coEvery { toolMailUnreadMessagesCount.invoke(any()) } returns "1"
        coEvery { toolMailListMessages.invoke(any()) } returns """
            ID: 50101 | From: Test Contact <test@example.com> | Subject: Отчет за сегодня
            ID: 50002 | From: Service Bot <noreply@example.com> | Subject: Daily digest
        """.trimIndent()
        coEvery { toolMailSearch.invoke(any()) } returns """
            ID: 50101 | From: Test Contact <test@example.com> | Subject: Отчет за сегодня
        """.trimIndent()

        runScenarioWithMocks(userPrompt) {
            bindSingleton<ToolMailUnreadMessagesCount> { toolMailUnreadMessagesCount }
            bindSingleton<ToolMailListMessages> { toolMailListMessages }
            bindSingleton<ToolMailSearch> { toolMailSearch }
        }
        coVerify(exactly = 1) { toolMailUnreadMessagesCount.invoke(any()) }
        coVerify(exactly = 1) { toolMailListMessages.invoke(any()) }
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

        coEvery { toolMailSendNewMessage.invoke(any()) } returns "Sent"
        coEvery { toolMailSearch.invoke(any()) } returns """
            ID: 50101 | From: Test Contact <test@example.com> | Subject: Переписка по тестам
        """.trimIndent()

        runScenarioWithMocks(userPrompt) {
            bindSingleton<ToolMailSendNewMessage> { toolMailSendNewMessage }
            bindSingleton<ToolMailSearch> { toolMailSearch }
        }
        coVerify(exactly = 1) {
            toolMailSendNewMessage.invoke(match { it.recipientAddress.contains("test@example.com") })
        }
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

        coEvery { toolGetClipboard.invoke(any()) } returns "Selected text"

        runScenarioWithMocks(userPrompt) {
            bindSingleton<ToolGetClipboard> { toolGetClipboard }
        }
        coVerify(atLeast = 1) { toolGetClipboard.invoke(any()) }
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
        val excelRead: ExcelRead = spyk(ExcelRead(filesUtil))
        val toolFindFiles: ToolFindFilesByName = spyk(ToolFindFilesByName(filesUtil))
        val toolListFiles: ToolListFiles = spyk(ToolListFiles(filesUtil))

        coEvery { toolFindFiles.suspendInvoke(any()) } returns "[\"~/sales.xlsx\"]"
        coEvery { excelRead.invoke(any()) } returns """{"headers":["Date","Amount"],"rowCount":10}"""
        coEvery { toolListFiles.invoke(any()) } returns """["~/price.xlsx"]"""

        runScenarioWithMocks(userPrompt) {
            bindSingleton<ExcelRead> { excelRead }
            bindSingleton<ToolFindFilesByName> { toolFindFiles }
            bindSingleton<ToolListFiles> { toolListFiles }
        }
        coVerify(atLeast = 1) {
            excelRead.invoke(match { it.path.contains("sales") && it.operation == ExcelRead.ReadOperation.STRUCTURE })
        }
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
        val excelRead: ExcelRead = spyk(ExcelRead(filesUtil))
        val toolFindFiles: ToolFindFilesByName = spyk(ToolFindFilesByName(filesUtil))
        val toolListFiles: ToolListFiles = spyk(ToolListFiles(filesUtil))

        coEvery { toolFindFiles.suspendInvoke(any()) } returns "[\"~/sales.xlsx\"]"
        coEvery { excelRead.invoke(any()) } returns """[
            |{"Date":"2024-01-01","Amount":"1500"}
            |{"Date":"2023-02-03","Amount":"2500"}
            |]""".trimMargin()
        coEvery { toolListFiles.invoke(any()) } returns """["~/price.xlsx"]"""

        runScenarioWithMocks(userPrompt) {
            bindSingleton<ExcelRead> { excelRead }
            bindSingleton<ToolFindFilesByName> { toolFindFiles }
            bindSingleton<ToolListFiles> { toolListFiles }
        }
        coVerify(atLeast = 1) {
            excelRead.invoke(match {
                it.path.contains("sales") &&
                        it.operation == ExcelRead.ReadOperation.QUERY &&
                        it.filter != null && it.filter.contains(">") && it.filter.contains("1000")
            })
        }
    }

    @ParameterizedTest(name = "excelRead_sort[{index}] {0}")
    @ValueSource(
        strings = [
            "Отсортируй продажи в sales.xlsx по Amount по убыванию",
            "Покажи sales.xlsx сортировка по Amount DESC",
            "Выведи данные из sales.xlsx упорядоченные по Amount"
        ]
    )
    fun excelRead_sort(userPrompt: String) = runTest {
        val excelRead: ExcelRead = spyk(ExcelRead(filesUtil))
        val toolFindFiles: ToolFindFilesByName = spyk(ToolFindFilesByName(filesUtil))
        val toolListFiles: ToolListFiles = spyk(ToolListFiles(filesUtil))

        coEvery { toolFindFiles.suspendInvoke(any()) } returns "[\"~/sales.xlsx\"]"
        coEvery { excelRead.invoke(any()) } returns """[
            |{"Date":"2024-01-01","Amount":"1500"}
            |{"Date":"2023-02-03","Amount":"2500"}
            |]""".trimMargin()
        coEvery { toolListFiles.invoke(any()) } returns """["~/price.xlsx"]"""

        runScenarioWithMocks(userPrompt) {
            bindSingleton<ExcelRead> { excelRead }
            bindSingleton<ToolFindFilesByName> { toolFindFiles }
            bindSingleton<ToolListFiles> { toolListFiles }
        }
        coVerify(atLeast = 1) {
            excelRead.invoke(match {
                it.path.contains("sales") &&
                        it.operation == ExcelRead.ReadOperation.QUERY &&
                        it.sortBy != null && it.sortBy.contains("Amount", ignoreCase = true)
            })
        }
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
        val excelRead: ExcelRead = spyk(ExcelRead(filesUtil))
        val toolFindFiles: ToolFindFilesByName = spyk(ToolFindFilesByName(filesUtil))
        val toolListFiles: ToolListFiles = spyk(ToolListFiles(filesUtil))

        coEvery { toolFindFiles.suspendInvoke(any()) } returns "[\"~/sales.xlsx\"]"
        coEvery { excelRead.invoke(any()) } returns "1500"
        coEvery { toolListFiles.invoke(any()) } returns """["~/price.xlsx"]"""

        runScenarioWithMocks(userPrompt) {
            bindSingleton<ExcelRead> { excelRead }
            bindSingleton<ToolFindFilesByName> { toolFindFiles }
            bindSingleton<ToolListFiles> { toolListFiles }
        }
        coVerify(atLeast = 1) {
            excelRead.invoke(match {
                it.path.contains("sales") &&
                        it.operation == ExcelRead.ReadOperation.CELL &&
                        (it.range != null && it.range.contains("B5")) ||
                        (it.returnColumn == "B5")
            })
        }
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
        val excelRead: ExcelRead = spyk(ExcelRead(filesUtil))
        val toolFindFiles: ToolFindFilesByName = spyk(ToolFindFilesByName(filesUtil))
        val toolListFiles: ToolListFiles = spyk(ToolListFiles(filesUtil))

        coEvery { toolListFiles.invoke(any()) } returns """["~/price.xlsx"]"""
        coEvery { toolFindFiles.suspendInvoke(any()) } returns """["~/price.xlsx"]"""
        coEvery { excelRead.invoke(any()) } answers {
            val input = firstArg<ExcelRead.Input>()
            if (
                input.path.contains("price", ignoreCase = true) &&
                input.operation == ExcelRead.ReadOperation.LOOKUP &&
                input.lookupValue?.contains("Ноутбук", ignoreCase = true) == true &&
                !input.lookupColumn.isNullOrBlank() &&
                !input.returnColumn.isNullOrBlank()
            ) {
                "45000"
            } else {
                "Error: Lookup requires operation=LOOKUP with lookupValue, lookupColumn and returnColumn."
            }
        }

        runScenarioWithMocks(userPrompt) {
            bindSingleton<ExcelRead> { excelRead }
            bindSingleton<ToolFindFilesByName> { toolFindFiles }
            bindSingleton<ToolListFiles> { toolListFiles }
        }
        coVerify(atLeast = 1) {
            excelRead.invoke(match {
                it.path.contains("price") &&
                        it.operation == ExcelRead.ReadOperation.LOOKUP &&
                        it.lookupValue != null && it.lookupValue.contains("Ноутбук") &&
                        it.returnColumn != null
            })
        }
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

        coEvery { excelReport.invoke(any()) } returns "Created report.xlsx"
        coEvery { toolListFiles.invoke(any()) } returns """["~/price.xlsx", "~/sales.xlsx"]"""
        coEvery { toolFindFiles.suspendInvoke(any()) } returns "[]"

        runScenarioWithMocks(userPrompt) {
            bindSingleton<ExcelReport> { excelReport }
            bindSingleton<ToolFindFilesByName> { toolFindFiles }
            bindSingleton<ToolListFiles> { toolListFiles }
        }
        coVerify(atLeast = 1) {
            excelReport.invoke(match {
                it.path.contains("report") &&
                        it.headers != null && it.headers.contains("Имя")
            })
        }
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

        coEvery { excelReport.invoke(any()) } returns "Created report stats.xlsx"
        coEvery { toolListFiles.invoke(any()) } returns """["~/price.xlsx", "~/sales.xlsx"]"""
        coEvery { toolFindFiles.suspendInvoke(any()) } returns "[]"

        runScenarioWithMocks(userPrompt) {
            bindSingleton<ExcelReport> { excelReport }
            bindSingleton<ToolFindFilesByName> { toolFindFiles }
            bindSingleton<ToolListFiles> { toolListFiles }
        }
        coVerify(atLeast = 1) {
            excelReport.invoke(match {
                it.path.contains("stats") && !it.csvData.isNullOrEmpty()
            })
        }
    }

    private suspend fun runScenarioWithMocks(
        userPrompt: String,
        overrides: DI.MainBuilder.() -> Unit,
    ) {
        val di = DI.invoke(allowSilentOverride = true) {
            import(mainDiModule)
            import(testOverrideModule, allowOverride = true)
            bindProvider<DI> { this.di }
            overrides()
        }
        val agent = GraphBasedAgent(di, gigaJsonMapper)
        agent.execute(userPrompt)
    }

    companion object {
        private var gigaRestChatAPI: GigaRestChatAPI? = null
        private var qwenChatAPI: QwenChatAPI? = null
        private var aiTunnelChatAPI: AiTunnelChatAPI? = null
        private var anthropicChatAPI: AnthropicChatAPI? = null
        private var openAiChatAPI: OpenAIChatAPI? = null
        private val httpRequestCount = AtomicLong(0)
        private val httpRequestTotalNanos = AtomicLong(0)

        private val filesUtil: FilesToolUtil by lazy { FilesToolUtil(spySettings) }
        private val testOverrideModule: DI.Module = DI.Module("TestOverrideModule") {
            bindSingleton<SettingsProvider>(overrides = true) { spySettings }
            bindSingleton<FilesToolUtil>(overrides = true) { filesUtil }

            // Safe defaults: prevent accidental system mutations if a scenario doesn't explicitly mock these tools.
            bindSingleton<ToolNewFile>(overrides = true) {
                val tool = spyk(ToolNewFile(filesUtil))
                coEvery { tool.invoke(any<ToolNewFile.Input>()) } returns "Created"
                tool
            }
            bindSingleton<ToolModifyFile>(overrides = true) {
                val tool = spyk(ToolModifyFile(filesUtil))
                coEvery { tool.invoke(any<ToolModifyFile.Input>()) } returns "Modified"
                tool
            }
            bindSingleton<ToolDeleteFile>(overrides = true) {
                val tool = spyk(ToolDeleteFile(filesUtil))
                coEvery { tool.invoke(any<ToolDeleteFile.Input>()) } returns "Deleted"
                tool
            }
            bindSingleton<ToolMoveFile>(overrides = true) {
                val tool = spyk(ToolMoveFile(filesUtil))
                coEvery { tool.invoke(any<ToolMoveFile.Input>()) } returns "Moved"
                tool
            }
            bindSingleton<ToolCreateNote>(overrides = true) {
                val tool = spyk(ToolCreateNote(ToolRunBashCommand))
                coEvery { tool.invoke(any<ToolCreateNote.Input>()) } returns "Created"
                tool
            }
            bindSingleton<ToolDeleteNote>(overrides = true) {
                val tool = spyk(ToolDeleteNote(ToolRunBashCommand))
                coEvery { tool.invoke(any<ToolDeleteNote.Input>()) } returns "Deleted"
                tool
            }
            bindSingleton<ToolCalendarCreateEvent>(overrides = true) {
                val tool = spyk(ToolCalendarCreateEvent(ToolRunBashCommand))
                coEvery { tool.invoke(any<ToolCalendarCreateEvent.Input>()) } returns "Event created"
                tool
            }
            bindSingleton<ToolCalendarDeleteEvent>(overrides = true) {
                val tool = spyk(ToolCalendarDeleteEvent(ToolRunBashCommand))
                coEvery { tool.invoke(any<ToolCalendarDeleteEvent.Input>()) } returns "Deleted"
                tool
            }
            bindSingleton<ToolMailSendNewMessage>(overrides = true) {
                val tool = spyk(ToolMailSendNewMessage(ToolRunBashCommand))
                coEvery { tool.invoke(any<ToolMailSendNewMessage.Input>()) } returns "Sent"
                tool
            }
            bindSingleton<ToolMailReplyMessage>(overrides = true) {
                val tool = spyk(ToolMailReplyMessage(ToolRunBashCommand))
                coEvery { tool.invoke(any<ToolMailReplyMessage.Input>()) } returns "Replied"
                tool
            }

            bindSingleton<GigaRestChatAPI>(overrides = true) {
                if (gigaRestChatAPI == null) {
                    gigaRestChatAPI = GigaRestChatAPI(instance(), instance(), instance()).apply {
                        getHttpClient().plugin(HttpSend).intercept { request ->
                            val startNanos = System.nanoTime()
                            try {
                                execute(request)
                            } finally {
                                httpRequestCount.incrementAndGet()
                                httpRequestTotalNanos.addAndGet(System.nanoTime() - startNanos)
                            }
                        }
                    }
                }
                gigaRestChatAPI!!
            }
            bindSingleton<QwenChatAPI>(overrides = true) {
                if (qwenChatAPI == null) {
                    qwenChatAPI = QwenChatAPI(instance(), instance()).apply {
                        getHttpClient().plugin(HttpSend).intercept { request ->
                            val startNanos = System.nanoTime()
                            try {
                                execute(request)
                            } finally {
                                httpRequestCount.incrementAndGet()
                                httpRequestTotalNanos.addAndGet(System.nanoTime() - startNanos)
                            }
                        }
                    }
                }
                qwenChatAPI!!
            }
            bindSingleton<AiTunnelChatAPI>(overrides = true) {
                if (aiTunnelChatAPI == null) {
                    aiTunnelChatAPI = AiTunnelChatAPI(instance(), instance()).apply {
                        getHttpClient().plugin(HttpSend).intercept { request ->
                            val startNanos = System.nanoTime()
                            try {
                                execute(request)
                            } finally {
                                httpRequestCount.incrementAndGet()
                                httpRequestTotalNanos.addAndGet(System.nanoTime() - startNanos)
                            }
                        }
                    }
                }
                aiTunnelChatAPI!!
            }
            bindSingleton<AnthropicChatAPI>(overrides = true) {
                if (anthropicChatAPI == null) {
                    anthropicChatAPI = AnthropicChatAPI(instance(), instance()).apply {
                        getHttpClient().plugin(HttpSend).intercept { request ->
                            val startNanos = System.nanoTime()
                            try {
                                execute(request)
                            } finally {
                                httpRequestCount.incrementAndGet()
                                httpRequestTotalNanos.addAndGet(System.nanoTime() - startNanos)
                            }
                        }
                    }
                }
                anthropicChatAPI!!
            }
            bindSingleton<OpenAIChatAPI>(overrides = true) {
                if (openAiChatAPI == null) {
                    openAiChatAPI = OpenAIChatAPI(instance(), instance()).apply {
                        getHttpClient().plugin(HttpSend).intercept { request ->
                            val startNanos = System.nanoTime()
                            try {
                                execute(request)
                            } finally {
                                httpRequestCount.incrementAndGet()
                                httpRequestTotalNanos.addAndGet(System.nanoTime() - startNanos)
                            }
                        }
                    }
                }
                openAiChatAPI!!
            }
            bindSingleton<GigaChatAPI>(overrides = true) {
                when (selectedModel.provider) {
                    LlmProvider.GIGA -> instance<GigaRestChatAPI>()
                    LlmProvider.QWEN -> instance<QwenChatAPI>()
                    LlmProvider.AI_TUNNEL -> instance<AiTunnelChatAPI>()
                    LlmProvider.ANTHROPIC -> instance<AnthropicChatAPI>()
                    LlmProvider.OPENAI -> instance<OpenAIChatAPI>()
                }
            }
            bindSingleton<DesktopInfoRepository>(overrides = true) {
                val r = DesktopInfoRepository(instance(), instance(), instance())
                spyk(r) { coEvery { search(any(), any()) } returns emptyList() }
            }
        }

        @JvmStatic
        @AfterAll
        fun finish() {
            when (selectedModel.provider) {
                LlmProvider.GIGA -> println("Spent: ${gigaRestChatAPI?.getSessionTokenUsage() ?: "n/a"}")
                LlmProvider.QWEN -> println("Spent: ${qwenChatAPI?.getSessionTokenUsage() ?: "n/a"}")
                LlmProvider.AI_TUNNEL -> println("Spent: ${aiTunnelChatAPI?.getSessionTokenUsage() ?: "n/a"}")
                LlmProvider.ANTHROPIC -> println("Spent: ${anthropicChatAPI?.getSessionTokenUsage() ?: "n/a"}")
                LlmProvider.OPENAI -> println("Spent: ${openAiChatAPI?.getSessionTokenUsage() ?: "n/a"}")
            }
            val requestCount = httpRequestCount.get()
            if (requestCount == 0L) {
                println("HTTP requests: 0")
                return
            }
            val avgMs = httpRequestTotalNanos.get().toDouble() / requestCount / 1_000_000.0
            println("HTTP requests: $requestCount, avg/request: ${"%.2f".format(avgMs)} ms")
        }
    }
}

private val DEFAULT_TEST_TIMEOUT: Duration = 5.minutes

private fun runTest(
    block: suspend TestScope.() -> Unit
) = kotlinx.coroutines.test.runTest(timeout = DEFAULT_TEST_TIMEOUT, testBody = block)
