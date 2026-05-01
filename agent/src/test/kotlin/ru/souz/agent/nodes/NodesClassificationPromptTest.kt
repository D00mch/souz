package ru.souz.agent.nodes

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.unmockkAll
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.AfterEach
import ru.souz.agent.spi.AgentSettingsProvider
import ru.souz.agent.spi.AgentToolCatalog
import ru.souz.agent.spi.AgentToolsFilter
import ru.souz.agent.state.AgentContext
import ru.souz.agent.state.AgentSettings
import ru.souz.graph.GraphRuntime
import ru.souz.graph.RetryPolicy
import ru.souz.llms.LLMMessageRole
import ru.souz.llms.LLMModel
import ru.souz.llms.LLMRequest
import ru.souz.llms.LLMResponse
import ru.souz.llms.LLMToolSetup
import ru.souz.llms.restJsonMapper
import ru.souz.tool.ToolCategory
import ru.souz.tool.UserMessageClassifier
import kotlin.collections.minus
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class NodesClassificationPromptTest {
    private val defaultTools: Map<ToolCategory, Map<String, LLMToolSetup>> = mapOf(
        ToolCategory.FILES to mapOf("Read" to dummySetup("Read")),
        ToolCategory.BROWSER to mapOf("Open" to dummySetup("Open")),
    )

    @AfterEach
    fun tearDown() {
        unmockkAll()
    }

    @Test
    fun `buildPrompt lists all categories when nothing is disabled`() {
        val prompt = buildPromptWith(defaultTools)

        assertTrue(prompt.contains("- FILES:"))
        assertTrue(prompt.contains("- BROWSER:"))
    }

    @Test
    fun `buildPrompt ignores disabled categories`() {
        val prompt = buildPromptWith(
            defaultTools - ToolCategory.BROWSER
        )

        assertTrue(prompt.contains("- FILES:"))
        assertFalse(prompt.contains("- BROWSER:"))
        assertFalse(prompt.contains("\nBROWSER: "))
    }

    @Test
    fun `buildPrompt skips categories without allowed tools`() {
        val prompt = buildPromptWith(
            mapOf(ToolCategory.FILES to defaultTools.getValue(ToolCategory.FILES))
        )

        assertTrue(prompt.contains("- FILES:"))
        assertFalse(prompt.contains("- BROWSER:"))
        assertFalse(prompt.contains("\nBROWSER: "))
    }

    @Test
    fun `applyFilter disables telegram tools when telegram is not connected`() {
        val toolsWithTelegram = defaultTools + (ToolCategory.TELEGRAM to mapOf("TgRead" to dummySetup("TgRead")))
        val toolsFactory = mockk<AgentToolCatalog> { every { toolsByCategory } returns toolsWithTelegram }
        val toolsSettings = mockTelegramFilteredToolsSettings(telegramConnected = false)

        val filtered = toolsSettings.applyFilter(toolsFactory.toolsByCategory)

        assertFalse(filtered.containsKey(ToolCategory.TELEGRAM))
    }

    @Test
    fun `local classifier still keeps api verification path for local models`() {
        val localTools = mapOf(ToolCategory.FILES to mapOf("Read" to dummySetup("Read")))
        val settingsProvider = mockk<AgentSettingsProvider> {
            every { gigaModel } returns LLMModel.LocalQwen3_4B_Instruct_2507
        }
        val apiClassifier = mockk<UserMessageClassifier>()
        val localClassifier = mockk<UserMessageClassifier>()
        coEvery { localClassifier.classify(any()) } returns UserMessageClassifier.Reply(
            categories = listOf(ToolCategory.FILES),
            confidence = 90.0,
        )
        coEvery { apiClassifier.classify(any()) } returns UserMessageClassifier.Reply(
            categories = listOf(ToolCategory.FILES),
            confidence = 90.0,
        )
        val toolsFactory = mockk<AgentToolCatalog> { every { toolsByCategory } returns localTools }
        val toolsSettings = mockk<AgentToolsFilter> {
            every { applyFilter(any()) } answers { firstArg() }
        }
        val classification = NodesClassification(
            settingsProvider = settingsProvider,
            logObjectMapper = ObjectMapper(),
            apiClassifier = apiClassifier,
            localClassifier = localClassifier,
            toolCatalog = toolsFactory,
            toolsFilter = toolsSettings,
        )

        val result = runBlocking {
            classification.node().execute(
                ctx = AgentContext(
                    input = "Прочитай файл",
                    settings = AgentSettings(
                        model = LLMModel.LocalQwen3_4B_Instruct_2507.alias,
                        temperature = 0.2f,
                        toolsByCategory = localTools,
                    ),
                    history = emptyList(),
                    activeTools = emptyList(),
                    systemPrompt = "",
                ),
                runtime = GraphRuntime(retryPolicy = RetryPolicy(), maxSteps = 10),
            )
        }

        assertEquals(listOf("Read"), result.activeTools.map { it.name })
        coVerify(exactly = 1) { apiClassifier.classify(any()) }
    }

    @Test
    fun `classifier body keeps focused history for concrete request`() {
        val localClassifier = CapturingClassifier(
            UserMessageClassifier.Reply(
                categories = listOf(ToolCategory.FILES),
                confidence = 90.0,
            )
        )
        val apiClassifier = CapturingClassifier(
            UserMessageClassifier.Reply(
                categories = listOf(ToolCategory.FILES),
                confidence = 90.0,
            )
        )

        executeClassification(
            input = "Fix typos in `/tmp/notes.md` and apply the optional style edits",
            history = listOf(
                LLMRequest.Message(LLMMessageRole.system, "system"),
                LLMRequest.Message(LLMMessageRole.user, "Old request about telegram"),
                LLMRequest.Message(LLMMessageRole.assistant, "Old telegram answer"),
                LLMRequest.Message(LLMMessageRole.user, "Please check `/tmp/notes.md`"),
                LLMRequest.Message(LLMMessageRole.assistant, "I found a few typos."),
                LLMRequest.Message(LLMMessageRole.user, "Fix typos in `/tmp/notes.md` and apply the optional style edits"),
            ),
            localClassifier = localClassifier,
            apiClassifier = apiClassifier,
        )

        val body: LLMRequest.Chat = restJsonMapper.readValue(localClassifier.requireBody())
        val historyMessage = body.messages[1].content

        assertFalse(historyMessage.contains("Old request about telegram"))
        assertTrue(historyMessage.contains("USER: Please check `/tmp/notes.md`"))
        assertTrue(historyMessage.contains("ASSISTANT: I found a few typos."))
    }

    @Test
    fun `classifier body ignores injected context messages`() {
        val localClassifier = CapturingClassifier(
            UserMessageClassifier.Reply(
                categories = listOf(ToolCategory.FILES),
                confidence = 90.0,
            )
        )
        val apiClassifier = CapturingClassifier(
            UserMessageClassifier.Reply(
                categories = listOf(ToolCategory.FILES),
                confidence = 90.0,
            )
        )

        executeClassification(
            input = "do it",
            history = listOf(
                LLMRequest.Message(LLMMessageRole.system, "system"),
                LLMRequest.Message(LLMMessageRole.user, "Please fix typos in `/tmp/article.md`"),
                LLMRequest.Message(LLMMessageRole.user, "<context>\n- [General fact]: Current date and time\n</context>"),
                LLMRequest.Message(LLMMessageRole.assistant, "The file is fixed. I can also apply a couple of style improvements."),
                LLMRequest.Message(LLMMessageRole.user, "<context>\n- [Default browser]: Safari\n</context>"),
                LLMRequest.Message(LLMMessageRole.assistant, "I would only apply the optional style improvements now."),
                LLMRequest.Message(LLMMessageRole.user, "do it"),
            ),
            localClassifier = localClassifier,
            apiClassifier = apiClassifier,
        )

        val body: LLMRequest.Chat = restJsonMapper.readValue(localClassifier.requireBody())
        val historyMessage = body.messages[1].content

        assertTrue(historyMessage.contains("USER: Please fix typos in `/tmp/article.md`"))
        assertTrue(historyMessage.contains("ASSISTANT: I would only apply the optional style improvements now."))
        assertFalse(historyMessage.contains("<context>"))
        assertFalse(historyMessage.contains("Default browser"))
        assertFalse(historyMessage.contains("Current date and time"))
    }

    @Test
    fun `classification drops categories with empty tool lists before prompting`() {
        val localClassifier = CapturingClassifier(
            UserMessageClassifier.Reply(
                categories = listOf(ToolCategory.FILES),
                confidence = 90.0,
            )
        )
        val apiClassifier = CapturingClassifier(
            UserMessageClassifier.Reply(
                categories = listOf(ToolCategory.FILES),
                confidence = 90.0,
            )
        )
        val toolsWithEmptyCategory = mapOf(
            ToolCategory.FILES to mapOf("Read" to dummySetup("Read")),
            ToolCategory.BROWSER to emptyMap(),
        )

        executeClassification(
            input = "Прочитай файл",
            history = emptyList(),
            tools = toolsWithEmptyCategory,
            localClassifier = localClassifier,
            apiClassifier = apiClassifier,
        )

        val body: LLMRequest.Chat = restJsonMapper.readValue(localClassifier.requireBody())
        val prompt = body.messages.first().content

        assertTrue(prompt.contains("- FILES:"))
        assertFalse(prompt.contains("- BROWSER:"))
        assertFalse(prompt.contains("\nBROWSER: "))
    }

    private fun mockTelegramFilteredToolsSettings(telegramConnected: Boolean): AgentToolsFilter {
        return mockk<AgentToolsFilter>().also { toolsSettings ->
            every { toolsSettings.applyFilter(any()) } answers {
                val input = firstArg<Map<ToolCategory, Map<String, LLMToolSetup>>>()
                if (telegramConnected) input else input - ToolCategory.TELEGRAM
            }
        }
    }

    private fun buildPromptWith(filteredTools: Map<ToolCategory, Map<String, LLMToolSetup>>): String {
        val settingsProvider = mockk<AgentSettingsProvider>()
        every { settingsProvider.gigaModel } returns LLMModel.Max

        val classification = NodesClassification(
            settingsProvider = settingsProvider,
            logObjectMapper = ObjectMapper(),
            apiClassifier = mockk(relaxed = true),
            localClassifier = mockk(relaxed = true),
            toolCatalog = mockk<AgentToolCatalog>(relaxed = true),
            toolsFilter = mockk<AgentToolsFilter>(relaxed = true),
        )

        return classification.buildPrompt(filteredTools)
    }

    private fun executeClassification(
        input: String,
        history: List<LLMRequest.Message>,
        tools: Map<ToolCategory, Map<String, LLMToolSetup>> = defaultTools,
        localClassifier: UserMessageClassifier,
        apiClassifier: UserMessageClassifier,
    ) {
        val settingsProvider = mockk<AgentSettingsProvider> {
            every { gigaModel } returns LLMModel.Max
        }
        val toolsFactory = mockk<AgentToolCatalog> { every { toolsByCategory } returns tools }
        val toolsSettings = mockk<AgentToolsFilter> {
            every { applyFilter(any()) } answers { firstArg() }
        }
        val classification = NodesClassification(
            settingsProvider = settingsProvider,
            logObjectMapper = ObjectMapper(),
            apiClassifier = apiClassifier,
            localClassifier = localClassifier,
            toolCatalog = toolsFactory,
            toolsFilter = toolsSettings,
        )

        runBlocking {
            classification.node().execute(
                ctx = AgentContext(
                    input = input,
                    settings = AgentSettings(
                        model = LLMModel.Max.alias,
                        temperature = 0.2f,
                        toolsByCategory = tools,
                    ),
                    history = history,
                    activeTools = emptyList(),
                    systemPrompt = "",
                ),
                runtime = GraphRuntime(retryPolicy = RetryPolicy(), maxSteps = 10),
            )
        }
    }

    private fun dummySetup(name: String): LLMToolSetup = object : LLMToolSetup {
        override val fn: LLMRequest.Function = LLMRequest.Function(
            name = name,
            description = "$name description",
            parameters = LLMRequest.Parameters(type = "object", properties = emptyMap()),
            returnParameters = LLMRequest.Parameters(type = "object", properties = emptyMap()),
        )

        override suspend fun invoke(functionCall: LLMResponse.FunctionCall): LLMRequest.Message {
            return LLMRequest.Message(LLMMessageRole.function, "ok")
        }
    }

    private class CapturingClassifier(
        private val reply: UserMessageClassifier.Reply,
    ) : UserMessageClassifier {
        private var body: String? = null

        override suspend fun classify(body: String): UserMessageClassifier.Reply {
            this.body = body
            return reply
        }

        fun requireBody(): String = checkNotNull(body) { "Classifier was not invoked" }
    }
}