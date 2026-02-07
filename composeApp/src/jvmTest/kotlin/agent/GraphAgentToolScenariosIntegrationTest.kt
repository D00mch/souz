package agent

import giga.getHttpClient
import giga.getSessionTokenUsage
import io.ktor.client.plugins.HttpSend
import io.ktor.client.plugins.plugin
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockkStatic
import io.mockk.spyk
import io.mockk.unmockkStatic
import ru.gigadesk.tool.ToolRunBashCommand
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.AfterAll
import org.kodein.di.DI
import org.kodein.di.bindProvider
import org.kodein.di.bindSingleton
import ru.gigadesk.agent.GraphBasedAgent
import ru.gigadesk.di.mainDiModule
import ru.gigadesk.giga.objectMapper
import ru.gigadesk.db.SettingsProvider
import ru.gigadesk.db.ConfigStore
import ru.gigadesk.tool.application.*
import ru.gigadesk.tool.browser.*
import ru.gigadesk.tool.calendar.*
import ru.gigadesk.tool.dataAnalytics.*
import ru.gigadesk.tool.files.*
import ru.gigadesk.tool.mail.*
import ru.gigadesk.tool.notes.*
import ru.gigadesk.tool.textReplace.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import org.kodein.di.instance
import ru.gigadesk.agent.DEFAULT_SYSTEM_PROMPT
import ru.gigadesk.db.SettingsProviderImpl
import ru.gigadesk.giga.GigaChatAPI
import ru.gigadesk.giga.GigaModel
import ru.gigadesk.giga.GigaRestChatAPI
import ru.gigadesk.giga.LlmProvider
import ru.gigadesk.qwen.QwenChatAPI
import java.util.concurrent.atomic.AtomicLong


/**
 * Integration tests for tool invocation scenarios via [GraphBasedAgent.execute].
 * Tools are mocked: we verify that LLM calls the required tools with the expected parameters.
 * All scenarios are run via graphAgent.execute(input).
 */
class GraphAgentToolScenariosIntegrationTest {

    private val spySettings: SettingsProviderImpl by lazy {
        spyk(SettingsProviderImpl(ConfigStore)) {
            every { forbiddenFolders } returns emptyList()
            every { useStreaming } returns false
            every { gigaModel } returns selectedModel
            every { temperature } returns 0.2f
            every { systemPrompt } returns DEFAULT_SYSTEM_PROMPT
        }
    }

    companion object {
        private val selectedModel = GigaModel.QwenFlash

        private var gigaRestChatAPI: GigaRestChatAPI? = null
        private var qwenChatAPI: QwenChatAPI? = null
        private val httpRequestCount = AtomicLong(0)
        private val httpRequestTotalNanos = AtomicLong(0)

        @JvmStatic
        @AfterAll
        fun finish() {
            when (selectedModel.provider) {
                LlmProvider.GIGA -> println("Spent: ${gigaRestChatAPI?.getSessionTokenUsage() ?: "n/a"}")
                LlmProvider.QWEN -> println("Spent: ${qwenChatAPI?.getSessionTokenUsage() ?: "n/a"}")
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

    private val filesUtil: FilesToolUtil by lazy { FilesToolUtil(spySettings) }
    private val testOverrideModule: DI.Module = DI.Module("TestOverrideModule") {
        bindSingleton<SettingsProvider>(overrides = true) { spySettings }
        bindSingleton<FilesToolUtil>(overrides = true) { filesUtil }
        bindSingleton<GigaRestChatAPI>(overrides = true) {
            if (gigaRestChatAPI == null) {
                gigaRestChatAPI = GigaRestChatAPI(instance(), instance()).apply {
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
                qwenChatAPI = QwenChatAPI(instance()).apply {
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
        bindSingleton<GigaChatAPI>(overrides = true) {
            when (selectedModel.provider) {
                LlmProvider.GIGA -> instance<GigaRestChatAPI>()
                LlmProvider.QWEN -> instance<QwenChatAPI>()
            }
        }
    }

    @BeforeEach
    fun checkEnvironment() {
        val apiKeyName = when (selectedModel.provider) {
            LlmProvider.GIGA -> "GIGA_KEY"
            LlmProvider.QWEN -> "QWEN_KEY"
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
            [{"app-bundle-id":"ru.keepcoder.Telegram","app-name":"WezTerm"},
             {"app-bundle-id":"ru.yandex.desktop.disk2","app-name":"Yandex.Disk.2"}]
        """.trimIndent()

        coEvery { testOpenApp.invoke(any()) } returns "Opened"

        val di = DI.invoke(allowSilentOverride = true) {
            import(mainDiModule)
            import(testOverrideModule, allowOverride = true)
            bindProvider<DI> { this.di }
            bindSingleton<ToolShowApps> { testGetApps }
            bindSingleton<ToolOpen> { testOpenApp }
        }

        val agent = GraphBasedAgent(di, objectMapper)
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
            "Посмотри, был ли в истории example",
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

        val realToolDel = ToolCalendarDeleteEvent(ToolRunBashCommand)
        val toolCalendarDeleteEvent: ToolCalendarDeleteEvent = spyk(realToolDel)

        coEvery { toolCalendarListEvents.invoke(any()) } returns "10:00 - 11:00: Важная встреча"
        coEvery { toolCalendarDeleteEvent.invoke(any()) } returns "Deleted"

        runScenarioWithMocks(userPrompt) {
            bindSingleton<ToolCalendarListEvents> { toolCalendarListEvents }
            bindSingleton<ToolCalendarDeleteEvent> { toolCalendarDeleteEvent }
        }

        // Agent should find the event first, then delete it by title
        coVerify(atLeast = 1) { toolCalendarListEvents.invoke(any()) }
        coVerify(atLeast = 1) {
            toolCalendarDeleteEvent.invoke(match { it.title.contains("Важная встреча", ignoreCase = true) })
        }
    }

    @ParameterizedTest(name = "scenario9_findCalendarEvent[{index}] {0}")
    @ValueSource(
        strings = [
            "Найди событие в календаре на эту неделю",
            "Покажи события в календаре на текущую неделю",
            "Поищи в календаре встречи на этой неделе",
        ]
    )
    fun scenario9_findCalendarEvent(userPrompt: String) = runTest {
        val realTool = ToolCalendarListEvents(ToolRunBashCommand)
        val toolCalendarListEvents: ToolCalendarListEvents = spyk(realTool)

        coEvery { toolCalendarListEvents.invoke(any()) } returns "[]"

        runScenarioWithMocks(userPrompt) {
            bindSingleton<ToolCalendarListEvents> { toolCalendarListEvents }
        }
        coVerify(exactly = 1) { toolCalendarListEvents.invoke(any()) }
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

        coEvery { toolCreatePlotFromCsv.invoke(any()) } returns "Plot saved"
        coEvery { toolListFiles.invoke(any()) } returns "[\"sample.csv\"]"

        runScenarioWithMocks(userPrompt) {
            bindSingleton<ToolCreatePlotFromCsv> { toolCreatePlotFromCsv }
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
        coEvery { toolFindFilesByName.suspendInvoke(any()) } returns "[\"/tmp/test-data/test_integration.txt\"]"

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

        coEvery { toolFindFilesByName.suspendInvoke(any()) } returns "[\"/tmp/test-data/test_integration.txt\"]"
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

        coEvery { toolMoveFile.invoke(any()) } returns "Moved"

        runScenarioWithMocks(userPrompt) {
            bindSingleton<ToolMoveFile> { toolMoveFile }
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

        coEvery { toolReadPdfPages.invoke(any()) } returns "Page 1 content"

        runScenarioWithMocks(userPrompt) {
            bindSingleton<ToolReadPdfPages> { toolReadPdfPages }
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

        coEvery { toolMailUnreadMessagesCount.invoke(any()) } returns "0"
        coEvery { toolMailListMessages.invoke(any()) } returns "[]"

        runScenarioWithMocks(userPrompt) {
            bindSingleton<ToolMailUnreadMessagesCount> { toolMailUnreadMessagesCount }
            bindSingleton<ToolMailListMessages> { toolMailListMessages }
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

        coEvery { toolMailSendNewMessage.invoke(any()) } returns "Sent"

        runScenarioWithMocks(userPrompt) {
            bindSingleton<ToolMailSendNewMessage> { toolMailSendNewMessage }
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
        val agent = GraphBasedAgent(di, objectMapper)
        agent.execute(userPrompt)
    }
}
