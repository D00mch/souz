package agent

import io.mockk.*
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import org.kodein.di.bindSingleton
import ru.souz.agent.AgentId
import ru.souz.giga.GigaModel
import ru.souz.service.telegram.TelegramService
import ru.souz.tool.ToolRunBashCommand
import ru.souz.tool.files.FilesToolUtil
import ru.souz.tool.files.ToolExtractText
import ru.souz.tool.files.ToolFindFilesByName
import ru.souz.tool.mail.ToolMailSendNewMessage
import ru.souz.tool.notes.ToolCreateNote
import ru.souz.tool.presentation.ToolWebSearch
import ru.souz.tool.presentation.WebResearchClient
import ru.souz.tool.telegram.ToolTelegramSearch

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class GraphAgentComplexScenarios {

    private val selectedModel = GigaModel.AnthropicHaiku45
    private val agentType = AgentId.GRAPH
    private val support = AgentScenarioTestSupport(selectedModel, agentType)
    private val runTest = support::runTest
    private val filesUtil: FilesToolUtil
        get() = support.filesUtil

    @BeforeEach
    fun checkEnvironment() = support.checkEnvironment()

    @AfterAll
    fun finish() = support.finish()

    @ParameterizedTest(name = "scenario1_readFileThenSendEmailIfNoSecret[{index}] {0}")
    @ValueSource(
        strings = [
            "Прочти public-note.txt. Если в тексте нет слова secret, создай письмо на audit@example.com с темой " +
                    "Public Note и вставь в тело текст файла.",
            "Сделай по шагам: 1) найди и прочти файл public-note.txt; 2) если в нём нет слова secret, " +
                    "подготовь email для audit@example.com с темой Public Note и исходным текстом файла",
        ]
    )
    fun scenario1_readFileThenSendEmailIfNoSecret(userPrompt: String) = runTest {
        val toolFindFilesByName: ToolFindFilesByName = spyk(ToolFindFilesByName(filesUtil))
        val toolExtractText: ToolExtractText = spyk(ToolExtractText(filesUtil))
        val toolMailSendNewMessage: ToolMailSendNewMessage = spyk(ToolMailSendNewMessage(ToolRunBashCommand))

        val foundFilePath = "~/tmp/public-note.txt"
        val safeFileText = "launch approved for finance review"

        every { toolFindFilesByName.invoke(any()) } returns """["$foundFilePath"]"""
        coEvery { toolFindFilesByName.suspendInvoke(any()) } returns """["$foundFilePath"]"""
        every { toolExtractText.invoke(any()) } returns safeFileText
        coEvery { toolExtractText.suspendInvoke(any()) } returns safeFileText
        every { toolMailSendNewMessage.invoke(any()) } returns "Sent"
        coEvery { toolMailSendNewMessage.suspendInvoke(any()) } returns "Sent"

        runScenarioWithMocks(userPrompt) {
            bindSingleton<ToolFindFilesByName> { toolFindFilesByName }
            bindSingleton<ToolExtractText> { toolExtractText }
            bindSingleton<ToolMailSendNewMessage> { toolMailSendNewMessage }
        }

        coVerifyOrder {
            toolFindFilesByName.suspendInvoke(match { it.fileName.contains("public-note.txt") })
            toolExtractText.suspendInvoke(match { it.filePath.contains("public-note.txt") })
            toolMailSendNewMessage.suspendInvoke(any())
        }
        coVerify(exactly = 1) {
            toolMailSendNewMessage.suspendInvoke(
                match {
                    it.recipientAddress.contains("audit@example.com", ignoreCase = true) &&
                        (it.subject?.contains("Public Note", ignoreCase = true) == true) &&
                        (it.content == safeFileText) &&
                        !it.content.contains("secret", ignoreCase = true)
                }
            )
        }
    }

    @ParameterizedTest(name = "scenario2_searchAiEventInWebThenTelegramThenCreateNoteIfMentioned[{index}] {0}")
    @ValueSource(
        strings = [
            """
                Поищи в вебе, какие есть мероприятия по ИИ в Москве. Возьми первое ближайшее. 
                Поищи в моих ТГ группах, есть ли упоминания об этом мероприятнии. 
                Если есть, создай заметку с данными о мероприятии + тг события.
            """,
        ]
    )
    fun scenario2_searchAiEventInWebThenTelegramThenCreateNoteIfMentioned(userPrompt: String) = runTest {
        val toolWebSearch: ToolWebSearch = spyk(ToolWebSearch(WebResearchClient()))
        val toolTelegramSearch: ToolTelegramSearch = spyk(ToolTelegramSearch(mockk<TelegramService>()))
        val toolCreateNote: ToolCreateNote = spyk(ToolCreateNote(ToolRunBashCommand))

        val expectedEventName = "AI Product Day"
        val expectedAddress = "Ломоносов"
        val nearestEventTitle = "$expectedEventName Moscow 2026"
        val nearestEventUrl = "https://events.example.com/ai-product-day-moscow-2026"
        val nearestEventSnippet = "22 марта 2026, Москва, Кластер $expectedAddress. Конференция по AI-продуктам и LLM."
        val webSearchResult = """
            {
              "query": "мероприятия по ИИ в Москве",
              "results": [
                {
                  "title": "$nearestEventTitle",
                  "url": "$nearestEventUrl",
                  "snippet": "$nearestEventSnippet"
                },
                {
                  "title": "Moscow LLM Hack Meetup",
                  "url": "https://events.example.com/moscow-llm-hack-meetup",
                  "snippet": "5 апреля 2026, Москва, Технопарк. Митап по агентам и LLM."
                }
              ]
            }
        """.trimIndent()
        val telegramSearchResult = """
            {
              "count": 2,
              "matches": [
                {
                  "chatId": 101,
                  "chatTitle": "Moscow AI Community",
                  "messageId": 501,
                  "sender": "@alice",
                  "time": 1774060200,
                  "text": "Кто идет на $expectedEventName Moscow 2026 22 марта? Встречаемся у входа в Кластере $expectedAddress в 10:30."
                },
                {
                  "chatId": 202,
                  "chatTitle": "AI Events RU",
                  "messageId": 502,
                  "sender": "@bob",
                  "time": 1774063800,
                  "text": "На $expectedEventName Moscow 2026 есть промокод COMMUNITY10. Будет сильная секция по LLM."
                }
              ]
            }
        """.trimIndent()

        every { toolWebSearch.invoke(any()) } returns webSearchResult
        coEvery { toolWebSearch.suspendInvoke(any()) } returns webSearchResult
        every { toolTelegramSearch.invoke(any()) } returns telegramSearchResult
        coEvery { toolTelegramSearch.suspendInvoke(any()) } returns telegramSearchResult
        every { toolCreateNote.invoke(any()) } returns "Created"
        coEvery { toolCreateNote.suspendInvoke(any()) } returns "Created"

        runScenarioWithMocks(userPrompt) {
            bindSingleton<ToolWebSearch> { toolWebSearch }
            bindSingleton<ToolTelegramSearch> { toolTelegramSearch }
            bindSingleton<ToolCreateNote> { toolCreateNote }
        }

        coVerifyOrder {
            toolWebSearch.suspendInvoke(
                match {
                    val query = it.query.lowercase()
                    (query.contains("моск") || query.contains("moscow")) &&
                        (query.contains("ии") || query.contains("ai") || query.contains("интеллект"))
                }
            )
            toolTelegramSearch.suspendInvoke(
                match {
                    val query = it.query.lowercase()
                    query.contains(expectedEventName, ignoreCase = true) ||
                        query.contains(expectedAddress, ignoreCase = true) ||
                        query.contains("22 марта")
                }
            )
            toolCreateNote.suspendInvoke(any())
        }
        coVerify(exactly = 1) {
            toolCreateNote.suspendInvoke(
                match {
                    val noteText = it.noteText.lowercase()
                    noteText.contains(nearestEventTitle.lowercase()) &&
                        (noteText.contains("22 марта") || noteText.contains("22.03")) &&
                        noteText.contains(expectedAddress, ignoreCase = true) &&
                        (
                            noteText.contains("10:30") ||
                                noteText.contains("community10") ||
                                noteText.contains("moscow ai community") ||
                                noteText.contains("ai events ru") ||
                                noteText.contains("встречаемся") ||
                                noteText.contains("промокод")
                            )
                }
            )
        }
    }

    private suspend fun runScenarioWithMocks(
        userPrompt: String,
        overrides: org.kodein.di.DI.MainBuilder.() -> Unit,
    ) = support.runScenarioWithMocks(userPrompt, overrides)
}
