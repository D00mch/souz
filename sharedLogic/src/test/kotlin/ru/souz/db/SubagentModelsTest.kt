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
    fun `custom parent resolves to its configured deployment without changing ordinary models`() {
        val settings = object : SettingsProvider by mockk() {
            override var openaiModel: String? = " Parent/Deployment "
            override fun executionModelId(model: LLMModel): String = super<SettingsProvider>.executionModelId(model)
        }
        assertEquals("Parent/Deployment", settings.executionModelId(LLMModel.OpenAICompatibleCustom))
        assertEquals(LLMModel.OpenAIGpt5Mini.alias, settings.executionModelId(LLMModel.OpenAIGpt5Mini))
    }

}
