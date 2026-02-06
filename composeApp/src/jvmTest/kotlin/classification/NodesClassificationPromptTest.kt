package classification

import com.fasterxml.jackson.databind.ObjectMapper
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.unmockkAll
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import ru.gigadesk.agent.nodes.NodesClassification
import ru.gigadesk.db.ConfigStore
import ru.gigadesk.db.SettingsProviderImpl
import ru.gigadesk.giga.GigaMessageRole
import ru.gigadesk.giga.GigaRequest
import ru.gigadesk.giga.GigaResponse
import ru.gigadesk.giga.GigaToolSetup
import ru.gigadesk.tool.ToolCategory
import ru.gigadesk.tool.ToolCategorySettings
import ru.gigadesk.tool.ToolSettingsEntry
import ru.gigadesk.tool.ToolsFactory
import ru.gigadesk.tool.ToolsSettings
import ru.gigadesk.tool.ToolsSettingsState
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class NodesClassificationPromptTest {
    private val defaultTools: Map<ToolCategory, Map<String, GigaToolSetup>> = mapOf(
        ToolCategory.FILES to mapOf("Read" to dummySetup("Read")),
        ToolCategory.BROWSER to mapOf("Open" to dummySetup("Open")),
    )

    @BeforeEach
    fun setUp() {
        mockkObject(ConfigStore)
        every { ConfigStore.get<ToolsSettingsState>(any()) } returns null
        every { ConfigStore.put(any(), any()) } returns Unit
    }

    @AfterEach
    fun tearDown() {
        unmockkAll()
    }

    @Test
    fun `buildPrompt lists all categories when nothing is disabled`() {
        val toolsFactory = mockk<ToolsFactory> { every { toolsByCategory } returns defaultTools }
        val toolsSettings = ToolsSettings(ConfigStore, toolsFactory)
        val prompt = buildPromptWith(toolsSettings, toolsFactory)

        assertTrue(prompt.contains("FILES"))
        assertTrue(prompt.contains("BROWSER"))
    }

    @Test
    fun `buildPrompt ignores disabled categories`() {
        val toolsFactory = mockk<ToolsFactory> { every { toolsByCategory } returns defaultTools }
        val toolsSettings = ToolsSettings(ConfigStore, toolsFactory)
        toolsSettings.save(
            ToolsSettingsState(
                categories = mapOf(
                    ToolCategory.FILES to ToolCategorySettings(enabled = true, settings = mapOf("Read" to ToolSettingsEntry(true))),
                    ToolCategory.BROWSER to ToolCategorySettings(enabled = false, settings = mapOf("Read" to ToolSettingsEntry(true))),
                )
            )
        )

        val prompt = buildPromptWith(toolsSettings, toolsFactory)

        assertTrue(prompt.contains("FILES"))
        assertFalse(prompt.contains("TEXT_REPLACE"))
    }

    @Test
    fun `buildPrompt skips categories without allowed tools`() {
        val toolsFactory = mockk<ToolsFactory> { every { toolsByCategory } returns defaultTools }
        val toolsSettings = ToolsSettings(ConfigStore, toolsFactory)
        toolsSettings.save(
            ToolsSettingsState(
                categories = mapOf(
                    ToolCategory.FILES to ToolCategorySettings(enabled = true, settings = mapOf("Read" to ToolSettingsEntry(true))),
                    ToolCategory.BROWSER to ToolCategorySettings(enabled = false, settings = mapOf("Read" to ToolSettingsEntry(false))),
                )
            )
        )

        val prompt = buildPromptWith(toolsSettings, toolsFactory)

        assertTrue(prompt.contains("FILES"))
        assertFalse(prompt.contains("TEXT_REPLACE"))
    }

    private fun buildPromptWith(toolsSettings: ToolsSettings, toolsFactory: ToolsFactory): String {
        val classification = NodesClassification(
            logObjectMapper = ObjectMapper(),
            apiClassifier = mockk(relaxed = true),
            localClassifier = mockk(relaxed = true),
            toolsFactory = toolsFactory,
            toolsSettings = toolsSettings,
            settingsProvider = SettingsProviderImpl(ConfigStore)
        )

        val filteredTools = toolsSettings.applyFilter(toolsFactory.toolsByCategory)
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
