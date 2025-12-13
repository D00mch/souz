package ru.abledo.agent.nodes

import com.fasterxml.jackson.databind.ObjectMapper
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.unmockkAll
import org.junit.After
import org.junit.Before
import ru.abledo.db.ConfigStore
import ru.abledo.giga.GigaModel
import ru.abledo.giga.GigaRequest
import ru.abledo.giga.GigaToolSetup
import ru.abledo.tool.ToolCategory
import ru.abledo.tool.ToolCategorySettings
import ru.abledo.tool.ToolsFactory
import ru.abledo.tool.ToolsSettings
import ru.abledo.tool.ToolsSettingsState
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class NodesClassificationPromptTest {
    private val defaultTools: Map<ToolCategory, Map<String, GigaToolSetup>> = mapOf(
        ToolCategory.FILES to mapOf("Read" to dummySetup("Read")),
        ToolCategory.BROWSER to mapOf("Open" to dummySetup("Open")),
    )

    @Before
    fun setUp() {
        mockkObject(ConfigStore)
        every { ConfigStore.get<ToolsSettingsState>(any()) } returns null
        every { ConfigStore.put(any(), any()) } returns Unit
    }

    @After
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
        assertTrue(prompt.contains("Ответь с только одним словом: FILES,BROWSER"))
    }

    @Test
    fun `buildPrompt ignores disabled categories`() {
        val toolsFactory = mockk<ToolsFactory> { every { toolsByCategory } returns defaultTools }
        val toolsSettings = ToolsSettings(ConfigStore, toolsFactory)
        toolsSettings.save(
            ToolsSettingsState(
                categories = mapOf(
                    ToolCategory.FILES to ToolCategorySettings(enabled = true, settings = mapOf("Read" to true)),
                    ToolCategory.BROWSER to ToolCategorySettings(enabled = false, settings = mapOf("Open" to true)),
                )
            )
        )

        val prompt = buildPromptWith(toolsSettings, toolsFactory)

        assertTrue(prompt.contains("FILES"))
        assertFalse(prompt.contains("BROWSER"))
        assertTrue(prompt.contains("Ответь с только одним словом: FILES"))
    }

    @Test
    fun `buildPrompt skips categories without allowed tools`() {
        val toolsFactory = mockk<ToolsFactory> { every { toolsByCategory } returns defaultTools }
        val toolsSettings = ToolsSettings(ConfigStore, toolsFactory)
        toolsSettings.save(
            ToolsSettingsState(
                categories = mapOf(
                    ToolCategory.FILES to ToolCategorySettings(enabled = true, settings = mapOf("Read" to true)),
                    ToolCategory.BROWSER to ToolCategorySettings(enabled = true, settings = mapOf("Open" to false)),
                )
            )
        )

        val prompt = buildPromptWith(toolsSettings, toolsFactory)

        assertTrue(prompt.contains("FILES"))
        assertFalse(prompt.contains("BROWSER"))
        assertTrue(prompt.contains("Ответь с только одним словом: FILES"))
    }

    private fun buildPromptWith(toolsSettings: ToolsSettings, toolsFactory: ToolsFactory): String {
        val classification = NodesClassification(
            model = GigaModel.Max,
            logObjectMapper = ObjectMapper(),
            apiClassifier = mockk(relaxed = true),
            localClassifier = mockk(relaxed = true),
            toolsFactory = toolsFactory,
            toolsSettings = toolsSettings,
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

        override suspend fun invoke(functionCall: ru.abledo.giga.GigaResponse.FunctionCall): GigaRequest.Message {
            return GigaRequest.Message(role = ru.abledo.giga.GigaMessageRole.function, content = "")
        }
    }
}
