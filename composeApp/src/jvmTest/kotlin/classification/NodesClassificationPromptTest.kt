package classification

import com.fasterxml.jackson.databind.ObjectMapper
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.unmockkAll
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import ru.souz.agent.nodes.NodesClassification
import ru.souz.db.ConfigStore
import ru.souz.db.SettingsProviderImpl
import ru.souz.giga.GigaMessageRole
import ru.souz.giga.GigaRequest
import ru.souz.giga.GigaResponse
import ru.souz.giga.GigaToolSetup
import ru.souz.tool.ToolCategory
import ru.souz.tool.ToolCategorySettings
import ru.souz.tool.ToolSettingsEntry
import ru.souz.tool.ToolsFactory
import ru.souz.tool.ToolsSettings
import ru.souz.tool.ToolsSettingsState
import ru.souz.service.telegram.TelegramAuthState
import ru.souz.service.telegram.TelegramAuthStep
import ru.souz.service.telegram.TelegramPlatformSupport
import ru.souz.service.telegram.TelegramService
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
        mockkObject(TelegramPlatformSupport)
        every { ConfigStore.get<ToolsSettingsState>(any()) } returns null
        every { ConfigStore.put(any(), any()) } returns Unit
        every { TelegramPlatformSupport.isSupported() } returns true
    }

    @AfterEach
    fun tearDown() {
        unmockkAll()
    }

    @Test
    fun `buildPrompt lists all categories when nothing is disabled`() {
        val toolsFactory = mockk<ToolsFactory> { every { toolsByCategory } returns defaultTools }
        val toolsSettings = toolsSettings(toolsFactory)
        val prompt = buildPromptWith(toolsSettings, toolsFactory)

        assertTrue(prompt.contains("FILES"))
        assertTrue(prompt.contains("BROWSER"))
    }

    @Test
    fun `buildPrompt ignores disabled categories`() {
        val toolsFactory = mockk<ToolsFactory> { every { toolsByCategory } returns defaultTools }
        val toolsSettings = toolsSettings(toolsFactory)
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
        val toolsSettings = toolsSettings(toolsFactory)
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

    @Test
    fun `applyFilter disables telegram tools when telegram is not connected`() {
        val toolsWithTelegram = defaultTools + (ToolCategory.TELEGRAM to mapOf("TgRead" to dummySetup("TgRead")))
        val toolsFactory = mockk<ToolsFactory> { every { toolsByCategory } returns toolsWithTelegram }
        val toolsSettings = toolsSettings(toolsFactory, telegramStep = TelegramAuthStep.WAIT_PHONE)

        val filtered = toolsSettings.applyFilter(toolsFactory.toolsByCategory)

        assertFalse(filtered.containsKey(ToolCategory.TELEGRAM))
    }

    @Test
    fun `applyFilter keeps telegram tools when telegram is connected`() {
        val toolsWithTelegram = defaultTools + (ToolCategory.TELEGRAM to mapOf("TgRead" to dummySetup("TgRead")))
        val toolsFactory = mockk<ToolsFactory> { every { toolsByCategory } returns toolsWithTelegram }
        val toolsSettings = toolsSettings(toolsFactory, telegramStep = TelegramAuthStep.READY)

        val filtered = toolsSettings.applyFilter(toolsFactory.toolsByCategory)

        assertTrue(filtered.containsKey(ToolCategory.TELEGRAM))
    }

    private fun toolsSettings(
        toolsFactory: ToolsFactory,
        telegramStep: TelegramAuthStep = TelegramAuthStep.READY,
    ): ToolsSettings {
        val telegramService = mockk<TelegramService>()
        every { telegramService.authState } returns MutableStateFlow(TelegramAuthState(step = telegramStep))
        return ToolsSettings(ConfigStore, toolsFactory, telegramService, TelegramPlatformSupport)
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
