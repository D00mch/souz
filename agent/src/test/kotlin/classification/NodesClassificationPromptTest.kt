package classification

import com.fasterxml.jackson.databind.ObjectMapper
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.unmockkAll
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.AfterEach
import ru.souz.agent.graph.GraphRuntime
import ru.souz.agent.graph.RetryPolicy
import ru.souz.agent.nodes.NodesClassification
import ru.souz.agent.spi.AgentSettingsProvider
import ru.souz.agent.spi.AgentToolCatalog
import ru.souz.agent.spi.AgentToolsFilter
import ru.souz.agent.state.AgentContext
import ru.souz.agent.state.AgentSettings
import ru.souz.llms.LLMMessageRole
import ru.souz.llms.LLMModel
import ru.souz.llms.LLMRequest
import ru.souz.llms.LLMResponse
import ru.souz.llms.LLMToolSetup
import ru.souz.tool.ToolCategory
import ru.souz.tool.UserMessageClassifier
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
}
