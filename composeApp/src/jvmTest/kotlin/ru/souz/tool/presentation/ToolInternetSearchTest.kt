package ru.souz.tool.presentation

import com.fasterxml.jackson.module.kotlin.readValue
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import ru.souz.db.SettingsProvider
import ru.souz.giga.GigaMessageRole
import ru.souz.giga.GigaModel
import ru.souz.giga.GigaResponse
import ru.souz.giga.gigaJsonMapper
import ru.souz.giga.GigaResponse.FinishReason
import ru.souz.giga.GigaResponse.Usage
import ru.souz.giga.GigaChatAPI
import ru.souz.tool.files.FilesToolUtil
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class ToolInternetSearchTest {
    private val api = mockk<GigaChatAPI>()
    private val settingsProvider = mockk<SettingsProvider>()
    private val webResearchClient = mockk<WebResearchClient>()
    private val filesToolUtil = mockk<FilesToolUtil>(relaxed = true)

    private val tool = ToolInternetSearch(
        api = api,
        settingsProvider = settingsProvider,
        webResearchClient = webResearchClient,
        filesToolUtil = filesToolUtil,
    )

    @Test
    fun `quick answer mode returns synthesized answer with sources`() = runTest {
        every { settingsProvider.gigaModel } returns GigaModel.OpenAIGpt5Mini
        every { webResearchClient.searchWeb(any(), any()) } returns listOf(
            WebSearchResult(
                title = "Tallinn weather today",
                url = "https://example.com/tallinn-weather",
                snippet = "Cloudy, feels like 4C in Tallinn.",
            )
        )
        every { webResearchClient.extractPageText(any(), any()) } returns "Tallinn weather today is cloudy with temperature around 4C."
        coEvery { api.message(any()) } returns chatOk(
            """
            {
              "answer": "В Таллине сейчас облачно, около 4°C [1].",
              "usedSourceIndexes": [1]
            }
            """.trimIndent()
        )

        val raw = tool.suspendInvoke(
            ToolInternetSearch.Input(
                query = "Какая погода в Таллине",
                mode = ToolInternetSearch.SearchMode.QUICK_ANSWER,
            )
        )
        val output = gigaJsonMapper.readValue<ToolInternetSearch.Output>(raw)

        assertEquals("COMPLETE", output.status)
        assertEquals("QUICK_ANSWER", output.mode)
        assertTrue(output.answer.contains("4°C"))
        assertEquals(1, output.sources.size)
        assertEquals(1, output.sources.first().index)
        assertTrue(output.reportMarkdown.contains("Источники"))
        assertNull(output.reportFilePath)
        coVerify(exactly = 1) { api.message(any()) }
    }

    @Test
    fun `research mode plans queries and returns strategy`() = runTest {
        every { settingsProvider.gigaModel } returns GigaModel.OpenAIGpt5Mini
        coEvery { api.message(any()) } returnsMany listOf(
            chatOk(
                """
                {
                  "goal": "Понять текущее состояние ИИ во Франции",
                  "searchQueries": ["France AI overview", "France AI policy", "France AI startups", "France AI regulation"],
                  "subQuestions": ["Какие инициативы у государства", "Какие компании лидируют"],
                  "answerSections": ["Вывод", "Государство", "Рынок"]
                }
                """.trimIndent()
            ),
            chatOk(
                """
                {
                  "answer": "Во Франции развитие ИИ опирается на сочетание госинициатив и активности частного сектора [1][2].",
                  "reportMarkdown": "## Вывод\nВо Франции развитие ИИ опирается на сочетание госинициатив и активности частного сектора [1][2].\n\n## Государство\nЕсть заметные инициативы и инвестиции [1].\n\n## Рынок\nЧастный сектор тоже активен [2].",
                  "usedSourceIndexes": [1, 2]
                }
                """.trimIndent()
            ),
        )
        every { webResearchClient.searchWeb("France AI overview", any()) } returns listOf(
            WebSearchResult(
                title = "France AI Overview",
                url = "https://example.com/france-ai-overview",
                snippet = "Overview of the French AI ecosystem.",
            )
        )
        every { webResearchClient.searchWeb("France AI policy", any()) } returns listOf(
            WebSearchResult(
                title = "France AI Policy",
                url = "https://example.com/france-ai-policy",
                snippet = "Government policy and investment in AI.",
            )
        )
        every { webResearchClient.searchWeb("France AI startups", any()) } returns emptyList()
        every { webResearchClient.searchWeb("France AI regulation", any()) } returns emptyList()
        every { webResearchClient.extractPageText(any(), any()) } answers {
            "Extracted content for ${firstArg<String>()}"
        }

        val raw = tool.suspendInvoke(
            ToolInternetSearch.Input(
                query = "Проведи исследование про ИИ во Франции",
                mode = ToolInternetSearch.SearchMode.RESEARCH,
                maxSources = 4,
            )
        )
        val output = gigaJsonMapper.readValue<ToolInternetSearch.Output>(raw)
        val strategy = assertNotNull(output.strategy)

        assertEquals("COMPLETE", output.status)
        assertEquals("RESEARCH", output.mode)
        assertEquals(
            listOf("France AI overview", "France AI policy", "France AI startups", "France AI regulation"),
            strategy.searchQueries
        )
        assertEquals(2, output.sources.size)
        assertTrue(output.answer.contains("[1][2]"))
        assertTrue(output.reportMarkdown.contains("## Вывод"))
        assertTrue(output.reportMarkdown.contains("Стратегия поиска"))
        assertNull(output.reportFilePath)
        coVerify(exactly = 2) { api.message(any()) }
    }

    @Test
    fun `research mode saves oversized report to markdown file`() = runTest {
        val tempDir = Files.createTempDirectory("internet-search-report-test")
        val largeReportJson = ("## Раздел\\nОчень подробный текст исследования [1][2][3].\\n\\n").repeat(260)
        every { settingsProvider.gigaModel } returns GigaModel.OpenAIGpt5Mini
        every { filesToolUtil.souzDocumentsDirectoryPath } returns tempDir
        every { filesToolUtil.requirePathIsSave(any()) } returns Unit
        coEvery { api.message(any()) } returnsMany listOf(
            chatOk(
                """
                {
                  "goal": "Оценить рынок инструментов для презентаций",
                  "searchQueries": ["presentation libraries overview", "presentation libraries comparison", "presentation generation frameworks", "presentation automation tools"],
                  "subQuestions": ["Какие библиотеки самые зрелые"],
                  "answerSections": ["Вывод", "Сравнение", "Рекомендация"]
                }
                """.trimIndent()
            ),
            chatOk(
                """
                {
                  "answer": "Для большинства продуктовых сценариев лучше брать библиотеку с хорошей экосистемой и экспортом [1][2].",
                  "reportMarkdown": "$largeReportJson",
                  "usedSourceIndexes": [1, 2]
                }
                """.trimIndent()
            ),
        )
        every { webResearchClient.searchWeb(any(), any()) } returns listOf(
            WebSearchResult(
                title = "Presentation library overview",
                url = "https://example.com/presentation-library-overview",
                snippet = "Overview of presentation libraries.",
            ),
            WebSearchResult(
                title = "Presentation library comparison",
                url = "https://example.com/presentation-library-comparison",
                snippet = "Comparison of presentation libraries.",
            ),
        )
        every { webResearchClient.extractPageText(any(), any()) } answers {
            "Extracted content for ${firstArg<String>()}"
        }

        val raw = tool.suspendInvoke(
            ToolInternetSearch.Input(
                query = "Найди подходящую библиотеку для презентаций",
                mode = ToolInternetSearch.SearchMode.RESEARCH,
                maxSources = 8,
            )
        )
        val output = gigaJsonMapper.readValue<ToolInternetSearch.Output>(raw)
        val reportFilePath = assertNotNull(output.reportFilePath)
        val savedFile = java.io.File(reportFilePath)

        assertEquals("COMPLETE", output.status)
        assertTrue(savedFile.exists())
        assertTrue(savedFile.readText().contains("Очень подробный текст исследования"))
        assertTrue(output.answer.contains(reportFilePath))
        assertTrue(output.reportMarkdown.contains(reportFilePath))
    }

    @Test
    fun `research fallback returns partial status and does not export fake full report`() = runTest {
        every { settingsProvider.gigaModel } returns GigaModel.OpenAIGpt5Mini
        coEvery { api.message(any()) } returnsMany listOf(
            chatOk(
                """
                {
                  "goal": "Понять развитие desktop AI agents",
                  "searchQueries": ["desktop ai agents overview", "computer use agents benchmarks", "desktop automation ai tools", "anthropic computer use"],
                  "subQuestions": [],
                  "answerSections": []
                }
                """.trimIndent()
            ),
            chatOk("not-json-response"),
            chatOk("still-not-json"),
        )
        every { webResearchClient.searchWeb(any(), any()) } returns listOf(
            WebSearchResult(
                title = "Computer use tool - Claude API Docs",
                url = "https://platform.claude.com/docs/en/agents-and-tools/tool-use/computer-use-tool",
                snippet = "Claude can interact with computer environments through the computer use tool.",
            )
        )
        every { webResearchClient.extractPageText(any(), any()) } returns
            "Claude can interact with computer environments through screenshots, mouse and keyboard control."

        val raw = tool.suspendInvoke(
            ToolInternetSearch.Input(
                query = "Проведи исследование по desktop ai agents",
                mode = ToolInternetSearch.SearchMode.RESEARCH,
                maxSources = 8,
            )
        )
        val output = gigaJsonMapper.readValue<ToolInternetSearch.Output>(raw)

        assertEquals("PARTIAL", output.status)
        assertNull(output.reportFilePath)
        assertTrue(output.answer.contains("черновой digest"))
        assertTrue(output.sources.isNotEmpty())
    }

    @Test
    fun `research rescue completes after malformed primary synthesis`() = runTest {
        every { settingsProvider.gigaModel } returns GigaModel.OpenAIGpt5Mini
        coEvery { api.message(any()) } returnsMany listOf(
            chatOk(
                """
                {
                  "goal": "Понять развитие desktop AI agents",
                  "searchQueries": ["desktop ai agents overview", "computer use agents benchmarks", "desktop automation ai tools", "anthropic computer use"],
                  "subQuestions": [],
                  "answerSections": []
                }
                """.trimIndent()
            ),
            chatOk("not-json-response"),
            chatOk(
                """
                {
                  "answer": "Desktop AI agents быстро движутся в сторону computer-use сценариев, но зрелость ещё ограничена [1].",
                  "reportMarkdown": "## Вывод\nDesktop AI agents быстро движутся в сторону computer-use сценариев, но зрелость ещё ограничена [1].\n\n## Что видно по источникам\nОсновной сдвиг идёт вокруг управления экраном, мышью и клавиатурой [1].\n\n## Ограничения\nИсточники описывают серьёзные ограничения по стабильности и надёжности [1].",
                  "usedSourceIndexes": [1]
                }
                """.trimIndent()
            ),
        )
        every { webResearchClient.searchWeb(any(), any()) } returns listOf(
            WebSearchResult(
                title = "Computer use tool - Claude API Docs",
                url = "https://platform.claude.com/docs/en/agents-and-tools/tool-use/computer-use-tool",
                snippet = "Claude can interact with computer environments through the computer use tool.",
            )
        )
        every { webResearchClient.extractPageText(any(), any()) } returns
            "Claude can interact with computer environments through screenshots, mouse and keyboard control."

        val raw = tool.suspendInvoke(
            ToolInternetSearch.Input(
                query = "Проведи исследование по desktop ai agents",
                mode = ToolInternetSearch.SearchMode.RESEARCH,
                maxSources = 8,
            )
        )
        val output = gigaJsonMapper.readValue<ToolInternetSearch.Output>(raw)

        assertEquals("COMPLETE", output.status)
        assertTrue(output.answer.contains("computer-use"))
        assertTrue(output.reportMarkdown.contains("## Вывод"))
        assertNull(output.reportFilePath)
        coVerify(exactly = 3) { api.message(any()) }
    }

    private fun chatOk(content: String): GigaResponse.Chat.Ok = GigaResponse.Chat.Ok(
        choices = listOf(
            GigaResponse.Choice(
                message = GigaResponse.Message(
                    content = content,
                    role = GigaMessageRole.assistant,
                    functionsStateId = null,
                ),
                index = 0,
                finishReason = FinishReason.stop,
            )
        ),
        created = 1L,
        model = "test-model",
        usage = Usage(0, 0, 0, 0),
    )
}
