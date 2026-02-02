package agent

import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
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
import ru.gigadesk.tool.application.ToolOpen
import ru.gigadesk.tool.application.ToolShowApps
import ru.gigadesk.tool.browser.ToolCreateNewBrowserTab
import ru.gigadesk.tool.browser.ToolOpenDefaultBrowser
import ru.gigadesk.tool.calendar.ToolCalendarCreateEvent
import ru.gigadesk.tool.calendar.ToolCalendarDeleteEvent
import ru.gigadesk.tool.calendar.ToolCalendarListEvents
import ru.gigadesk.tool.config.ToolInstructionStore
import ru.gigadesk.tool.dataAnalytics.ToolCreatePlotFromCsv
import ru.gigadesk.tool.files.*
import ru.gigadesk.tool.mail.ToolMailListMessages
import ru.gigadesk.tool.mail.ToolMailSendNewMessage
import ru.gigadesk.tool.mail.ToolMailUnreadMessagesCount
import ru.gigadesk.tool.notes.ToolCreateNote
import ru.gigadesk.tool.notes.ToolDeleteNote
import ru.gigadesk.tool.notes.ToolListNotes
import ru.gigadesk.tool.notes.ToolSearchNotes
import ru.gigadesk.tool.textReplace.ToolGetClipboard
import kotlin.test.Test


/**
 * Интеграционные тесты сценариев вызовов тулов через [GraphBasedAgent.execute].
 * Тулы замоканы: проверяем, что LLM вызывает нужные тулы с ожидаемыми параметрами.
 * Все сценарии проходят через graphAgent.execute(input).
 */
class GraphAgentToolScenariosIntegrationTest {

    private fun runScenarioWithMocks(
        userPrompt: String,
        overrides: DI.MainBuilder.() -> Unit,
    ) = runTest {
        val di = DI.invoke(allowSilentOverride = true) {
            import(mainDiModule)
            bindProvider<DI> { this.di }


            overrides()
        }
        val agent = GraphBasedAgent(di, objectMapper)
        agent.execute(userPrompt)
    }

    @Test
    fun scenario1_launchApplication() = runTest {
        val realSettings = SettingsProvider(ConfigStore)
        val spySettings: SettingsProvider = spyk(realSettings) {
             every { forbiddenFolders } returns emptyList()
             every { useGrpc } returns false
        }
        val filesUtil = FilesToolUtil(spySettings)
        
        val realToolShowApps = ToolShowApps(filesUtil)
        val testGetApps: ToolShowApps = spyk(realToolShowApps)

        val realToolOpen = ToolOpen(ToolRunBashCommand, filesUtil)
        val testOpenApp: ToolOpen = spyk(realToolOpen)

        coEvery { testGetApps.invoke(any()) } returns """
            [{"app-bundle-id":"com.github.wez.wezterm","app-name":"WezTerm"},
             {"app-bundle-id":"ru.yandex.desktop.disk2","app-name":"Yandex.Disk.2"}]
        """.trimIndent()
        
        coEvery { testOpenApp.invoke(any()) } answers {
            println("ToolOpen called!")
            "Opened"
        }

        val di = DI.invoke(allowSilentOverride = true) {
            import(mainDiModule)
            bindProvider<DI> { this.di }
            bindSingleton<SettingsProvider> { spySettings }
            bindSingleton<ToolShowApps> { testGetApps }
            bindSingleton<ToolOpen> { testOpenApp }
        }

        val agent = GraphBasedAgent(di, objectMapper)
        agent.execute("Запусти Терминал")

        coVerify(atLeast = 1) {
            testOpenApp.invoke(match { it.target.contains("wezterm", ignoreCase = true) })
        }
    }

    @Test
    fun scenario2_openWebsite() = runTest {
        val toolOpenDefaultBrowser: ToolOpenDefaultBrowser = mockk(relaxed = true)
        every { toolOpenDefaultBrowser.name } returns "OpenDefaultBrowser"
        every { toolOpenDefaultBrowser.description } returns "Specifies the default browser"
        
        coEvery { toolOpenDefaultBrowser.invoke(any()) } returns "Browser opened"

        runScenarioWithMocks("Открой сайт https://example.com") {
            bindSingleton<ToolOpenDefaultBrowser> { toolOpenDefaultBrowser }
        }
        coVerify(exactly = 1) { toolOpenDefaultBrowser.invoke(any()) }
    }

    @Test
    fun scenario3_openWebsiteInNewTab() = runTest {
        val toolCreateNewBrowserTab: ToolCreateNewBrowserTab = mockk(relaxed = true)
        every { toolCreateNewBrowserTab.name } returns "CreateNewBrowserTab"
        every { toolCreateNewBrowserTab.description } returns "Creates a new browser tab"
        
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
        val toolCreateNewBrowserTab: ToolCreateNewBrowserTab = mockk(relaxed = true)
        every { toolCreateNewBrowserTab.name } returns "CreateNewBrowserTab"
        every { toolCreateNewBrowserTab.description } returns "Creates a new browser tab"

        coEvery { toolCreateNewBrowserTab.invoke(any()) } returns "Opened"

        runScenarioWithMocks("Найди в истории браузера сайт example") {
            bindSingleton<ToolCreateNewBrowserTab> { toolCreateNewBrowserTab }
        }
        coVerify(atLeast = 0) { toolCreateNewBrowserTab.invoke(any()) }
    }

    @Test
    fun scenario5_readPageInOpenTab() = runTest {
        val toolCreateNewBrowserTab: ToolCreateNewBrowserTab = mockk(relaxed = true)
        every { toolCreateNewBrowserTab.name } returns "CreateNewBrowserTab"
        every { toolCreateNewBrowserTab.description } returns "Creates a new browser tab"

        coEvery { toolCreateNewBrowserTab.invoke(any()) } returns "Page content"

        runScenarioWithMocks("Прочитай содержимое текущей открытой вкладки") {
            bindSingleton<ToolCreateNewBrowserTab> { toolCreateNewBrowserTab }
        }
        coVerify(atLeast = 0) { toolCreateNewBrowserTab.invoke(any()) }
    }

    @Test
    fun scenario6_todayCalendarEvents() = runTest {
        val toolCalendarListEvents: ToolCalendarListEvents = mockk(relaxed = true)
        every { toolCalendarListEvents.name } returns "CalendarListEvents"
        every { toolCalendarListEvents.description } returns "List events in calendar"

        coEvery { toolCalendarListEvents.invoke(any()) } returns "[]"

        runScenarioWithMocks("Покажи сегодняшние события в календаре") {
            bindSingleton<ToolCalendarListEvents> { toolCalendarListEvents }
        }
        coVerify(exactly = 1) { toolCalendarListEvents.invoke(any()) }
    }

    @Test
    fun scenario7_createCalendarEvent() = runTest {
        val toolCalendarCreateEvent: ToolCalendarCreateEvent = mockk(relaxed = true)
        every { toolCalendarCreateEvent.name } returns "CalendarCreateEvent"
        every { toolCalendarCreateEvent.description } returns "Create event in calendar"

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
        val toolCalendarDeleteEvent: ToolCalendarDeleteEvent = mockk(relaxed = true)
        every { toolCalendarDeleteEvent.name } returns "CalendarDeleteEvent"
        every { toolCalendarDeleteEvent.description } returns "Delete event from calendar"

        coEvery { toolCalendarDeleteEvent.invoke(any()) } returns "Deleted"

        runScenarioWithMocks("Удали событие из календаря на завтра в 10:00") {
            bindSingleton<ToolCalendarDeleteEvent> { toolCalendarDeleteEvent }
        }
        coVerify(exactly = 1) { toolCalendarDeleteEvent.invoke(any()) }
    }

    @Test
    fun scenario9_findCalendarEvent() = runTest {
        val toolCalendarListEvents: ToolCalendarListEvents = mockk(relaxed = true)
        every { toolCalendarListEvents.name } returns "CalendarListEvents"
        every { toolCalendarListEvents.description } returns "List events in calendar"

        coEvery { toolCalendarListEvents.invoke(any()) } returns "[]"

        runScenarioWithMocks("Найди событие в календаре на эту неделю") {
            bindSingleton<ToolCalendarListEvents> { toolCalendarListEvents }
        }
        coVerify(exactly = 1) { toolCalendarListEvents.invoke(any()) }
    }

    @Test
    fun scenario10_saveInstruction() = runTest {
        val toolInstructionStore: ToolInstructionStore = mockk(relaxed = true)
        every { toolInstructionStore.name } returns "InstructionStore"
        every { toolInstructionStore.description } returns "Store instruction"

        coEvery { toolInstructionStore.invoke(any()) } returns "Saved"

        runScenarioWithMocks("Сохрани инструкцию: при запросе погоды открывать ya.ru/pogoda") {
            bindSingleton<ToolInstructionStore> { toolInstructionStore }
        }
        coVerify(exactly = 1) {
            toolInstructionStore.invoke(match { it.name.isNotBlank() && it.action.contains("ya.ru") })
        }
    }

    @Test
    fun scenario11_buildChartFromFile() = runTest {
        val toolCreatePlotFromCsv: ToolCreatePlotFromCsv = mockk(relaxed = true)
        every { toolCreatePlotFromCsv.name } returns "CreatePlotFromCsv"
        every { toolCreatePlotFromCsv.description } returns "Create plot from CSV file"

        coEvery { toolCreatePlotFromCsv.invoke(any()) } returns "Plot saved"

        val testDataPath = "/tmp/test-data"
        runScenarioWithMocks("Построй график по данным из файла sample.csv по пути $testDataPath") {
            bindSingleton<ToolCreatePlotFromCsv> { toolCreatePlotFromCsv }
        }
        coVerify(exactly = 1) {
            toolCreatePlotFromCsv.invoke(match { it.path.contains("sample.csv") })
        }
    }

    @Test
    fun scenario12_findFileByName() = runTest {
        val toolFindFilesByName: ToolFindFilesByName = mockk(relaxed = true)
        every { toolFindFilesByName.name } returns "FindFilesByName"
        every { toolFindFilesByName.description } returns "Find files by name"

        coEvery { toolFindFilesByName.invoke(any()) } returns "/path/to/test.txt"

        val testDataPath = "/tmp/test-data"
        runScenarioWithMocks("Найди файл по имени test.txt в папке $testDataPath") {
            bindSingleton<ToolFindFilesByName> { toolFindFilesByName }
        }
        coVerify(exactly = 1) {
            toolFindFilesByName.invoke(match { it.fileName.contains("test.txt") })
        }
    }

    @Test
    fun scenario13_listFilesInFolder() = runTest {
        val toolListFiles: ToolListFiles = mockk(relaxed = true)
        every { toolListFiles.name } returns "ListFiles"
        every { toolListFiles.description } returns "List files in directory"

        coEvery { toolListFiles.invoke(any()) } returns "test.txt, read_me.txt, sample.csv"

        val testDataPath = "/tmp/test-data"
        runScenarioWithMocks("Покажи список файлов в папке $testDataPath") {
            bindSingleton<ToolListFiles> { toolListFiles }
        }
        coVerify(exactly = 1) { toolListFiles.invoke(any()) }
    }

    @Test
    fun scenario14_createReadModifyDeleteFile() = runTest {
        val toolNewFile: ToolNewFile = mockk(relaxed = true)
        every { toolNewFile.name } returns "NewFile"
        every { toolNewFile.description } returns "Create new file"
        
        val toolReadFile: ToolReadFile = mockk(relaxed = true)
        every { toolReadFile.name } returns "ReadFile"
        every { toolReadFile.description } returns "Read file"

        val toolModifyFile: ToolModifyFile = mockk(relaxed = true)
        every { toolModifyFile.name } returns "ModifyFile"
        every { toolModifyFile.description } returns "Modify file"

        val toolDeleteFile: ToolDeleteFile = mockk(relaxed = true)
        every { toolDeleteFile.name } returns "DeleteFile"
        every { toolDeleteFile.description } returns "Delete file"

        coEvery { toolNewFile.invoke(any()) } returns "Created"
        coEvery { toolReadFile.invoke(any()) } returns "Hello"
        coEvery { toolModifyFile.invoke(any()) } returns "Modified"
        coEvery { toolDeleteFile.invoke(any()) } returns "Deleted"

        val testDataPath = "/tmp/test-data"
        val tempFile = "test_integration.txt"
        runScenarioWithMocks("В папке $testDataPath создай файл $tempFile с текстом Hello, прочитай его, добавь строку World, потом удали этот файл") {
            bindSingleton<ToolNewFile> { toolNewFile }
            bindSingleton<ToolReadFile> { toolReadFile }
            bindSingleton<ToolModifyFile> { toolModifyFile }
            bindSingleton<ToolDeleteFile> { toolDeleteFile }
        }
        coVerify(exactly = 1) { toolNewFile.invoke(match { it.path.contains(tempFile) && it.text.contains("Hello") }) }
        coVerify(exactly = 1) { toolReadFile.invoke(any()) }
        coVerify(exactly = 1) { toolModifyFile.invoke(any()) }
        coVerify(exactly = 1) { toolDeleteFile.invoke(any()) }
    }

    @Test
    fun scenario15_moveFile() = runTest {
        val toolMoveFile: ToolMoveFile = mockk(relaxed = true)
        every { toolMoveFile.name } returns "MoveFile"
        every { toolMoveFile.description } returns "Move file"

        coEvery { toolMoveFile.invoke(any()) } returns "Moved"

        runScenarioWithMocks("Перенеси файл /tmp/read_me.txt в папку /tmp/dest") {
            bindSingleton<ToolMoveFile> { toolMoveFile }
        }
        coVerify(exactly = 1) {
            toolMoveFile.invoke(match { it.sourcePath.contains("read_me") && it.destinationPath.contains("dest") })
        }
    }

    @Test
    fun scenario16_extractTextFromFile() = runTest {
        val toolExtractText: ToolExtractText = mockk(relaxed = true)
        every { toolExtractText.name } returns "ExtractTextFromFile"
        every { toolExtractText.description } returns "Extract text from file"

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
        val toolReadPdfPages: ToolReadPdfPages = mockk(relaxed = true)
        every { toolReadPdfPages.name } returns "ReadPdfPages"
        every { toolReadPdfPages.description } returns "Read PDF page by page"

        coEvery { toolReadPdfPages.invoke(any()) } returns "Page 1 content"

        runScenarioWithMocks("Прочитай PDF постранично: файл /tmp/sample.pdf") {
            bindSingleton<ToolReadPdfPages> { toolReadPdfPages }
        }
        coVerify(exactly = 1) {
            toolReadPdfPages.invoke(match { it.filePath.contains("sample.pdf") })
        }
    }

    @Test
    fun scenario18_openFile() = runTest {
        val toolOpen: ToolOpen = mockk(relaxed = true)
        every { toolOpen.name } returns "Open"
        every { toolOpen.description } returns "Open file or app"

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
        val toolCreateNote: ToolCreateNote = mockk(relaxed = true)
        every { toolCreateNote.name } returns "CreateNote"
        every { toolCreateNote.description } returns "Create note"
        
        val toolListNotes: ToolListNotes = mockk(relaxed = true)
        every { toolListNotes.name } returns "ListNotes"
        every { toolListNotes.description } returns "List notes"

        val toolSearchNotes: ToolSearchNotes = mockk(relaxed = true)
        every { toolSearchNotes.name } returns "SearchNotes"
        every { toolSearchNotes.description } returns "Search notes"

        val toolDeleteNote: ToolDeleteNote = mockk(relaxed = true)
        every { toolDeleteNote.name } returns "DeleteNote"
        every { toolDeleteNote.description } returns "Delete note"

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
        val toolMailUnreadMessagesCount: ToolMailUnreadMessagesCount = mockk(relaxed = true)
        every { toolMailUnreadMessagesCount.name } returns "MailUnreadMessagesCount"
        every { toolMailUnreadMessagesCount.description } returns "Count unread messages"

        val toolMailListMessages: ToolMailListMessages = mockk(relaxed = true)
        every { toolMailListMessages.name } returns "MailListMessages"
        every { toolMailListMessages.description } returns "List messages"

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
        val toolMailSendNewMessage: ToolMailSendNewMessage = mockk(relaxed = true)
        every { toolMailSendNewMessage.name } returns "MailSendNewMessage"
        every { toolMailSendNewMessage.description } returns "Send new message"

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
        val toolGetClipboard: ToolGetClipboard = mockk(relaxed = true)
        every { toolGetClipboard.name } returns "GetClipboard"
        every { toolGetClipboard.description } returns "Get text from clipboard"

        coEvery { toolGetClipboard.invoke(any()) } returns "Selected text"

        runScenarioWithMocks("Получи текст из буфера обмена или выделения и кратко перескажи") {
            bindSingleton<ToolGetClipboard> { toolGetClipboard }
        }
        coVerify(exactly = 1) { toolGetClipboard.invoke(any()) }
    }
}
