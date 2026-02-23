package classification

import com.fasterxml.jackson.databind.ObjectMapper
import io.mockk.every
import io.mockk.mockk
import io.mockk.unmockkAll
import org.junit.jupiter.api.AfterEach
import ru.souz.agent.nodes.NodesClassification
import ru.souz.db.SettingsProvider
import ru.souz.giga.GigaModel
import ru.souz.giga.GigaMessageRole
import ru.souz.giga.GigaRequest
import ru.souz.giga.GigaResponse
import ru.souz.giga.GigaToolSetup
import ru.souz.tool.ToolCategory
import ru.souz.tool.ToolsFactory
import ru.souz.tool.ToolsSettings
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class NodesClassificationPromptTest {
    private val defaultTools: Map<ToolCategory, Map<String, GigaToolSetup>> = mapOf(
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
        val toolsFactory = mockk<ToolsFactory> { every { toolsByCategory } returns toolsWithTelegram }
        val toolsSettings = mockTelegramFilteredToolsSettings(telegramConnected = false)

        val filtered = toolsSettings.applyFilter(toolsFactory.toolsByCategory)

        assertFalse(filtered.containsKey(ToolCategory.TELEGRAM))
    }

    @Test
    fun `applyFilter keeps telegram tools when telegram is connected`() {
        val toolsWithTelegram = defaultTools + (ToolCategory.TELEGRAM to mapOf("TgRead" to dummySetup("TgRead")))
        val toolsFactory = mockk<ToolsFactory> { every { toolsByCategory } returns toolsWithTelegram }
        val toolsSettings = mockTelegramFilteredToolsSettings(telegramConnected = true)

        val filtered = toolsSettings.applyFilter(toolsFactory.toolsByCategory)

        assertTrue(filtered.containsKey(ToolCategory.TELEGRAM))
    }

    private fun mockTelegramFilteredToolsSettings(telegramConnected: Boolean): ToolsSettings {
        return mockk<ToolsSettings>().also { toolsSettings ->
            every { toolsSettings.applyFilter(any()) } answers {
                val input = firstArg<Map<ToolCategory, Map<String, GigaToolSetup>>>()
                if (telegramConnected) input else input - ToolCategory.TELEGRAM
            }
        }
    }

    private fun buildPromptWith(filteredTools: Map<ToolCategory, Map<String, GigaToolSetup>>): String {
        val settingsProvider = mockk<SettingsProvider>()
        every { settingsProvider.gigaModel } returns GigaModel.Max

        val classification = NodesClassification(
            settingsProvider = settingsProvider,
            logObjectMapper = ObjectMapper(),
            apiClassifier = mockk(relaxed = true),
            localClassifier = mockk(relaxed = true),
            toolsFactory = mockk(relaxed = true),
            toolsSettings = mockk(relaxed = true),
        )

        return classification.buildPrompt(filteredTools)
    }

    private fun dummySetup(name: String): GigaToolSetup = object : GigaToolSetup {
        override val fn: GigaRequest.Function = GigaRequest.Function(
            name = name,
            description = "$name description",
            parameters = GigaRequest.Parameters(type = "object", properties = emptyMap()),
            returnParameters = GigaRequest.Parameters(type = "object", properties = emptyMap()),
        )

        override suspend fun invoke(functionCall: GigaResponse.FunctionCall): GigaRequest.Message {
            return GigaRequest.Message(role = GigaMessageRole.function, content = "")
        }
    }
}
