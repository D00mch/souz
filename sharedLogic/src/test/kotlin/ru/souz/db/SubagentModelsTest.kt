package ru.souz.db

import io.mockk.mockk
import ru.souz.llms.LLMModel
import ru.souz.llms.LlmProvider
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class SubagentModelsTest {
    @Test
    fun `empty configuration adds no choices and exact IDs retain their providers`() {
        listOf(null, "{}", "{\"OPENAI\":[]}").forEach { assertTrue(parseSubagentModels(it).isEmpty()) }
        assertEquals(
            mapOf(" Deployment/v2 " to LlmProvider.OPENAI, "custom" to LlmProvider.ANTHROPIC),
            parseSubagentModels("""{"OPENAI":[" Deployment/v2 "," Deployment/v2 "],"ANTHROPIC":["custom"]}"""),
        )
    }

    @Test
    fun `malformed configurations fail instead of falling back`() {
        listOf(
            "", " ", "null", "[]", "{", "{} {}", "{\"UNKNOWN\":[]}",
            """{"OPENAI":null}""", """{"OPENAI":"model"}""", """{"OPENAI":[null]}""",
            """{"OPENAI":[1]}""", """{"OPENAI":[" "]}""", """{"OPENAI":[""]}""",
            """{"OPENAI":["model"],"ANTHROPIC":["model"]}""", """{"OPENAI":[],"OPENAI":[]}""",
        ).forEach { json ->
            val error = assertFailsWith<IllegalArgumentException>(json) { parseSubagentModels(json) }
            assertTrue(error.message.orEmpty().startsWith("Invalid $SUBAGENT_MODELS_JSON:"))
        }
        assertFailsWith<IllegalArgumentException> {
            parseSubagentModels("""{"GIGA":[]}""", setOf(LlmProvider.OPENAI))
        }
    }

    @Test
    fun `desktop settings prefer stored configuration and snapshot it until restart`() {
        val stored = ConfigStore.get<String>(SUBAGENT_MODELS_JSON)
        val property = System.getProperty(SUBAGENT_MODELS_JSON)
        try {
            ConfigStore.rm(SUBAGENT_MODELS_JSON)
            System.setProperty(SUBAGENT_MODELS_JSON, """{"ANTHROPIC":["property-model"]}""")
            assertEquals(mapOf("property-model" to LlmProvider.ANTHROPIC), settings().subagentModels)
            ConfigStore.put(SUBAGENT_MODELS_JSON, """{"OPENAI":["stored-model"]}""")
            val settings = settings()
            ConfigStore.put(SUBAGENT_MODELS_JSON, "{}")
            assertEquals(mapOf("stored-model" to LlmProvider.OPENAI), settings.subagentModels)
            assertTrue(settings().subagentModels.isEmpty())
            ConfigStore.put(SUBAGENT_MODELS_JSON, "broken")
            assertFailsWith<IllegalArgumentException> { settings() }
        } finally {
            if (stored == null) ConfigStore.rm(SUBAGENT_MODELS_JSON) else ConfigStore.put(SUBAGENT_MODELS_JSON, stored)
            if (property == null) System.clearProperty(SUBAGENT_MODELS_JSON) else System.setProperty(SUBAGENT_MODELS_JSON, property)
        }
    }

    @Test
    fun `custom parent resolves to its configured deployment without changing ordinary models`() {
        val settings = object : SettingsProvider by mockk() {
            override var openaiModel: String? = " Parent/Deployment "
            override fun executionModelId(model: LLMModel): String = super<SettingsProvider>.executionModelId(model)
        }
        assertEquals("Parent/Deployment", settings.executionModelId(LLMModel.OpenAICompatibleCustom))
        assertEquals(LLMModel.OpenAIGpt5Mini.alias, settings.executionModelId(LLMModel.OpenAIGpt5Mini))
    }

    private fun settings() = SettingsProviderImpl(ConfigStore, mockk(relaxed = true))
}
