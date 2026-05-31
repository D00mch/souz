package ru.souz.tool

import ru.souz.agent.spi.AgentToolCatalog
import ru.souz.llms.LLMMessageRole
import ru.souz.llms.LLMRequest
import ru.souz.llms.LLMResponse
import ru.souz.llms.LLMToolSetup
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ToolsSettingsTest {
    @Test
    fun `category availability reloads persisted state without stale local cache`() {
        val store = MemoryToolsSettingsStore()
        val settings = ToolsSettings(
            store = store,
            toolCatalog = FakeToolCatalog(
                mapOf(ToolCategory.FILES to mapOf("read_file" to fakeTool("read_file")))
            ),
        )

        assertTrue(settings.isCategoryAllowed(ToolCategory.FILES))

        store.state = ToolsSettingsState(
            categories = mapOf(ToolCategory.FILES to ToolCategorySettings(enabled = false))
        )

        assertFalse(settings.isCategoryAllowed(ToolCategory.FILES))

        store.state = ToolsSettingsState(
            categories = mapOf(ToolCategory.FILES to ToolCategorySettings(enabled = true))
        )

        assertTrue(settings.isCategoryAllowed(ToolCategory.FILES))
    }

    @Test
    fun `save stores platform restricted categories already normalized`() {
        val store = MemoryToolsSettingsStore()
        val settings = ToolsSettings(
            store = store,
            toolCatalog = FakeToolCatalog(
                mapOf(ToolCategory.BROWSER to mapOf("open_url" to fakeTool("open_url")))
            ),
            availabilityPolicy = ToolAvailabilityPolicy { category -> category == ToolCategory.BROWSER },
        )

        settings.save(
            ToolsSettingsState(
                categories = mapOf(
                    ToolCategory.BROWSER to ToolCategorySettings(
                        enabled = true,
                        settings = mapOf("open_url" to ToolSettingsEntry(enabled = true)),
                    )
                )
            )
        )

        val browserSettings = store.state?.categories?.get(ToolCategory.BROWSER)
        assertEquals(false, browserSettings?.enabled)
        assertEquals(false, browserSettings?.settings?.get("open_url")?.enabled)
    }

    private class MemoryToolsSettingsStore : ToolsSettingsStore {
        var state: ToolsSettingsState? = null

        override fun loadToolsSettings(key: String): ToolsSettingsState? = state

        override fun saveToolsSettings(key: String, state: ToolsSettingsState) {
            this.state = state
        }
    }

    private class FakeToolCatalog(
        override val toolsByCategory: Map<ToolCategory, Map<String, LLMToolSetup>>,
    ) : AgentToolCatalog

    private companion object {
        fun fakeTool(name: String): LLMToolSetup = object : LLMToolSetup {
            override val fn = LLMRequest.Function(
                name = name,
                description = "description",
                parameters = LLMRequest.Parameters(
                    type = "object",
                    properties = emptyMap(),
                ),
            )

            override suspend fun invoke(functionCall: LLMResponse.FunctionCall): LLMRequest.Message =
                LLMRequest.Message(
                    role = LLMMessageRole.function,
                    content = "ok",
                    name = name,
                )
        }
    }
}
