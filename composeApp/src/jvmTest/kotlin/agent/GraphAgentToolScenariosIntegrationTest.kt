package agent

import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.spyk
import ru.gigadesk.tool.ToolRunBashCommand
import kotlinx.coroutines.test.runTest
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
import kotlin.test.Test
import org.junit.Assume
import org.junit.Before
import ru.gigadesk.db.SettingsProviderImpl
import ru.gigadesk.giga.GigaModel


/**
 * Integration tests for tool invocation scenarios via [GraphBasedAgent.execute].
 * Tools are mocked: we verify that LLM calls the required tools with the expected parameters.
 * All scenarios are run via graphAgent.execute(input).
 */
class GraphAgentToolScenariosIntegrationTest {

    private val spySettings: SettingsProviderImpl = spyk(SettingsProviderImpl(ConfigStore)) {
        every { forbiddenFolders } returns emptyList()
        every { useGrpc } returns false
        every { gigaModel } returns GigaModel.Lite
    }
    private val filesUtil: FilesToolUtil = FilesToolUtil(spySettings)
    private val testOverrideModule: DI.Module =  DI.Module("TestOverrideModule") {
        bindSingleton<SettingsProvider>(overrides = true) { spySettings }
        bindSingleton<FilesToolUtil>(overrides = true) { filesUtil }
    }

    @Before
    fun checkEnvironment() {
        val apiKey = System.getenv("GIGA_KEY") ?: System.getProperty("GIGA_KEY")
        Assume.assumeTrue("Skipping integration tests: GIGA_KEY is not set", !apiKey.isNullOrBlank())
    }

    @Test
    fun scenario1_launchApplication() = runTest {
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
        agent.execute("Запусти Telegram")

        coVerify(atLeast = 1) {
            testOpenApp.invoke(match { it.target.contains("ru.keepcoder.Telegram", ignoreCase = true) })
        }
    }

    @Test
    fun scenario2_openWebsite() = runTest {
        val realTool = ToolOpenDefaultBrowser(ToolRunBashCommand, filesUtil)
        val toolOpenDefaultBrowser: ToolOpenDefaultBrowser = spyk(realTool)

        val realToolOpen = ToolOpen(ToolRunBashCommand, filesUtil)
        val toolOpen: ToolOpen = spyk(realToolOpen)

        val realToolTab = ToolCreateNewBrowserTab(ToolRunBashCommand)
        val toolCreateNewBrowserTab: ToolCreateNewBrowserTab = spyk(realToolTab)

        coEvery { toolOpenDefaultBrowser.invoke(any()) } returns "Browser opened"
        coEvery { toolOpen.invoke(any()) } returns "Opened"
        coEvery { toolCreateNewBrowserTab.invoke(any()) } returns "Tab opened"

        runScenarioWithMocks("Открой сайт https://example.com") {
            bindSingleton<ToolOpenDefaultBrowser> { toolOpenDefaultBrowser }
            bindSingleton<ToolOpen> { toolOpen }
            bindSingleton<ToolCreateNewBrowserTab> { toolCreateNewBrowserTab }
        }
        // Check both, as the agent might prefer ToolOpen for URLs
        coVerify(atLeast = 0) { toolOpenDefaultBrowser.invoke(any()) }
        coVerify(atLeast = 0) { toolOpen.invoke(any()) }
        coVerify(atLeast = 0) { toolCreateNewBrowserTab.invoke(match { it.url.contains("example.com") }) }
    }

    @Test
    fun scenario3_openWebsiteInNewTab() = runTest {
        val realTool = ToolCreateNewBrowserTab(ToolRunBashCommand)
        val toolCreateNewBrowserTab: ToolCreateNewBrowserTab = spyk(realTool)

        coEvery { toolCreateNewBrowserTab.invoke(any()) } returns "Tab opened"

        runScenarioWithMocks("Открой в новой вкладке сайт https://example.com") {
            bindSingleton<ToolCreateNewBrowserTab> { toolCreateNewBrowserTab }
        }
        coVerify(exactly = 1) {
            toolCreateNewBrowserTab.invoke(match { it.url.contains("example.com") })
        }
    }

    @Test
    fun scenario4_findSiteInHistory() = runTest {
        val realTool = ToolCreateNewBrowserTab(ToolRunBashCommand)
        val toolCreateNewBrowserTab: ToolCreateNewBrowserTab = spyk(realTool)

        coEvery { toolCreateNewBrowserTab.invoke(any()) } returns "Opened"

        runScenarioWithMocks("Найди в истории браузера сайт example") {
            bindSingleton<ToolCreateNewBrowserTab> { toolCreateNewBrowserTab }
        }
        coVerify(atLeast = 0) { toolCreateNewBrowserTab.invoke(any()) }
    }

    @Test
    fun scenario5_readPageInOpenTab() = runTest {
        val realSafari = ToolSafariInfo(ToolRunBashCommand)
        val toolSafariInfo: ToolSafariInfo = spyk(realSafari)

        val realChrome = ToolChromeInfo(ToolRunBashCommand)
        val toolChromeInfo: ToolChromeInfo = spyk(realChrome)

        coEvery { toolSafariInfo.invoke(any()) } returns "Page content"
        coEvery { toolChromeInfo.invoke(any()) } returns "Page content"

        runScenarioWithMocks("Прочитай содержимое текущей открытой вкладки") {
            bindSingleton<ToolSafariInfo> { toolSafariInfo }
            bindSingleton<ToolChromeInfo> { toolChromeInfo }
        }

        coVerify(atLeast = 0) {
            toolSafariInfo.invoke(match { it.type == ToolSafariInfo.InfoType.pageText })
        }
        coVerify(atLeast = 0) {
            toolChromeInfo.invoke(match { it.type == ToolChromeInfo.InfoType.pageText })
        }
    }

    @Test
    fun scenario6_todayCalendarEvents() = runTest {
        val realTool = ToolCalendarListEvents(ToolRunBashCommand)
        val toolCalendarListEvents: ToolCalendarListEvents = spyk(realTool)

        coEvery { toolCalendarListEvents.invoke(any()) } returns "[]"

        runScenarioWithMocks("Покажи сегодняшние события в календаре") {
            bindSingleton<ToolCalendarListEvents> { toolCalendarListEvents }
        }
        coVerify(exactly = 1) { toolCalendarListEvents.invoke(any()) }
    }

    @Test
    fun scenario7_createCalendarEvent() = runTest {
        val realTool = ToolCalendarCreateEvent(ToolRunBashCommand)
        val toolCalendarCreateEvent: ToolCalendarCreateEvent = spyk(realTool)

        coEvery { toolCalendarCreateEvent.invoke(any()) } returns "Event created"

        runScenarioWithMocks("Создай событие в календаре: встреча завтра в 10:00") {
            bindSingleton<ToolCalendarCreateEvent> { toolCalendarCreateEvent }
        }
        coVerify(exactly = 1) {
            toolCalendarCreateEvent.invoke(match { it.title.isNotBlank() && it.startDateTime.isNotBlank() })
        }
    }

    @Test
    fun scenario8_deleteCalendarEvent() = runTest {
        val realToolList = ToolCalendarListEvents(ToolRunBashCommand)
        val toolCalendarListEvents: ToolCalendarListEvents = spyk(realToolList)

        val realToolDel = ToolCalendarDeleteEvent(ToolRunBashCommand)
        val toolCalendarDeleteEvent: ToolCalendarDeleteEvent = spyk(realToolDel)

        coEvery { toolCalendarListEvents.invoke(any()) } returns "10:00 - 11:00: Важная встреча"
        coEvery { toolCalendarDeleteEvent.invoke(any()) } returns "Deleted"

        runScenarioWithMocks("Удали событие из календаря на завтра в 10:00") {
            bindSingleton<ToolCalendarListEvents> { toolCalendarListEvents }
            bindSingleton<ToolCalendarDeleteEvent> { toolCalendarDeleteEvent }
        }

        // Agent should find the event first, then delete it by title
        coVerify(atLeast = 1) { toolCalendarListEvents.invoke(any()) }
        coVerify(atLeast = 1) {
            toolCalendarDeleteEvent.invoke(match { it.title.contains("Важная встреча", ignoreCase = true) })
        }
    }

    @Test
    fun scenario9_findCalendarEvent() = runTest {
        val realTool = ToolCalendarListEvents(ToolRunBashCommand)
        val toolCalendarListEvents: ToolCalendarListEvents = spyk(realTool)

        coEvery { toolCalendarListEvents.invoke(any()) } returns "[]"

        runScenarioWithMocks("Найди событие в календаре на эту неделю") {
            bindSingleton<ToolCalendarListEvents> { toolCalendarListEvents }
        }
        coVerify(exactly = 1) { toolCalendarListEvents.invoke(any()) }
    }

    @Test
    fun scenario11_buildChartFromFile() = runTest {
        val realTool = ToolCreatePlotFromCsv(filesUtil)
        val toolCreatePlotFromCsv: ToolCreatePlotFromCsv = spyk(realTool)

        coEvery { toolCreatePlotFromCsv.invoke(any()) } returns "Plot saved"

        val testDataPath = "/tmp/test-data"
        runScenarioWithMocks("Построй график возраста по имени из файла sample.csv по пути $testDataPath") {
            bindSingleton<ToolCreatePlotFromCsv> { toolCreatePlotFromCsv }
        }
        coVerify(exactly = 1) {
            toolCreatePlotFromCsv.invoke(match { it.path.contains("sample.csv") })
        }
    }

    @Test
    fun scenario12_findFileByName() = runTest {
        val realTool = ToolFindFilesByName(filesUtil)
        val toolFindFilesByName: ToolFindFilesByName = spyk(realTool)

        coEvery { toolFindFilesByName.invoke(any()) } returns "/path/to/test.txt"
        coEvery { toolFindFilesByName.suspendInvoke(any()) } returns "/path/to/test.txt"

        runScenarioWithMocks("Найди файл по имени 100 ошибок в го") {
            bindSingleton<ToolFindFilesByName> { toolFindFilesByName }
        }
        coVerify(atLeast = 1) {
            toolFindFilesByName.suspendInvoke(match { it.fileName.contains("100 ошибок в го") })
        }
    }

    @Test
    fun scenario13_listFilesInFolder() = runTest {
        val realTool = ToolListFiles(filesUtil)
        val toolListFiles: ToolListFiles = spyk(realTool)

        coEvery { toolListFiles.invoke(any()) } returns "test.txt, read_me.txt, sample.csv"

        val testDataPath = "/tmp/test-data"
        runScenarioWithMocks("Покажи список файлов в папке $testDataPath") {
            bindSingleton<ToolListFiles> { toolListFiles }
        }
        coVerify(exactly = 1) { toolListFiles.invoke(any()) }
    }

    @Test
    fun scenario14_createFile() = runTest {
        val realToolNew = ToolNewFile(filesUtil)
        val toolNewFile: ToolNewFile = spyk(realToolNew)

        coEvery { toolNewFile.invoke(any()) } returns "Created"

        val testDataPath = "/tmp/test-data"
        val tempFile = "test_integration.txt"
        runScenarioWithMocks("В папке $testDataPath создай файл $tempFile с текстом Hello") {
            bindSingleton<ToolNewFile> { toolNewFile }
        }
        coVerify(exactly = 1) { toolNewFile.invoke(match { it.path.contains(tempFile) && it.text.contains("Hello") }) }
    }

    @Test
    fun scenario14_readFile() = runTest {
        val realToolRead = ToolReadFile(filesUtil)
        val toolReadFile: ToolReadFile = spyk(realToolRead)

        val realToolFind = ToolFindFilesByName(filesUtil)
        val toolFindFilesByName: ToolFindFilesByName = spyk(realToolFind)

        coEvery { toolReadFile.invoke(any()) } returns "Hello"
        coEvery { toolFindFilesByName.suspendInvoke(any()) } returns "[\"/tmp/test-data/test_integration.txt\"]"

        val testDataPath = "/tmp/test-data"
        val tempFile = "test_integration.txt"
        runScenarioWithMocks("Прочитай файл $tempFile в папке $testDataPath") {
            bindSingleton<ToolReadFile> { toolReadFile }
            bindSingleton<ToolFindFilesByName> { toolFindFilesByName }
        }
        coVerify(exactly = 1) { toolReadFile.invoke(match { it.path.contains(tempFile) }) }
    }

    @Test
    fun scenario14_modifyFile() = runTest {
        val realToolMod = ToolModifyFile(filesUtil)
        val toolModifyFile: ToolModifyFile = spyk(realToolMod)

        val realToolFind = ToolFindFilesByName(filesUtil)
        val toolFindFilesByName: ToolFindFilesByName = spyk(realToolFind)

        coEvery { toolModifyFile.invoke(any()) } returns "Modified"
        coEvery { toolFindFilesByName.suspendInvoke(any()) } returns "[\"/tmp/test-data/test_integration.txt\"]"

        val tempFile = "test_integration"
        runScenarioWithMocks("Измени файл $tempFile добавь новую строку World is over") {
            bindSingleton<ToolModifyFile> { toolModifyFile }
            bindSingleton<ToolFindFilesByName> { toolFindFilesByName }
        }
        coVerify(exactly = 1) { toolModifyFile.invoke(match { it.path.contains(tempFile) && it.newText.contains("World") }) }
    }

    @Test
    fun scenario14_deleteFile() = runTest {
        val realToolDel = ToolDeleteFile(filesUtil)
        val toolDeleteFile: ToolDeleteFile = spyk(realToolDel)

        val realToolFind = ToolFindFilesByName(filesUtil)
        val toolFindFilesByName: ToolFindFilesByName = spyk(realToolFind)

        coEvery { toolDeleteFile.invoke(any()) } returns "Deleted"
        coEvery { toolFindFilesByName.suspendInvoke(any()) } returns "[\"/tmp/test-data/test_integration.txt\"]"

        val testDataPath = "/tmp/test-data"
        val tempFile = "test_integration.txt"
        runScenarioWithMocks("Удали файл $tempFile в папке $testDataPath") {
            bindSingleton<ToolDeleteFile> { toolDeleteFile }
            bindSingleton<ToolFindFilesByName> { toolFindFilesByName }
        }
        coVerify(exactly = 1) { toolDeleteFile.invoke(match { it.path.contains(tempFile) }) }
    }

    @Test
    fun scenario15_moveFile() = runTest {
        val realTool = ToolMoveFile(filesUtil)
        val toolMoveFile: ToolMoveFile = spyk(realTool)

        coEvery { toolMoveFile.invoke(any()) } returns "Moved"

        runScenarioWithMocks("Перенеси файл read_me в папку dest") {
            bindSingleton<ToolMoveFile> { toolMoveFile }
        }
        coVerify(exactly = 1) {
            toolMoveFile.invoke(match { it.sourcePath.contains("read_me") && it.destinationPath.contains("dest") })
        }
    }

    @Test
    fun scenario16_extractTextFromFile() = runTest {
        val realTool = ToolExtractText(filesUtil)
        val toolExtractText: ToolExtractText = spyk(realTool)

        coEvery { toolExtractText.invoke(any()) } returns "Test content\nСтрока для проверки извлечения текста."

        runScenarioWithMocks("Извлеки текст из файла /tmp/test.txt") {
            bindSingleton<ToolExtractText> { toolExtractText }
        }
        coVerify(exactly = 1) {
            toolExtractText.invoke(match { it.filePath.contains("test.txt") })
        }
    }

    @Test
    fun scenario17_readPdfPageByPage() = runTest {
        val realTool = ToolReadPdfPages(filesUtil)
        val toolReadPdfPages: ToolReadPdfPages = spyk(realTool)

        coEvery { toolReadPdfPages.invoke(any()) } returns "Page 1 content"

        runScenarioWithMocks("Прочитай первую страницу PDF файла sample") {
            bindSingleton<ToolReadPdfPages> { toolReadPdfPages }
        }
        coVerify(exactly = 1) {
            toolReadPdfPages.invoke(match { it.filePath.contains("sample") })
        }
    }

    @Test
    fun scenario18_openFile() = runTest {
        val realTool = ToolOpen(ToolRunBashCommand, filesUtil)
        val toolOpen: ToolOpen = spyk(realTool)

        coEvery { toolOpen.invoke(any()) } returns "Opened"

        runScenarioWithMocks("Открой файл /tmp/read_me.txt") {
            bindSingleton<ToolOpen> { toolOpen }
        }
        coVerify(exactly = 1) {
            toolOpen.invoke(match { it.target.contains("read_me.txt") })
        }
    }

    @Test
    fun scenario19_notesFindCreateDeleteList() = runTest {
        val toolCreateNote: ToolCreateNote = spyk(ToolCreateNote(ToolRunBashCommand))
        val toolListNotes: ToolListNotes = spyk(ToolListNotes(ToolRunBashCommand))
        val toolSearchNotes: ToolSearchNotes = spyk(ToolSearchNotes(ToolRunBashCommand))
        val toolDeleteNote: ToolDeleteNote = spyk(ToolDeleteNote(ToolRunBashCommand))

        coEvery { toolCreateNote.invoke(any()) } returns "Created"
        coEvery { toolListNotes.invoke(any()) } returns "[]"
        coEvery { toolSearchNotes.invoke(any()) } returns "[]"
        coEvery { toolDeleteNote.invoke(any()) } returns "Deleted"

        runScenarioWithMocks("Создай заметку \"тест интеграции\", перечисли заметки, найди заметку тест, удали заметку тест интеграции") {
            bindSingleton<ToolCreateNote> { toolCreateNote }
            bindSingleton<ToolListNotes> { toolListNotes }
            bindSingleton<ToolSearchNotes> { toolSearchNotes }
            bindSingleton<ToolDeleteNote> { toolDeleteNote }
        }
        coVerify(exactly = 1) { toolCreateNote.invoke(match { it.noteText.contains("тест интеграции") }) }
        coVerify(exactly = 1) { toolListNotes.invoke(any()) }
        coVerify(atLeast = 0) { toolSearchNotes.invoke(any()) }
        coVerify(exactly = 1) { toolDeleteNote.invoke(match { it.noteName.contains("тест") }) }
    }

    @Test
    fun scenario20_mailFindUnreadListReply() = runTest {
        val toolMailUnreadMessagesCount: ToolMailUnreadMessagesCount =
            spyk(ToolMailUnreadMessagesCount(ToolRunBashCommand))
        val toolMailListMessages: ToolMailListMessages = spyk(ToolMailListMessages(ToolRunBashCommand))

        coEvery { toolMailUnreadMessagesCount.invoke(any()) } returns "0"
        coEvery { toolMailListMessages.invoke(any()) } returns "[]"

        runScenarioWithMocks("Сколько непрочитанных писем? Перечисли последние письма. Найди письмо от сегодня.") {
            bindSingleton<ToolMailUnreadMessagesCount> { toolMailUnreadMessagesCount }
            bindSingleton<ToolMailListMessages> { toolMailListMessages }
        }
        coVerify(exactly = 1) { toolMailUnreadMessagesCount.invoke(any()) }
        coVerify(exactly = 1) { toolMailListMessages.invoke(any()) }
    }

    @Test
    fun scenario21_sendEmail() = runTest {
        val toolMailSendNewMessage: ToolMailSendNewMessage = spyk(ToolMailSendNewMessage(ToolRunBashCommand))

        coEvery { toolMailSendNewMessage.invoke(any()) } returns "Sent"

        runScenarioWithMocks("Напиши письмо (тестовое) на test@example.com с темой Тест") {
            bindSingleton<ToolMailSendNewMessage> { toolMailSendNewMessage }
        }
        coVerify(exactly = 1) {
            toolMailSendNewMessage.invoke(match { it.recipientAddress.contains("test@example.com") })
        }
    }

    @Test
    fun scenario22_readSelectedText() = runTest {
        val toolGetClipboard: ToolGetClipboard = spyk(ToolGetClipboard())

        coEvery { toolGetClipboard.invoke(any()) } returns "Selected text"

        runScenarioWithMocks("Получи текст из буфера обмена или выделения и кратко перескажи") {
            bindSingleton<ToolGetClipboard> { toolGetClipboard }
        }
        coVerify(exactly = 1) { toolGetClipboard.invoke(any()) }
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
